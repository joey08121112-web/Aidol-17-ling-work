"""Deliberate synthetic keyword/full-duplex probe; temporary store, no saved audio."""
import argparse
import asyncio
from pathlib import Path
import struct
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Config
from app.providers.qwen_realtime import QwenRealtimeProvider
from app.providers.volc_speech import VolcSpeechProvider
from app.services.assistant_tools import AssistantTools
from app.services.duplex_voice import duplex_call
from app.services.mobile import MobileRuntime
from app.services.qwen_voice import realtime_instructions


async def probe():
    cfg = Config()
    speech = VolcSpeechProvider(cfg.speech)
    async def synth(text):
        raw = b''.join([p async for p in speech.stream_tts(text)])
        values = struct.unpack('<' + 'h' * (len(raw) // 2), raw)
        return b''.join(struct.pack('<h', values[i * 3 // 2]) for i in range((len(values) - 1) * 2 // 3))
    first = await synth('请给我讲一个很长的故事。')
    print('synthetic_first=ready', flush=True)
    ordinary = await synth('你好。')
    keyword = await synth('小万，换成一句话。')
    print('synthetic_inputs=ready', flush=True)

    class Phone:
        def __init__(self):
            self.input = asyncio.Queue()
            self.audio = asyncio.Event()
            self.complete = asyncio.Event()
            self.tool_done = asyncio.Event()
            self.results = []
            self.output_bytes = 0
            self.interrupts = 0
            self.started = 0
        async def receive(self): return await self.input.get()
        async def send_bytes(self, value):
            self.output_bytes += len(value)
            self.audio.set()
        async def send_json(self, event):
            kind = event['type']
            if kind in {'ready', 'reply.start', 'reply.done', 'reply.error'}:
                print('event=' + kind, flush=True)
            if kind == 'reply.start': self.started += 1
            if kind == 'reply.interrupted' and self.audio.is_set(): self.interrupts += 1
            if kind == 'reply.error': raise RuntimeError('provider reply failed')
            if kind == 'reply.done':
                await self.input.put({'type': 'websocket.receive', 'text': __import__('json').dumps(
                    {'type': 'playback.done', 'turn': event['turn']})})
            if kind == 'result':
                self.results.append(event['result'])
                if len(self.results) == 2: self.complete.set()
                if len(self.results) == 3: self.tool_done.set()
    phone = Phone()
    async def feed(data):
        for offset in range(0, len(data), 640):
            await phone.input.put({'type': 'websocket.receive', 'bytes': data[offset:offset + 640]})
            await asyncio.sleep(.02)
    async def mic():
        await feed(first)
        while not phone.audio.is_set(): await feed(bytes(640))
        await feed(ordinary)
        await feed(bytes(32000))
        assert phone.interrupts == 0, 'ordinary speech interrupted playback'
        print('ordinary_speech_ignored=True', flush=True)
        await feed(keyword)
        while not phone.complete.is_set(): await feed(bytes(640))
        # Silence must leave the same live call available, beyond the reported 10s.
        for _ in range(750): await feed(bytes(640))
        await phone.input.put({'type': 'websocket.receive', 'text': __import__('json').dumps(
            {'type': 'text', 'text': '现在几点？请调用 current_time 查询时间。'})})
        while not phone.tool_done.is_set(): await feed(bytes(640))
        await phone.input.put({'type': 'websocket.receive', 'text': '{"type":"end"}'})

    with tempfile.TemporaryDirectory(prefix='aidol-keyword-') as folder:
        runtime = MobileRuntime(cfg, Path(folder) / 'probe.sqlite3')
        async with QwenRealtimeProvider(cfg.qwen).duplex_session(
                realtime_instructions(runtime.agent.instructions(directed=True)),
                schemas=AssistantTools(runtime.agent).schemas()) as native:
            take = native.take
            async def observed(queue, timeout=100):
                event = await take(queue, timeout)
                if queue is native.inputs and event['type'].endswith('transcription.completed'):
                    from app.services.duplex_voice import interruption_request
                    value = event.get('transcript', event.get('text', ''))
                    print('asr_complete keyword=' + str(interruption_request(value) is not None)
                          + ' homophones=' + str([w for w in ('小晚', '小碗', '小王', '小玩', '小婉') if w in value]), flush=True)
                return event
            native.take = observed
            call = asyncio.create_task(duplex_call(phone, runtime, native, speech, 'synthetic-keyword-probe', lambda _: True))
            sender = asyncio.create_task(mic())
            try:
                await asyncio.wait_for(asyncio.gather(call, sender), 90)
                assert phone.results[0].get('voice_interrupted')
                assert phone.results[1].get('response_complete')
                assert phone.output_bytes > 0 and phone.interrupts > 0
                assert any(t['tool'] == 'current_time' for t in phone.results[-1].get('tool_results', []))
                print('keyword_interrupt=True same_call_silence_15s=True audio_bytes=' + str(phone.output_bytes), flush=True)
                print('tool_calls=' + str(sum(len(r.get('tool_results', [])) for r in phone.results)), flush=True)
            finally:
                call.cancel(); sender.cancel()
                await asyncio.gather(call, sender, return_exceptions=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true')
    if not parser.parse_args().live: parser.error('--live required; consumes TTS and realtime API quota')
    try: asyncio.run(probe())
    except Exception as error:
        print('probe_failed=' + type(error).__name__)
        import traceback
        for frame in traceback.extract_tb(error.__traceback__):
            print(Path(frame.filename).name + ':' + str(frame.lineno) + ' ' + frame.name)
        sys.exit(1)
