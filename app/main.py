import asyncio
from contextlib import asynccontextmanager
import json
from pathlib import Path
import secrets
import hashlib
from datetime import datetime, timezone, timedelta

from fastapi import FastAPI, HTTPException, Request, WebSocket, Query
from fastapi.responses import StreamingResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .config import Config
from .domain import Settings, TextInput, LocationInput, MemoryInput, SpeechInput, NearbyInput
from .domain import MeetingRequest, RecallRequest, TaskStatusInput, MemoryUpdate
from .storage import Store
from .events import EventBus
from .services.agent import Agent
from .services.audio import serve_audio
from .providers.volc_speech import VolcSpeechProvider, SpeechProviderError
from .services.insights import MeetingInsights
from .services.recall import RecallService


def create_app(config=None):
    config = config or Config()
    store = Store(config.database_path)
    bus = EventBus(store)
    agent = Agent(config, store, bus)
    speech = VolcSpeechProvider(config.speech)
    audio_lock = asyncio.Lock()
    meeting_lock = asyncio.Lock()
    insights, recall = MeetingInsights(config), RecallService(config)
    data_generation = 0

    @asynccontextmanager
    async def lifespan(app):
        await agent.decide()
        scheduler = asyncio.create_task(agent.scheduler())
        yield
        scheduler.cancel()
        await asyncio.gather(scheduler, return_exceptions=True)

    app = FastAPI(title="Aidol · 聆", version="0.1.0", lifespan=lifespan)
    app.state.store, app.state.agent, app.state.bus = store, agent, bus
    app.state.speech = speech
    app.state.insights, app.state.recall = insights, recall

    @app.middleware("http")
    async def guard(request: Request, call_next):
        if request.url.path.startswith("/api/") and request.url.path != "/api/health":
            token = request.headers.get("authorization", "").removeprefix("Bearer ")
            if config.app_token and not secrets.compare_digest(token, config.app_token):
                return JSONResponse({"detail": "需要访问口令"}, status_code=401)
            origin = request.headers.get("origin")
            if request.method not in {"GET", "HEAD"} and origin and origin.rstrip("/") != str(request.base_url).rstrip("/"):
                return JSONResponse({"detail": "跨站请求被拒绝"}, status_code=403)
            try:
                length = int(request.headers.get("content-length", "0"))
            except ValueError:
                return JSONResponse({"detail": "无效的 Content-Length"}, status_code=400)
            limit = 2 * 1024 * 1024 if request.url.path == "/api/speech/transcribe" else 65536
            if length > limit:
                return JSONResponse({"detail": "请求过大"}, status_code=413)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["X-Frame-Options"] = "DENY"
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/api/health")
    async def health():
        return {"ok": True, "version": "0.1.0", "auth_required": bool(config.app_token)}

    @app.get("/api/state")
    async def state():
        return {"agent": agent.snapshot(), "settings": store.settings().model_dump(),
                "capabilities": config.capabilities(), "memories": store.list("memories"),
                "transcripts": store.list("transcripts"), "events": store.list("events", 40),
                "drafts": store.list("drafts", 30), "meeting_report": store.meeting_report(),
                "action_tasks": store.list("action_tasks", 300)}

    @app.post("/api/meetings/summarize")
    async def summarize_meeting(value: MeetingRequest):
        # Serializing generation avoids duplicate billable calls for the same snapshot.
        async with meeting_lock:
            generation = data_generation
            cutoff = (datetime.now(timezone.utc) - timedelta(hours=value.hours)).isoformat()
            transcripts = [row for row in store.list("transcripts", 1000)
                           if row["mode"] == "work" and row["created_at"] >= cutoff
                           and (value.include_demo or row["source"] != "demo")][:80]
            if not transcripts:
                raise HTTPException(422, "所选时段没有工作转写；请先记录会议，或勾选包含模拟演示")
            signature = hashlib.sha256(json.dumps(transcripts, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
            existing = store.meeting_report(signature)
            if existing:
                return {**existing, "cached": True}
            report = await insights.generate(transcripts)
            if generation != data_generation:
                raise HTTPException(409, "数据已清空，本次纪要已取消，请重新生成")
            source_ids = set(report["source_ids"])
            report.update(sources=[row for row in reversed(transcripts) if row["id"] in source_ids],
                          hours=value.hours, includes_demo=any(row["source"] == "demo" for row in transcripts))
            saved = store.save_meeting_report(signature, report)
            await bus.emit("meeting.created", {"id": saved["id"], "tasks": len(saved["tasks"])}, persist=False)
            return {**saved, "cached": False}

    @app.post("/api/recall")
    async def ask_memory(value: RecallRequest):
        generation = data_generation
        memories = [row for row in store.list("memories", 500)
                    if value.include_demo or not row["source"].startswith("demo")]
        transcripts = [row for row in store.list("transcripts", 500)
                       if value.include_demo or row["source"] != "demo"]
        reports = {row["id"]: row for row in store.all_meeting_reports()}
        tasks = []
        for task in store.list("action_tasks", 300):
            if not value.include_demo and reports.get(task["report_id"], {}).get("includes_demo"):
                continue
            tasks.append({"id": "task:" + task["id"], "kind": "task", "source": "action_task", "created_at": task["created_at"],
                          "content": f"待办：{task['content']}；负责人：{task['owner'] or '未明确'}；截止：{task['due'] or '未明确'}；完成状态：{'已完成' if task['status'] == 'done' else '未完成'}"})
        try:
            answer = await recall.answer(value.question, tasks + memories, transcripts)
        except ValueError:
            raise HTTPException(422, "问题需要 1 至 500 个有效字符")
        if generation != data_generation:
            raise HTTPException(409, "数据已清空，本次回答已取消")
        return answer

    @app.patch("/api/tasks/{task_id}")
    async def update_task(task_id: str, value: TaskStatusInput):
        if not store.action_task_status(task_id, value.status):
            raise HTTPException(404, "待办不存在")
        await bus.emit("task.updated", {"id": task_id, "status": value.status}, persist=False)
        return {"ok": True, "status": value.status}

    @app.put("/api/settings")
    async def settings(value: Settings):
        old = store.settings()
        route_fields = ("destination_lon", "destination_lat", "destination_name", "transport", "transit_city")
        if any(getattr(old, key) != getattr(value, key) for key in route_fields):
            agent.route, agent.last_route, agent.last_notice = None, 0, 0
        store.save_settings(value)
        await agent.decide()
        await bus.emit("settings.updated", {"mode": agent.mode, "auto_mode": value.auto_mode})
        return value

    @app.post("/api/text")
    async def text(value: TextInput):
        return await agent.ingest(value)

    @app.post("/api/location")
    async def location(value: LocationInput):
        return await agent.update_location(value)

    @app.get("/api/maps/search")
    async def search_places(q: str = Query(min_length=1, max_length=100), city: str = Query(default="", max_length=80)):
        try:
            return await agent.maps.search(q, city)
        except Exception:
            raise HTTPException(502, "目的地搜索失败，请检查高德配置、网络与服务配额")

    @app.post("/api/maps/nearby")
    async def nearby_places(value: NearbyInput):
        try:
            return await agent.maps.nearby(value, value.keyword)
        except Exception:
            raise HTTPException(502, "周边查询失败，请检查高德配置与定位权限")

    @app.get("/api/maps/weather")
    async def weather(city: str = Query(min_length=1, max_length=20)):
        try:
            return await agent.maps.weather(city)
        except Exception:
            raise HTTPException(502, "天气查询失败")

    @app.post("/api/speech/synthesize")
    async def synthesize(value: SpeechInput):
        if not config.speech.tts_ready:
            raise HTTPException(503, "尚未配置独立豆包 TTS")
        try:
            wav = await speech.synthesize_wav(value.text)
            return Response(wav, media_type="audio/wav", headers={"Cache-Control": "no-store"})
        except (SpeechProviderError, TimeoutError):
            raise HTTPException(502, "语音合成失败，请检查 TTS 权限、音色与配额")

    @app.post("/api/speech/transcribe")
    async def transcribe(request: Request):
        """Accept PCM16 16kHz mono or WAV; streaming ASR avoids separate flash quota."""
        if not config.speech.asr_ready:
            raise HTTPException(503, "尚未配置独立豆包 ASR")
        import io
        import wave
        payload = bytearray()
        async for chunk in request.stream():
            payload.extend(chunk)
            if len(payload) > 2 * 1024 * 1024:
                raise HTTPException(413, "录音最多 2 MB")
        pcm = bytes(payload)
        if pcm.startswith(b"RIFF"):
            try:
                with wave.open(io.BytesIO(pcm)) as wav:
                    if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                        raise HTTPException(422, "WAV 必须是 16kHz、单声道、16bit PCM")
                    pcm = wav.readframes(wav.getnframes())
            except (wave.Error, EOFError):
                raise HTTPException(422, "无效 WAV 文件")
        elif request.headers.get("content-type", "").split(";")[0] != "audio/pcm":
            raise HTTPException(415, "仅支持 WAV 或 audio/pcm")
        if not pcm or len(pcm) % 2:
            raise HTTPException(422, "PCM 音频为空或数据不完整")
        async def chunks():
            for offset in range(0, len(pcm), 3200):
                yield pcm[offset:offset + 3200]
                await asyncio.sleep(0.02)
        try:
            final = ""
            if config.asr_mode == "flash":
                result = await speech.recognize(pcm, format="pcm", codec="raw")
                final = result.text
            else:
                async for result in speech.stream_asr(chunks()):
                    if result.text:
                        final = result.text
            if not final:
                return {"text": "", "response": "未识别到有效语音"}
            processed = await agent.ingest(TextInput(text=final[:6000], speaker="录音（未分离声纹）", source="asr"))
            return {"text": final, **processed}
        except (SpeechProviderError, TimeoutError):
            raise HTTPException(502, "语音识别失败，请检查 ASR 权限与录音格式")

    @app.get("/api/memories")
    async def memories(q: str = Query(default="", max_length=200)):
        return store.list("memories", 300, q)

    @app.post("/api/memories")
    async def add_memory(value: MemoryInput):
        row = store.add("memories", **value.model_dump(), source="user")
        await bus.emit("memory.created", row, persist=False)
        return row

    @app.post("/api/memories/from-transcript/{transcript_id}")
    async def remember_transcript(transcript_id: str):
        row = store.remember_transcript(transcript_id)
        if row is None:
            raise HTTPException(404, "转写已不存在，请刷新后重试")
        await bus.emit("memory.created", row, persist=False)
        return row

    @app.get("/api/memories/{memory_id}/source")
    async def memory_source(memory_id: str):
        source = store.memory_source(memory_id)
        if source is None:
            raise HTTPException(404, "该记忆没有可追溯的原始转写")
        return source

    @app.delete("/api/memories/{memory_id}")
    async def delete_memory(memory_id: str):
        if not store.delete_memory(memory_id):
            raise HTTPException(404, "记忆不存在")
        await bus.emit("memory.deleted", {"id": memory_id}, persist=False)
        return {"ok": True}

    @app.put("/api/memories/{memory_id}")
    async def edit_memory(memory_id: str, value: MemoryUpdate):
        if not store.update_memory(memory_id, value.content, value.kind, value.pinned):
            raise HTTPException(404, "记忆不存在")
        await bus.emit("memory.updated", {"id": memory_id}, persist=False)
        return {"ok": True}

    @app.post("/api/drafts/{draft_id}/{action}")
    async def draft(draft_id: str, action: str):
        if action not in {"copied", "dismissed"}:
            raise HTTPException(400, "只支持复制或忽略；尚未接入发送渠道")
        if not store.draft_status(draft_id, action):
            raise HTTPException(404, "草稿不存在或已处理")
        return {"ok": True, "status": action, "sent": False}

    @app.get("/api/export")
    async def export():
        return {"version": 2, "settings": store.settings().model_dump(),
                "memories": store.list("memories", 10000), "transcripts": store.list("transcripts", 10000),
                "drafts": store.list("drafts", 10000), "meeting_reports": store.all_meeting_reports(),
                "action_tasks": store.list("action_tasks", 10000)}

    @app.delete("/api/data")
    async def clear_data():
        nonlocal data_generation
        if agent.audio_clients:
            raise HTTPException(409, "请先停止录音")
        async with agent.ingest_lock:
            data_generation += 1
            store.clear()
            agent.location, agent.route = None, None
            agent.last_notice, agent.last_motion = 0, 0
        await bus.emit("data.cleared", {}, persist=False)
        return {"ok": True}

    @app.get("/api/events")
    async def events(request: Request):
        async def stream():
            queue = bus.subscribe()
            try:
                yield ": connected\n\n"
                while not await request.is_disconnected():
                    try:
                        event = await asyncio.wait_for(queue.get(), timeout=15)
                        yield "data: " + json.dumps(event, ensure_ascii=False) + "\n\n"
                    except asyncio.TimeoutError:
                        yield ": heartbeat\n\n"
            finally:
                bus.unsubscribe(queue)
        return StreamingResponse(stream(), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})

    @app.websocket("/ws/audio")
    async def audio(websocket: WebSocket):
        origin = websocket.headers.get("origin")
        from urllib.parse import urlparse
        if origin and urlparse(origin).netloc != websocket.headers.get("host"):
            await websocket.close(code=1008)
            return
        if audio_lock.locked():
            await websocket.accept()
            await websocket.send_json({"type": "error", "message": "单用户 Demo 一次只允许一个语音会话"})
            await websocket.close(code=1008)
            return
        async with audio_lock:
            if store.settings().provider == "cascade":
                if config.asr_mode == "stream":
                    from .services.streaming_cascade import serve_streaming_cascade
                    await serve_streaming_cascade(websocket, agent, config)
                else:
                    from .services.cascade import serve_cascade
                    await serve_cascade(websocket, agent, config)
            else:
                await serve_audio(websocket, agent, config)

    app.mount("/", StaticFiles(directory=Path(__file__).resolve().parent.parent / "web", html=True), name="web")
    return app


# Hosted startup does not open the developer's single-user database or web surface.
import os as _os
app = None if _os.getenv('AIDOL_HOSTED', '').lower() == 'true' else create_app()


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
    from .storage import AccountStore
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

    async def process(rt, current, request_id, fingerprint, text=None, pcm=None):
        try:
            cached = rt.store.mobile_turn(request_id, fingerprint, reserve=False)
        except ValueError as exc:
            raise HTTPException(409, str(exc))
        if cached is not None:
            return cached
        if not accounts.consume('usage:' + current['id'], int(os.getenv('AIDOL_DAILY_REQUESTS', '300')), 86400):
            raise HTTPException(429, '今日体验额度已用完，请明天再试')
        rt.store.mobile_turn(request_id, fingerprint)
        if pcm is not None:
            async def chunks():
                for offset in range(0, len(pcm), 3200):
                    yield pcm[offset:offset + 3200]
                    await asyncio.sleep(.02)
            text = ''
            try:
                async with asyncio.timeout(65):
                    async for result in speech.stream_asr(chunks()):
                        if result.text:
                            text = result.text
            except (SpeechProviderError, TimeoutError):
                raise HTTPException(502, '识别暂不可用，请稍后重试')
            if not text.strip():
                result = {'text': '', 'response': '没有听清，请靠近麦克风再试一次', 'speak': False}
                rt.store.finish_mobile_turn(request_id, result)
                return result
        result = await rt.turn(text[:6000], 'asr' if pcm is not None else 'text')
        rt.store.finish_mobile_turn(request_id, result)
        return result

    @api.post('/v1/chat')
    async def mobile_chat(value: Turn, request: Request, current=Depends(user)):
        if not value.text.strip():
            raise HTTPException(422, '请输入内容')
        async with runtime(current, request) as rt:
            return await process(rt, current, value.request_id, 'text:' + digest(value.text), text=value.text)

    @api.post('/v1/voice')
    async def mobile_voice(request: Request, request_id: str = Query(pattern=r'^[a-zA-Z0-9-]{16,64}$'), current=Depends(user)):
        if not config.speech.asr_ready:
            raise HTTPException(503, '服务器尚未配置语音识别')
        if request.headers.get('content-type', '').split(';')[0] != 'audio/pcm':
            raise HTTPException(415, '需要 16kHz 单声道 PCM16')
        pcm = await request.body()
        if len(pcm) < 3200 or len(pcm) % 2:
            raise HTTPException(422, '录音太短或不完整，请重新录音')
        async with runtime(current, request) as rt:
            return await process(rt, current, request_id, 'pcm:' + hashlib.sha256(pcm).hexdigest(), pcm=pcm)

    @api.post('/v1/speech')
    async def mobile_speech(value: SpeechInput, request: Request, current=Depends(user)):
        if not config.speech.tts_ready:
            raise HTTPException(503, '服务器尚未配置语音合成')
        async with runtime(current, request, True):
            try:
                wav = await speech.synthesize_wav(value.text)
                return Response(wav, media_type='audio/wav')
            except (SpeechProviderError, TimeoutError):
                raise HTTPException(502, '语音播放暂不可用，文字回复已保留')

    @api.post('/v1/memories')
    async def mobile_memory(value: MemoryInput, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            return rt.store.add('memories', **value.model_dump(), source='user')

    @api.post('/v1/memories/from-transcript/{transcript_id}')
    async def mobile_remember(transcript_id: str, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            row = rt.store.remember_transcript(transcript_id)
            if row is None:
                raise HTTPException(404, '原始对话不存在')
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
            return {'ok': True}

    @api.put('/v1/memories/{memory_id}')
    async def mobile_edit_memory(memory_id: str, value: MemoryUpdate, request: Request, current=Depends(user)):
        async with runtime(current, request) as rt:
            if not rt.store.update_memory(memory_id, value.content, value.kind, value.pinned):
                raise HTTPException(404, '记忆不存在')
            return {'ok': True}

    @api.post('/v1/recall')
    async def mobile_recall(value: RecallRequest, request: Request, current=Depends(user)):
        if not value.question.strip():
            raise HTTPException(422, '请输入你想回忆的问题')
        async with runtime(current, request, True) as rt:
            tasks = [{'id': 'task:' + row['id'], 'kind': 'task', 'source': 'action_task', 'created_at': row['created_at'],
                      'content': f"待办：{row['content']}；负责人：{row['owner'] or '未明确'}；截止：{row['due'] or '未明确'}；完成状态：{'已完成' if row['status'] == 'done' else '未完成'}"}
                     for row in rt.store.list('action_tasks', 300)]
            return await RecallService(config).answer(value.question, tasks + rt.store.list('memories', 500), rt.store.list('transcripts', 500))

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
                return await rt.agent.maps.nearby(value, value.keyword)
            except Exception:
                raise HTTPException(502, '周边查询暂不可用，请稍后再试')

    @api.get('/v1/maps/weather')
    async def mobile_weather(request: Request, city: str = Query(default='', pattern=r'^([0-9]{6})?$'), current=Depends(user)):
        async with runtime(current, request, True) as rt:
            if not city and rt.store.settings().destination_lon is None:
                raise HTTPException(422, '请先选择目的地，再查询当地天气')
            try:
                if city:
                    return await rt.agent.maps.weather(city)
                settings = rt.store.settings()
                return await rt.agent.maps.weather_at(settings.destination_lon, settings.destination_lat)
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
            rows = [r for r in rt.store.list('transcripts', 1000) if r['mode'] == 'work' and r['speaker'] != 'assistant' and r['created_at'] >= cutoff][:80]
            if not rows:
                raise HTTPException(422, '请先在工作模式记录会议，再生成纪要')
            signature = digest(json.dumps(rows, sort_keys=True, ensure_ascii=False))
            old = rt.store.meeting_report(signature)
            if old:
                return old
            report = await MeetingInsights(config).generate(rows)
            report.update(sources=list(reversed(rows)), hours=value.hours, includes_demo=False)
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
            rt.agent.location, rt.agent.route = None, None
            return {'ok': True}

    return api
