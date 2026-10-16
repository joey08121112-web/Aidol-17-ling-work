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


app = create_app()
