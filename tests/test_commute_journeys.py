from datetime import datetime, timezone, timedelta
from uuid import uuid4
import asyncio
import pytest
from app.storage import Store
from app.services.commute_journeys import CommuteRefresh, refresh_journey
from app.services.maps import MapService

PLACE = {'name': '测试公司', 'address': '北京市测试路1号', 'city': '北京', 'longitude': 116.4, 'latitude': 39.9, 'adcode': '110101'}

def trip(client, fixed=False):
    body = {'journey_id': uuid4().hex, 'origin_mode': 'fixed' if fixed else 'gps', 'origin': PLACE if fixed else None,
            'destination': {**PLACE, 'longitude': 116.5}, 'transport': 'walking'}
    response = client.post('/v1/commute', json=body)
    assert response.status_code == 200, response.text
    return body, response.json()

def fix(**kwargs):
    return {'longitude': 116.3, 'latitude': 39.9, 'accuracy': 30, 'source': 'gps',
            'observed_at': datetime.now(timezone.utc).isoformat(), **kwargs}

def refresh_body(fixed=False):
    return {'request_id': uuid4().hex, **({} if fixed else {'location': fix()})}

@pytest.mark.parametrize('tag', ['home', 'work', 'school', 'custom'])
def test_addresses_persist_export_and_do_not_geocode_again(client, runtime, monkeypatch, tag):
    async def forbidden(*args, **kwargs): raise AssertionError('Saved address unexpectedly called a provider')
    monkeypatch.setattr(MapService, 'geocode', forbidden)
    address_id = uuid4().hex
    saved = client.put('/v1/saved-addresses/' + address_id, json={**PLACE, 'tag': tag}).json()
    assert saved['longitude'] == PLACE['longitude'] and saved['tag'] == tag
    assert Store(runtime.store.path).saved_addresses() == [saved]
    assert client.get('/v1/saved-addresses').json()['addresses'] == [saved]
    assert client.get('/v1/export').json()['saved_addresses'] == [saved]
    if tag in ['home', 'work']:
        assert getattr(runtime.store.settings(), tag + '_lon') == PLACE['longitude']
        stale = runtime.store.settings().model_dump(); stale[tag + '_lon'] = 100
        client.put('/v1/settings', json=stale)
        assert getattr(runtime.store.settings(), tag + '_lon') == PLACE['longitude']
    assert client.delete('/v1/saved-addresses/' + address_id).status_code == 200
    assert not runtime.store.saved_addresses()
    if tag in ['home', 'work']: assert getattr(runtime.store.settings(), tag + '_lon') is None


def test_addresses_unique_tags_validation_and_multiple_custom(client):
    assert client.put('/v1/saved-addresses/a', json={**PLACE, 'tag': 'home'}).status_code == 200
    assert client.put('/v1/saved-addresses/b', json={**PLACE, 'tag': 'home'}).status_code == 409
    for key in ['c', 'd']:
        assert client.put('/v1/saved-addresses/' + key, json={**PLACE, 'tag': 'custom'}).status_code == 200
    for extra in [{'tag': 'invalid'}, {'longitude': 181}, {'address': '  '}, {'city': ''}, {'adcode': 'not-a-code'}]:
        assert client.put('/v1/saved-addresses/e', json={**PLACE, 'tag': 'school', **extra}).status_code == 422


def test_legacy_home_coordinates_are_reused_without_resolution(client, runtime):
    settings = runtime.store.settings(); settings.home_lon = 116.4; settings.home_lat = 39.9; settings.home_name = '原来的家'
    runtime.store.save_settings(settings)
    saved = client.get('/v1/saved-addresses').json()['addresses'][0]
    assert saved['id'] == 'legacy-home' and saved['name'] == '原来的家'
    assert client.delete('/v1/saved-addresses/legacy-home').status_code == 200
    assert not runtime.store.saved_addresses()


def test_account_isolation_in_addresses_and_journeys(client):
    client.put('/v1/saved-addresses/private', json={**PLACE, 'tag': 'home'})
    _, journey = trip(client)
    other = client.post('/auth/register', json={'username': 'other', 'password': 'test-password'}).json()
    client.headers['Authorization'] = 'Bearer ' + other['token']
    assert client.get('/v1/saved-addresses').json() == {'addresses': []}
    assert client.delete('/v1/saved-addresses/private').status_code == 404
    assert client.get('/v1/commute').json() == {}
    assert client.delete('/v1/commute/' + journey['id']).status_code == 404
    assert client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body()).status_code == 404


def test_start_is_explicit_idempotent_and_stop_cannot_restart(client, runtime):
    body, journey = trip(client)
    assert runtime.store.settings().mode == 'commute'
    assert client.post('/v1/commute', json=body).json() == journey
    assert client.post('/v1/commute', json={**body, 'transport': 'driving'}).status_code == 409
    assert client.post('/v1/commute', json={**body, 'journey_id': uuid4().hex}).status_code == 409
    stopped = client.delete('/v1/commute/' + journey['id']).json()
    assert stopped['active'] is False
    assert client.post('/v1/commute', json=body).json()['active'] is False
    assert client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body()).status_code == 404


def test_cannot_start_during_work_recording_or_with_wrong_origin(client, runtime):
    body = {'journey_id': uuid4().hex, 'origin_mode': 'fixed', 'destination': PLACE}
    assert client.post('/v1/commute', json=body).status_code == 422
    body.update(origin_mode='gps', origin=PLACE)
    assert client.post('/v1/commute', json=body).status_code == 422
    body['origin'] = None
    runtime.store.start_work_meeting(uuid4().hex)
    assert client.post('/v1/commute', json=body).status_code == 409


@pytest.mark.parametrize('fixed', [True, False])
def test_refresh_coordinate_system_throttle_failure_and_restart(client, runtime, monkeypatch, fixed):
    calls = []
    async def route(self, location, settings, **kwargs):
        calls.append((location, settings, kwargs))
        return {'available': True, 'eta_seconds': 600, 'distance_meters': 1000, 'destination': settings.destination_name, 'steps': []}
    monkeypatch.setattr(MapService, 'route', route)
    _, journey = trip(client, fixed)
    body = refresh_body(fixed)
    url = '/v1/commute/' + journey['id'] + '/refresh'
    result = client.post(url, json=body).json()
    assert result['route']['eta_seconds'] == 600
    assert len(calls) == 1
    if fixed: assert calls[0][2] == {'origin_gcj': True, 'origin_city': '北京', 'prefer_shorter': True}
    else: assert calls[0][2] == {'prefer_shorter': True} and datetime.fromisoformat(result['location']['observed_at']) == datetime.fromisoformat(body['location']['observed_at'])
    assert client.post(url, json=body).status_code == 200
    assert client.post(url, json=refresh_body(fixed)).status_code == 200
    assert len(calls) == 1
    assert Store(runtime.store.path).commute_journey()['route'] == result['route']
    with runtime.store.connect() as db:
        saved = runtime.store._commute_read(db, 'journey', {}); saved['last_attempt'] -= 60
        runtime.store._commute_write(db, 'journey', saved)
    async def failure(*args, **kwargs): raise TimeoutError('private provider error must not leak')
    monkeypatch.setattr(MapService, 'route', failure)
    failed = client.post(url, json=refresh_body(fixed)).json()
    assert failed['route'] == result['route'] and failed['error'] and not failed['refreshing']
    assert 'private provider' not in str(failed)


@pytest.mark.parametrize('extra', [{'observed_at': '2000-01-01T00:00:00Z'}, {'observed_at': '2099-01-01T00:00:00Z'},
                                  {'observed_at': '2026-10-18T00:00:00'}, {'accuracy': 300}, {'source': 'demo'}])
def test_old_inaccurate_and_non_gps_fixes_are_rejected(client, extra):
    _, journey = trip(client)
    assert client.post('/v1/commute/' + journey['id'] + '/refresh', json={'request_id': uuid4().hex, 'location': fix(**extra)}).status_code == 422


def test_fixed_start_does_not_follow_new_gps_and_gps_requires_fix(client):
    _, journey = trip(client, True)
    assert client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body()).status_code == 422
    client.delete('/v1/commute/' + journey['id'])
    _, journey = trip(client)
    assert client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body(True)).status_code == 422


@pytest.mark.asyncio
@pytest.mark.parametrize('mutation', ['stop', 'clear', 'new', 'delete'])
async def test_late_route_never_restores_stopped_deleted_or_replaced_trip(store, config, mutation):
    from pathlib import Path
    journey = store.start_commute({'id': uuid4().hex, 'origin_mode': 'fixed', 'origin': PLACE, 'destination': PLACE, 'transport': 'walking'})
    entered, release = asyncio.Event(), asyncio.Event()
    class Maps:
        async def route(self, *args, **kwargs):
            entered.set(); await release.wait()
            return {'available': True, 'eta_seconds': 10}
    pending = asyncio.create_task(refresh_journey(store, Maps(), journey['id'], CommuteRefresh(request_id=uuid4().hex), lambda: True))
    await entered.wait()
    if mutation == 'clear': store.clear()
    elif mutation == 'delete': Path(store.path).unlink()
    else:
        store.stop_commute(journey['id'])
        if mutation == 'new': store.start_commute({'id': uuid4().hex, 'origin_mode': 'fixed', 'origin': PLACE, 'destination': PLACE, 'transport': 'walking'})
    release.set(); result = await pending
    assert not result.get('route')
    if mutation == 'delete': assert not Path(store.path).exists()


def test_route_refresh_does_not_need_voice_lock_and_mode_change_ends_trip(client, runtime, monkeypatch):
    async def route(*args, **kwargs): return {'available': True, 'eta_seconds': 300, 'distance_meters': 1000}
    monkeypatch.setattr(MapService, 'route', route)
    _, journey = trip(client, True)
    client.portal.call(runtime.lock.acquire)
    try:
        assert client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body(True)).status_code == 200
    finally:
        client.portal.call(runtime.lock.release)
    settings = runtime.store.settings().model_dump(); settings['mode'] = 'work'
    assert client.put('/v1/settings', json=settings).status_code == 200
    assert client.get('/v1/commute').json()['active'] is False


def test_weather_reuses_saved_adcode(client, monkeypatch):
    calls = []
    async def weather(self, code, forecast=False):
        calls.append((code, forecast)); return {'available': True}
    async def forbidden(*args, **kwargs): raise AssertionError('Unexpected address lookup')
    monkeypatch.setattr(MapService, 'weather', weather)
    monkeypatch.setattr(MapService, 'weather_at', forbidden)
    assert client.post('/v1/maps/weather-at?forecast=true', json=PLACE).json()['available']
    assert calls == [('110101', True)]


def test_gps_near_arrival_keeps_existing_draft_feature_without_duplicates(client, runtime, monkeypatch):
    async def route(*args, **kwargs): return {'available': True, 'eta_seconds': 120, 'distance_meters': 100}
    monkeypatch.setattr(MapService, 'route', route)
    _, journey = trip(client)
    result = client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body()).json()
    drafts = client.get('/v1/drafts').json()['drafts']
    assert len(drafts) == 1 and '2分钟' in drafts[0]['text']
    assert result['arrival_draft_id'] == drafts[0]['id']
    client.post('/v1/commute/' + journey['id'] + '/refresh', json=refresh_body())
    assert len(runtime.store.list('drafts')) == 1


def test_clearing_records_ends_trip_but_preserves_address_settings(client, runtime):
    client.put('/v1/saved-addresses/home', json={**PLACE, 'tag': 'home'})
    trip(client)
    assert client.request('DELETE', '/v1/data', json={'password': 'test-password-123'}).status_code == 200
    assert client.get('/v1/commute').json() == {}
    assert len(client.get('/v1/saved-addresses').json()['addresses']) == 1



@pytest.mark.asyncio
@pytest.mark.parametrize('transport', ['driving', 'transit'])
async def test_fixed_gcj_origin_needs_no_coordinate_conversion_or_city_lookup(config, monkeypatch, transport):
    import httpx
    from app.domain import LocationInput, Settings
    config.amap_key = 'offline-only'
    requests = []
    def handler(request):
        requests.append(request)
        assert 'direction/' in request.url.path
        assert request.url.params['origin'] == '116.4,39.9'
        if transport == 'transit':
            assert request.url.params['city'] == '北京'
        return httpx.Response(200, json={'status': '1', 'route': {
            'transits' if transport == 'transit' else 'paths': [{'duration': '600', 'distance': '2000'}]}})
    real = httpx.AsyncClient
    monkeypatch.setattr('app.services.maps.httpx.AsyncClient', lambda **kw: real(transport=httpx.MockTransport(handler)))
    result = await MapService(config).route(LocationInput(longitude=116.4, latitude=39.9),
        Settings(destination_lon=116.5, destination_lat=40, transport=transport), origin_gcj=True, origin_city='北京')
    assert result['available'] and len(requests) == 1


def test_logout_ends_commute_before_session_is_revoked(client, runtime):
    _, journey = trip(client)
    assert client.post('/auth/logout').status_code == 200
    assert runtime.store.commute_journey()['id'] == journey['id']
    assert runtime.store.commute_journey()['active'] is False
    assert client.get('/v1/commute').status_code == 401
