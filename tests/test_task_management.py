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
