from uuid import uuid4

import pytest

from app.domain import Mode
from app.storage import Store


def finished(store, started='2030-01-01T00:00:00+00:00'):
    mid = uuid4().hex
    store.start_work_meeting(mid, started)
    source = store.save_work_segment(mid, 0, uuid4().hex, '提交会议设计稿。', duration_ms=1000, audio=b'\0' * 32000)
    store.add_work_event(mid, uuid4().hex, 'highlight', 0, 'app')
    store.finish_work_meeting(mid, 1, True, transcribe=False)
    report = {'meeting_id': mid, 'title': '会议管理测试', 'summary': '提交设计稿。', 'extraction': 'llm',
              'source_ids': [source + '.0'],
              'tasks': [{'content': '提交设计稿', 'owner': '测试', 'due': '周五', 'source_ids': [source + '.0']}]}
    assert store.save_work_summary(mid, report, [source])
    return mid, source, report


def test_multiple_pins_sort_by_actual_time_and_unpin_restores_order(client, runtime):
    a, _, _ = finished(runtime.store, '2030-01-01T08:00:00+08:00')
    b, _, _ = finished(runtime.store, '2030-01-01T00:30:00+00:00')
    c, _, _ = finished(runtime.store, '2030-01-01T02:00:00+00:00')
    original = runtime.store.work_meeting(a)
    assert [r['id'] for r in runtime.store.work_meetings()] == [c, b, a]
    assert client.patch('/v1/work-meetings/' + a, json={'pinned': True}).status_code == 200
    response = client.patch('/v1/work-meetings/' + b, json={'pinned': True})
    assert [r['id'] for r in response.json()['meetings']] == [b, a, c]
    again = client.patch('/v1/work-meetings/' + b, json={'pinned': True}).json()
    assert [r['id'] for r in again['meetings']] == [b, a, c]
    reopened = Store(runtime.store.path)
    assert reopened.work_meeting(a)['pinned'] is True
    assert reopened.work_meeting(a)['started_at'] == original['started_at']
    assert reopened.work_meeting(a)['report'] == original['report']
    result = client.patch('/v1/work-meetings/' + a, json={'pinned': False}).json()
    assert [r['id'] for r in result['meetings']] == [b, c, a]
    assert client.get('/v1/export').json()['work_meetings'][0]['pinned'] is True


def test_delete_cleans_meeting_and_linked_tasks_but_preserves_independent_content(client, runtime):
    store = runtime.store
    settings = store.settings(); settings.mode = Mode.work; store.save_settings(settings)
    mid, source, report = finished(store)
    other, other_source, _ = finished(store)
    generated = next(row for row in store.list('action_tasks') if row['report_id'] == store.work_meeting(mid)['report_id'])
    edited = store.edit_action_task(generated['id'], '修改后的设计稿任务', '测试', '周一', '', 0)
    manual = store.create_action_task(uuid4().hex, '独立任务', '', '2031-01-01T00:00:00+00:00', 1)
    memory = store.remember_transcript(source)
    reply = store.add('transcripts', speaker='assistant', text='另一项独立对话', mode='memory', source='asr')
    assert store._work_audio_dir(mid).exists()
    response = client.delete('/v1/work-meetings/' + mid)
    assert response.status_code == 200, response.text
    assert response.json()['deleted_task_ids'] == [edited['id']]
    assert client.delete('/v1/work-meetings/' + mid).json()['deleted_task_ids'] == [edited['id']]
    assert client.get('/v1/work-meetings/' + mid).status_code == 404
    assert client.get('/v1/work-meetings/' + mid + '/audio').status_code == 404
    assert client.patch('/v1/work-meetings/' + mid, json={'pinned': True}).status_code == 404
    assert client.post('/v1/work-meetings', json={'meeting_id': mid}).status_code == 409
    assert store.work_meeting(other) is not None and store.get_memory(memory['id']) is not None
    assert manual['id'] in {row['id'] for row in store.list('action_tasks')}
    assert source not in {row['id'] for row in store.list('transcripts')}
    assert other_source in {row['id'] for row in store.list('transcripts')}
    assert reply['id'] in {row['id'] for row in store.list('transcripts')}
    assert not store._work_audio_dir(mid).exists()
    with store.connect() as db:
        for table in ('work_utterances', 'work_meeting_events', 'work_meeting_segments', 'work_audio_uploads'):
            assert db.execute(f'SELECT count(*) FROM {table} WHERE meeting_id=?', (mid,)).fetchone()[0] == 0
    assert not store.save_work_summary(mid, report, [source])
    assert not store.save_work_meeting_address(mid, {}, '迟到地址')
    assert not store.save_work_dialogue(mid, [{'text': '迟到转写', 'start_ms': 0, 'end_ms': 1000, 'speaker': '说话人1'}])
    with pytest.raises(ValueError):
        Store(store.path).start_work_meeting(mid)
    with pytest.raises(ValueError):
        store.save_work_segment(mid, 0, uuid4().hex, '迟到分段')


def test_recording_delete_is_blocked_and_bookmarks_are_account_scoped(client, runtime):
    mid = uuid4().hex; runtime.store.start_work_meeting(mid)
    assert client.delete('/v1/work-meetings/' + mid).status_code == 409
    assert runtime.store.work_meeting(mid)['status'] == 'recording'
    assert client.patch('/v1/work-meetings/' + mid, json={'pinned': 'true'}).status_code == 422
    other = client.post('/auth/register', json={'username': 'meeting-other', 'password': 'x'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    for method in ('patch', 'delete'):
        kwargs = {'json': {'pinned': True}} if method == 'patch' else {}
        assert getattr(client, method)('/v1/work-meetings/' + mid, headers=headers, **kwargs).status_code == 404
        assert getattr(client, method)('/v1/work-meetings/' + mid, headers={'Authorization': ''}, **kwargs).status_code == 401
    assert not runtime.store.work_meeting(mid)['pinned']


def test_old_database_migrates_pins_without_changing_existing_record(store):
    mid, _, _ = finished(store)
    original = store.work_meeting(mid)
    with store.connect() as db:
        db.execute('ALTER TABLE work_meetings DROP COLUMN pinned')
    reopened = Store(store.path)
    assert reopened.work_meeting(mid)['pinned'] is False
    assert reopened.work_meeting(mid)['report'] == original['report']


def test_deleting_empty_meeting_does_not_invalidate_unrelated_conversation(store):
    mid = uuid4().hex
    store.start_work_meeting(mid); store.finish_work_meeting(mid, 0, True, transcribe=False)
    reply = store.add('transcripts', speaker='assistant', text='另一项独立对话', mode='memory', source='asr')
    assert store.delete_work_meeting(mid) == []
    assert reply['id'] in {row['id'] for row in store.list('transcripts')}
