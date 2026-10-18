from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest


def sample():
    return {'record_id': uuid4().hex, 'observed_at': datetime.now(timezone.utc).isoformat(),
            'longitude': 116.4, 'latitude': 39.9, 'accuracy': 30, 'source': 'gps', 'trigger': 'interval'}


def test_passive_recording_is_idempotent_exported_and_cleared(client, runtime):
    body = sample()
    first = client.post('/v1/location-records', json=body)
    assert first.status_code == 200, first.text
    assert client.post('/v1/location-records', json=body).json() == first.json()
    changed = {**body, 'longitude': 121.5}
    assert client.post('/v1/location-records', json=changed).status_code == 409
    records = client.get('/v1/location-records').json()['records']
    assert len(records) == 1 and records[0]['trigger'] == 'interval'
    assert client.get('/v1/export').json()['location_records'] == records
    assert client.request('DELETE', '/v1/data', json={'password': 'test-password-123'}).status_code == 200
    assert not client.get('/v1/location-records').json()['records']


def test_location_records_do_not_take_voice_lock_or_change_mode_route_or_quotas(client, runtime, monkeypatch):
    mode, route, drafts = runtime.agent.mode, runtime.agent.route, runtime.store.list('drafts')
    location = runtime.agent.location
    async def forbidden(*args, **kwargs):
        pytest.fail('Passive record invoked business location updates')
    monkeypatch.setattr(runtime.agent, 'update_location', forbidden)
    accounts = client.app.state.accounts
    with accounts.connect() as db:
        before = list(db.execute("SELECT key,count FROM limits WHERE key LIKE 'usage:%'"))
    client.portal.call(runtime.lock.acquire)
    try:
        assert client.post('/v1/location-records', json=sample()).status_code == 200
        assert runtime.agent.location is location
        assert runtime.pending_location['longitude'] == 116.4
    finally:
        client.portal.call(runtime.lock.release)
    assert runtime.agent.mode == mode and runtime.agent.route == route and runtime.store.list('drafts') == drafts
    client.get('/v1/state')
    assert runtime.agent.location['longitude'] == 116.4
    with accounts.connect() as db:
        after = list(db.execute("SELECT key,count FROM limits WHERE key LIKE 'usage:%'"))
    assert before == after


def test_records_are_account_isolated_and_require_authentication(client):
    assert client.post('/v1/location-records', json=sample()).status_code == 200
    other = client.post('/auth/register', json={'username': 'other-location-user', 'password': 'test-password-123'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    assert client.get('/v1/location-records', headers=headers).json()['records'] == []
    assert client.post('/v1/location-records', json=sample(), headers={'Authorization': ''}).status_code == 401


@pytest.mark.parametrize('changes', [
    {'longitude': 181}, {'latitude': -91}, {'accuracy': 201}, {'trigger': 'unknown'},
    {'observed_at': (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()},
    {'observed_at': (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()},
    {'observed_at': '2026-10-18T00:00:00'},
])
def test_invalid_or_stale_location_records_are_rejected(client, changes):
    assert client.post('/v1/location-records', json={**sample(), **changes}).status_code == 422
