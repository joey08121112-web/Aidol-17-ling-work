"""Live captions for one work meeting: stream PCM to ASR and save final sentences on the meeting timeline.

The socket carries recorded PCM only (pauses send no audio), so the running byte count is the
meeting timeline. Each ASR upstream run starts at a known offset; provider times are relative to it.
Interim text is shown, never stored. On any upstream failure the socket closes with an error and
the client falls back to recognising the affected 20 s segments on the server.
"""
import asyncio
import json

from ..providers.volc_speech import SpeechProviderError
from ..storage import speaker_label

PCM_BYTES_PER_MS = 32
MAX_FRAME_BYTES = 64000
MAX_RUN_MS = 10 * 60 * 1000  # Start a fresh upstream run before any provider session limit.


class _Run:
    def __init__(self, base_ms):
        self.base_ms, self.queue = base_ms, asyncio.Queue()
        self.saved, self.audio_ms, self.partial = 0, 0, ''


def _shifted(words, base_ms):
    shifted = []
    for word in words or ():
        if isinstance(word, dict) and type(word.get('start_time')) is int and type(word.get('end_time')) is int:
            shifted.append({**word, 'start_time': base_ms + word['start_time'], 'end_time': base_ms + word['end_time']})
    return shifted


async def live_captions(socket, speech, store, meeting_id, offset_ms):
    """Run until the client sends end (reply done), disconnects, or the upstream fails (reply error)."""
    send_lock = asyncio.Lock()
    position = offset_ms
    run = task = None

    async def send(payload):
        async with send_lock:
            await socket.send_text(json.dumps(payload, ensure_ascii=False))

    async def chunks(current):
        while True:
            item = await current.queue.get()
            if item is None:
                return
            yield item

    async def save(current, item):
        start = current.base_ms + max(0, int(item.get('start_time') or 0))
        end = current.base_ms + max(0, int(item.get('end_time') or 0))
        rows = store.save_live_sentence(meeting_id, str(item['text']).strip(), start, max(start, end),
                                        _shifted(item.get('words'), current.base_ms), speaker_label(item))
        if rows:
            await send({'type': 'final', 'sentences': rows})

    async def consume(current):
        # Speaker clusters are numbered per upstream run, so labels can shift after a pause or rotation.
        async for result in speech.stream_asr(chunks(current), speakers=True):
            utterances = [item for item in result.utterances if str(item.get('text') or '').strip()]
            for index, item in enumerate(utterances):
                if index < current.saved:
                    continue
                if item.get('definite') or result.final:
                    await save(current, item)
                    current.saved = index + 1
                    current.partial = ''
                    continue
                text = str(item['text']).strip()
                if text != current.partial:
                    current.partial = text
                    await send({'type': 'partial', 'text': text,
                                'start_ms': current.base_ms + max(0, int(item.get('start_time') or 0))})
                break

    async def finish_run():
        nonlocal run, task
        if run is None:
            return
        current, running = run, task
        run = task = None
        await current.queue.put(None)
        await running  # Saves the last sentences; raises if the provider failed.
        if current.partial:
            await send({'type': 'partial', 'text': '', 'start_ms': position})

    try:
        while True:
            if task is not None and task.done():
                task.result()
                run = task = None  # The provider closed early; the next audio opens a new run.
            message = await socket.receive()
            if message['type'] == 'websocket.disconnect':
                return
            data = message.get('bytes')
            if data is not None:
                if len(data) % 2 or len(data) > MAX_FRAME_BYTES:
                    raise ValueError('invalid PCM frame')
                if run is None:
                    store.anchor_work_audio(meeting_id, position)
                    run = _Run(position)
                    task = asyncio.create_task(consume(run))
                await run.queue.put(data)
                elapsed = len(data) // PCM_BYTES_PER_MS
                position += elapsed
                run.audio_ms += elapsed
                if run.audio_ms >= MAX_RUN_MS:
                    await finish_run()
                continue
            control = json.loads(message.get('text') or '{}')
            kind = control.get('type') if isinstance(control, dict) else None
            if kind == 'pause':
                await finish_run()
                await send({'type': 'paused', 'offset_ms': position})
            elif kind == 'resume':
                value = control.get('offset_ms')
                if type(value) is not int or not 0 <= value <= 86_400_000:
                    raise ValueError('invalid resume offset')
                if run is None:
                    position = value  # The client's recorded-PCM count is authoritative between runs.
            elif kind == 'end':
                await finish_run()
                await send({'type': 'done', 'offset_ms': position})
                await socket.close()
                return
            else:
                raise ValueError('unknown control message')
    except (SpeechProviderError, ValueError, TimeoutError, json.JSONDecodeError):
        try:
            await send({'type': 'error', 'message': '实时字幕中断，这段录音会改用整段识别'})
            await socket.close(code=1011)
        except Exception:
            pass
    finally:
        if task is not None and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
