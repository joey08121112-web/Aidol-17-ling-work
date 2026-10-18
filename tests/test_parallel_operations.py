"""Independent HTTP operations keep running while another provider is waiting."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
import threading
from uuid import uuid4

import httpx
import pytest

from app.providers.bocha import BochaSearch
from app.services.insights import MeetingInsights
from app.services.maps import MapService
from app.services.recall import RecallService


def task_body(**changes):
    return {'task_id': uuid4().hex, 'content': '提交材料', 'owner': '测试同事',
            'due_at': (datetime.now(timezone.utc) + timedelta(days=2)).isoformat(),
            'reminder_hours': 1, **changes}


def work_source(store, text='我负责提交设计稿'):
    return store.add('transcripts', speaker='测试同事', text=text, mode='work', source='asr')


def delayed_history(monkeypatch):
    entered, release, calls = threading.Event(), asyncio.Event(), []

    async def generate(self, rows):
        calls.append(rows)
        entered.set()
        await release.wait()
        source = rows[0]['id']
        return {'summary': '提交设计稿', 'decisions': [], 'extraction': 'llm',
                'source_ids': [source], 'tasks': [
                    {'content': '提交设计稿', 'owner': '测试同事', 'due': '', 'source_ids': [source]}]}

    monkeypatch.setattr(MeetingInsights, 'generate', generate)
    return entered, release, calls


@pytest.mark.parametrize('method,path,body,adapter,operation', [
    ('GET', '/v1/search?q=公开资料', None, BochaSearch, 'search'),
    ('GET', '/v1/maps/geocode?address=公园', None, MapService, 'geocode'),
    ('POST', '/v1/maps/reverse', {'longitude': 116.4, 'latitude': 39.9}, MapService, 'reverse'),
    ('GET', '/v1/maps/search?q=公园', None, MapService, 'search'),
    ('POST', '/v1/maps/nearby', {'longitude': 116.4, 'latitude': 39.9}, MapService, 'nearby'),
    ('GET', '/v1/maps/weather?city=110000', None, MapService, 'weather'),
])
def test_provider_queries_and_task_writes_do_not_take_voice_lock(client, runtime, monkeypatch,
                                                               method, path, body, adapter, operation):
    entered, release = threading.Event(), asyncio.Event()

    async def query(self, *args, **kwargs):
        entered.set()
        await release.wait()
        return {'available': True}

    monkeypatch.setattr(adapter, operation, query)
    client.portal.call(runtime.lock.acquire)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.request, method, path, json=body)
        try:
            assert entered.wait(2), 'Query was blocked by an unrelated voice session'
            assert client.get('/v1/state').status_code == 200
            created = client.post('/v1/tasks', json=task_body())
            assert created.status_code == 200
            task_id = created.json()['id']
            assert client.patch('/v1/tasks/' + task_id, json={'status': 'done'}).status_code == 200
            assert client.get('/v1/tasks').json()['tasks'][0]['status'] == 'done'
            assert runtime.lock.locked() and not pending.done()
        finally:
            client.portal.call(release.set)
            client.portal.call(runtime.lock.release)
        assert pending.result(timeout=3).status_code == 200


def test_history_generation_allows_tasks_settings_and_new_recording(client, runtime, monkeypatch):
    original = work_source(runtime.store)
    entered, release, _ = delayed_history(monkeypatch)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.post, '/v1/meetings', json={'hours': 24})
        try:
            assert entered.wait(2)
            assert client.post('/v1/tasks', json=task_body()).status_code == 200
            assert client.patch('/v1/settings', json={'listen_seconds': 20}).status_code == 200
            later = work_source(runtime.store, '后来增加一项新的讨论')
            assert client.get('/v1/state').status_code == 200 and not pending.done()
        finally:
            client.portal.call(release.set)
        result = pending.result(timeout=3)
    assert result.status_code == 200
    assert result.json()['source_ids'] == [original['id']]
    assert later['id'] not in result.json()['source_ids']
    assert len(runtime.store.list('action_tasks', 100)) == 2


def test_slow_map_allows_address_save_and_commute_start_stop(client, monkeypatch):
    entered, release = threading.Event(), asyncio.Event()

    async def geocode(self, *args):
        entered.set()
        await release.wait()
        return {'available': True}

    monkeypatch.setattr(MapService, 'geocode', geocode)
    place = {'name': '测试目的地', 'address': '测试地址', 'city': '北京',
             'longitude': 116.4, 'latitude': 39.9, 'adcode': '110101'}
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.get, '/v1/maps/geocode?address=公园')
        try:
            assert entered.wait(2)
            assert client.put('/v1/saved-addresses/home', json={**place, 'tag': 'home'}).status_code == 200
            journey_id = uuid4().hex
            started = client.post('/v1/commute', json={
                'journey_id': journey_id, 'origin_mode': 'gps',
                'destination': place, 'transport': 'walking'})
            assert started.status_code == 200 and started.json()['active']
            stopped = client.delete('/v1/commute/' + journey_id)
            assert stopped.status_code == 200 and not stopped.json()['active']
            assert client.delete('/v1/saved-addresses/home').status_code == 200
            assert not pending.done()
        finally:
            client.portal.call(release.set)
        assert pending.result(timeout=3).status_code == 200


def test_same_history_requests_share_generation_and_atomic_result(client, runtime, monkeypatch):
    work_source(runtime.store)
    entered, release, calls = delayed_history(monkeypatch)
    second_snapshot, reads = threading.Event(), []
    original = runtime.store.meeting_report

    def read(signature=None):
        if signature is not None:
            reads.append(signature)
            if len(reads) == 2:
                second_snapshot.set()
        return original(signature)

    monkeypatch.setattr(runtime.store, 'meeting_report', read)
    with ThreadPoolExecutor(max_workers=2) as workers:
        first = workers.submit(client.post, '/v1/meetings', json={})
        assert entered.wait(2)
        second = workers.submit(client.post, '/v1/meetings', json={})
        try:
            assert second_snapshot.wait(2)
            assert len(calls) == 1
            assert not first.done() and not second.done()
        finally:
            client.portal.call(release.set)
        results = [first.result(timeout=3), second.result(timeout=3)]
    assert all(response.status_code == 200 for response in results)
    assert results[0].json() == results[1].json()
    assert len(runtime.store.all_meeting_reports()) == 1
    assert len(runtime.store.list('action_tasks', 100)) == 1


def test_cancelling_one_history_waiter_keeps_shared_operation_running(client, runtime, monkeypatch):
    work_source(runtime.store)
    entered, release, second_snapshot = asyncio.Event(), asyncio.Event(), asyncio.Event()
    calls, snapshots = [], []
    original = runtime.store.meeting_report

    def read(signature=None):
        if signature is not None:
            snapshots.append(signature)
            if len(snapshots) == 2:
                second_snapshot.set()
        return original(signature)

    async def generate(self, rows):
        calls.append(rows)
        entered.set()
        await release.wait()
        return {'summary': '提交材料', 'decisions': [], 'tasks': [], 'source_ids': [rows[0]['id']]}

    monkeypatch.setattr(runtime.store, 'meeting_report', read)
    monkeypatch.setattr(MeetingInsights, 'generate', generate)

    async def requests():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app),
                                     base_url='http://testserver', headers=client.headers) as http:
            first = asyncio.create_task(http.post('/v1/meetings', json={}))
            await asyncio.wait_for(entered.wait(), 2)
            second = asyncio.create_task(http.post('/v1/meetings', json={}))
            await asyncio.wait_for(second_snapshot.wait(), 2)
            try:
                first.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await first
                assert not second.done() and len(calls) == 1
            finally:
                release.set()
            assert (await asyncio.wait_for(second, 3)).status_code == 200

    client.portal.call(requests)
    assert len(calls) == len(runtime.store.all_meeting_reports()) == 1


@pytest.mark.parametrize('mutation', ['delete_meeting', 'clear', 'logout', 'delete_account', 'correct_source'])
def test_history_revalidates_sources_and_account_after_network(client, runtime, monkeypatch, mutation):
    source = work_source(runtime.store)
    meeting_id = uuid4().hex
    runtime.store.start_work_meeting(meeting_id)
    with runtime.store.connect() as db:
        db.execute("UPDATE work_meetings SET status='saved' WHERE id=?", (meeting_id,))
        db.execute('INSERT INTO work_meeting_segments(meeting_id,segment_index,request_id,transcript_id) VALUES(?,0,?,?)',
                   (meeting_id, uuid4().hex, source['id']))
    entered, release, _ = delayed_history(monkeypatch)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.post, '/v1/meetings', json={})
        try:
            assert entered.wait(2)
            if mutation == 'delete_meeting':
                changed = client.delete('/v1/work-meetings/' + meeting_id)
            elif mutation == 'clear':
                changed = client.request('DELETE', '/v1/data', json={'password': 'test-password-123'})
            elif mutation == 'delete_account':
                changed = client.request('DELETE', '/auth/account', json={'password': 'test-password-123'})
            elif mutation == 'logout':
                changed = client.post('/auth/logout')
            else:
                memory = client.post('/v1/memories/from-transcript/' + source['id']).json()
                changed = client.put('/v1/memories/' + memory['id'], json={'content': '修正后的内容'})
            assert changed.status_code == 200
        finally:
            client.portal.call(release.set)
        result = pending.result(timeout=3)
    assert result.status_code == (401 if mutation in {'logout', 'delete_account'} else 409)
    if mutation == 'delete_account':
        assert not Path(runtime.store.path).exists(), 'Late result recreated a deleted account database'
    else:
        assert runtime.store.all_meeting_reports() == []
        assert runtime.store.list('action_tasks', 100) == []


@pytest.mark.parametrize('mutation', ['edit_memory', 'delete_memory', 'complete_task', 'clear', 'logout'])
def test_recall_drops_stale_evidence_after_concurrent_changes(client, runtime, monkeypatch, mutation):
    if mutation == 'complete_task':
        target = client.post('/v1/tasks', json=task_body()).json()['id']
        question = '我的未完成待办'
    else:
        target = client.post('/v1/memories', json={'content': '饮食偏好是清淡食物'}).json()['id']
        question = '我的饮食偏好'
    entered, release = threading.Event(), asyncio.Event()
    original = RecallService.answer

    async def answer(self, *args):
        result = await original(self, *args)
        assert result['citations']
        entered.set()
        await release.wait()
        return result

    monkeypatch.setattr(RecallService, 'answer', answer)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.post, '/v1/recall', json={'question': question})
        try:
            assert entered.wait(2)
            if mutation == 'complete_task':
                changed = client.patch('/v1/tasks/' + target, json={'status': 'done'})
            elif mutation == 'edit_memory':
                changed = client.put('/v1/memories/' + target, json={'content': '饮食偏好已经改变'})
            elif mutation == 'delete_memory':
                changed = client.delete('/v1/memories/' + target)
            elif mutation == 'clear':
                changed = client.request('DELETE', '/v1/data', json={'password': 'test-password-123'})
            else:
                changed = client.post('/auth/logout')
            assert changed.status_code == 200
        finally:
            client.portal.call(release.set)
        result = pending.result(timeout=3)
    assert result.status_code == (401 if mutation == 'logout' else 409)
    assert '清淡食物' not in result.text and '负责人' not in result.text


def test_recall_keeps_valid_result_while_unrelated_records_are_added(client, runtime, monkeypatch):
    original_memory = client.post('/v1/memories', json={'content': '饮食偏好是清淡食物'}).json()
    entered, release = threading.Event(), asyncio.Event()
    original = RecallService.answer

    async def answer(self, *args):
        result = await original(self, *args)
        entered.set()
        await release.wait()
        return result

    monkeypatch.setattr(RecallService, 'answer', answer)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(client.post, '/v1/recall', json={'question': '我的饮食偏好'})
        try:
            assert entered.wait(2)
            assert client.post('/v1/tasks', json=task_body()).status_code == 200
            assert client.post('/v1/memories', json={'content': '明天讨论项目计划'}).status_code == 200
            assert not pending.done()
        finally:
            client.portal.call(release.set)
        result = pending.result(timeout=3)
    assert result.status_code == 200
    assert result.json()['citations'][0]['id'] == original_memory['id']


def test_task_creation_serializes_same_resource_without_voice_lock(client, runtime):
    body = task_body()
    client.portal.call(runtime.lock.acquire)
    try:
        with ThreadPoolExecutor(max_workers=2) as workers:
            first = workers.submit(client.post, '/v1/tasks', json=body)
            second = workers.submit(client.post, '/v1/tasks', json={**body, 'content': '不同的内容'})
            results = [first.result(timeout=3), second.result(timeout=3)]
        assert sorted(response.status_code for response in results) == [200, 409]
        assert len(runtime.store.list('action_tasks', 100)) == 1
        assert runtime.lock.locked()
    finally:
        client.portal.call(runtime.lock.release)


def test_settings_patch_merges_current_fields_and_validates_combined_values(client, runtime):
    assert client.patch('/v1/settings', json={'work_start': 20, 'work_end': 23,
                                            'destination_lon': 116.4, 'destination_lat': 39.9}).status_code == 200
    with ThreadPoolExecutor(max_workers=2) as workers:
        first = workers.submit(client.patch, '/v1/settings', json={'listen_seconds': 20})
        second = workers.submit(client.patch, '/v1/settings', json={'retain_transcripts': False})
        assert first.result(timeout=3).status_code == second.result(timeout=3).status_code == 200
    settings = client.get('/v1/state').json()['settings']
    assert settings['listen_seconds'] == 20 and settings['retain_transcripts'] is False
    assert client.patch('/v1/settings', json={'work_start': 21}).status_code == 200
    assert client.patch('/v1/settings', json={'destination_lon': 116.5}).status_code == 200
    assert client.patch('/v1/settings', json={'work_end': 19}).status_code == 422
    assert client.patch('/v1/settings', json={'destination_lat': None}).status_code == 422
    assert runtime.store.settings().work_end == 23


def test_settings_patch_waits_for_short_lock_without_cancelling_owner(client, runtime, monkeypatch):
    client.portal.call(runtime.lock.acquire)
    waiting = threading.Event()
    acquire = runtime.lock.acquire

    async def mark_waiter():
        waiting.set()
        return await acquire()

    monkeypatch.setattr(runtime.lock, 'acquire', mark_waiter)
    with ThreadPoolExecutor(max_workers=1) as workers:
        result = workers.submit(client.patch, '/v1/settings', json={'listen_seconds': 25})
        try:
            assert waiting.wait(2) and not result.done()
            assert runtime.lock.locked()
        finally:
            # The lock owner's work continues; it releases its own lock normally.
            client.portal.call(runtime.lock.release)
        assert result.result(timeout=3).status_code == 200
    assert runtime.store.settings().listen_seconds == 25


def test_settings_patch_times_out_without_cancelling_voice_owner(client, runtime):
    previous = runtime.store.settings().listen_seconds
    client.portal.call(runtime.lock.acquire)
    try:
        result = client.patch('/v1/settings', json={'listen_seconds': 25})
        assert result.status_code == 409
        assert runtime.lock.locked() and runtime.store.settings().listen_seconds == previous
    finally:
        client.portal.call(runtime.lock.release)


def test_query_quota_and_authentication_remain_enforced(client, monkeypatch):
    monkeypatch.setenv('AIDOL_DAILY_REQUESTS', '1')
    assert client.get('/v1/maps/geocode?address=公园').status_code == 200
    assert client.get('/v1/maps/geocode?address=公园').status_code == 429
    assert client.patch('/v1/settings', headers={'Authorization': ''}, json={'mode': 'work'}).status_code == 401
