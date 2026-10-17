"""Deliberate live Qwen probe using only synthetic speech; never run in CI.

Consumes one configured Volcano TTS request plus one Qwen realtime turn. No
audio, credentials, provider bodies or user databases are written to disk.
"""
import argparse
import asyncio
from pathlib import Path
import struct
import sys
import time
from dataclasses import replace
import json
import tempfile
from uuid import uuid4
from datetime import datetime
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Config
from app.providers.qwen_realtime import QwenRealtimeProvider
from app.providers.volc_speech import VolcSpeechProvider


def account_probe(config, pcm, tools):
    """Real account contract + live provider, using a disposable isolated account."""
    from fastapi.testclient import TestClient
    from app.main import create_hosted_app
    # This probe validates native replies; memory extraction stays local/rules.
    config = replace(config, text_key='')
    with tempfile.TemporaryDirectory(prefix='aidol-qwen-probe-') as directory:
        with TestClient(create_hosted_app(config, directory)) as client:
            account = client.post('/auth/register', json={'username': 'synthetic-probe', 'password': uuid4().hex})
            account.raise_for_status()
            client.headers['Authorization'] = 'Bearer ' + account.json()['token']
            for number in range(2):
                request_id = uuid4().hex
                endpoint = '/v1/voice/stream?request_id=' + request_id + '&interaction_source=direct_question&reply_audio=1&engine=qwen'
                with client.websocket_connect(endpoint) as ws:
                    assert ws.receive_json()['type'] == 'ready'
                    for offset in range(0, len(pcm), 32000):
                        ws.send_bytes(bytes(pcm[offset:offset + 32000]))
                    ws.send_json({'type': 'end'})
                    started = time.monotonic()
                    result, audio_size, first_ms, caption = None, 0, None, ''
                    audio_done = False
                    for _ in range(2000):
                        frame = ws.receive()
                        if frame.get('bytes') is not None:
                            if first_ms is None:
                                first_ms = round((time.monotonic() - started) * 1000)
                            audio_size += len(frame['bytes'])
                            continue
                        event = json.loads(frame['text'])
                        if event['type'] in {'error', 'reply.error'}:
                            raise RuntimeError('Account stream failed')
                        if event['type'] == 'reply.text':
                            caption = event['text']
                        if event['type'] == 'reply.done':
                            audio_done = True
                        if event['type'] == 'result':
                            result = event['result']
                        if event['type'] == 'stream.done':
                            break
                    assert result and audio_done and audio_size and result['response_complete']
                    assert result['interaction'] == 'qwen_realtime' and caption == result['response']
                    if tools:
                        assert any(row['tool'] == 'current_time' for row in result.get('tool_results', []))
                assert client.get('/v1/turns/' + request_id).json()['status'] == 'completed'
                print(f'account_turn={number + 1} pass first_audio_after_end_ms={first_ms} audio_bytes={audio_size}', flush=True)
            rows = client.get('/v1/state').json()['transcripts']
            assert len([r for r in rows if r['speaker'] == 'assistant']) == 2
            print('account_persistence_and_history=pass', flush=True)


async def probe(tools=False, account=False):
    config = Config()
    if not config.qwen.ready or not config.speech.tts_ready:
        raise RuntimeError('Configure Qwen and synthetic-speech TTS credentials first')
    speech = VolcSpeechProvider(config.speech)
    prompt = '现在是星期几？请查询当前时间。' if tools else '你好，请说一句简短的问候。'
    pcm24 = b''.join([part async for part in speech.stream_tts(prompt)])
    samples = struct.unpack('<' + 'h' * (len(pcm24) // 2), pcm24)
    # 24 kHz -> 16 kHz linear interpolation, diagnostic synthetic speech only.
    pcm16 = bytearray()
    for index in range((len(samples) - 1) * 2 // 3):
        pos = index * 3
        left = pos // 2
        value = samples[left] if pos % 2 == 0 else (samples[left] + samples[left + 1]) // 2
        pcm16.extend(struct.pack('<h', value))

    if account:
        await asyncio.to_thread(account_probe, config, pcm16, tools)
        return

    async def chunks():
        for offset in range(0, len(pcm16), 640):
            yield bytes(pcm16[offset:offset + 640])
            await asyncio.sleep(.02)

    tool_calls = []
    async def call_tool(name, arguments):
        if name != 'current_time' or arguments or not tools:
            raise RuntimeError('Unexpected tool call in synthetic probe')
        tool_calls.append(name)
        now = datetime.now(ZoneInfo('Asia/Shanghai'))
        return {'datetime': now.isoformat(), 'weekday': now.isoweekday(), 'source': 'server_clock'}

    schemas = [{'type': 'function', 'function': {'name': 'current_time', 'description': '查询当前真实日期、星期和时间',
        'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}}}] if tools else []

    started = time.monotonic()
    instructions = '必须调用 current_time 后，用一句简短中文回答日期和星期，不得猜测。' if tools else '用一句简短中文问候用户。'
    async with QwenRealtimeProvider(config.qwen).session(instructions, schemas=schemas) as session:
        print('authenticated_session=pass', flush=True)
        transcript = ''
        async for event in session.stream_asr(chunks()):
            transcript = event.text
        print('input_transcript=' + ('pass' if transcript.strip() else 'empty'), flush=True)
        generated_at = time.monotonic()
        audio_size, text = 0, ''
        async for event in session.reply(instructions, schemas, call_tool):
            if 'audio' in event:
                if not audio_size:
                    print('reply_first_audio_ms=' + str(round((time.monotonic() - generated_at) * 1000)), flush=True)
                audio_size += len(event['audio'])
            if 'text' in event:
                text = event['text']
        print(f'native_reply=pass audio_bytes={audio_size} text_chars={len(text)} total_ms={round((time.monotonic()-started)*1000)}')
        if tools:
            if not tool_calls:
                raise RuntimeError('Model did not execute the requested tool')
            print('tool_round_trip=pass calls=' + str(len(tool_calls)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Explicitly consume provider quota')
    parser.add_argument('--tools', action='store_true', help='Verify a real current_time tool round trip')
    parser.add_argument('--account', action='store_true', help='Two live turns through a disposable authenticated account')
    args = parser.parse_args()
    if not args.live:
        parser.error('--live is required; this probe consumes quota')
    try:
        asyncio.run(asyncio.wait_for(probe(args.tools, args.account), 240 if args.account else 100))
    except Exception as error:
        print('probe_failed=' + type(error).__name__)
        sys.exit(1)
