"""Live integration probe; explicit invocation only. Never prints credential values."""
import asyncio
import json
import tempfile
from pathlib import Path
import httpx
from app.config import Config
from app.domain import Settings, LocationInput
from app.services.maps import MapService
from app.services.memory import MemoryService
from app.storage import Store


async def main():
    config = Config()
    result = {}
    if config.text_key:
        try:
            async with httpx.AsyncClient(timeout=25, proxy=config.proxy) as client:
                response = await client.post(config.text_url.rstrip('/') + '/chat/completions',
                    headers={'Authorization': 'Bearer ' + config.text_key},
                    json={'model': config.text_model, 'max_tokens': 150,
                          'messages': [{'role':'user','content':'只回复：测试通过。'}]})
                result['text_model_http'] = response.status_code
                if response.is_success:
                    result['text_model_response'] = response.json()['choices'][0]['message']['content']
                else:
                    result['text_model_error_code'] = response.json().get('error', {}).get('code')
        except Exception as error:
            result['text_model_error'] = type(error).__name__
        with tempfile.TemporaryDirectory() as directory:
            memories, provider = await MemoryService(config, Store(str(Path(directory) / 'test.db'))).extract(
                '开始会议，我负责前端，明天下午三点提交耳机控制台。', '测试用户', 'work')
            result['memory'] = {'provider': provider, 'count': len(memories)}
    if config.amap_key:
        try:
            # Public Shanghai landmarks, not a person's location.
            route = await MapService(config).route(LocationInput(longitude=121.4737, latitude=31.2304),
                Settings(destination_lon=121.4998, destination_lat=31.2397, destination_name='上海东方明珠'))
            result['amap'] = route
        except Exception as error:
            result['amap_error'] = type(error).__name__
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    asyncio.run(main())
