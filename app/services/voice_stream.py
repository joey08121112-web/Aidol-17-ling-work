"""Bounded live upload: capture and provider ASR run concurrently.

Only an explicit end frame allows business execution. Disconnect, backpressure,
timeouts, odd samples and overruns cancel both sides without processing a turn.
"""
import asyncio
import json
from contextlib import aclosing

from fastapi import WebSocketDisconnect
from ..providers.volc_speech import SpeechProviderError


class CompletionBarrier:
    """Publish stream.done only after provider cleanup and the account lease release."""
    def __init__(self, socket):
        self.socket = socket
        self.completed = False

    def __getattr__(self, key):
        return getattr(self.socket, key)

    async def send_json(self, value):
        if value.get('type') == 'stream.done':
            self.completed = True
        else:
            if value.get('type') == 'error':
                self.completed = False
            await self.socket.send_json(value)

    async def flush(self):
        if self.completed:
            self.completed = False
            await self.socket.send_json({'type': 'stream.done'})


async def reply_live(socket, runtime, speech, text, interaction, commit):
    """One opted-in turn: reply PCM overlaps extraction; result is still durable.

    PCM16/24kHz binary frames end only at reply.done. stream.done confirms both
    business and voice completion. Disconnect cancels every child before unlock.
    """
    send_lock = asyncio.Lock()
    state_lock = asyncio.Lock()
    interruption_done = asyncio.Event()
    voice = None
    announced = False
    interrupted = False
    voice_complete = False

    async def send(value):
        async with send_lock, asyncio.timeout(15):
            if isinstance(value, bytes):
                await socket.send_bytes(value)
            else:
                await socket.send_json(value)

    async def render(answer):
        nonlocal voice_complete
        try:
            size = 0
            async with asyncio.timeout(60):
                # Keep the displayed/stored answer intact; each provider request
                # stays bounded without silently dropping the end of the reply.
                for start in range(0, len(answer), 1500):
                    async with aclosing(speech.stream_tts(answer[start:start + 1500])) as chunks:
                        async for chunk in chunks:
                            if not chunk:
                                continue
                            size += len(chunk)
                            if len(chunk) % 2 or size > 6 * 1024 * 1024:
                                raise SpeechProviderError('Invalid PCM')
                            for offset in range(0, len(chunk), 24000):
                                await send(chunk[offset:offset + 24000])
            if not size:
                raise SpeechProviderError('Empty PCM')
            await send({'type': 'reply.done'})
            voice_complete = True
        except (SpeechProviderError, TimeoutError):
            await send({'type': 'reply.error', 'detail': '语音中断，文字回复仍可查看；不会自动重播'})
            voice_complete = True

    async def response_ready(answer):
        nonlocal voice, announced
        async with state_lock:
            if announced or interrupted:
                return
            announced = True
            await send({'type': 'reply.start', 'text': answer, 'sample_rate': 24000})
            voice = asyncio.create_task(render(answer))

    async def business():
        await send({'type': 'thinking'})
        result = await runtime.turn(text, 'asr', interaction, on_response=response_ready)
        commit(result)
        if result.get('speak') and result.get('response') and not announced:
            await response_ready(result['response'])
        await send({'type': 'result', 'result': result})
        if voice is not None:
            try:
                await voice
            except asyncio.CancelledError:
                if not interrupted:
                    raise
        if interrupted:
            await interruption_done.wait()
        await send({'type': 'stream.done'})

    async def disconnected():
        nonlocal interrupted
        while True:
            message = await socket.receive()
            if message['type'] == 'websocket.disconnect':
                raise WebSocketDisconnect()
            raw = message.get('text', '')
            if len(raw) > 128 or raw != '{"type":"interrupt"}':
                # Parse JSON without permitting any additional command fields.
                if not raw or len(raw) > 128 or json.loads(raw) != {'type': 'interrupt'}:
                    raise ValueError('结束后不能追加录音')
            async with state_lock:
                if interrupted:
                    continue
                interrupted = True
                if voice is not None and not voice_complete:
                    voice.cancel()
                    await asyncio.gather(voice, return_exceptions=True)
                    await send({'type': 'reply.interrupted'})
                interruption_done.set()

    work = asyncio.create_task(business())
    monitor = asyncio.create_task(disconnected())
    try:
        async with asyncio.timeout(100):
            completed, _ = await asyncio.wait([work, monitor], return_when=asyncio.FIRST_COMPLETED)
            for task in completed:
                await task
    finally:
        tasks = [work, monitor] + ([voice] if voice is not None else [])
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def transcribe_live(socket, speech):
    queue = asyncio.Queue(maxsize=32)
    text = ""
    size = 0
    ended = False
    drained = False

    async def receive():
        nonlocal size, ended
        async with asyncio.timeout(75):
            while True:
                message = await socket.receive()
                if message['type'] == 'websocket.disconnect':
                    raise WebSocketDisconnect()
                chunk = message.get('bytes')
                if chunk is not None:
                    size += len(chunk)
                    if not chunk or len(chunk) % 2 or len(chunk) > 32000 or size > 1920000:
                        raise ValueError('录音帧无效或超过 60 秒上限')
                    # Explicit backpressure budget: never buffer unbounded audio.
                    await asyncio.wait_for(queue.put(chunk), 3)
                else:
                    raw = message.get('text', '')
                    if len(raw) > 128 or json.loads(raw) != {'type': 'end'} or size < 3200:
                        raise ValueError('录音结束帧无效或录音太短')
                    ended = True
                    await asyncio.wait_for(queue.put(None), 3)
                    return

    async def chunks():
        nonlocal drained
        while True:
            chunk = await queue.get()
            if chunk is None:
                drained = True
                return
            yield chunk

    async def recognize():
        nonlocal text
        async with aclosing(speech.stream_asr(chunks())) as results:
            async for result in results:
                if result.text:
                    text = result.text[:6000]
                    await socket.send_json({'type': 'transcript.partial', 'text': text})
        if not ended or not drained:
            raise ValueError('识别在录音结束确认前中断，请重新开始')

    tasks = [asyncio.create_task(receive()), asyncio.create_task(recognize())]
    try:
        async with asyncio.timeout(90):
            await asyncio.gather(*tasks)
        return text
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
