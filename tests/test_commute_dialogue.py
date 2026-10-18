import asyncio
import time
import pytest
from app.domain import TextInput, Mode
from app.services.commute_dialogue import CommuteDialogue, rule_slots
from app.services.commute_journeys import journey_briefing
from app.services.maps import MapService
from app.services.mobile import MobileRuntime

PLACE = {'name': '公司', 'address': '北京市测试路1号', 'city': '北京', 'longitude': 116.4, 'latitude': 39.9, 'adcode': '110101'}


def setup(store):
    settings = store.settings(); settings.mode = Mode.commute; settings.auto_mode = False; store.save_settings(settings)
    store.save_address('work', {**PLACE, 'tag': 'work'})
    store.save_address('home', {**PLACE, 'name': '家', 'tag': 'home', 'longitude': 116.3})


@pytest.mark.parametrize('text,expected', [
    ('从家到公司坐地铁', {'origin': '家', 'destination': '公司', 'transport': 'transit'}),
    ('我想从当前位置到公司，步行', {'origin': '当前位置', 'destination': '公司', 'transport': 'walking'}),
    ('起点是家，目的地是公司，开车', {'origin': '家', 'destination': '公司', 'transport': 'driving'}),
])
def test_rules(text, expected):
    assert rule_slots(text) == expected


def test_required_slots_followups_reuse_saved_points_and_no_early_start(config, store, monkeypatch):
    setup(store)
    async def forbidden(*args): raise AssertionError('saved addresses must not query providers')
    maps = MapService(config); monkeypatch.setattr(maps, 'search', forbidden)
    async def run():
        dialogue = CommuteDialogue(config, store, maps)
        first = await dialogue.handle('我想去公司')
        assert '起点' in first['response'] and '出行方式' in first['response']
        assert not first.get('commute_plan') and not store.commute_journey()
        second = await dialogue.handle('家')
        assert '出行方式' in second['response'] and 'commute_plan' not in second
        ready = await CommuteDialogue(config, store, maps).handle('坐地铁')
        assert ready['interaction'] == 'commute_ready' and ready['speak'] is False
        assert ready['commute_plan']['transport'] == 'transit'
        assert ready['commute_plan']['origin']['longitude'] == 116.3
        assert not store.commute_journey()  # phone starts only after permission
        assert not store.commute_dialogue()
    asyncio.run(run())


def test_ambiguous_address_selection_and_current_position(config, store, monkeypatch):
    setup(store)
    async def search(*args):
        return {'available': True, 'pois': [{**PLACE, 'name': '公园东门'}, {**PLACE, 'name': '公园西门', 'longitude': 116.6}]}
    maps = MapService(config); monkeypatch.setattr(maps, 'search', search)
    async def run():
        dialogue = CommuteDialogue(config, store, maps)
        first = await dialogue.handle('从当前位置去公园，骑行')
        assert '请说序号' in first['response'] and 'commute_plan' not in first
        ready = await dialogue.handle('第二个')
        assert ready['commute_plan']['origin'] is None
        assert ready['commute_plan']['origin_mode'] == 'gps'
        assert ready['commute_plan']['destination']['longitude'] == 116.6
    asyncio.run(run())


def test_unavailable_maps_cancellation_and_no_fabricated_location(config, store):
    setup(store)
    async def run():
        d = CommuteDialogue(config, store, MapService(config))
        result = await d.handle('从家去未知公园，步行')
        assert '暂时未能定位' in result['response'] and 'commute_plan' not in result
        assert (await d.handle('算了'))['response'].startswith('已取消')
        assert not store.commute_dialogue()
        assert await d.handle('今天天气怎么样') is None
    asyncio.run(run())


def test_directed_commute_only_and_no_premature_tts(agent, store):
    setup(store)
    async def run():
        ready = await agent.ingest(TextInput(text='从家到公司，步行'), directed=True)
        assert ready['commute_plan'] and ready['speak'] is False
        ambient = await agent.ingest(TextInput(text='从家到公司，步行'), directed=False)
        assert 'commute_plan' not in ambient
        settings = store.settings(); settings.mode = Mode.work; store.save_settings(settings)
        work = await agent.ingest(TextInput(text='从家到公司，步行'), directed=True, silent=True)
        assert 'commute_plan' not in work
    asyncio.run(run())


def test_model_cannot_invent_place_and_negated_transport_is_not_defaulted(config, store, monkeypatch):
    from app.services.conversation import ConversationService
    setup(store); config.text_key = 'mock'; config.text_model = 'mock'
    async def extract(*args): return '{"origin":"火星","destination":"公司","transport":"driving"}'
    monkeypatch.setattr(ConversationService, 'reply', extract)
    async def run():
        d = CommuteDialogue(config, store, MapService(config))
        result = await d.handle('去公司，不开车')
        assert 'commute_plan' not in result and '起点' in result['response'] and '出行方式' in result['response']
    asyncio.run(run())


def test_clear_removes_unfinished_private_draft(config, store):
    setup(store)
    asyncio.run(CommuteDialogue(config, store, MapService(config)).handle('我想去公司'))
    assert store.commute_dialogue()
    store.clear()
    assert not store.commute_dialogue()


def test_briefing_account_route_and_weather(client, runtime, monkeypatch):
    async def weather(*args, **kwargs): return {'available': True, 'weather': '晴', 'temperature': '21'}
    monkeypatch.setattr(MapService, 'weather', weather)
    plan = {'journey_id': 'commute-briefing-fixture', 'origin_mode': 'fixed', 'origin': PLACE, 'destination': PLACE, 'transport': 'transit'}
    assert client.post('/v1/commute', json=plan).status_code == 200
    runtime.store.claim_commute_refresh(plan['journey_id'], 'fixture-refresh-id', None, time.time())
    runtime.store.finish_commute_refresh(plan['journey_id'], 'fixture-refresh-id', {'available': True, 'eta_seconds': 1800, 'distance_meters': 10000, 'steps': [{'type': 'transit', 'line': '地铁1号线'}]})
    result = client.get('/v1/commute/' + plan['journey_id'] + '/briefing')
    assert result.status_code == 200
    text = result.json()['text']
    assert all(x in text for x in ['30分钟', '10.0公里', '地铁1号线', '晴', '21', '即将打开高德地图'])
    assert client.get('/v1/commute/another-account-id/briefing').status_code == 404
    client.delete('/v1/commute/' + plan['journey_id'])
    assert client.get('/v1/commute/' + plan['journey_id'] + '/briefing').status_code == 404


def test_transit_station_order_and_missing_coordinates():
    points = MapService._station_points({'departure_stop': {'name': '起站', 'location': '116.1,39.1'}, 'via_stops': [{'name': '前一站', 'location': ''}], 'arrival_stop': {'name': '换乘站', 'location': '116.3,39.3'}})
    assert [p['name'] for p in points] == ['起站', '前一站', '换乘站']
    assert 'longitude' not in points[1] and points[-1]['longitude'] == 116.3


def test_mobile_ready_result_does_not_reenable_voice(config, tmp_path):
    rt = MobileRuntime(config, tmp_path / 'mobile.sqlite3'); setup(rt.store)
    result = asyncio.run(rt.turn('从家去公司，坐地铁', 'text', 'direct_question'))
    assert result['speak'] is False and result['interaction'] == 'commute_ready'


def test_location_from_model_needs_role_not_just_mentioned_name(config, store, monkeypatch):
    from app.services.conversation import ConversationService
    setup(store); config.text_key = 'mock'; config.text_model = 'mock'
    async def extract(*args): return '{"origin":"公司","destination":"公司"}'
    monkeypatch.setattr(ConversationService, 'reply', extract)
    result = asyncio.run(CommuteDialogue(config, store, MapService(config)).handle('我想去公司，坐地铁'))
    assert '起点' in result['response'] and 'commute_plan' not in result
