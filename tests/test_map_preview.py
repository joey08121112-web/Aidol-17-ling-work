"""Offline route preview, coordinate, image and account isolation regressions."""
import math
from datetime import datetime, timezone

import httpx
import pytest

from app.domain import LocationInput, Settings
from app.providers.amap import amap_image
from app.services.maps import MapService
from app.services.map_preview import MapSnapshot, route_geometry, simplify, snapshot_params

PLACE = dict(name='测试公园', address='公开测试路1号', city='北京', adcode='110101', longitude=116.4, latitude=39.9)


def test_preview_does_not_start_journey_change_settings_or_wait_for_voice(client, runtime, monkeypatch):
    calls = []
    async def route(self, location, settings, **kwargs):
        calls.append((location, kwargs)); return {'available': True, 'geometry': [[[116.4, 39.9], [116.5, 40]]]}
    async def snapshot(self, value): return b'\x89PNG\r\n\x1a\nfixture', 'image/png'
    async def weather(self, code, forecast): return {'available': True, 'city': '北京'}
    monkeypatch.setattr(MapService, 'route', route)
    monkeypatch.setattr(MapService, 'snapshot', snapshot)
    monkeypatch.setattr(MapService, 'weather', weather)
    before = runtime.store.settings().model_dump()
    journey = client.get('/v1/commute').json()
    client.portal.call(runtime.lock.acquire)
    try:
        result = client.post('/v1/commute/preview', json=dict(origin_mode='fixed', origin=PLACE, destination=PLACE))
        assert result.status_code == 200 and result.json()['route']['available']
        response = client.post('/v1/maps/snapshot', json={'destination': PLACE})
        assert response.status_code == 200 and response.headers['content-type'] == 'image/png'
        assert client.post('/v1/maps/weather-at', json=PLACE).json()['available']
    finally:
        client.portal.call(runtime.lock.release)
    assert calls[0][1] == dict(origin_gcj=True, origin_city='北京', prefer_shorter=True)
    assert calls[0][0].longitude == 116.4
    assert runtime.store.settings().model_dump() == before
    assert client.get('/v1/commute').json() == journey


def test_single_pin_and_failed_route_keep_real_endpoints(client, monkeypatch):
    async def failure(*args, **kwargs): raise ValueError('provider secret should not appear')
    monkeypatch.setattr(MapService, 'route', failure)
    result = client.post('/v1/commute/preview', json={'destination': PLACE}).json()
    assert result['origin'] is None and result['destination'] == PLACE
    result = client.post('/v1/commute/preview', json=dict(origin_mode='fixed', origin=PLACE, destination=PLACE)).json()
    assert result['origin'] == PLACE and result['destination'] == PLACE
    assert not result['route']['available'] and 'secret' not in str(result)


def test_gps_preview_converts_once_then_routes_gcj(client, monkeypatch):
    calls = []
    async def convert(self, value): calls.append('convert'); return {'longitude': 116.405, 'latitude': 39.902}
    async def route(self, location, settings, **kwargs):
        calls.append('route'); assert location.longitude == 116.405 and kwargs['origin_gcj']
        return {'available': True}
    monkeypatch.setattr(MapService, 'map_point', convert)
    monkeypatch.setattr(MapService, 'route', route)
    fix = dict(longitude=116.4, latitude=39.9, accuracy=15, observed_at=datetime.now(timezone.utc).isoformat(), source='gps')
    assert client.post('/v1/commute/preview', json=dict(location=fix, destination=PLACE)).json()['route']['available']
    assert calls == ['convert', 'route']
    fix['observed_at'] = '2020-01-01T00:00:00Z'
    assert client.post('/v1/commute/preview', json=dict(location=fix, destination=PLACE)).status_code == 422


@pytest.mark.parametrize('body', [{}, {'destination': {'longitude': 181, 'latitude': 0}}, {'destination': PLACE, 'paths': [[[0, 0]]] * 5}, {'destination': PLACE, 'paths': [[[0, 0]] * 121]}])
def test_snapshot_rejects_missing_out_of_bounds_and_excess_geometry(client, body):
    assert client.post('/v1/maps/snapshot', json=body).status_code == 422


def test_map_routes_require_authentication_and_quota(client, monkeypatch):
    for path, body in [('/v1/maps/snapshot', {'destination': PLACE}), ('/v1/commute/preview', {'destination': PLACE})]:
        assert client.post(path, json=body, headers={'Authorization': ''}).status_code == 401
    monkeypatch.setenv('AIDOL_DAILY_REQUESTS', '1')
    client.post('/v1/maps/snapshot', json={'destination': PLACE})
    assert client.post('/v1/maps/snapshot', json={'destination': PLACE}).status_code == 429


@pytest.mark.asyncio
@pytest.mark.parametrize('transport', ['driving', 'walking', 'bicycling', 'transit'])
async def test_shorter_route_uses_returned_geometry_and_correct_strategy(config, monkeypatch, transport):
    config.amap_key = 'fixture'
    async def get(self, client, path, params):
        if transport == 'driving': assert params['strategy'] == '11'
        paths = [dict(distance='4000', duration='300'), dict(distance='2000', duration='500', steps=[{'polyline': '116.4,39.9;116.5,40'}])]
        if transport == 'transit': paths[1]['segments'] = [{'walking': {'steps': paths[1]['steps']}}]
        return {'data' if transport == 'bicycling' else 'route': {'transits' if transport == 'transit' else 'paths': paths}}
    monkeypatch.setattr(MapService, '_get', get)
    result = await MapService(config).route(LocationInput(longitude=116.4, latitude=39.9), Settings(destination_lon=116.5, destination_lat=40, transport=transport), origin_gcj=True, origin_city='北京', prefer_shorter=True)
    assert result['distance_meters'] == 2000 and result['eta_seconds'] == 500
    assert result['geometry'] == [[[116.4, 39.9], [116.5, 40]]]


def test_transit_geometry_never_joins_alternatives_or_invents_railway():
    line = lambda value: {'polyline': value}
    path = {'segments': [{'bus': {'buslines': [line('1,1;2,2'), line('50,50;60,60')]}}, {'railway': {'name': '铁路'}}, {'walking': {'steps': [line('3,3;4,4')]}}]}
    geometry, partial = route_geometry(path, 'transit', MapService._coordinates)
    assert geometry == [[[1, 1], [2, 2]], [[3, 3], [4, 4]]] and partial
    points = [[i / 10000, math.sin(i / 10) / 10000] for i in range(10000)]
    simplified = simplify(points)
    assert 2 <= len(simplified) <= 120 and simplified[0] == points[0] and simplified[-1] == points[-1]


def test_snapshot_fits_endpoints_and_geometry_without_explicit_zoom():
    params = snapshot_params(MapSnapshot(destination=PLACE, paths=[[(116.4, 39.9), (116.5, 40)]]))
    assert 'zoom' not in params and 'location' not in params
    assert '终:116.400000,39.900000' in params['markers'] and '116.500000,40.000000' in params['paths']


@pytest.mark.asyncio
@pytest.mark.parametrize('content,status', [(b'{"key":"private","error":"bad"}', 200), (b'bad', 403), (b'\x89PNG\r\n\x1a\n' + b'x' * (4 * 1024 * 1024), 200)])
async def test_image_proxy_rejects_errors_and_oversize_without_leaking_key(content, status):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(status, content=content))) as client:
        with pytest.raises(ValueError) as error: await amap_image(client, 'private', {})
        assert 'private' not in str(error.value) and 'https://' not in str(error.value)


@pytest.mark.asyncio
async def test_preview_points_routes_and_images_reuse_owned_http_pools(config, monkeypatch):
    """Merge guard: the new preview path retains shared clients and route geometry."""
    config.amap_key = 'offline-map-key'
    created, hits = [], []
    real_client = httpx.AsyncClient
    image = b'\x89PNG\r\n\x1a\nfixture'

    def handler(request):
        hits.append(request.url.path)
        if request.url.path.endswith('/convert'):
            return httpx.Response(200, json={'status': '1', 'locations': '116.405,39.902'})
        if request.url.path.endswith('/driving'):
            assert request.url.params['strategy'] == '11'
            assert request.url.params['origin'] == '116.405,39.902'
            return httpx.Response(200, json={'status': '1', 'route': {'paths': [
                {'distance': '3000', 'duration': '200'},
                {'distance': '2000', 'duration': '300', 'steps': [{'polyline': '116.405,39.902;116.4,39.9'}]},
            ]}})
        assert request.url.path == '/v3/staticmap'
        assert '116.405000,39.902000' in request.url.params['paths']
        return httpx.Response(200, content=image)

    def factory(**kwargs):
        client = real_client(transport=httpx.MockTransport(handler))
        created.append((kwargs['timeout'], client))
        return client

    monkeypatch.setattr(httpx, 'AsyncClient', factory)
    for _ in range(2):
        # Endpoints construct fresh MapService objects but retain config-owned transports.
        point = await MapService(config).map_point(LocationInput(longitude=116.4, latitude=39.9))
        route = await MapService(config).route(LocationInput(**point),
            Settings(destination_lon=116.4, destination_lat=39.9), origin_gcj=True, prefer_shorter=True)
        assert route['distance_meters'] == 2000 and route['geometry'] == [[[116.405, 39.902], [116.4, 39.9]]]
        snapshot = MapSnapshot(origin=point, destination=PLACE, paths=route['geometry'])
        assert await MapService(config).snapshot(snapshot) == (image, 'image/png')
    assert [timeout for timeout, _ in created] == [10, 12]
    assert len(hits) == 6 and all(not client.is_closed for _, client in created)
    await config.http_pool.aclose()
    assert all(client.is_closed for _, client in created)
