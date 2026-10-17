"""Hosted Android API; importing this module never opens account databases."""
import asyncio
from contextlib import contextmanager, AsyncExitStack
import json
import logging
from pathlib import Path
import secrets
import shutil
import hashlib
import time
from datetime import datetime, timezone, timedelta

from fastapi import FastAPI, HTTPException, Request, Query, WebSocket, WebSocketDisconnect, BackgroundTasks
from fastapi.responses import JSONResponse, Response, StreamingResponse

from .config import Config
from .domain import Settings, LocationInput, MemoryInput, SpeechInput, NearbyInput
from .domain import MeetingRequest, RecallRequest, TaskStatusInput, MemoryUpdate
from .providers.volc_speech import VolcSpeechProvider, SpeechProviderError, pcm_to_wav
from .providers.qwen_realtime import QwenRealtimeProvider
from .providers.qwen_work_asr import QwenWorkAsrProvider
from .providers.qwen_meeting_asr import QwenMeetingAsrProvider, MeetingTaskFailed
from .services.insights import MeetingInsights, meeting_coverage
from .services.work_meetings import WorkMeetingMinutes, attach_times
from .services.meeting_addresses import MeetingAddresses
from .services.work_live import live_captions
from .services.recall import RecallService
from .services.voice_stream import CompletionBarrier, transcribe_live, reply_live
from .services.qwen_voice import reply_qwen, realtime_instructions
from .services.duplex_voice import duplex_call
from .services.assistant_tools import AssistantTools
from .services.commute import ROUTE_FIELDS, commute_destination
from .providers.bocha import BochaSearch


def create_hosted_app(config=None, data_directory=None):
    """Public Android API. Never mounts the unauthenticated single-user API/web UI."""
    import os
    import re
    import sqlite3
    from contextlib import asynccontextmanager
    from fastapi import Depends
    from fastapi.exceptions import RequestValidationError
    from pydantic import BaseModel, Field, field_validator
    from starlette.concurrency import run_in_threadpool
    from .storage import AccountStore, Store
    from .services.accounts import digest, password_hash, password_matches, DUMMY_PASSWORD
    from .services.mobile import MobileRuntimes

    config = config or Config()
    directory = Path(data_directory or os.getenv('AIDOL_DATA_DIR', 'data/hosted'))
    accounts = AccountStore(str(directory / 'accounts.sqlite3'))
    runtimes = MobileRuntimes(config, directory / 'users')
    meeting_addresses = MeetingAddresses(config)
    speech = VolcSpeechProvider(config.speech)
    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            await meeting_addresses.close()
    api = FastAPI(title='Aidol Android API', version='0.2.0', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    api.state.accounts, api.state.runtimes, api.state.speech = accounts, runtimes, speech
    api.state.qwen = QwenRealtimeProvider(config.qwen)
    api.state.work_asr = QwenWorkAsrProvider(config.work_asr)
    api.state.meeting_asr = QwenMeetingAsrProvider(config.work_asr)
    active = 0
    preparing_voice = set()
    # Inherit Uvicorn's configured INFO handler without changing global logging.
    # No account identifiers, payloads, audio or exception details enter these logs.
    diagnostics = logging.getLogger('uvicorn.error.aidol.mobile')

    def trace(request_id, stage, status, elapsed_ms=0):
        diagnostics.info('mobile request_id=%s stage=%s status=%s elapsed_ms=%d',
                         request_id, stage, status, elapsed_ms)

    @contextmanager
    def measured(request_id, stage):
        started = time.monotonic()
        status = 'done'
        try:
            yield
        except asyncio.CancelledError:
            status = 'cancelled'
            raise
        except Exception:
            status = 'failed'
            raise
        finally:
            trace(request_id, stage, status, int((time.monotonic() - started) * 1000))

    class Credentials(BaseModel):
        username: str = Field(min_length=1, max_length=64)
        password: str = Field(min_length=1, max_length=128)

        @field_validator('username', mode='before')
        @classmethod
        def valid_username(cls, value):
            if not isinstance(value, str):
                raise ValueError('请输入账号')
            value = value.strip()
            if not value or not value.isprintable():
                raise ValueError('请输入账号，不能包含换行或控制字符')
            return value.translate(str.maketrans('ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))

    class ChangePassword(BaseModel):
        old_password: str = Field(min_length=1, max_length=128)
        new_password: str = Field(min_length=1, max_length=128)

    class Recovery(Credentials):
        recovery_code: str = Field(min_length=16, max_length=128)

    class ConfirmPassword(BaseModel):
        password: str = Field(min_length=1, max_length=128)

    class Turn(BaseModel):
        text: str = Field(min_length=1, max_length=6000)
        request_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
        interaction_source: str = Field(default='ambient_capture', pattern=r'^(direct_question|ambient_capture|continuous_call)$')

    class WorkMeetingStart(BaseModel):
        meeting_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
        started_at: datetime | None = None
        location: LocationInput | None = None
        whole_audio: bool = False

        @field_validator('started_at')
        @classmethod
        def aware_start(cls, value):
            if value is not None and (value.tzinfo is None or abs((datetime.now(timezone.utc) - value).total_seconds()) > 86400):
                raise ValueError('会议开始时间需包含时区且在一天内')
            return value

    class LocationRecord(LocationInput):
        accuracy: float = Field(default=0, ge=0, le=200)
        record_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
        observed_at: datetime
        trigger: str = Field(pattern=r'^(app_open|page_return|interval)$')

        @field_validator('observed_at')
        @classmethod
        def fresh_observation(cls, value):
            if value.tzinfo is None or not -120 <= (datetime.now(timezone.utc) - value).total_seconds() <= 600:
                raise ValueError('定位时间必须是近期带时区的时间')
            return value

    class WorkMeetingFinish(BaseModel):
        segment_count: int = Field(ge=0, le=100000)
        complete: bool = True

    class WorkMeetingEvent(BaseModel):
        event_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
        kind: str = Field(pattern=r'^(pause|resume|mark)$')
        offset_ms: int = Field(ge=0, le=86_400_000)
        origin: str = Field(default='app', pattern=r'^(app|headset)$')

    @api.exception_handler(RequestValidationError)
    async def invalid(request, error):
        message = '请求参数格式不正确，请检查必填项和输入长度'
        if request.url.path.startswith('/auth/'):
            message = '请检查输入：账号为 1–64 位可见字符，不能全为空格；密码为 1–128 位；找回账号时请填写完整恢复码'
        elif request.url.path == '/v1/maps/weather':
            message = '请先选择目的地，或填写 6 位城市行政区代码'
        return JSONResponse({'detail': message}, 422)

    @api.middleware('http')
    async def security(request, call_next):
        # Bound actual streamed bodies, including chunked uploads without Content-Length.
        audio_upload = request.url.path == '/v1/voice' or re.fullmatch(
            r'/v1/work-meetings/[a-zA-Z0-9-]{16,64}/audio', request.url.path
        ) is not None
        limit = 1920000 if audio_upload else 65536
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > limit:
                return JSONResponse({'detail': '录音最多 60 秒，文字请求最多 64 KB'}, 413)
        request._body = bytes(body)
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['X-Frame-Options'] = 'DENY'
        return response

    def token(request):
        value = request.headers.get('authorization', '')
        return value[7:] if value.startswith('Bearer ') else ''

    def user(request: Request):
        found = accounts.authenticate(digest(token(request)))
        if not found:
            raise HTTPException(401, '登录已失效，请重新登录')
        return found

    def throttle(request, name):
        # Uvicorn trusts only the loopback TLS proxy; never reads untrusted XFF itself.
        address = request.client.host if request.client else 'unknown'
        if not accounts.consume('auth:' + digest(address), 40, 900):
            raise HTTPException(429, '尝试过于频繁，请 15 分钟后重试')
        if not accounts.consume('account:' + digest(name), 15, 900):
            raise HTTPException(429, '此账号尝试过于频繁，请 15 分钟后重试')

    def session(found):
        raw = secrets.token_urlsafe(32)
        if not accounts.issue_session(found['id'], digest(raw), expected_password=found.get('password')):
            raise HTTPException(401, '凭据已变更，请重新登录')
        return {'token': raw, 'expires_in': 30 * 86400,
                'user': {key: found[key] for key in ('id', 'username', 'created_at')}}

    @asynccontextmanager
    async def runtime(current, request, billable=False, wait_seconds=0):
        nonlocal active
        if active >= 8:
            raise HTTPException(503, '服务器正在处理较多请求，请稍后再试')
        active += 1
        try:
            async with runtimes.use(current['id'], wait_seconds=wait_seconds) as value:
                # Recheck after acquiring the per-account lock (password/logout races).
                if not accounts.authenticate(digest(token(request))):
                    raise HTTPException(401, '登录已失效')
                if billable and not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
                    raise HTTPException(429, '今日体验额度已用完，请明天再试')
                value.agent.tool_charge = lambda: accounts.consume(
                    'usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400)
                yield value
        finally:
            active -= 1

    @api.get('/healthz')
    async def hosted_health():
        return {'ok': True, 'version': '0.2.0', 'work_meetings': True, 'ready': bool(config.qwen.ready or
            config.text_key and config.text_model and config.speech.asr_ready and config.speech.tts_ready)}

    @api.post('/auth/register', status_code=201)
    def register(value: Credentials, request: Request):
        throttle(request, value.username)
        if os.getenv('AIDOL_REGISTRATION_OPEN', 'true').lower() != 'true':
            raise HTTPException(403, '当前暂停注册，请联系测试负责人')
        recovery = secrets.token_urlsafe(24)
        try:
            found = accounts.register(value.username, password_hash(value.password), digest(recovery), int(os.getenv('AIDOL_MAX_USERS', '500')))
        except sqlite3.IntegrityError:
            raise HTTPException(409, '这个账号已被使用，换一个试试')
        except OverflowError:
            raise HTTPException(403, '测试名额已满')
        return {**session(found), 'recovery_code': recovery}

    @api.post('/auth/login')
    def login(value: Credentials, request: Request):
        throttle(request, value.username)
        found = accounts.user(value.username)
        valid = password_matches(value.password, found['password'] if found else DUMMY_PASSWORD)
        if not valid or not found:
            raise HTTPException(401, '账号或密码不正确')
        return session(found)

    @api.get('/auth/me')
    def me(current=Depends(user)):
        return current

    @api.post('/auth/logout')
    def logout(request: Request, current=Depends(user)):
        accounts.logout(digest(token(request)))
        return {'ok': True}

    @api.post('/auth/recover')
    def recover(value: Recovery, request: Request):
        throttle(request, value.username)
        found = accounts.user(value.username)
        if not found or not secrets.compare_digest(digest(value.recovery_code), found['recovery']):
            raise HTTPException(401, '账号或恢复码不正确')
        replacement = secrets.token_urlsafe(24)
        hashed = password_hash(value.password)
        if not accounts.rotate(found['id'], found['password'], hashed, digest(replacement)):
            raise HTTPException(409, '凭据已变更，请重新操作')
        found['password'] = hashed
        return {**session(found), 'recovery_code': replacement}

    @api.put('/auth/password')
    async def change_password(value: ChangePassword, request: Request, current=Depends(user)):
        throttle(request, current['username'])
        async with runtime(current, request):
            found = accounts.user(current['username'])
            if not await run_in_threadpool(password_matches, value.old_password, found['password']):
                raise HTTPException(401, '原密码不正确')
            hashed = await run_in_threadpool(password_hash, value.new_password)
            if not accounts.rotate(found['id'], found['password'], hashed):
                raise HTTPException(409, '凭据已变更，请重新登录')
            found['password'] = hashed
            return session(found)

    @api.delete('/auth/account')
    async def delete_account(value: ConfirmPassword, request: Request, current=Depends(user)):
        throttle(request, current['username'])
        async with runtime(current, request) as rt:
            found = accounts.user(current['username'])
            if not await run_in_threadpool(password_matches, value.password, found['password']):
                raise HTTPException(401, '密码不正确')
            rt.store.clear()
            rt.store.save_settings(Settings(auto_mode=False, preferences=''))
            accounts.delete(current['id'])
        runtimes.items.pop(current['id'], None)
        # No live connection remains: remove private SQLite files as well.
        path = directory / 'users' / (current['id'] + '.sqlite3')
        for suffix in ('', '-wal', '-shm'):
            Path(str(path) + suffix).unlink(missing_ok=True)
        shutil.rmtree(path.with_suffix(''), ignore_errors=True)  # Meeting audio.
        return {'ok': True}

    @api.get('/v1/state')
    async def mobile_state(request: Request, background: BackgroundTasks, current=Depends(user)):
        async with runtime(current, request, wait_seconds=5) as rt:
            await rt.agent.decide()
            meetings = rt.store.work_meetings()
            resume_work_summaries(background, current, meetings)
            meeting_addresses.schedule(background, rt.store, meetings)
            return {'user': current, 'settings': rt.store.settings().model_dump(),
                    'capabilities': config.capabilities(), 'agent': rt.agent.snapshot(),
                    'transcripts': list(reversed(rt.store.list('transcripts', 100))),
                    'memories': rt.store.list('memories', 300), 'tasks': rt.store.list('action_tasks', 300),
                    'drafts': rt.store.list('drafts', 30),
                    'meeting_report': rt.store.meeting_report(), 'work_meetings': meetings}

    @api.put('/v1/settings')
    async def mobile_settings(value: Settings, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            # Android v1 is foreground-only; do not advertise background listening.
            value.auto_mode = False
            value.life_poll_enabled = False
            value.provider = 'cascade'
            old = rt.store.settings()
            if any(getattr(old, key) != getattr(value, key) for key in ROUTE_FIELDS):
                rt.agent.route, rt.agent.commute, rt.agent.last_route, rt.agent.last_notice = None, None, 0, 0
            rt.store.save_settings(value)
            rt.store.purge_work_audio()
            await rt.agent.decide()
            return value

    async def process(rt, current, request_id, fingerprint, text=None, pcm=None, interaction_source='ambient_capture', meeting_id=None, segment_index=None, offset_ms=None, recognize=True):
        with measured(request_id, 'turn'):
            return await process_turn(rt, current, request_id, fingerprint, text=text, pcm=pcm, interaction_source=interaction_source,
                                      meeting_id=meeting_id, segment_index=segment_index, offset_ms=offset_ms, recognize=recognize)

    async def process_turn(rt, current, request_id, fingerprint, text=None, pcm=None, interaction_source='ambient_capture', meeting_id=None, segment_index=None, offset_ms=None, recognize=True):
        try:
            cached = rt.store.mobile_turn(request_id, fingerprint, reserve=False)
        except ValueError as exc:
            trace(request_id, 'cache', 'conflict')
            raise HTTPException(409, str(exc))
        if cached is not None:
            trace(request_id, 'cache', 'hit')
            return cached
        if meeting_id:
            if rt.store.settings().mode != 'work' or interaction_source != 'ambient_capture':
                raise HTTPException(409, '会议录音仅能在工作模式保存')
            try:
                rt.store.check_work_segment(meeting_id, segment_index, request_id)
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from None
        trace(request_id, 'cache', 'miss')
        # Audio-only meeting segments (text came from live captions) use no ASR and no request quota.
        if recognize and not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
            raise HTTPException(429, '今日体验额度已用完，请明天再试')
        rt.store.mobile_turn(request_id, fingerprint)
        utterances = ()
        if pcm is not None and not recognize:
            text = ''
        elif pcm is not None:
            async def chunks():
                for offset in range(0, len(pcm), 3200):
                    yield pcm[offset:offset + 3200]
                    await asyncio.sleep(.02)
            text, utterances = '', ()
            with measured(request_id, 'asr'):
                try:
                    async with asyncio.timeout(65):
                        # Segment fallback: Qwen Flash (segment-local speakers) when configured, else Volcengine with speakers.
                        qwen_segment = bool(meeting_id) and config.work_asr.ready
                        provider = api.state.work_asr if qwen_segment else speech
                        options = {'speakers': True} if meeting_id and not qwen_segment else {}
                        async for result in provider.stream_asr(chunks(), **options):
                            if result.text:
                                text = result.text
                            if result.utterances:
                                utterances = result.utterances
                except (SpeechProviderError, TimeoutError):
                    rt.store.fail_mobile_turn(request_id)
                    raise HTTPException(502, '识别暂不可用，请稍后重试')
                except BaseException:
                    rt.store.fail_mobile_turn(request_id)
                    raise
            if not text.strip() and not meeting_id:
                result = {'text': '', 'response': '没有听清，请靠近麦克风再试一次', 'speak': False}
                rt.store.finish_mobile_turn(request_id, result)
                return result
        rt.store.mobile_turn_stage(request_id, 'business')
        try:
            with measured(request_id, 'business'):
                if meeting_id:
                    transcript_id = rt.store.save_work_segment(
                        meeting_id, segment_index, request_id, text, offset_ms=offset_ms,
                        duration_ms=len(pcm) // 32, utterances=utterances, audio=pcm, recognized=recognize,
                        keep_for_transcription=False)
                    result = {'text': text, 'transcript_id': transcript_id, 'meeting_id': meeting_id,
                              'segment_index': segment_index, 'mode': 'work', 'interaction': 'silent_capture',
                              'response': '', 'memories': [], 'speak': False}
                elif interaction_source == 'ambient_capture':
                    result = await rt.turn(text[:6000], 'asr' if pcm is not None else 'text')
                else:
                    result = await rt.turn(text[:6000], 'asr' if pcm is not None else 'text', interaction_source)
        except BaseException:
            rt.store.fail_mobile_turn(request_id)
            raise
        rt.store.finish_mobile_turn(request_id, result)
        return result

    @api.post('/v1/chat')
    async def mobile_chat(value: Turn, request: Request, current=Depends(user)):
        if not value.text.strip():
            raise HTTPException(422, '请输入内容')
        async with runtime(current, request) as rt:
            fingerprint = 'text:' + digest(value.text) + (':' + value.interaction_source if value.interaction_source != 'ambient_capture' else '')
            return await process(rt, current, value.request_id, fingerprint, text=value.text, interaction_source=value.interaction_source)

    @api.post('/v1/voice')
    async def mobile_voice(request: Request, request_id: str = Query(pattern=r'^[a-zA-Z0-9-]{16,64}$'),
                           interaction_source: str = Query(default='ambient_capture', pattern=r'^(direct_question|ambient_capture|continuous_call)$'),
                           meeting_id: str | None = Query(default=None, pattern=r'^[a-zA-Z0-9-]{16,64}$'),
                           segment_index: int | None = Query(default=None, ge=0, le=100000),
                           offset_ms: int | None = Query(default=None, ge=0, le=86_400_000),
                           recognize: int = Query(default=1, ge=0, le=1), current=Depends(user)):
        ready = (config.work_asr.ready or config.speech.asr_ready) if meeting_id else config.speech.asr_ready
        if recognize and not ready:
            raise HTTPException(503, '服务器尚未配置语音识别')
        if request.headers.get('content-type', '').split(';')[0] != 'audio/pcm':
            raise HTTPException(415, '需要 16kHz 单声道 PCM16')
        pcm = await request.body()
        if len(pcm) < 3200 or len(pcm) % 2:
            raise HTTPException(422, '录音太短或不完整，请重新录音')
        if (meeting_id is None) != (segment_index is None):
            raise HTTPException(422, '会议编号和片段编号必须同时提供')
        if offset_ms is not None and meeting_id is None:
            raise HTTPException(422, '录音时间偏移只用于会议片段')
        if not recognize and meeting_id is None:
            raise HTTPException(422, '只存录音不识别只用于会议片段')
        async with runtime(current, request) as rt:
            fingerprint = 'pcm:' + hashlib.sha256(pcm).hexdigest() + (':' + interaction_source if interaction_source != 'ambient_capture' else '')
            if meeting_id:
                fingerprint += ':meeting:' + meeting_id + ':' + str(segment_index)
                if offset_ms is not None:
                    fingerprint += ':' + str(offset_ms)
                if not recognize:
                    fingerprint += ':audio-only'
            return await process(rt, current, request_id, fingerprint, pcm=pcm, interaction_source=interaction_source,
                                 meeting_id=meeting_id, segment_index=segment_index, offset_ms=offset_ms, recognize=bool(recognize))

    @api.websocket('/v1/voice/stream')
    async def mobile_voice_stream(socket: WebSocket):
        # Header auth only: never put account tokens into URLs/access logs.
        current = accounts.authenticate(digest(token(socket)))
        if not current:
            await socket.close(code=4401)
            return
        await socket.accept()
        socket = CompletionBarrier(socket)
        request_id = socket.query_params.get('request_id', '')
        interaction = socket.query_params.get('interaction_source', 'ambient_capture')
        reply_audio = socket.query_params.get('reply_audio', '0')
        engine = socket.query_params.get('engine', 'cascade')
        duplex = socket.query_params.get('duplex', '0')
        prepare = socket.query_params.get('prepare', '0')
        reserved = False
        preparation_owned = False
        upstream = AsyncExitStack()
        prepared_native = None
        prepared_context = None
        rt = None

        @asynccontextmanager
        async def activated_runtime():
            async with runtime(current, socket, wait_seconds=5) as value:
                try:
                    yield value
                finally:
                    # Even a failed activation closes provider transports before
                    # another request can acquire this account's orchestrator.
                    await upstream.aclose()

        try:
            if not re.fullmatch(r'[a-zA-Z0-9-]{16,64}', request_id) or interaction not in {'direct_question', 'ambient_capture', 'continuous_call'}:
                raise HTTPException(422, '语音会话参数不正确')
            if reply_audio not in {'0', '1'} or (reply_audio == '1' and interaction not in {'direct_question', 'continuous_call'}):
                raise HTTPException(422, '流式回复仅用于主动 AI 提问')
            if engine not in {'cascade', 'qwen'} or (engine == 'qwen' and (reply_audio != '1' or interaction not in {'direct_question', 'continuous_call'})):
                raise HTTPException(422, '千问实时语音仅用于主动 AI 语音对话')
            if duplex not in {'0', '1'} or (duplex == '1' and (engine != 'qwen' or interaction != 'continuous_call')):
                raise HTTPException(422, '全双工仅用于通义连续通话')
            if prepare not in {'0', '1'} or (prepare == '1' and duplex != '1'):
                raise HTTPException(422, '预连接仅用于全双工通话')
            if engine == 'qwen' and not config.qwen.ready:
                raise HTTPException(503, '服务器尚未配置千问实时语音')
            if engine == 'cascade' and reply_audio == '1' and not config.speech.tts_ready:
                raise HTTPException(503, '服务器尚未配置语音合成')
            if engine == 'cascade' and not config.speech.asr_ready:
                raise HTTPException(503, '服务器尚未配置语音识别')
            if prepare == '1':
                preparation_started = time.monotonic()
                # Read one account snapshot, then release its lock before any
                # upstream wait. Standby reserves no turn or business quota.
                if current['id'] in preparing_voice:
                    raise HTTPException(409, '已有语音连接正在准备')
                if len(preparing_voice) >= 8:
                    raise HTTPException(503, '语音准备繁忙，请稍后接通')
                if not accounts.consume('voice_prepare:' + current['id'], 6, 60):
                    raise HTTPException(429, '语音准备较频繁，请稍后再试')
                preparing_voice.add(current['id'])
                preparation_owned = True
                async with runtime(current, socket, wait_seconds=5) as snapshot:
                    if snapshot.store.settings().mode == 'work':
                        raise HTTPException(409, '工作模式不预连接 AI 语音回复')
                    prepared_context = (realtime_instructions(snapshot.agent.instructions(directed=True)),
                                        AssistantTools(snapshot.agent).schemas())
                def preparation_command(message):
                    if message['type'] == 'websocket.disconnect':
                        return 'end'
                    if message.get('bytes') is not None:
                        raise HTTPException(422, '接通前不能上传录音')
                    command = json.loads(message.get('text') or '{}')
                    if command not in ({'type': 'start'}, {'type': 'end'}):
                        raise HTTPException(422, '请先主动接通语音')
                    return command['type']

                opening = asyncio.create_task(upstream.enter_async_context(
                    api.state.qwen.duplex_session(prepared_context[0], (), prepared_context[1])))
                control = asyncio.create_task(socket.receive())
                command = None
                try:
                    async with asyncio.timeout(25):
                        done, _ = await asyncio.wait((opening, control), return_when=asyncio.FIRST_COMPLETED)
                        if control in done:
                            command = preparation_command(control.result())
                            if command == 'end':
                                return
                        prepared_native = await opening
                    await socket.send_json({'type': 'prepared', 'expires_in': 120})
                    diagnostics.info('voice_prepare request_id=%s ready_ms=%d', request_id,
                                     (time.monotonic() - preparation_started) * 1000)
                    if command is None:
                        async with asyncio.timeout(120):
                            command = preparation_command(await control)
                    if command == 'end':
                        return
                finally:
                    for task in (opening, control):
                        if not task.done():
                            task.cancel()
                    await asyncio.gather(opening, control, return_exceptions=True)
                if not accounts.authenticate(digest(token(socket))):
                    raise HTTPException(401, '登录已失效')
                # This transport is now a call, not a standby reservation. A
                # following preparation can wait for its account cleanup.
                preparing_voice.discard(current['id'])
                preparation_owned = False
            async with activated_runtime() as rt:
                # A streaming ID is single-use. Recovery reads /v1/turns; no replay
                # can swap in different audio or charge twice for an uncertain turn.
                if rt.store.mobile_turn_status(request_id) is not None:
                    raise HTTPException(409, '该语音会话已提交，请查询本轮状态和历史记录')
                if not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
                    raise HTTPException(429, '今日体验额度已用完，请明天再试')
                rt.store.mobile_turn(request_id, 'stream:' + interaction)
                rt.store.mobile_turn_stage(request_id, 'streaming')
                reserved = True
                if engine == 'qwen' and rt.store.settings().mode == 'work':
                    raise HTTPException(409, '工作模式请使用录音转写通道，不开启 AI 语音回复')
                if duplex == '1':
                    def charge_turn(billable):
                        if not accounts.authenticate(digest(token(socket))):
                            raise HTTPException(401, '登录已失效')
                        return not billable or accounts.consume('usage:' + current['id'],
                            int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400)
                    instructions = realtime_instructions(rt.agent.instructions(directed=True))
                    history, schemas = rt.conversation_history(), AssistantTools(rt.agent).schemas()
                    if prepared_native is not None:
                        native = prepared_native
                        if prepared_context != (instructions, schemas):
                            await native.update(instructions=instructions,
                                tools=[{'type': 'function', **s['function']} for s in schemas])
                        await native.seed_history(history)
                    else:
                        native = await upstream.enter_async_context(
                            api.state.qwen.duplex_session(instructions, history, schemas))
                    result = await duplex_call(socket, rt, native, speech, request_id, charge_turn)
                    rt.store.finish_mobile_turn(request_id, result)
                    await upstream.aclose()
                    await socket.send_json({'type': 'call.done'})
                    return
                if engine == 'qwen':
                    async with api.state.qwen.session(realtime_instructions(rt.agent.instructions(directed=True)),
                            rt.conversation_history(), AssistantTools(rt.agent).schemas()) as native:
                        async def speech_ended():
                            await socket.send_json({'type': 'input.speech_end'})
                        await native.enable_endpoint(speech_ended)
                        await socket.send_json({'type': 'ready', 'sample_rate': 16000, 'engine': 'qwen'})
                        with measured(request_id, 'qwen_input'):
                            text = await transcribe_live(socket, native)
                        if not accounts.authenticate(digest(token(socket))):
                            raise HTTPException(401, '登录已失效')
                        if text.strip():
                            rt.store.mobile_turn_stage(request_id, 'business')
                            with measured(request_id, 'qwen_reply'):
                                await reply_qwen(socket, rt, native, speech, text, interaction,
                                    lambda result: rt.store.finish_mobile_turn(request_id, result))
                        else:
                            result = {'text': '', 'response': '没有听清，请再试一次', 'speak': False}
                            rt.store.finish_mobile_turn(request_id, result)
                            await socket.send_json({'type': 'result', 'result': result})
                            await socket.send_json({'type': 'stream.done'})
                    return
                await socket.send_json({'type': 'ready', 'sample_rate': 16000})
                with measured(request_id, 'live_asr'):
                    text = await transcribe_live(socket, speech)
                if not accounts.authenticate(digest(token(socket))):
                    raise HTTPException(401, '登录已失效')
                if not text.strip():
                    result = {'text': '', 'response': '没有听清，请再试一次', 'speak': False}
                else:
                    rt.store.mobile_turn_stage(request_id, 'business')
                    with measured(request_id, 'business'):
                        if reply_audio == '1':
                            await reply_live(socket, rt, speech, text, interaction,
                                lambda result: rt.store.finish_mobile_turn(request_id, result))
                            return
                        result = await rt.turn(text, 'asr', interaction)
                rt.store.finish_mobile_turn(request_id, result)
                await socket.send_json({'type': 'result', 'result': result})
                if reply_audio == '1':
                    await socket.send_json({'type': 'stream.done'})
        except WebSocketDisconnect:
            pass
        except (HTTPException, ValueError, SpeechProviderError, TimeoutError) as error:
            status = error.status_code if isinstance(error, HTTPException) else 502
            logging.getLogger('uvicorn.error.aidol.mobile').warning(
                'voice_session request_id=%s failure=%s status=%d', request_id, type(error).__name__, status)
            detail = error.detail if isinstance(error, HTTPException) else '实时语音中断，请检查网络并查询本轮状态后重试'
            try:
                await socket.send_json({'type': 'error', 'status': status, 'detail': detail})
            except (WebSocketDisconnect, RuntimeError):
                pass
        finally:
            try:
                await upstream.aclose()
            finally:
                if preparation_owned:
                    preparing_voice.discard(current['id'])
                if reserved:
                    rt.store.fail_mobile_turn(request_id)  # completed responses stay completed
                try:
                    await socket.flush()
                    await socket.close()
                except (WebSocketDisconnect, RuntimeError):
                    pass

    @api.get('/v1/turns/{request_id}')
    async def mobile_turn_status(request_id: str, request: Request, current=Depends(user)):
        # Read-only recovery remains available during a long ASR/model operation.
        # The path comes from the authenticated UUID, never from a supplied path.
        rt = runtimes.items.get(current['id'])
        path = directory / 'users' / (current['id'] + '.sqlite3')
        if rt is None and not path.exists():
            raise HTTPException(404, '请求不存在')
        store = rt.store if rt else Store(str(path))
        result = store.mobile_turn_status(request_id)
        if result is None:
            raise HTTPException(404, '请求不存在')
        if rt and rt.lock.locked():
            result['retryable'] = False
        return result

    @api.post('/v1/speech')
    async def mobile_speech(value: SpeechInput, request: Request, current=Depends(user)):
        supplied_id = request.headers.get('x-aidol-request-id', '')
        # Optional correlation only: malformed diagnostic input cannot alter the
        # speech contract, quota, authentication or idempotency. Never log it raw.
        request_id = supplied_id if re.fullmatch(r'[a-zA-Z0-9-]{16,64}', supplied_id) else secrets.token_hex(16)
        with measured(request_id, 'tts'):
            if not config.speech.tts_ready:
                raise HTTPException(503, '服务器尚未配置语音合成')
            async with runtime(current, request, True):
                try:
                    wav = await speech.synthesize_wav(value.text)
                    return Response(wav, media_type='audio/wav')
                except (SpeechProviderError, TimeoutError):
                    raise HTTPException(502, '语音播放暂不可用，文字回复已保留')

    @api.post('/v1/speech/stream')
    async def mobile_speech_stream(value: SpeechInput, request: Request, current=Depends(user)):
        # Length-prefixed PCM with a zero-length success trailer: a truncated
        # provider/network response must never be mistaken for a complete reply.
        import struct
        from contextlib import aclosing
        if not config.speech.tts_ready:
            raise HTTPException(503, '服务器尚未配置语音合成')
        async def generate():
            async with runtime(current, request, True):
                size = 0
                async with aclosing(speech.stream_tts(value.text)) as chunks:
                    async for chunk in chunks:
                        if not chunk:
                            continue
                        size += len(chunk)
                        if len(chunk) % 2 or size > 6 * 1024 * 1024:
                            raise SpeechProviderError('语音数据超出上限或格式无效')
                        for offset in range(0, len(chunk), 24000):
                            part = chunk[offset:offset + 24000]
                            yield struct.pack('>I', len(part)) + part
                if not size:
                    raise SpeechProviderError('语音服务未返回音频')
                yield struct.pack('>I', 0)
        stream = generate()
        try:
            first = await anext(stream)
        except (SpeechProviderError, TimeoutError):
            await stream.aclose()
            raise HTTPException(502, '语音合成暂不可用，文字回复已保留')
        async def response_stream():
            try:
                yield first
                async for frame in stream:
                    yield frame
            except (SpeechProviderError, TimeoutError):
                # Deliberately omit success trailer; client reports partial playback.
                return
            finally:
                await stream.aclose()
        return StreamingResponse(response_stream(), media_type='application/x-aidol-pcm',
                                 headers={'X-Aidol-Audio-Rate': '24000'})

    @api.get('/v1/search')
    async def mobile_search(request: Request, q: str = Query(min_length=1, max_length=500),
                            freshness: str = Query(default='noLimit', max_length=24), current=Depends(user)):
        async with runtime(current, request, True):
            try:
                return await BochaSearch(config).search(q, freshness)
            except ValueError as error:
                raise HTTPException(502, str(error))

    @api.get('/v1/maps/geocode')
    async def mobile_geocode(request: Request, address: str = Query(min_length=1, max_length=100),
                             city: str = Query(default='', max_length=80), current=Depends(user)):
        async with runtime(current, request, True) as rt:
            try:
                return await rt.agent.maps.geocode(address, city)
            except ValueError as error:
                raise HTTPException(502, str(error))

    @api.post('/v1/maps/reverse')
    async def mobile_reverse(value: LocationInput, request: Request, current=Depends(user)):
        async with runtime(current, request, True) as rt:
            try:
                return await rt.agent.maps.reverse(value)
            except ValueError as error:
                raise HTTPException(502, str(error))

    @api.post('/v1/memories')
    async def mobile_memory(value: MemoryInput, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            row = rt.store.add('memories', **value.model_dump(), source='user')
            rt.agent.memory_interaction.reset()
            return row

    @api.post('/v1/memories/from-transcript/{transcript_id}')
    async def mobile_remember(transcript_id: str, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            row = rt.store.remember_transcript(transcript_id)
            if row is None:
                raise HTTPException(404, '原始对话不存在')
            rt.agent.memory_interaction.reset()
            return row

    @api.get('/v1/memories/{memory_id}/source')
    async def mobile_source(memory_id: str, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            row = rt.store.memory_source(memory_id)
            if row is None:
                raise HTTPException(404, '这条记忆没有可追溯的原始转写')
            return row

    @api.delete('/v1/memories/{memory_id}')
    async def mobile_delete_memory(memory_id: str, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            if not rt.store.delete_memory(memory_id):
                raise HTTPException(404, '记忆不存在')
            rt.agent.memory_interaction.reset()
            rt.agent.conversation.history = []
            return {'ok': True}

    @api.put('/v1/memories/{memory_id}')
    async def mobile_edit_memory(memory_id: str, value: MemoryUpdate, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            if not rt.store.update_memory(memory_id, value.content, value.kind, value.pinned):
                raise HTTPException(404, '记忆不存在')
            rt.agent.memory_interaction.reset()
            rt.agent.conversation.history = []
            return {'ok': True}

    @api.post('/v1/recall')
    async def mobile_recall(value: RecallRequest, request: Request, current=Depends(user)):
        if not value.question.strip():
            raise HTTPException(422, '请输入你想回忆的问题')
        async with runtime(current, request, True) as rt:
            memories, transcripts = rt.store.recall_records(value.question)
            return await RecallService(config).answer(value.question, memories, transcripts)

    @api.get('/v1/maps/search')
    async def mobile_places(request: Request, q: str = Query(min_length=1, max_length=100), city: str = Query(default='', max_length=80), current=Depends(user)):
        async with runtime(current, request, True) as rt:
            try:
                return await rt.agent.maps.search(q, city)
            except ValueError:
                raise HTTPException(422, '地点关键词无效')
            except Exception:
                raise HTTPException(502, '地点查询暂不可用，请稍后再试')

    @api.post('/v1/location')
    async def mobile_location(value: LocationInput, request: Request, current=Depends(user)):
        async with runtime(current, request, True) as rt:
            return await rt.agent.update_location(value)

    @api.post('/v1/location-records')
    async def record_location(value: LocationRecord, current=Depends(user)):
        # Passive maintenance never acquires the voice lock, invokes a provider,
        # changes modes/routes, or consumes the voice/business request quota.
        store = read_work_store(current) or Store(str(directory / 'users' / (current['id'] + '.sqlite3')))
        point = value.model_dump(exclude={'record_id', 'observed_at'})
        observed_at = value.observed_at.astimezone(timezone.utc).isoformat()
        try:
            record = store.save_location_record(value.record_id, observed_at, point)
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None
        existing = runtimes.items.get(current['id'])
        if existing is not None:
            existing.record_passive_location({**value.model_dump(include={'longitude', 'latitude', 'accuracy', 'source', 'speed'}), 'observed_at': observed_at})
        return record

    @api.get('/v1/location-records')
    async def recorded_locations(limit: int = Query(default=100, ge=1, le=1000), current=Depends(user)):
        store = read_work_store(current)
        return {'records': store.location_records(limit) if store else []}

    @api.post('/v1/maps/nearby')
    async def mobile_nearby(value: NearbyInput, request: Request, current=Depends(user)):
        async with runtime(current, request, True) as rt:
            try:
                return await rt.agent.maps.nearby(value, value.keyword, value.radius, value.metro_only)
            except Exception:
                raise HTTPException(502, '周边查询暂不可用，请稍后再试')

    @api.get('/v1/maps/weather')
    async def mobile_weather(request: Request, city: str = Query(default='', pattern=r'^([0-9]{6})?$'), forecast: bool = False, current=Depends(user)):
        async with runtime(current, request, True) as rt:
            fix = rt.agent.location
            destination = None if city else commute_destination(
                rt.store.settings(), (fix['longitude'], fix['latitude']) if fix else None)
            if destination is not None and not destination['target']:
                raise HTTPException(422, destination['reason'] + '，再查询当地天气')
            try:
                if city:
                    return await rt.agent.maps.weather(city, forecast)
                return await rt.agent.maps.weather_at(destination['longitude'], destination['latitude'], forecast)
            except Exception:
                raise HTTPException(502, '天气查询暂不可用，请稍后再试')

    @api.post('/v1/drafts/{draft_id}/{action}')
    async def mobile_draft(draft_id: str, action: str, request: Request, current=Depends(user)):
        if action not in {'copied', 'dismissed'}:
            raise HTTPException(400, '仅支持复制或忽略')
        async with runtime(current, request) as rt:
            if not rt.store.draft_status(draft_id, action):
                raise HTTPException(404, '草稿不存在或已处理')
            return {'ok': True, 'status': action, 'sent': False}

    # Persist pending jobs before returning the raw transcript. GETs resume pending jobs
    # after a restart; an in-process key prevents duplicate model calls from concurrent polls.
    work_summaries_running = set()
    work_summary_slots = asyncio.Semaphore(2)

    async def summarize_work(current, meeting_id):
        key = (current['id'], meeting_id)
        store = None
        try:
            async with work_summary_slots:
                found = accounts.user(current['username'])
                if found is None or found['id'] != current['id']:
                    return
                async with runtimes.use(current['id'], wait_seconds=10) as rt:
                    store = rt.store
                    meeting = store.work_meeting(meeting_id)
                    if meeting is None or meeting['summary_status'] != 'pending':
                        return
                if meeting['transcription_status'] == 'pending':
                    # Whole-meeting diarization from the saved audio: one speaker namespace for the meeting.
                    import tempfile
                    store.work_summary_progress(meeting_id, 'transcribing', 0, 1)
                    turns = None
                    try:
                        if not meeting['asr_task_id'] and not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
                            raise SpeechProviderError('Meeting transcription quota reached')
                        with tempfile.TemporaryFile(suffix='.wav') as wav:
                            if not meeting['asr_task_id']:
                                await run_in_threadpool(store.write_work_wav, meeting_id, wav)
                            wav.seek(0)
                            turns = await api.state.meeting_asr.transcribe(wav, meeting['asr_task_id'],
                                lambda task_id: store.work_asr_task(meeting_id, task_id))
                    except (SpeechProviderError, ValueError) as exc:
                        # Keep the live sentences; a later retry can submit a new task.
                        store.fail_work_transcription(meeting_id, reset_task=isinstance(exc, MeetingTaskFailed))
                    async with runtimes.use(current['id'], wait_seconds=10) as rt:
                        fresh = rt.store.work_meeting(meeting_id)
                        if fresh is None or [row['id'] for row in fresh['sources']] != [row['id'] for row in meeting['sources']]:
                            # A source was forgotten while the provider ran; never recreate it from audio.
                            rt.store.fail_work_transcription(meeting_id)
                            return
                        if turns is not None and not rt.store.save_work_dialogue(meeting_id, turns):
                            return
                        meeting = rt.store.work_meeting(meeting_id)
                rows = store.work_meeting_evidence(meeting)
                source_ids = [row['id'] for row in meeting['sources']]
                if not rows:
                    store.fail_work_summary(meeting_id)
                    return
                charge = lambda: accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400)
                report = await WorkMeetingMinutes(config, charge).generate(rows,
                    lambda stage, done, total: store.work_summary_progress(meeting_id, stage, done, total))
                if meeting['transcription_status'] == 'failed':
                    report['notice'] = '整场说话人识别未完成，以下基于实时或分段原文整理，编号可能跨段变化。' + report.get('notice', '')
                # The network work never holds the account's voice/control lock.
                found = accounts.user(current['username'])
                if found is None or found['id'] != current['id']:
                    return
                async with runtimes.use(current['id'], wait_seconds=10) as rt:
                    attach_times(report, meeting)
                    report['includes_demo'] = False
                    report['meeting_id'] = meeting_id
                    report['started_at'] = meeting['started_at']
                    report['location'] = meeting['location']
                    report['capture_status'] = meeting['status']
                    if not rt.store.save_work_summary(meeting_id, report, source_ids):
                        rt.store.fail_work_summary(meeting_id)
        except asyncio.CancelledError:
            # Keep the persisted pending job; the next page read resumes it.
            raise
        except Exception:
            if store is not None and Path(store.path).exists():
                store.fail_work_summary(meeting_id)
            diagnostics.info('work meeting summary failed')
        finally:
            work_summaries_running.discard(key)

    def resume_work_summaries(background, current, meetings):
        for meeting in meetings:
            key = (current['id'], meeting['id'])
            if meeting['summary_status'] == 'pending' and key not in work_summaries_running:
                work_summaries_running.add(key)
                background.add_task(summarize_work, current, meeting['id'])

    def read_work_store(current):
        # SQLite snapshot reads need not wait for ASR's account lock. This lets the
        # progress page remain usable while another segment is being transcribed.
        existing = runtimes.items.get(current['id'])
        if existing is not None:
            return existing.store
        path = directory / 'users' / (current['id'] + '.sqlite3')
        return Store(str(path)) if path.exists() else None

    @api.post('/v1/work-meetings')
    async def start_work_meeting(value: WorkMeetingStart, request: Request, background: BackgroundTasks, current=Depends(user)):
        async with runtime(current, request) as rt:
            if rt.store.settings().mode != 'work':
                raise HTTPException(409, '请先切换到工作模式')
            existing = rt.store.work_meeting(value.meeting_id)
            if existing and bool(existing['whole_audio']) != value.whole_audio:
                raise HTTPException(409, '会议录音方式不一致，请开始新的会议')
            location = value.location.model_dump() if value.location else {}
            meeting = rt.store.start_work_meeting(value.meeting_id,
                value.started_at.isoformat() if value.started_at else None,
                location, value.whole_audio)
            if meeting['status'] != 'recording':
                raise HTTPException(409, '这场会议已结束，请开始新的会议')
            meeting_addresses.schedule(background, rt.store, [meeting])
            return meeting

    @api.post('/v1/work-meetings/{meeting_id}/audio')
    async def upload_work_audio(meeting_id: str, request: Request,
                                upload_index: int = Query(ge=0, le=100000),
                                request_id: str = Query(pattern=r'^[a-zA-Z0-9-]{16,64}$'), current=Depends(user)):
        if not config.work_asr.ready:
            raise HTTPException(503, '服务器尚未配置会议语音识别')
        if request.headers.get('content-type', '').split(';')[0] != 'audio/pcm':
            raise HTTPException(415, '需要 16kHz 单声道 PCM16')
        pcm = await request.body()
        if not 3200 <= len(pcm) <= 60 * 32000 or len(pcm) % 2:
            raise HTTPException(422, '录音格式或大小不正确')
        async with runtime(current, request) as rt:
            if rt.store.settings().mode != 'work':
                raise HTTPException(409, '请先切换到工作模式')
            try:
                rt.store.save_work_audio(meeting_id, upload_index, request_id, pcm)
            except (ValueError, sqlite3.IntegrityError):
                raise HTTPException(409, '录音上传冲突、会议已结束或超出两小时上限') from None
        return {'meeting_id': meeting_id, 'upload_index': upload_index, 'saved': True,
                'mode': 'work', 'response': '', 'text': '', 'speak': False}

    @api.get('/v1/work-meetings')
    async def list_work_meetings(request: Request, background: BackgroundTasks, current=Depends(user)):
        store = read_work_store(current)
        if store:
            store.purge_work_audio()
        meetings = store.work_meetings() if store else []
        resume_work_summaries(background, current, meetings)
        meeting_addresses.schedule(background, store, meetings)
        return {'meetings': meetings}

    @api.get('/v1/work-meetings/{meeting_id}')
    async def get_work_meeting(meeting_id: str, request: Request, background: BackgroundTasks, current=Depends(user)):
        store = read_work_store(current)
        if store:
            store.purge_work_audio()
        meeting = store.work_meeting(meeting_id) if store else None
        if meeting is None:
            raise HTTPException(404, '会议记录不存在')
        resume_work_summaries(background, current, [meeting])
        meeting_addresses.schedule(background, store, [meeting])
        return meeting

    @api.post('/v1/work-meetings/{meeting_id}/events')
    async def work_meeting_event(meeting_id: str, value: WorkMeetingEvent, current=Depends(user)):
        # Marks must not wait behind a segment's ASR, which holds the account lock for seconds.
        store = read_work_store(current)
        try:
            event = store.add_work_event(meeting_id, value.event_id, value.kind, value.offset_ms, value.origin) if store else None
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None
        if event is None:
            raise HTTPException(404, '会议记录不存在')
        return event

    @api.websocket('/v1/work-meetings/{meeting_id}/live')
    async def work_meeting_live(socket: WebSocket, meeting_id: str):
        # Header auth only, like /v1/voice/stream. The account lock is not held: segment uploads
        # run alongside, and each sentence is its own SQLite transaction.
        current = accounts.authenticate(digest(token(socket)))
        if not current:
            await socket.close(code=4401)
            return
        try:
            offset_ms = int(socket.query_params.get('offset_ms', '0'))
        except ValueError:
            offset_ms = -1
        store = read_work_store(current)
        meeting = store.work_meeting(meeting_id) if store else None
        if not config.speech.asr_ready or not 0 <= offset_ms <= 86_400_000 or meeting is None \
                or meeting['status'] != 'recording' or store.settings().mode != 'work':
            await socket.close(code=4409)
            return
        if not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
            await socket.close(code=4429)
            return
        await socket.accept()
        await socket.send_text(json.dumps({'type': 'ready', 'offset_ms': offset_ms}))
        await live_captions(socket, speech, store, meeting_id, offset_ms)

    @api.get('/v1/work-meetings/{meeting_id}/audio')
    async def work_meeting_audio(meeting_id: str, start_ms: int = Query(default=0, ge=0, le=86_400_000),
                                 duration_ms: int = Query(default=600_000, ge=100, le=600_000), current=Depends(user)):
        store = read_work_store(current)
        if store:
            store.purge_work_audio()
        if store is None or store.work_meeting(meeting_id) is None:
            raise HTTPException(404, '会议记录不存在')
        pcm = await run_in_threadpool(store.work_audio, meeting_id, start_ms, start_ms + duration_ms)
        if not pcm:
            raise HTTPException(404, '本场没有保存的录音，或已过保存期限')
        return Response(pcm_to_wav(pcm, 16000), media_type='audio/wav',
                        headers={'X-Audio-Start-Ms': str(start_ms), 'Cache-Control': 'no-store'})

    @api.delete('/v1/work-meetings/{meeting_id}/audio')
    async def delete_work_meeting_audio(meeting_id: str, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            try:
                deleted = rt.store.delete_work_audio(meeting_id)
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from None
            if deleted is None:
                raise HTTPException(404, '会议记录不存在')
            return rt.store.work_meeting(meeting_id)

    @api.post('/v1/work-meetings/{meeting_id}/finish')
    async def finish_work_meeting(meeting_id: str, value: WorkMeetingFinish, request: Request, background: BackgroundTasks, current=Depends(user)):
        async with runtime(current, request) as rt:
            try:
                meeting = rt.store.finish_work_meeting(meeting_id, value.segment_count, value.complete,
                                                       transcribe=config.work_asr.ready)
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from None
            if meeting is None:
                raise HTTPException(404, '会议记录不存在')
            resume_work_summaries(background, current, [meeting])
            return meeting

    @api.post('/v1/work-meetings/{meeting_id}/retry-summary')
    async def retry_work_meeting(meeting_id: str, request: Request, background: BackgroundTasks, current=Depends(user)):
        async with runtime(current, request) as rt:
            meeting = rt.store.work_meeting(meeting_id)
            if meeting is None:
                raise HTTPException(404, '会议记录不存在')
            if (not meeting['sources'] and meeting['transcription_status'] not in ('pending', 'failed')) or meeting['status'] == 'recording':
                raise HTTPException(409, '请先结束会议并保存原文')
            if (current['id'], meeting_id) in work_summaries_running:
                return meeting
            rt.store.retry_work_summary(meeting_id)
            meeting = rt.store.work_meeting(meeting_id)
            resume_work_summaries(background, current, [meeting])
            return meeting

    @api.post('/v1/meetings')
    async def mobile_meeting(value: MeetingRequest, request: Request, current=Depends(user)):
        async with runtime(current, request, True) as rt:
            cutoff = (datetime.now(timezone.utc) - timedelta(hours=value.hours)).isoformat()
            rows = rt.store.meeting_records(cutoff)
            if not rows:
                raise HTTPException(422, '请先在工作模式记录会议，再生成纪要')
            signature = digest(json.dumps(rows, sort_keys=True, ensure_ascii=False))
            old = rt.store.meeting_report(signature)
            if old:
                return old
            report = await MeetingInsights(config).generate(rows)
            selected = set(report.get('source_ids', []))
            report.update(sources=[row for row in reversed(rows) if row['id'] in selected],
                          coverage=meeting_coverage(rows, report.get('source_ids', [])), hours=value.hours, includes_demo=False)
            return rt.store.save_meeting_report(signature, report)

    @api.patch('/v1/tasks/{task_id}')
    async def mobile_task(task_id: str, value: TaskStatusInput, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            if not rt.store.action_task_status(task_id, value.status):
                raise HTTPException(404, '待办不存在')
            return {'ok': True}

    @api.get('/v1/export')
    async def mobile_export(request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            return {'user': current, 'settings': rt.store.settings().model_dump(), 'memories': rt.store.list('memories', 10000),
                    'version': 4, 'drafts': rt.store.list('drafts', 10000),
                    'location_records': rt.store.location_records(10000),
                    'transcripts': rt.store.list('transcripts', 10000), 'reports': rt.store.all_meeting_reports(), 'tasks': rt.store.list('action_tasks', 10000),
                    'work_meetings': [rt.store.work_meeting(row['id']) for row in rt.store.work_meetings(10000)]}

    @api.delete('/v1/data')
    async def mobile_clear(value: ConfirmPassword, request: Request, current=Depends(user)):
        throttle(request, current['username'])
        async with runtime(current, request) as rt:
            found = accounts.user(current['username'])
            if not await run_in_threadpool(password_matches, value.password, found['password']):
                raise HTTPException(401, '密码不正确')
            rt.store.clear()
            rt.agent.conversation.history = []
            rt.agent.memory_interaction.reset()
            rt.agent.location, rt.agent.route, rt.agent.commute = None, None, None
            rt.pending_location = None
            return {'ok': True}

    return api
