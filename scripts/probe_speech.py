"""Explicit live speech round trip: TTS -> PCM resample -> streaming ASR.

Uses synthetic test speech, no hardware recording. Credential values never print.
"""
import asyncio
import io
import json
import struct
import time
import wave
from app.config import Config
from app.providers.volc_speech import VolcSpeechProvider


def resample_wav(wav):
    with wave.open(io.BytesIO(wav)) as audio:
        assert audio.getsampwidth() == 2 and audio.getnchannels() == 1
        rate = audio.getframerate()
        data = audio.readframes(audio.getnframes())
    samples = struct.unpack('<' + 'h' * (len(data) // 2), data)
    count = int(len(samples) * 16000 / rate)
    out = []
    for i in range(count):
        pos = i * rate / 16000
        left = int(pos)
        right = min(left + 1, len(samples) - 1)
        out.append(round(samples[left] * (1 - pos + left) + samples[right] * (pos - left)))
    return struct.pack('<' + 'h' * len(out), *out)


async def main():
    config = Config()
    provider = VolcSpeechProvider(config.speech)
    report = {}
    start = time.monotonic()
    try:
        wav = await provider.synthesize_wav('开始会议，我负责前端，明天提交耳机控制台。')
        report['tts'] = {'ok': True, 'wav_bytes': len(wav), 'latency_ms': round((time.monotonic() - start) * 1000)}
        pcm = resample_wav(wav)
        async def chunks():
            for pos in range(0, len(pcm), 3200):
                yield pcm[pos:pos + 3200]
                await asyncio.sleep(0.025)
        transcripts = []
        if config.asr_mode == 'flash':
            result = await provider.recognize(pcm, format='pcm', codec='raw')
            transcripts.append(result.text)
        else:
            async for result in provider.stream_asr(chunks()):
                if result.text:
                    transcripts.append(result.text)
        report['asr'] = {'ok': bool(transcripts), 'transport': config.asr_mode,
                         'text': transcripts[-1] if transcripts else '', 'events': len(transcripts)}
    except Exception as error:
        report['error_type'] = type(error).__name__
        # The adapter only surfaces sanitized provider error codes, never headers.
        if type(error).__name__ == 'SpeechProviderError':
            report['detail'] = str(error)
    print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    asyncio.run(main())
