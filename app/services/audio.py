"""Session lifecycle and backpressure, independent of HTTP and vendor protocols."""
import asyncio
import json
import time
from uuid import uuid4
from fastapi import WebSocketDisconnect
from ..domain import TextInput, Mode
from ..providers.realtime import RealtimeProvider, candidates


async def serve_audio(websocket, agent, config):
    await websocket.accept()
    provider = None
    tasks = []
    queue = asyncio.Queue(maxsize=64)
    send_lock = asyncio.Lock()
    session_id = uuid4().hex
    closing = asyncio.Event()

    async def send(event):
        async with send_lock:
            await asyncio.wait_for(websocket.send_json(event), timeout=5)

    try:
        # First message authenticates without exposing the password in URL/access logs.
        hello = await asyncio.wait_for(websocket.receive_json(), timeout=10)
        import secrets
        if config.app_token and not secrets.compare_digest(str(hello.get("token", "")), config.app_token):
            await websocket.close(code=1008)
            return
        if hello.get("type") != "start":
            await websocket.close(code=1008)
            return
        selected = agent.store.settings().provider
        choices = candidates(config, selected)
        if not choices:
            await send({"type": "error", "message": "没有可用的实时语音密钥。可先使用文字演示，或配置后端 .env。"})
            return
        for name in choices:
            attempt = RealtimeProvider(name, config)
            try:
                await attempt.open(agent.instructions())
                provider = attempt
                break
            except Exception:
                try:
                    await attempt.close()
                except Exception:
                    pass
                await send({"type": "provider.failed", "provider": name, "message": "连接失败，请检查密钥、网络或模型权限"})
        if provider is None:
            await send({"type": "error", "message": "语音服务均未连接成功。文字演示仍可使用。"})
            return
        agent.audio_clients += 1
        await send({"type": "ready", "provider": provider.name, "input_rate": 16000, "output_rate": 24000})
        await agent.bus.emit("audio.connected", {"provider": provider.name, "session": session_id})
        stop_at = time.monotonic() + min(max(int(hello.get("duration", 3600)), 5), 3600)

        async def receive():
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    return
                if message.get("bytes") is not None:
                    pcm = message["bytes"]
                    if len(pcm) > 32000 or len(pcm) % 2:
                        await send({"type": "error", "message": "音频帧必须是小于 1 秒的 PCM16 数据"})
                        return
                    await asyncio.wait_for(provider.audio(pcm), timeout=5)
                elif message.get("text"):
                    event = json.loads(message["text"])
                    if event.get("type") == "stop":
                        return
                    if event.get("type") == "text":
                        await provider.text(str(event.get("text", ""))[:6000])

        async def upstream():
            async for event in provider.events():
                if event["type"] == "audio":
                    # Work mode never plays assistant audio into the meeting.
                    if agent.mode != Mode.work:
                        async with send_lock:
                            await asyncio.wait_for(websocket.send_bytes(event["pcm"]), timeout=5)
                elif event["type"] == "transcript":
                    if event["text"]:
                        if queue.full():
                            await send({"type": "error", "message": "记忆处理积压，请暂停录音后重试"})
                            return
                        queue.put_nowait(event)
                    await send(event)
                else:
                    await send(event)

        async def process():
            while True:
                item = await queue.get()
                try:
                    # ASR speaker labels are not assumed; no invented diarization.
                    await agent.ingest(TextInput(text=item["text"][:6000], speaker="现场发言（未分离声纹）",
                        source="asr", event_id=session_id + ":" + str(item["event_id"])))
                    if not closing.is_set():
                        await provider.update(agent.instructions())
                finally:
                    queue.task_done()

        async def deadline():
            await asyncio.sleep(max(0, stop_at - time.monotonic()))

        worker = asyncio.create_task(process())
        tasks = [asyncio.create_task(receive()), asyncio.create_task(upstream()), asyncio.create_task(deadline())]
        done, _ = await asyncio.wait([*tasks, worker], return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if task.exception():
                raise task.exception()
        closing.set()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        try:
            await asyncio.wait_for(queue.join(), timeout=20)
        finally:
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
    except (WebSocketDisconnect, ConnectionError):
        pass
    except Exception:
        try:
            await send({"type": "error", "message": "会话已中断；已保存的记忆仍保留，请重新开始连接。"})
        except Exception:
            pass
    finally:
        closing.set()
        if 'worker' in locals():
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if provider:
            try:
                await provider.close()
            except Exception:
                pass
            finally:
                agent.audio_clients = max(0, agent.audio_clients - 1)
                await agent.bus.emit("audio.disconnected", {"session": session_id})
        try:
            await websocket.close()
        except RuntimeError:
            pass
