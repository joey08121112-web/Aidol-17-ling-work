import json
from datetime import datetime, timezone, timedelta

import httpx
import pytest

from app.providers.bocha import BochaSearch, validate_freshness
from app.services.assistant_tools import AssistantTools
from app.services.conversation import ConversationService


def mock_http(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kwargs: original(transport=httpx.MockTransport(handler)))


@pytest.mark.asyncio
async def test_bocha_structured_sources_and_bounded_request(config, monkeypatch):
    config.bocha_key = 'offline-key'
    def handler(request):
        assert request.url == 'https://api.bochaai.com/v1/web-search'
        assert request.headers['Authorization'] == 'Bearer offline-key'
        assert json.loads(request.content) == {'query': '高铁时刻', 'freshness': 'oneDay', 'count': 5, 'summary': True}
        return httpx.Response(200, json={'code': 200, 'data': {'webPages': {'value': [
            {'name': '公开来源', 'url': 'https://example.com', 'summary': 'x' * 2000, 'datePublished': '2026-10-17'},
            {'name': 'bad', 'url': 'javascript:alert(1)'}, {'url': 'https://user:pass@example.com'}]}}})
    mock_http(monkeypatch, handler)
    result = await BochaSearch(config).search('高铁时刻', 'oneDay')
    assert result['available'] and len(result['results']) == 1
    assert len(result['results'][0]['summary']) == 1500
    assert result['results'][0]['published'] == '2026-10-17'


@pytest.mark.asyncio
@pytest.mark.parametrize('body', [{'code': 401, 'message': 'offline-key'}, {'data': []}, []])
async def test_bocha_sanitizes_even_http_200_errors(config, monkeypatch, body):
    config.bocha_key = 'offline-key'
    mock_http(monkeypatch, lambda r: httpx.Response(200, json=body))
    with pytest.raises(ValueError) as error:
        await BochaSearch(config).search('公园')
    assert 'offline-key' not in str(error.value)


@pytest.mark.parametrize('value', ['2026-02-30', '2026-10-18..2026-10-17', 'anytime', '2026-1-1'])
def test_invalid_search_freshness_is_not_silently_widened(value):
    with pytest.raises(ValueError):
        validate_freshness(value)


@pytest.mark.asyncio
async def test_tools_reject_unknown_write_and_model_coordinates(agent):
    tools = AssistantTools(agent)
    for name, args in [('shell', {'command': 'echo secret'}), ('current_location', {'longitude': 121.5}),
                       ('plan_route', {'destination_id': 'invented'}), ('nearby_places', {})]:
        assert not (await tools.call(name, args))['available']
    assert not tools.places


@pytest.mark.asyncio
async def test_tools_reject_stale_and_inaccurate_location(agent):
    tools = AssistantTools(agent)
    for age, accuracy, source in [(301, 10, 'gps'), (0, 201, 'gps'), (0, 10, 'demo')]:
        agent.location = {'longitude': 121.5, 'latitude': 31.2, 'accuracy': accuracy, 'source': source,
            'observed_at': (datetime.now(timezone.utc) - timedelta(seconds=age)).isoformat()}
        assert not (await tools.call('current_location', {}))['available']


@pytest.mark.asyncio
async def test_route_uses_only_returned_poi_without_modifying_settings(agent, monkeypatch):
    agent.location = {'longitude': 121.5, 'latitude': 31.2, 'accuracy': 10, 'source': 'gps',
        'observed_at': datetime.now(timezone.utc).isoformat()}
    async def search(query, city):
        return {'available': True, 'pois': [{'id': 'real', 'longitude': 121.6, 'latitude': 31.3, 'name': '公园', 'city': '上海'}]}
    async def route(location, settings):
        assert settings.transport == 'bicycling' and settings.destination_name == '公园'
        return {'available': True, 'source': 'amap_bicycling'}
    monkeypatch.setattr(agent.maps, 'search', search)
    monkeypatch.setattr(agent.maps, 'route', route)
    before = agent.store.settings()
    tools = AssistantTools(agent)
    await tools.call('search_places', {'query': '公园', 'city': '上海'})
    assert (await tools.call('plan_route', {'destination_id': 'real', 'transport': 'bicycling'}))['available']
    assert before == agent.store.settings()
    assert not (await AssistantTools(agent).call('plan_route', {'destination_id': 'real'}))['available']


@pytest.mark.asyncio
async def test_conversation_executes_tools_then_grounds_answer(agent, config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'test'
    requests = []
    def handler(request):
        body = json.loads(request.content); requests.append(body)
        if len(requests) == 1:
            message = {'content': None, 'tool_calls': [{'id': 't1', 'type': 'function',
                'function': {'name': 'current_time', 'arguments': '{}'}}]}
        else:
            receipt = json.loads(body['messages'][-1]['content'])
            assert receipt['source'] == 'server_clock' and receipt['timezone'] == 'Asia/Shanghai'
            message = {'content': '时间来自服务器时钟。'}
        return httpx.Response(200, json={'choices': [{'message': message}]})
    mock_http(monkeypatch, handler)
    service = ConversationService(config); service.tool_runner = AssistantTools(agent)
    assert await service.reply('现在几点', '助手') == '时间来自服务器时钟。'
    assert len(service.tool_runner.receipts) == 1 and len(requests) == 2


@pytest.mark.asyncio
async def test_conversation_cannot_loop_forever(agent, config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'test'
    requests = []
    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={'choices': [{'message': {'content': None, 'tool_calls': [
            {'id': 't' + str(len(requests)), 'type': 'function', 'function': {'name': 'current_time', 'arguments': '{}'}}]}}]})
    mock_http(monkeypatch, handler)
    service = ConversationService(config); service.tool_runner = AssistantTools(agent)
    assert '未能完整完成' in await service.reply('时间', '助手')
    assert len(requests) == 4 and 'tools' not in requests[-1]
    assert len(service.tool_runner.receipts) == 3


@pytest.mark.asyncio
async def test_ambient_capture_never_enables_external_tools(agent, monkeypatch):
    async def reply(*args):
        assert agent.conversation.tool_runner is None
        return '收到'
    monkeypatch.setattr(agent.conversation, 'reply', reply)
    from app.domain import TextInput
    await agent.ingest(TextInput(text='帮我搜索最新新闻'), respond=True, directed=False)


def test_new_http_endpoints_require_auth(client):
    for method, path, body in [('GET', '/v1/search?q=hello', None),
        ('GET', '/v1/maps/geocode?address=上海', None),
        ('POST', '/v1/maps/reverse', {'longitude': 121.5, 'latitude': 31.2}),
        ('POST', '/v1/speech/stream', {'text': 'hello'})]:
        assert client.request(method, path, json=body, headers={'Authorization': ''}).status_code == 401


@pytest.mark.parametrize('question', ['请告诉我现在是星期几', '今天北京天气怎么样', '我附近有什么地铁站', '帮我查最新新闻'])
def test_public_queries_are_not_misrouted_to_private_memory(question):
    from app.services.memory_interaction import MemoryInteraction
    assert MemoryInteraction.intent(question) is None


@pytest.mark.parametrize('question', ['我上次去的地铁站是哪一站？', '今天我答应办什么事？', '我喜欢哪家附近咖啡馆？'])
def test_historical_place_questions_still_use_memory(question):
    from app.services.memory_interaction import MemoryInteraction
    assert MemoryInteraction.intent(question) == 'recall'


@pytest.mark.asyncio
async def test_tool_quota_blocks_network_but_not_clock(agent):
    agent.tool_charge = lambda: False
    tools = AssistantTools(agent)
    assert '额度' in (await tools.call('web_search', {'query': '公开新闻'}))['reason']
    assert (await tools.call('current_time', {}))['available']
