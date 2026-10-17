"""天气/时间/路线高频意图规则直达，跳过完整模型+工具循环。"""
import re
from datetime import datetime, timezone

import pytest

from app.domain import Settings, TextInput
from app.services.voice_intent import shortcut_command


def test_shortcut_rules_match_exact_public_lookups():
    assert shortcut_command('现在几点了') == {'kind': 'time'}
    assert shortcut_command('今天星期几') == {'kind': 'time'}
    assert shortcut_command('北京天气怎么样') == {'kind': 'weather', 'city': '北京', 'day': '', 'forecast': False}
    assert shortcut_command('明天天气怎么样') == {'kind': 'weather', 'city': '', 'day': '明天', 'forecast': True}
    assert shortcut_command('查一下上海后天天气预报') == {'kind': 'weather', 'city': '上海', 'day': '后天', 'forecast': True}
    assert shortcut_command('怎么回家') == {'kind': 'route', 'target': 'home', 'transport': None}
    assert shortcut_command('走路去公司要多久') == {'kind': 'route', 'target': 'work', 'transport': 'walking'}


def test_shortcut_rules_refuse_history_and_memory_commands():
    for text in ['帮我记住明天天气', '上次去三亚天气怎么样', '我记得明天几点开会', '回家', '去公司',
                 '明天去上海出差要注意什么', '今天开会讨论需求', '我们聊聊天气对出行的影响']:
        assert shortcut_command(text) is None


def forbidden_reply():
    async def reply(*args, **kwargs):
        raise AssertionError('意图捷径不得进入模型循环')
    return reply


@pytest.mark.asyncio
async def test_time_shortcut_answers_from_clock_without_model(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode='life'))
    monkeypatch.setattr(agent.conversation, 'reply', forbidden_reply())
    result = await agent.ingest(TextInput(text='现在几点了'), directed=True)
    assert result['interaction'] == 'intent_shortcut' and result['extraction'] == 'rules'
    assert re.fullmatch(r'现在是\d{4}年\d{1,2}月\d{1,2}日 星期[一二三四五六日] \d{1,2}点\d{1,2}分。',
                        result['response'])
    assert result['memories'] == []
    assert len(store.list('transcripts')) == 1  # retain_transcripts 默认开启时仍落转写


@pytest.mark.asyncio
async def test_weather_shortcut_uses_single_assistant_tool_call(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode='life'))
    calls = []

    async def geocode(address, city=''):
        calls.append(('geocode', address))
        return {'available': True, 'places': [{'adcode': '310000', 'city': '上海'}]}

    async def weather(code, forecast=False):
        calls.append(('weather', code, forecast))
        return {'available': True, 'source': 'amap_weather', 'city': '上海', 'weather': '多云',
                'temperature': '22', 'humidity': '61', 'wind_direction': '东南', 'wind_power': '3'}

    monkeypatch.setattr(agent.maps, 'geocode', geocode)
    monkeypatch.setattr(agent.maps, 'weather', weather)
    monkeypatch.setattr(agent.conversation, 'reply', forbidden_reply())
    result = await agent.ingest(TextInput(text='上海天气怎么样'), directed=True)
    assert result['interaction'] == 'intent_shortcut'
    assert '多云' in result['response'] and '22' in result['response'] and '61' in result['response']
    assert calls == [('geocode', '上海'), ('weather', '310000', False)]  # 城市解析+单次天气
    assert [receipt['tool'] for receipt in result['tool_results']] == ['weather']


@pytest.mark.asyncio
async def test_route_shortcut_uses_saved_places_and_transport(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode='commute', home_lon=121.45, home_lat=31.23,
                                 home_name='家', commute_target='home'))
    agent.location = {'longitude': 121.50, 'latitude': 31.20, 'accuracy': 30, 'source': 'gps',
                      'observed_at': datetime.now(timezone.utc).isoformat()}
    calls = []

    async def route(location, settings):
        calls.append(settings.transport)
        return {'available': True, 'source': 'amap_' + settings.transport, 'transport': settings.transport,
                'eta_seconds': 1500, 'distance_meters': 4200, 'destination': settings.destination_name,
                'note': '估算'}

    monkeypatch.setattr(agent.maps, 'route', route)
    monkeypatch.setattr(agent.conversation, 'reply', forbidden_reply())
    result = await agent.ingest(TextInput(text='怎么回家'), directed=True)
    assert result['interaction'] == 'intent_shortcut'
    assert '25分钟' in result['response'] and '4.2公里' in result['response'] and '家' in result['response']
    assert calls == ['driving']  # 未说出行方式时沿用用户设置
    result = await agent.ingest(TextInput(text='骑车回家'), directed=True)
    assert calls == ['driving', 'bicycling'] and '骑行' in result['response']


@pytest.mark.asyncio
async def test_shortcut_falls_back_to_model_when_tool_unavailable(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode='life'))

    async def geocode(address, city=''):
        return {'available': False, 'places': [], 'reason': '尚未配置高德 Key'}

    async def reply(text, instructions):
        return '模型兜底回答'

    monkeypatch.setattr(agent.maps, 'geocode', geocode)
    monkeypatch.setattr(agent.conversation, 'reply', reply)
    result = await agent.ingest(TextInput(text='北京天气怎么样'), directed=True)
    assert result['interaction'] == 'text_llm' and result['response'] == '模型兜底回答'
    assert len(store.list('transcripts')) == 1  # 兜底路径转写只落一次
