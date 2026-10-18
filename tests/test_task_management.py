from datetime import datetime, timedelta, timezone
from uuid import uuid4


def task_body():
    return {'task_id': uuid4().hex, 'content': '提交设计稿', 'owner': '小林',
            'due_at': (datetime.now(timezone.utc) + timedelta(days=2)).isoformat(), 'reminder_hours': 6}


def test_custom_task_create_idempotency_completion_deletion_and_isolation(client):
    body = task_body()
    first = client.post('/v1/tasks', json=body)
    assert first.status_code == 200, first.text
    assert client.post('/v1/tasks', json=body).json() == first.json()
    assert client.post('/v1/tasks', json={**body, 'content': '其他内容'}).status_code == 409
    tasks = client.get('/v1/tasks').json()['tasks']
    assert len(tasks) == 1 and tasks[0]['reminder_hours'] == 6 and tasks[0]['due_at'] == body['due_at']
    task_id = tasks[0]['id']
    assert client.patch('/v1/tasks/' + task_id, json={'status': 'done'}).status_code == 200
    assert client.get('/v1/tasks').json()['tasks'][0]['status'] == 'done'
    other = client.post('/auth/register', json={'username': 'task-other', 'password': 'x'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    assert not client.get('/v1/tasks', headers=headers).json()['tasks']
    assert client.delete('/v1/tasks/' + task_id, headers=headers).status_code == 404
    assert client.delete('/v1/tasks/' + task_id).status_code == 200
    assert not client.get('/v1/tasks').json()['tasks']


def test_invalid_task_deadline_reminder_and_empty_content(client):
    body = task_body()
    for values in [{'content': '   '}, {'reminder_hours': 2}, {'due_at': '2026-01-01T01:00:00'},
                   {'due_at': (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()}]:
        assert client.post('/v1/tasks', json={**body, **values}).status_code == 422


def test_task_deletion_survives_summary_regeneration_without_deleting_other_manual_items(store):
    meeting_id = uuid4().hex
    meeting = store.start_work_meeting(meeting_id)
    report = {'summary': '测试', 'meeting_id': meeting_id, 'extraction': 'llm',
              'tasks': [{'content': '报告', 'owner': '小林', 'due': '周五', 'source_ids': []}]}
    store.save_work_summary(meeting_id, report, [])
    generated = store.list('action_tasks')[0]
    manual = store.create_action_task(uuid4().hex, '手动任务', '', '2030-01-01T00:00:00+00:00', 1)
    assert store.delete_action_task(generated['id'])
    store.save_work_summary(meeting_id, report, [])
    assert [row['id'] for row in store.list('action_tasks')] == [manual['id']]
    store.clear()
    assert not store.list('action_tasks')


def test_edit_keeps_identity_status_order_and_reschedules_fields(client):
    body = task_body()
    original = client.post('/v1/tasks', json=body).json()
    client.patch('/v1/tasks/' + original['id'], json={'status': 'done'})
    later = client.post('/v1/tasks', json=task_body()).json()
    edited = {'content': '  修订设计稿  ', 'owner': '  小陈  ', 'due_at': '2031-02-03T18:00:00+08:00', 'reminder_hours': 24}
    response = client.put('/v1/tasks/' + original['id'], json=edited)
    assert response.status_code == 200, response.text
    saved = response.json()
    assert saved['id'] == original['id'] and saved['created_at'] == original['created_at']
    assert saved['status'] == 'done' and saved['content'] == '修订设计稿' and saved['owner'] == '小陈'
    assert saved['due_at'] == saved['due'] == '2031-02-03T10:00:00+00:00' and saved['reminder_hours'] == 24
    assert client.put('/v1/tasks/' + original['id'], json=edited).json() == saved
    tasks = client.get('/v1/tasks').json()['tasks']
    assert [row['id'] for row in tasks] == [later['id'], original['id']]


def test_edit_is_account_scoped_and_cannot_resurrect_deleted_task(client):
    task = client.post('/v1/tasks', json=task_body()).json()
    edit = {'content': '修订', 'due': '下周五', 'reminder_hours': 0}
    other = client.post('/auth/register', json={'username': 'edit-other', 'password': 'x'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    assert client.put('/v1/tasks/' + task['id'], json=edit, headers=headers).status_code == 404
    assert client.put('/v1/tasks/' + task['id'], json=edit, headers={'Authorization': ''}).status_code == 401
    assert client.delete('/v1/tasks/' + task['id']).status_code == 200
    assert client.put('/v1/tasks/' + task['id'], json=edit).status_code == 404
    assert not client.get('/v1/tasks').json()['tasks']


def test_edit_supports_legacy_text_deadlines_expired_tasks_and_disabling_reminders(client):
    task = client.post('/v1/tasks', json=task_body()).json()
    endpoint = '/v1/tasks/' + task['id']
    for due_at, due in [(None, '周五'), ('2020-01-01T00:00:00Z', '')]:
        saved = client.put(endpoint, json={'content': '修订内容', 'due': due, 'due_at': due_at, 'reminder_hours': 0}).json()
        assert saved['reminder_hours'] == 0
        assert saved['due'] == ('2020-01-01T00:00:00+00:00' if due_at else '周五')
    for fields in [{'content': ' '}, {'owner': 'x' * 101}, {'due_at': '2030-01-01T00:00:00'},
                   {'reminder_hours': 2}, {'reminder_hours': 1}, {'due': 'x' * 501}]:
        assert client.put(endpoint, json={'content': '有效内容', **fields}).status_code == 422
    assert client.post('/v1/tasks', json={**task_body(), 'reminder_hours': 0}).status_code == 200


def test_edited_ai_task_survives_regeneration_retaining_sources_and_report_snapshot(store):
    meeting_id = uuid4().hex
    store.start_work_meeting(meeting_id)
    task = {'content': '报告', 'owner': '小林', 'due': '周五', 'source_ids': ['source-1']}
    report = {'summary': '测试', 'meeting_id': meeting_id, 'extraction': 'llm', 'tasks': [task]}
    assert store.save_work_summary(meeting_id, report, [])
    original = store.list('action_tasks')[0]
    store.action_task_status(original['id'], 'done')
    edited = store.edit_action_task(original['id'], '修订报告', '小陈', '下周一', '', 0)
    assert edited['source_ids'] == task['source_ids'] and edited['status'] == 'done'
    assert store.work_meeting(meeting_id)['report']['tasks'] == [task]
    assert store.save_work_summary(meeting_id, report, [])
    assert store.list('action_tasks') == [edited]
    assert store.delete_action_task(edited['id'])
    assert store.save_work_summary(meeting_id, report, [])
    assert not store.list('action_tasks')


def test_unchanged_ai_edit_does_not_override_report_ownership(store):
    meeting_id = uuid4().hex
    store.start_work_meeting(meeting_id)
    report = {'summary': '测试', 'meeting_id': meeting_id, 'extraction': 'llm',
              'tasks': [{'content': '报告', 'owner': '小林', 'due': '周五', 'source_ids': []}]}
    store.save_work_summary(meeting_id, report, [])
    original = store.list('action_tasks')[0]
    assert store.edit_action_task(original['id'], '报告', '小林', '周五', '', 0) == original
    assert store.save_work_summary(meeting_id, report, [])
    assert store.list('action_tasks')[0]['id'] == original['id']
    assert store.list('action_tasks')[0]['report_id']


def test_first_report_does_not_reuse_id_of_independent_edited_task(store):
    task = store.create_action_task(uuid4().hex, '独立行动', '小林', '2030-01-01T00:00:00+00:00', 1)
    meeting_id = uuid4().hex
    store.start_work_meeting(meeting_id)
    report = {'summary': '测试', 'meeting_id': meeting_id, 'extraction': 'llm',
              'tasks': [{'content': task['content'], 'owner': task['owner'], 'due': task['due'], 'source_ids': []}]}
    assert store.save_work_summary(meeting_id, report, [])
    assert len({row['id'] for row in store.list('action_tasks')}) == 2
