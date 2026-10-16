"""Continuous PCM/ASR transport with independent memory and response workers.

Unlike flash cascade, VAD never buffers or gates ASR input. It only interrupts
assistant playback. Vendor definite utterances supply the transcription boundary.
"""
import asyncio
from contextlib import aclosing, suppress
from dataclasses import replace
import json
import secrets
from uuid import uuid4

from fastapi import WebSocketDisconnect

from ..domain import Mode, TextInput
from ..providers.volc_speech import VolcSpeechProvider
from .cascade import pcm_rms


AUDIO_QUEUE_SIZE = 64
TRANSCRIPT_QUEUE_SIZE = 32


class FinalUtterances:
    """Commit stable vendor utterances once, never the repeated cumulative text."""

    def __init__(self):
        self.seen = set()
        self.fallback_done = False
        self.committed_text = []

    def accept(self, result):
        finalized = []
        for utterance in result.utterances:
            if not isinstance(utterance, dict):
                continue
            text = str(utterance.get("text", "")).strip()
            if not text or not (utterance.get("definite") is True or result.final):
                continue
            start, end = utterance.get("start_time"), utterance.get("end_time")
            # Text is intentionally absent from timestamp identity: a later
            # punctuation correction must not duplicate an already final sentence.
            key = ("time", str(start), str(end)) if start is not None and end is not None else ("text", text)
            if key in self.seen:
                continue
            if len(self.seen) >= 4096:
                raise BufferError("too many utterances in session")
            self.seen.add(key)
            finalized.append(text[:6000])
            self.committed_text.append(text)
        # Some resources omit utterance metadata entirely. Their final complete
        # result is valid once, but cannot replace previously committed sentences.
        if result.final and not result.utterances and not self.fallback_done:
            self.fallback_done = True
            remaining = result.text.strip()
            for committed in self.committed_text:
                if not remaining.startswith(committed):
                    # An unmatched cumulative revision cannot safely be treated
                    # as a new sentence. Preserve already committed evidence.
                    remaining = ""
                    break
                remaining = remaining[len(committed):].lstrip()
            if remaining:
                finalized.append(remaining[:6000])
        return finalized


async def serve_streaming_cascade(websocket, agent, config):
    await websocket.accept()
    audio_queue = asyncio.Queue(maxsize=AUDIO_QUEUE_SIZE)
    transcripts = asyncio.Queue(maxsize=TRANSCRIPT_QUEUE_SIZE)
    send_lock = asyncio.Lock()
    tasks = []
    playback_tasks = set()
    playback = None
    counted = False
    stopping = False
    turn = 0
    session_id = uuid4().hex

    async def send(event):
        async with send_lock:
            await asyncio.wait_for(websocket.send_json(event), 5)

    def interrupt_playback():
        nonlocal playback
        if playback is not None:
            playback.cancel()
            playback = None

    try:
        hello = await asyncio.wait_for(websocket.receive_json(), 10)
        if not isinstance(hello, dict) or hello.get("type") != "start":
            await websocket.close(code=1008)
            return
        if config.app_token and not secrets.compare_digest(str(hello.get("token", "")), config.app_token):
            await websocket.close(code=1008)
            return
        try:
            duration = min(3600, max(5, int(hello.get("duration", 3600))))
        except (ValueError, TypeError):
            await send({"type": "error", "message": "录音时长必须是有效秒数"})
            return
        if not config.speech.asr_ready:
            await send({"type": "error", "message": "尚未配置连续豆包 ASR"})
            return
        asr = VolcSpeechProvider(replace(config.speech, timeout=duration + 15))
        tts = VolcSpeechProvider(config.speech)
        agent.audio_clients += 1
        counted = True
        await send({"type": "ready", "provider": "cascade", "asr_mode": "stream", "input_rate": 16000,
                    "output_rate": config.speech.output_rate, "tts": config.speech.tts_ready})
        await agent.bus.emit("audio.connected", {"provider": "cascade", "asr_mode": "stream", "session": session_id})

        async def chunks():
            while True:
                pcm = await audio_queue.get()
                try:
                    if pcm is None:
                        return
                    yield pcm
                finally:
                    audio_queue.task_done()

        async def receive():
            nonlocal turn
            voiced = False
            silence_bytes = 0
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    return "disconnected"
                pcm = message.get("bytes")
                if pcm is not None:
                    if not pcm or len(pcm) % 2 or len(pcm) > 32000:
                        await send({"type": "error", "message": "音频帧必须是 1 秒以内的完整 PCM16 数据"})
                        return "invalid"
                    if audio_queue.full():
                        await send({"type": "error", "message": "ASR 音频上传积压，已停止录音，请检查网络后重连。"})
                        raise BufferError("audio queue full")
                    # Send every frame immediately, including silence. No local
                    # utterance completion or recognition request per sentence.
                    audio_queue.put_nowait(pcm)
                    threshold = agent.store.settings().vad_threshold
                    for offset in range(0, len(pcm), 640):
                        quantum = pcm[offset:offset + 640]
                        if pcm_rms(quantum) >= threshold:
                            silence_bytes = 0
                            if not voiced:
                                voiced = True
                                turn += 1
                                interrupt_playback()
                                await send({"type": "interrupt"})
                        elif voiced:
                            silence_bytes += len(quantum)
                            if silence_bytes >= 25600:
                                voiced = False
                elif message.get("text"):
                    command = json.loads(message["text"])
                    if not isinstance(command, dict):
                        raise ValueError("invalid control frame")
                    if command.get("type") == "stop":
                        return "stop"

        async def recognize():
            tracker = FinalUtterances()
            number = 0
            last_partial = None
            async with aclosing(asr.stream_asr(chunks())) as results:
                async for result in results:
                    if result.text and not result.final and result.text != last_partial:
                        last_partial = result.text
                        await send({"type": "transcript.delta", "text": result.text[:6000]})
                    for text in tracker.accept(result):
                        if transcripts.full():
                            await send({"type": "error", "message": "转写记忆处理积压，已停止录音，请稍后重连。"})
                            raise BufferError("transcript queue full")
                        number += 1
                        event_id = f"{session_id}:{number}"
                        transcripts.put_nowait((turn, event_id, text))
                        await send({"type": "transcript", "text": text, "event_id": event_id})

        async def reply(text, item_turn):
            try:
                if stopping or item_turn != turn or agent.mode == Mode.work:
                    return
                await send({"type": "assistant.delta", "text": text})
                if config.speech.tts_ready:
                    async with aclosing(tts.stream_tts(text)) as packets:
                        async for packet in packets:
                            if stopping or item_turn != turn or agent.mode == Mode.work:
                                return
                            async with send_lock:
                                if not stopping and item_turn == turn and agent.mode != Mode.work:
                                    await asyncio.wait_for(websocket.send_bytes(packet), 5)
                if not stopping and item_turn == turn and agent.mode != Mode.work:
                    await send({"type": "response.done"})
            except Exception:
                if not stopping:
                    with suppress(Exception):
                        await send({"type": "error", "message": "语音回复暂时不可用，转写与记忆仍继续。"})

        async def remember():
            nonlocal playback
            while True:
                item_turn, event_id, text = await transcripts.get()
                try:
                    result = await agent.ingest(TextInput(text=text, source="asr",
                        speaker="现场发言（未分离声纹）", event_id=event_id), respond=True)
                    response = result.get("response", "")
                    if response and not stopping and item_turn == turn and agent.mode != Mode.work:
                        interrupt_playback()
                        playback = asyncio.create_task(reply(response, item_turn))
                        playback_tasks.add(playback)
                        playback.add_done_callback(playback_tasks.discard)
                finally:
                    transcripts.task_done()

        receiver = asyncio.create_task(receive())
        recognizer = asyncio.create_task(recognize())
        memory = asyncio.create_task(remember())
        deadline = asyncio.create_task(asyncio.sleep(duration))
        tasks = [receiver, recognizer, memory, deadline]
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if not task.cancelled() and task.exception():
                raise task.exception()
        stopping = True
        interrupt_playback()
        if receiver in done and receiver.result() in {"disconnected", "invalid"}:
            return
        receiver.cancel()
        deadline.cancel()
        await asyncio.gather(receiver, deadline, return_exceptions=True)
        if not recognizer.done():
            # Ending the iterator sends the provider's final PCM packet. Allow
            # a bounded flush on explicit stop/deadline, never on disconnect.
            await asyncio.wait_for(audio_queue.put(None), 1)
            await asyncio.wait_for(recognizer, 10)
        drain = asyncio.create_task(transcripts.join())
        try:
            completed, _ = await asyncio.wait([drain, memory], timeout=10, return_when=asyncio.FIRST_COMPLETED)
            if memory in completed and not memory.cancelled() and memory.exception():
                raise memory.exception()
        finally:
            drain.cancel()
            await asyncio.gather(drain, return_exceptions=True)
    except (WebSocketDisconnect, ConnectionError):
        pass
    except BufferError:
        pass
    except Exception:
        with suppress(Exception):
            await send({"type": "error", "message": "连续语音连接中断，请检查 ASR 资源权限、网络与配额后重连。"})
    finally:
        stopping = True
        interrupt_playback()
        pending = [*tasks, *playback_tasks]
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        if counted:
            agent.audio_clients = max(0, agent.audio_clients - 1)
            with suppress(Exception):
                await agent.bus.emit("audio.disconnected", {"provider": "cascade", "asr_mode": "stream", "session": session_id})
        with suppress(Exception):
            await websocket.close()
