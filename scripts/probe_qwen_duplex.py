"""Deliberate real duplex/barge-in probe. Synthetic speech only; stores no audio or credentials."""
import argparse
import asyncio
from contextlib import aclosing
from pathlib import Path
import struct
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Config
from app.providers.qwen_realtime import QwenRealtimeProvider
from app.providers.volc_speech import VolcSpeechProvider

async def probe(tools=False):
    config = Config()
    speech = VolcSpeechProvider(config.speech)
    async def pcm(text):
        raw = b''.join([p async for p in speech.stream_tts(text)])
        samples = struct.unpack('<' + 'h' * (len(raw) // 2), raw)
        return b''.join(struct.pack('<h', samples[i*3//2] if i % 2 == 0 else (samples[i*3//2] + samples[i*3//2+1])//2)
                        for i in range((len(samples)-1)*2//3))
    first = await pcm('先查时间再讲一个长故事。' if tools else '请给我讲一个很长的故事。')
    second = await pcm('换成一句话。')
    first_audio, complete = asyncio.Event(), asyncio.Event()
    interrupted = False
    output = None
    generation = 0
    total = [0, 0]
    started = time.monotonic()
    tool_calls = []
    schemas = [{'type': 'function', 'function': {'name': 'current_time', 'description': '查询当前真实时间',
        'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}}}] if tools else []
    instructions = '必须先调用current_time查询真实时间，再讲一个很长的故事。' if tools else '自然中文回答，无需唤醒词。'
    async with QwenRealtimeProvider(config.qwen).duplex_session(instructions, schemas=schemas) as native:
        async def render(number):
            async def call_tool(name, arguments):
                from datetime import datetime
                from zoneinfo import ZoneInfo
                if not tools or name != 'current_time' or arguments:
                    raise RuntimeError('Unexpected tool call')
                tool_calls.append(name)
                return {'datetime': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()}
            async with aclosing(native.reply(instructions, schemas, call_tool)) as events:
                async for event in events:
                    if 'audio' in event:
                        total[number-1] += len(event['audio'])
                        if number == 1: first_audio.set()
                        await asyncio.sleep(len(event['audio'])/48000)
            if number == 2: complete.set()
        async def watch():
            nonlocal output, generation, interrupted
            while True:
                event = await native.take(native.inputs, 30)
                if event['type'] == 'input_audio_buffer.speech_started':
                    print('speech_start generation='+str(generation)+' first_audio='+str(first_audio.is_set()), flush=True)
                if event['type'] == 'input_audio_buffer.speech_started' and generation == 1 and first_audio.is_set():
                    interrupted = True
                    await native.cancel()
                    if output:
                        output.cancel(); await asyncio.gather(output, return_exceptions=True)
                    print('barge_in=received', flush=True)
                if event['type'] == 'conversation.item.input_audio_transcription.completed':
                    generation += 1
                    print('transcript_round='+str(generation), flush=True)
                    await native.text(event.get('transcript', event.get('text', '')))
                    output = asyncio.create_task(render(generation))
        async def feed(data):
            for offset in range(0, len(data), 640):
                await native.append(data[offset:offset+640]); await asyncio.sleep(.02)
        async def microphone():
            await feed(first)
            while not first_audio.is_set():
                await feed(bytes(640))
            await feed(second)
            while not complete.is_set():
                await feed(bytes(640))
        watcher = asyncio.create_task(watch())
        mic = asyncio.create_task(microphone())
        try:
            await asyncio.wait_for(complete.wait(), 45)
            print('observed_interrupt='+str(interrupted)+' audio='+str(total),flush=True)
            assert interrupted and total[0] and total[1]
            if tools:
                assert tool_calls
                print('tool_round_trip=pass calls='+str(len(tool_calls)),flush=True)
            print('duplex_native=pass audio_rounds='+str(total)+' elapsed_ms='+str(round((time.monotonic()-started)*1000)), flush=True)
        finally:
            for task in [watcher, mic] + ([output] if output else []): task.cancel()
            await asyncio.gather(watcher, mic, *([output] if output else []), return_exceptions=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--tools', action='store_true')
    args = parser.parse_args()
    if not args.live: parser.error('--live is required; consumes two TTS requests and native realtime quota')
    try: asyncio.run(probe(args.tools))
    except Exception as error:
        print('probe_failed='+type(error).__name__)
        sys.exit(1)
