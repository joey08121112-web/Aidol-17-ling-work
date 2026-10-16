"""Explicit synthetic speech E2E test against a running local server.

Creates a short billable TTS sample and submits it as synthetic microphone PCM.
Uses the actual WebSocket, ASR, interaction LLM, memory and TTS paths. It leaves
test transcripts in the local database so the result can be inspected in the UI.
No microphone or private location is accessed. Run: python -m scripts.probe_e2e
"""
import asyncio
import json
import time
from pathlib import Path
import httpx
from websockets.asyncio.client import connect
from app.config import Config
from scripts.probe_speech import resample_wav


async def main():
    config = Config()
    headers = {'Authorization': 'Bearer ' + config.app_token} if config.app_token else {}
    report = {'source': 'synthetic audio via live APIs', 'model': config.text_model, 'thinking': False}
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000', headers=headers, timeout=60, trust_env=False) as client:
        state = (await client.get('/api/state')).json()
        original = state['settings']
        settings = {**original, 'provider':'cascade', 'auto_mode':False, 'mode':'life'}
        try:
            (await client.put('/api/settings', json=settings)).raise_for_status()
            sample = await client.post('/api/speech/synthesize', json={'text':'我喜欢清淡的食物，请推荐一份晚餐。'})
            sample.raise_for_status()
            pcm = resample_wav(sample.content)
            report['input_pcm_bytes'] = len(pcm)
            async with connect('ws://127.0.0.1:8000/ws/audio', proxy=None, max_size=4*1024*1024) as ws:
                await ws.send(json.dumps({'type':'start','token':config.app_token,'duration':60}))
                hello = json.loads(await ws.recv())
                report['ready'] = hello
                if hello.get('type') != 'ready':
                    raise RuntimeError('cascade session not ready')
                finished = asyncio.Event()
                async def upload():
                    # Same 40ms mono PCM16 frames as the browser worklet, at real time.
                    for pos in range(0, len(pcm), 1280):
                        await ws.send(pcm[pos:pos+1280])
                        await asyncio.sleep(.04)
                    while not finished.is_set():
                        await ws.send(bytes(1280))
                        await asyncio.sleep(.04)
                send = asyncio.create_task(upload())
                started = time.monotonic()
                output_bytes, transcript, reply, errors = 0, '', '', []
                async with asyncio.timeout(55):
                    while True:
                        event = await ws.recv()
                        if isinstance(event, bytes):
                            output_bytes += len(event)
                        else:
                            message = json.loads(event)
                            if message['type'] == 'transcript': transcript = message.get('text','')
                            elif message['type'] == 'assistant.delta': reply += message.get('text','')
                            elif message['type'] == 'error':
                                errors.append(message.get('message'))
                                break
                            elif message['type'] == 'response.done': break
                finished.set()
                await send
                await ws.send(json.dumps({'type':'stop'}))
                report['cascade'] = {'transcript':transcript, 'reply':reply, 'output_pcm_bytes':output_bytes,
                    'elapsed_ms_including_input':round((time.monotonic()-started)*1000), 'errors':errors}
            current = (await client.get('/api/state')).json()
            report['memory_count'] = len(current['memories'])
            report['asr_persisted'] = any(t['source']=='asr' and '清淡' in t['text'] for t in current['transcripts'])
            maps = await client.get('/api/maps/search',params={'q':'东方明珠','city':'上海'})
            report['maps_search'] = {'status':maps.status_code,'count':len(maps.json().get('pois',[]))}
            for transport in ('walking','transit'):
                await client.put('/api/settings',json={**settings,'mode':'commute','transport':transport,
                    'destination_name':'上海东方明珠','destination_lon':121.4998,'destination_lat':31.2397})
                # Read adapter directly to avoid 30s live GPS throttle, using public landmark coords.
                from app.services.maps import MapService
                from app.domain import LocationInput, Settings
                route = await MapService(config).route(LocationInput(longitude=121.4737,latitude=31.2304),
                    Settings(transport=transport,destination_lon=121.4998,destination_lat=31.2397))
                report['maps_'+transport] = {'available':route.get('available'), 'source':route.get('source'), 'reason':route.get('reason')}
        finally:
            await client.put('/api/settings',json={**original,'provider':'cascade'})
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True,indent=2))


if __name__ == '__main__':
    asyncio.run(main())
