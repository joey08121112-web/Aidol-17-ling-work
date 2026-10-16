"""Deliberate live hosted check. Synthetic speech/public landmarks; consumes quota.

python scripts/probe_android_hosted.py --base-url https://IP:7776 --ca server-ca.pem
No developer credentials are used by this script. Test account is deleted at exit.
"""
import argparse
import io
import json
import secrets
import ssl
import struct
import time
import wave
from uuid import uuid4

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--ca', required=True)
    args = parser.parse_args()
    context = ssl.create_default_context(cafile=args.ca)
    password = secrets.token_urlsafe(24)
    username = 'probe_' + secrets.token_hex(5)
    results = {}
    with httpx.Client(base_url=args.base_url.rstrip('/'), verify=context, timeout=120, trust_env=False) as client:
        def request(method, path, **kwargs):
            response = client.request(method, path, **kwargs)
            if response.status_code >= 400:
                raise RuntimeError(f'{path}: HTTP {response.status_code}: {response.text[:200]}')
            return response
        registered = request('POST', '/auth/register', json={'username': username, 'password': password}).json()
        client.headers['Authorization'] = 'Bearer ' + registered['token']
        try:
            state = request('GET', '/v1/state').json()
            assert state['capabilities']['asr'] and state['capabilities']['tts'] and state['capabilities']['text_llm']
            start = time.monotonic()
            sample = request('POST', '/v1/speech', json={'text': '你好，这是语音链路测试。请简短介绍一下你自己。'}).content
            with wave.open(io.BytesIO(sample)) as source:
                assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, 24000)
                frames = source.readframes(source.getnframes())
            values = struct.unpack('<' + 'h' * (len(frames) // 2), frames)
            # Deterministic 24 -> 16 kHz linear interpolation of generated speech.
            out = []
            for i in range(len(values) * 2 // 3):
                p = i * 1.5
                index = int(p)
                out.append(round(values[index] * (1 - (p-index)) + values[min(index+1, len(values)-1)] * (p-index)))
            pcm = struct.pack('<' + 'h' * len(out), *out)
            turn = request('POST', '/v1/voice?request_id=' + uuid4().hex, content=pcm, headers={'Content-Type': 'audio/pcm'}).json()
            assert turn['text'] and turn['response'] and turn['interaction'] == 'text_llm', turn
            answer_audio = request('POST', '/v1/speech', json={'text': turn['response']}).content
            with wave.open(io.BytesIO(answer_audio)) as answer:
                assert answer.getnframes() > 2400
                results['voice_chain'] = {'asr_text': turn['text'], 'reply': turn['response'], 'reply_seconds': round(answer.getnframes()/answer.getframerate(), 2),
                                          'total_seconds': round(time.monotonic()-start, 2)}
            settings = state['settings']
            settings.update(mode='work', auto_mode=False)
            request('PUT', '/v1/settings', json=settings)
            note = request('POST', '/v1/chat', json={'text': '会议测试：小明负责在周五前完成演示文档。已确认使用安卓应用进行内部测试。', 'request_id': uuid4().hex}).json()
            assert not note['speak']
            meeting = request('POST', '/v1/meetings', json={'hours': 1}).json()
            assert meeting['summary']
            tasks = request('GET', '/v1/state').json()['tasks']
            if tasks:
                request('PATCH', '/v1/tasks/' + tasks[0]['id'], json={'status': 'done'})
            memory = request('POST', '/v1/memories', json={'content': '测试偏好：我喜欢清淡饮食。', 'kind': 'preference'}).json()
            recall = request('POST', '/v1/recall', json={'question': '我有什么饮食偏好？'}).json()
            results['memory_meeting'] = {'meeting_extraction': meeting.get('extraction'), 'tasks': len(tasks), 'recall': recall}
            # Public landmark only; no real user location is used.
            pois = request('GET', '/v1/maps/search?q=上海人民广场&city=上海').json()
            assert pois['available'] and pois['pois'], pois
            poi = pois['pois'][0]
            weather = request('GET', '/v1/maps/weather?city=' + (poi['adcode'] or '310101')).json()
            settings.update(mode='commute', destination_name=poi['name'], destination_lon=poi['longitude'], destination_lat=poi['latitude'], transport='walking', transit_city='上海')
            request('PUT', '/v1/settings', json=settings)
            location = {'longitude': 121.4737, 'latitude': 31.2304, 'accuracy': 10, 'source': 'gps'}
            route = request('POST', '/v1/location', json=location).json()
            nearby = request('POST', '/v1/maps/nearby', json={**location, 'keyword': '咖啡'}).json()
            results['maps'] = {'pois': len(pois['pois']), 'weather_available': weather.get('available'),
                               'route_available': (route.get('route') or {}).get('available'), 'nearby_available': nearby.get('available')}
            exported = request('GET', '/v1/export').json()
            assert exported['memories'] and exported['transcripts']
            results['export'] = 'passed'
        finally:
            deletion = client.request('DELETE', '/auth/account', json={'password': password})
            results['test_account_deleted'] = deletion.status_code == 200
            print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
