"""Hosted Android API; importing this module never opens account databases."""
import asyncio
from contextlib import contextmanager
import json
import logging
from pathlib import Path
import secrets
import hashlib
import time
from datetime import datetime, timezone, timedelta

from fastapi import FastAPI, HTTPException, Request, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, Response, StreamingResponse

from .config import Config
from .domain import Settings, LocationInput, MemoryInput, SpeechInput, NearbyInput
from .domain import MeetingRequest, RecallRequest, TaskStatusInput, MemoryUpdate
from .providers.volc_speech import VolcSpeechProvider, SpeechProviderError
from .services.insights import MeetingInsights, meeting_coverage
from .services.recall import RecallService
from .services.voice_stream import transcribe_live
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
    speech = VolcSpeechProvider(config.speech)
    api = FastAPI(title='Aidol Android API', version='0.2.0', docs_url=None, redoc_url=None, openapi_url=None)
    api.state.accounts, api.state.runtimes, api.state.speech = accounts, runtimes, speech
    active = 0
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
        username: str = Field(min_length=3, max_length=24)
        password: str = Field(min_length=8, max_length=128)

        @field_validator('username')
        @classmethod
        def valid_username(cls, value):
            if not re.fullmatch(r'[a-zA-Z0-9_]{3,24}', value):
                raise ValueError('账号仅支持 3–24 位字母、数字、下划线')
            return value.lower()

    class ChangePassword(BaseModel):
        old_password: str = Field(min_length=1, max_length=128)
        new_password: str = Field(min_length=8, max_length=128)

    class Recovery(Credentials):
        recovery_code: str = Field(min_length=16, max_length=128)

    class ConfirmPassword(BaseModel):
        password: str = Field(min_length=1, max_length=128)

    class Turn(BaseModel):
        text: str = Field(min_length=1, max_length=6000)
        request_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
        interaction_source: str = Field(default='ambient_capture', pattern=r'^(direct_question|ambient_capture)$')

    @api.exception_handler(RequestValidationError)
    async def invalid(request, error):
        message = '请求参数格式不正确，请检查必填项和输入长度'
        if request.url.path.startswith('/auth/'):
            message = '请检查输入：账号为 3–24 位字母、数字或下划线；密码为 8–128 位'
        elif request.url.path == '/v1/maps/weather':
            message = '请先选择目的地，或填写 6 位城市行政区代码'
        return JSONResponse({'detail': message}, 422)

    @api.middleware('http')
    async def security(request, call_next):
        # Bound actual streamed bodies, including chunked uploads without Content-Length.
        limit = 1920000 if request.url.path == '/v1/voice' else 65536
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
    async def runtime(current, request, billable=False):
        nonlocal active
        if active >= 8:
            raise HTTPException(503, '服务器正在处理较多请求，请稍后再试')
        active += 1
        try:
            async with runtimes.use(current['id']) as value:
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
        return {'ok': True, 'version': '0.2.0', 'ready': bool(config.text_key and config.text_model and config.speech.asr_ready and config.speech.tts_ready)}

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
        return {'ok': True}

    @api.get('/v1/state')
    async def mobile_state(request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            await rt.agent.decide()
            return {'user': current, 'settings': rt.store.settings().model_dump(),
                    'capabilities': config.capabilities(), 'agent': rt.agent.snapshot(),
                    'transcripts': list(reversed(rt.store.list('transcripts', 100))),
                    'memories': rt.store.list('memories', 300), 'tasks': rt.store.list('action_tasks', 300),
                    'drafts': rt.store.list('drafts', 30),
                    'meeting_report': rt.store.meeting_report()}

    @api.put('/v1/settings')
    async def mobile_settings(value: Settings, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            # Android v1 is foreground-only; do not advertise background listening.
            value.life_poll_enabled = False
            value.provider = 'cascade'
            old = rt.store.settings()
            if any(getattr(old, key) != getattr(value, key) for key in
                   ('destination_lon', 'destination_lat', 'destination_name', 'transport', 'transit_city')):
                rt.agent.route, rt.agent.last_route, rt.agent.last_notice = None, 0, 0
            rt.store.save_settings(value)
            await rt.agent.decide()
            return value

    async def process(rt, current, request_id, fingerprint, text=None, pcm=None, interaction_source='ambient_capture'):
        with measured(request_id, 'turn'):
            return await process_turn(rt, current, request_id, fingerprint, text=text, pcm=pcm, interaction_source=interaction_source)

    async def process_turn(rt, current, request_id, fingerprint, text=None, pcm=None, interaction_source='ambient_capture'):
        try:
            cached = rt.store.mobile_turn(request_id, fingerprint, reserve=False)
        except ValueError as exc:
            trace(request_id, 'cache', 'conflict')
            raise HTTPException(409, str(exc))
        if cached is not None:
            trace(request_id, 'cache', 'hit')
            return cached
        trace(request_id, 'cache', 'miss')
        if not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
            raise HTTPException(429, '今日体验额度已用完，请明天再试')
        rt.store.mobile_turn(request_id, fingerprint)
        if pcm is not None:
            async def chunks():
                for offset in range(0, len(pcm), 3200):
                    yield pcm[offset:offset + 3200]
                    await asyncio.sleep(.02)
            text = ''
            with measured(request_id, 'asr'):
                try:
                    async with asyncio.timeout(65):
                        async for result in speech.stream_asr(chunks()):
                            if result.text:
                                text = result.text
                except (SpeechProviderError, TimeoutError):
                    rt.store.fail_mobile_turn(request_id)
                    raise HTTPException(502, '识别暂不可用，请稍后重试')
                except BaseException:
                    rt.store.fail_mobile_turn(request_id)
                    raise
            if not text.strip():
                result = {'text': '', 'response': '没有听清，请靠近麦克风再试一次', 'speak': False}
                rt.store.finish_mobile_turn(request_id, result)
                return result
        rt.store.mobile_turn_stage(request_id, 'business')
        try:
            with measured(request_id, 'business'):
                if interaction_source == 'ambient_capture':
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
            fingerprint = 'text:' + digest(value.text) + (':direct_question' if value.interaction_source == 'direct_question' else '')
            return await process(rt, current, value.request_id, fingerprint, text=value.text, interaction_source=value.interaction_source)

    @api.post('/v1/voice')
    async def mobile_voice(request: Request, request_id: str = Query(pattern=r'^[a-zA-Z0-9-]{16,64}$'),
                           interaction_source: str = Query(default='ambient_capture', pattern=r'^(direct_question|ambient_capture)$'), current=Depends(user)):
        if not config.speech.asr_ready:
            raise HTTPException(503, '服务器尚未配置语音识别')
        if request.headers.get('content-type', '').split(';')[0] != 'audio/pcm':
            raise HTTPException(415, '需要 16kHz 单声道 PCM16')
        pcm = await request.body()
        if len(pcm) < 3200 or len(pcm) % 2:
            raise HTTPException(422, '录音太短或不完整，请重新录音')
        async with runtime(current, request) as rt:
            fingerprint = 'pcm:' + hashlib.sha256(pcm).hexdigest() + (':direct_question' if interaction_source == 'direct_question' else '')
            return await process(rt, current, request_id, fingerprint, pcm=pcm, interaction_source=interaction_source)

    @api.websocket('/v1/voice/stream')
    async def mobile_voice_stream(socket: WebSocket):
        # Header auth only: never put account tokens into URLs/access logs.
        current = accounts.authenticate(digest(token(socket)))
        if not current:
            await socket.close(code=4401)
            return
        await socket.accept()
        request_id = socket.query_params.get('request_id', '')
        interaction = socket.query_params.get('interaction_source', 'ambient_capture')
        reserved = False
        rt = None
        try:
            if not re.fullmatch(r'[a-zA-Z0-9-]{16,64}', request_id) or interaction not in {'direct_question', 'ambient_capture'}:
                raise HTTPException(422, '语音会话参数不正确')
            if not config.speech.asr_ready:
                raise HTTPException(503, '服务器尚未配置语音识别')
            async with runtime(current, socket) as rt:
                # A streaming ID is single-use. Recovery reads /v1/turns; no replay
                # can swap in different audio or charge twice for an uncertain turn.
                if rt.store.mobile_turn_status(request_id) is not None:
                    raise HTTPException(409, '该语音会话已提交，请查询本轮状态和历史记录')
                if not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
                    raise HTTPException(429, '今日体验额度已用完，请明天再试')
                rt.store.mobile_turn(request_id, 'stream:' + interaction)
                rt.store.mobile_turn_stage(request_id, 'streaming')
                reserved = True
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
                        result = await rt.turn(text, 'asr', interaction)
                rt.store.finish_mobile_turn(request_id, result)
                await socket.send_json({'type': 'result', 'result': result})
        except WebSocketDisconnect:
            pass
        except (HTTPException, ValueError, SpeechProviderError, TimeoutError) as error:
            status = error.status_code if isinstance(error, HTTPException) else 502
            detail = error.detail if isinstance(error, HTTPException) else '实时识别中断，请检查网络并查询本轮状态后重试'
            try:
                await socket.send_json({'type': 'error', 'status': status, 'detail': detail})
            except (WebSocketDisconnect, RuntimeError):
                pass
        finally:
            if reserved:
                rt.store.fail_mobile_turn(request_id)  # completed responses stay completed
            try:
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
            if not city and rt.store.settings().destination_lon is None:
                raise HTTPException(422, '请先选择目的地，再查询当地天气')
            try:
                if city:
                    return await rt.agent.maps.weather(city, forecast)
                settings = rt.store.settings()
                return await rt.agent.maps.weather_at(settings.destination_lon, settings.destination_lat, forecast)
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
                    'version': 3, 'drafts': rt.store.list('drafts', 10000),
                    'transcripts': rt.store.list('transcripts', 10000), 'reports': rt.store.all_meeting_reports(), 'tasks': rt.store.list('action_tasks', 10000)}

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
            rt.agent.location, rt.agent.route = None, None
            return {'ok': True}

    return api
