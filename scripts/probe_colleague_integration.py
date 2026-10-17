"""Deliberate live checks; public landmarks only, never part of pytest.

Run from the repository root: python scripts/probe_colleague_integration.py --live
Optional --speech uses one short synthetic TTS and ASR call; no audio is saved.
"""
import argparse
import asyncio
from pathlib import Path
import struct
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Config
from app.domain import LocationInput, Settings
from app.providers.bocha import BochaSearch
from app.providers.volc_speech import VolcSpeechProvider
from app.services.maps import MapService


async def run(check_speech=False):
    config = Config()
    maps = MapService(config)
    checks = [
        ('amap_forecast', lambda: maps.weather('110101', True)),
        ('amap_geocode', lambda: maps.geocode('北京市天安门')),
        ('amap_nearby_metro', lambda: maps.nearby(LocationInput(longitude=116.397, latitude=39.908), metro_only=True)),
        ('amap_bicycling', lambda: maps.route(LocationInput(longitude=116.397, latitude=39.908),
            Settings(destination_lon=116.404, destination_lat=39.915, destination_name='故宫附近', transport='bicycling'))),
        ('bocha_search', lambda: BochaSearch(config).search('故宫博物院 官方 开放时间', count=1)),
    ]
    for name, operation in checks:
        started = time.monotonic()
        try:
            result = await operation()
            print(name, 'available=' + str(result.get('available')), 'elapsed_ms=' + str(round((time.monotonic() - started) * 1000)),
                  'items=' + str(len(result.get('results', result.get('pois', result.get('places', result.get('forecast', [])))))))
        except Exception:
            # Deliberately no exception repr: provider HTTP errors can contain keys.
            print(name, 'failed; inspect backend configuration, provider permissions and quota')
    if check_speech:
        speech = VolcSpeechProvider(config.speech)
        started = time.monotonic()
        pcm = bytearray()
        try:
            async for chunk in speech.stream_tts('你好，这是语音连接测试。'):
                if not pcm:
                    print('tts_first_chunk_ms=' + str(round((time.monotonic() - started) * 1000)))
                pcm.extend(chunk)
            # Linear 24k -> 16k conversion for synthetic probe only.
            samples = struct.unpack('<' + 'h' * (len(pcm) // 2), pcm)
            converted = []
            for i in range(int(len(samples) * 2 / 3)):
                position = i * 1.5
                low = int(position)
                high = min(low + 1, len(samples) - 1)
                converted.append(round(samples[low] + (samples[high] - samples[low]) * (position - low)))
            audio = struct.pack('<' + 'h' * len(converted), *converted)
            async def chunks():
                for offset in range(0, len(audio), 3200):
                    yield audio[offset:offset + 3200]
                    await asyncio.sleep(.1)
            began = time.monotonic()
            first = True
            final = False
            async for result in speech.stream_asr(chunks()):
                if result.text and first:
                    print('asr_first_text_ms=' + str(round((time.monotonic() - began) * 1000)))
                    first = False
                final |= result.final
            print('synthetic_asr_final=' + str(final), 'audio_seconds=' + str(round(len(audio) / 32000, 2)))
        except Exception:
            print('synthetic_speech_failed; no hardware listening result claimed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Allow quota-consuming provider requests')
    parser.add_argument('--speech', action='store_true', help='Also check synthetic speech')
    args = parser.parse_args()
    if not args.live:
        parser.error('--live is required; these checks consume provider quota')
    asyncio.run(run(args.speech))
