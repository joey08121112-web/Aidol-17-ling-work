"""Bounded live upload: capture and provider ASR run concurrently.

Only an explicit end frame allows business execution. Disconnect, backpressure,
timeouts, odd samples and overruns cancel both sides without processing a turn.
"""
import asyncio
import json
from contextlib import aclosing

from fastapi import WebSocketDisconnect


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
