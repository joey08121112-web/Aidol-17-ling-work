from uuid import uuid4
import asyncio

from fastapi import BackgroundTasks
import pytest

from app.services.meeting_addresses import MeetingAddresses
from app.storage import Store


POINT = {'longitude': 116.4, 'latitude': 39.9, 'accuracy': 30, 'source': 'gps'}


@pytest.mark.asyncio
async def test_background_enrichment_updates_existing_report_and_is_not_repeated(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting_id = uuid4().hex
    meeting = store.start_work_meeting(meeting_id, location=POINT)
    assert store.save_work_summary(meeting_id, {'title': '测试', 'tasks': [], 'extraction': 'llm'}, [])
    calls = []

    async def reverse(self, point):
        calls.append(point)
        return {'available': True, 'address': '测试路会议中心'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    resolver, background = MeetingAddresses(config), BackgroundTasks()
    resolver.schedule(background, store, [meeting])
    resolver.schedule(background, store, [meeting])
    assert not calls  # Neither scheduling nor a page read waits for the provider.
    assert len(background.tasks) == 1
    await background()
    saved = store.work_meeting(meeting_id)
    assert saved['location'] == {**POINT, 'address': '测试路会议中心'}
    assert saved['report']['location'] == saved['location']
    assert store.work_meetings()[0]['location'] == saved['location']
    again = BackgroundTasks()
    resolver.schedule(again, store, [saved])
    assert not again.tasks and len(calls) == 1
    # A report generated from an older snapshot must keep the resolved address.
    assert store.save_work_summary(meeting_id, {'location': POINT, 'tasks': [], 'extraction': 'llm'}, [])
    assert store.work_meeting(meeting_id)['report']['location'] == saved['location']


@pytest.mark.asyncio
async def test_provider_failure_keeps_coordinates_and_throttles_poll_retries(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    point = {**POINT, 'cached': True, 'observed_at': '2026-10-18T01:00:00+00:00'}
    meeting = store.start_work_meeting(uuid4().hex, location=point)
    calls = []

    async def reverse(self, point):
        calls.append(point)
        if len(calls) == 1:
            raise TimeoutError('offline test')
        return {'available': True, 'address': '重试后的地址'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    stamp, delays = [1000], []
    async def advance(delay):
        delays.append(delay); stamp[0] += delay
        await asyncio.sleep(0)
    resolver, background = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance), BackgroundTasks()
    resolver.schedule(background, store, [meeting])
    await background()
    assert store.work_meeting(meeting['id'])['location'] == point
    retry = BackgroundTasks()
    resolver.schedule(retry, store, [meeting])
    assert not retry.tasks and len(calls) == 1
    await asyncio.gather(*list(resolver.tasks))
    assert store.work_meeting(meeting['id'])['location']['address'] == '重试后的地址'
    assert store.work_meeting(meeting['id'])['location']['observed_at'] == point['observed_at']
    assert store.work_meeting(meeting['id'])['location']['cached'] is True
    assert delays == [30]
    assert store.work_meeting(meeting['id'])['address_attempts'] == 2


@pytest.mark.asyncio
async def test_clearing_meetings_during_lookup_never_restores_location(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)

    async def reverse(self, point):
        store.clear()
        return {'available': True, 'address': '不能恢复的地址'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    background = BackgroundTasks()
    MeetingAddresses(config).schedule(background, store, [meeting])
    await background()
    assert store.work_meeting(meeting['id']) is None
    assert not store.work_meetings()


@pytest.mark.asyncio
async def test_same_meeting_id_in_other_accounts_is_enriched_independently(config, store, tmp_path, monkeypatch):
    config.amap_key = 'offline-only'
    other = Store(str(tmp_path / 'other.sqlite3'))
    meeting_id = uuid4().hex
    first = store.start_work_meeting(meeting_id, location=POINT)
    second = other.start_work_meeting(meeting_id, location={**POINT, 'longitude': 121.5})

    async def reverse(self, point):
        return {'available': True, 'address': '地点甲' if point.longitude == 116.4 else '地点乙'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    resolver, background = MeetingAddresses(config), BackgroundTasks()
    resolver.schedule(background, store, [first])
    resolver.schedule(background, other, [second])
    await background()
    assert store.work_meeting(meeting_id)['location']['address'] == '地点甲'
    assert other.work_meeting(meeting_id)['location']['address'] == '地点乙'


def test_page_reads_backfill_old_coordinates_without_replacing_snapshot(client, runtime, config, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = runtime.store.start_work_meeting(uuid4().hex, location=POINT)

    async def reverse(self, point):
        return {'available': True, 'address': '老会议的开始地址'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    assert client.get('/v1/work-meetings/' + meeting['id']).status_code == 200
    detail = client.get('/v1/work-meetings/' + meeting['id']).json()
    assert detail['started_at'] == meeting['started_at']
    assert detail['location'] == {**POINT, 'address': '老会议的开始地址'}
    assert client.get('/v1/work-meetings').json()['meetings'][0]['location'] == detail['location']
    assert client.get('/v1/state').json()['work_meetings'][0]['location'] == detail['location']


def test_late_lookup_cannot_overwrite_a_different_location_or_saved_address(store):
    meeting_id = uuid4().hex
    store.start_work_meeting(meeting_id, location=POINT)
    assert not store.save_work_meeting_address(meeting_id, {**POINT, 'longitude': 121.5}, '错误地址')
    assert store.save_work_meeting_address(meeting_id, POINT, '原始地址')
    assert not store.save_work_meeting_address(meeting_id, POINT, '覆盖地址')
    assert store.work_meeting(meeting_id)['location'] == {**POINT, 'address': '原始地址'}


@pytest.mark.asyncio
async def test_three_thirty_second_retries_stop_even_after_a_service_restart(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)
    stamp, delays, calls = [1000], [], []
    async def advance(delay):
        delays.append(delay); stamp[0] += delay
        await asyncio.sleep(0)
    async def unavailable(self, point):
        calls.append(stamp[0])
        return {'available': False}
    monkeypatch.setattr('app.services.maps.MapService.reverse', unavailable)
    resolver = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance)
    background = BackgroundTasks(); resolver.schedule(background, store, [meeting]); await background()
    await asyncio.gather(*list(resolver.tasks))
    assert calls == [1000, 1030, 1060, 1090] and delays == [30, 30, 30]
    saved = store.work_meeting(meeting['id'])
    assert saved['address_attempts'] == 4 and saved['address_retry_at'] is None
    assert saved['location'] == POINT
    restarted = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance)
    again = BackgroundTasks(); restarted.schedule(again, store, [saved]); await again()
    assert not again.tasks and len(calls) == 4


@pytest.mark.asyncio
async def test_pending_retry_survives_restart_and_does_not_repeat_initial_attempt(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)
    stamp, delays, calls = [1000], [], []
    async def blocked(delay):
        await asyncio.Event().wait()
    async def advance(delay):
        delays.append(delay); stamp[0] += delay
        await asyncio.sleep(0)
    async def reverse(self, point):
        calls.append(stamp[0])
        return {'available': len(calls) > 1, 'address': '重启后完成'}
    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    old = MeetingAddresses(config, clock=lambda: stamp[0], sleep=blocked)
    background = BackgroundTasks(); old.schedule(background, store, [meeting]); await background()
    await old.close()
    saved = store.work_meeting(meeting['id'])
    assert saved['address_attempts'] == 1 and saved['address_retry_at'] == 1030
    new = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance)
    background = BackgroundTasks(); new.schedule(background, store, [saved]); await background()
    assert calls == [1000]
    await asyncio.gather(*list(new.tasks))
    assert calls == [1000, 1030] and delays == [30]
    assert store.work_meeting(meeting['id'])['location']['address'] == '重启后完成'


@pytest.mark.asyncio
async def test_clearing_data_during_retry_wait_cancels_future_provider_requests(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)
    stamp, calls = [1000], []
    async def advance(delay):
        stamp[0] += delay
        await asyncio.sleep(0)
    async def unavailable(self, point):
        calls.append(point); return {'available': False}
    monkeypatch.setattr('app.services.maps.MapService.reverse', unavailable)
    resolver = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance)
    background = BackgroundTasks(); resolver.schedule(background, store, [meeting]); await background()
    store.clear()
    await asyncio.gather(*list(resolver.tasks))
    assert len(calls) == 1 and store.work_meeting(meeting['id']) is None


@pytest.mark.asyncio
async def test_restart_during_final_attempt_expires_pending_status_without_a_fifth_request(config, store, monkeypatch):
    config.amap_key = 'offline-only'
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)
    for attempt in range(4):
        assert store.claim_meeting_address(meeting['id'], POINT, 1000 + attempt * 42)['attempt'] == attempt + 1
    stamp, delays = [1126], []
    async def advance(delay):
        delays.append(delay); stamp[0] += delay
        await asyncio.sleep(0)
    async def forbidden(self, point):
        pytest.fail('A fifth address request must never be issued')
    monkeypatch.setattr('app.services.maps.MapService.reverse', forbidden)
    resolver = MeetingAddresses(config, clock=lambda: stamp[0], sleep=advance)
    background = BackgroundTasks()
    resolver.schedule(background, store, [store.work_meeting(meeting['id'])]); await background()
    await asyncio.gather(*list(resolver.tasks))
    saved = store.work_meeting(meeting['id'])
    assert delays == [42] and saved['address_attempts'] == 4 and saved['address_retry_at'] is None
