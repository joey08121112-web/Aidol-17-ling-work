"""Buffered speech cascade: PCM -> VAD -> Volc ASR -> agent -> Volc TTS.

This adapter intentionally uses the same browser WebSocket contract as realtime
providers. It is a configurable fallback, not a claim of realtime-model latency.
Only utterances and bounded pre-roll live in memory; raw audio is never persisted.
"""
import asyncio
from array import array
from contextlib import aclosing, suppress
import json
import math
import secrets
import sys
from uuid import uuid4

from fastapi import WebSocketDisconnect

from ..domain import Mode, TextInput
from ..providers.volc_speech import VolcSpeechProvider


INPUT_RATE = 16000
MAX_PENDING_UTTERANCES = 4


def pcm_rms(pcm: bytes) -> float:
    samples = array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    return math.sqrt(sum(value * value for value in samples) / len(samples)) / 32768 if samples else 0.0


class UtteranceSegmenter:
    """300ms pre-roll, 800ms silence, 15s maximum; durations count samples."""

    def __init__(self, threshold: float):
        self.threshold = threshold
        self.prefix = bytearray()
        self.active = bytearray()
        self.silence_bytes = 0
        self.prefix_bytes = int(INPUT_RATE * 2 * .3)
        self.end_silence_bytes = int(INPUT_RATE * 2 * .8)
        self.max_bytes = INPUT_RATE * 2 * 15

    def push(self, pcm: bytes):
        """Accept one <=20ms quantum; return (speech_started, complete_pcm)."""
        voiced = pcm_rms(pcm) >= self.threshold
        started = False
        if not self.active:
            if not voiced:
                self.prefix.extend(pcm)
                del self.prefix[:-self.prefix_bytes]
                return False, None
            started = True
            self.active.extend(self.prefix)
            self.prefix.clear()
        self.active.extend(pcm)
        self.silence_bytes = 0 if voiced else self.silence_bytes + len(pcm)
        if len(self.active) >= self.max_bytes:
            complete = bytes(self.active[:self.max_bytes])
            del self.active[:self.max_bytes]
            self.silence_bytes = 0
            return started, complete
        if self.silence_bytes >= self.end_silence_bytes:
            return started, self.flush()
        return started, None

    def flush(self):
        value = bytes(self.active) if self.active else None
        self.active.clear()
        self.silence_bytes = 0
        return value


async def serve_cascade(websocket, agent, config):
    await websocket.accept()
    queue = asyncio.Queue(maxsize=MAX_PENDING_UTTERANCES)
    lock = asyncio.Lock()
    tasks = []
    counted = False
    closing = False
    turn = 0
    sequence = 0
    session_id = uuid4().hex
    segmenter = UtteranceSegmenter(agent.store.settings().vad_threshold)

    async def send(event):
        async with lock:
            await asyncio.wait_for(websocket.send_json(event), 5)

    async def enqueue(pcm):
        nonlocal sequence
        if not pcm:
            return
        if queue.full():
            await send({"type": "error", "message": "语音处理积压，已停止采集；请稍后重新连接。"})
            raise BufferError("cascade utterance queue full")
        sequence += 1
        queue.put_nowait((turn, sequence, pcm))

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
            await send({"type": "error", "message": "尚未配置独立豆包 ASR，无法开启级联语音。"})
            return
        speech = VolcSpeechProvider(config.speech)
        agent.audio_clients += 1
        counted = True
        await send({"type": "ready", "provider": "cascade", "input_rate": INPUT_RATE,
                    "output_rate": config.speech.output_rate, "tts": config.speech.tts_ready})
        await agent.bus.emit("audio.connected", {"provider": "cascade", "session": session_id})

        async def receive():
            nonlocal turn
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    return
                pcm = message.get("bytes")
                if pcm is not None:
                    if not pcm or len(pcm) > 32000 or len(pcm) % 2:
                        await send({"type": "error", "message": "音频帧必须是 1 秒以内的完整 PCM16 单声道数据"})
                        return
                    # Browser normally sends 40ms. Smaller VAD quanta make
                    # threshold behavior independent of WebSocket packet sizes.
                    for offset in range(0, len(pcm), 640):
                        started, complete = segmenter.push(pcm[offset:offset + 640])
                        if started:
                            turn += 1
                            await send({"type": "interrupt"})
                        await enqueue(complete)
                elif message.get("text"):
                    try:
                        command = json.loads(message["text"])
                    except (json.JSONDecodeError, TypeError):
                        await send({"type": "error", "message": "无效语音控制消息"})
                        return
                    if not isinstance(command, dict):
                        await send({"type": "error", "message": "无效语音控制消息"})
                        return
                    if command.get("type") == "stop":
                        return

        async def process():
            while True:
                item_turn, number, pcm = await queue.get()
                try:
                    async def chunks():
                        for offset in range(0, len(pcm), 3200):
                            yield pcm[offset:offset + 3200]

                    text = ""
                    if config.asr_mode == "flash":
                        recognized = await speech.recognize(pcm, format="pcm", codec="raw")
                        text = recognized.text[:6000]
                    else:
                        async with aclosing(speech.stream_asr(chunks())) as results:
                            async for result in results:
                                if result.text:
                                    text = result.text[:6000]
                    if not text:
                        continue
                    event_id = f"{session_id}:{number}"
                    if not closing:
                        await send({"type": "transcript", "text": text, "event_id": event_id})
                    result = await agent.ingest(TextInput(text=text, speaker="现场发言（未分离声纹）",
                        source="asr", event_id=event_id), respond=True)
                    response = result.get("response", "")
                    if closing or item_turn != turn or segmenter.active or agent.mode == Mode.work or not response:
                        continue
                    await send({"type": "assistant.delta", "text": response})
                    if config.speech.tts_ready:
                        async with aclosing(speech.stream_tts(response)) as audio:
                            async for packet in audio:
                                if closing or item_turn != turn or agent.mode == Mode.work:
                                    break
                                async with lock:
                                    if not closing and item_turn == turn and agent.mode != Mode.work:
                                        await asyncio.wait_for(websocket.send_bytes(packet), 5)
                    if not closing and item_turn == turn:
                        await send({"type": "response.done"})
                finally:
                    queue.task_done()

        worker = asyncio.create_task(process())
        receiver = asyncio.create_task(receive())
        deadline = asyncio.create_task(asyncio.sleep(duration))
        tasks = [worker, receiver, deadline]
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if not task.cancelled() and task.exception():
                raise task.exception()
        closing = True
        receiver.cancel()
        deadline.cancel()
        await asyncio.gather(receiver, deadline, return_exceptions=True)
        # Keep the final short utterance on an explicit stop/disconnect. Stop
        # playback immediately, but give memory ingestion a bounded drain window.
        await enqueue(segmenter.flush())
        drain = asyncio.create_task(queue.join())
        try:
            drained, _ = await asyncio.wait([drain, worker], timeout=20, return_when=asyncio.FIRST_COMPLETED)
            if worker in drained and not worker.cancelled() and worker.exception():
                raise worker.exception()
        finally:
            drain.cancel()
            await asyncio.gather(drain, return_exceptions=True)
    except (WebSocketDisconnect, ConnectionError):
        pass
    except BufferError:
        pass
    except Exception:
        with suppress(Exception):
            await send({"type": "error", "message": "级联语音服务中断，请检查 ASR/TTS 权限、网络和模型配额后重连。"})
    finally:
        closing = True
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if counted:
            agent.audio_clients = max(0, agent.audio_clients - 1)
            with suppress(Exception):
                await agent.bus.emit("audio.disconnected", {"provider": "cascade", "session": session_id})
        with suppress(Exception):
            await websocket.close()
