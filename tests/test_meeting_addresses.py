from uuid import uuid4

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
    meeting = store.start_work_meeting(uuid4().hex, location=POINT)
    calls = []

    async def reverse(self, point):
        calls.append(point)
        if len(calls) == 1:
            raise TimeoutError('offline test')
        return {'available': True, 'address': '重试后的地址'}

    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    resolver, background = MeetingAddresses(config), BackgroundTasks()
    resolver.schedule(background, store, [meeting])
    await background()
    assert store.work_meeting(meeting['id'])['location'] == POINT
    retry = BackgroundTasks()
    resolver.schedule(retry, store, [meeting])
    assert not retry.tasks and len(calls) == 1
    resolver.retry_after.clear()  # Advance past the retry window without sleeping.
    resolver.schedule(retry, store, [meeting])
    await retry()
    assert store.work_meeting(meeting['id'])['location']['address'] == '重试后的地址'


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
