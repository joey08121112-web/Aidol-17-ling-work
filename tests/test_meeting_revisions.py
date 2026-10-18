from copy import deepcopy
import json
from uuid import uuid4
import pytest
import httpx

from app.services.meeting_revisions import editable_document, prepare_revision, merge_revision, MeetingRevisions
from app.services.work_meetings import MinutesFailure


def report():
    return {'title': '设计评审', 'summary': '小林负责设计。\n安排周五交稿。',
            'tasks': [{'content': '提交设计', 'owner': '小林', 'due': '周五', 'source_ids': ['s']}],
            'topics': [{'text': '小林周五交稿', 'source_ids': ['s']}],
            'viewpoints': [], 'conflicts': [], 'events': [], 'decisions': [], 'open_questions': [], 'chapters': [], 'highlights': []}


ROWS = [{'id': 's', 'speaker': '小林', 'text': '小林负责设计，周五交稿。'}]


def test_user_edit_wins_ai_updates_other_fields_and_deleted_items_cannot_return():
    original = report(); doc = editable_document(original)
    doc['tasks'][0]['owner'] = '说话人1'
    doc['tasks'][0]['due'] = '周一'
    doc['summary'][0]['text'] = '说话人1负责设计。'
    doc['decisions'] = [{'id': 'new-' + uuid4().hex, 'text': '新增审核流程', 'source_ids': []}]
    old, edited, protected, evidence = prepare_revision(original, doc, uuid4().hex)
    model = deepcopy(edited)
    model['tasks'][0]['owner'] = '小林'  # Must not undo the user edit.
    model['summary'][1]['text'] = '安排周一交稿。'
    model['topics'][0].update(text='说话人1周一交稿', source_ids=[evidence[1]['id']])
    final = merge_revision(model, original, old, edited, protected, ROWS + evidence, 'revision')
    assert final['tasks'][0]['owner'] == '说话人1' and final['tasks'][0]['due'] == '周一'
    assert final['summary'] == '说话人1负责设计。\n安排周一交稿。'
    assert final['decisions'][0]['text'] == '新增审核流程'
    assert final['revision_sources'] and original == report()
    bad = deepcopy(model); bad['topics'].append({'id': 'topics:99', 'text': '不允许新增', 'source_ids': ['s']})
    with pytest.raises(ValueError): merge_revision(bad, original, old, edited, protected, ROWS + evidence, 'revision')


def test_deleting_sections_and_new_user_citations_do_not_rewrite_original_evidence():
    original = report(); doc = editable_document(original); doc['tasks'] = []; doc['summary'] = []
    doc['topics'][0]['source_ids'] = ['other-account']  # Hidden citations from clients are not trusted.
    old, edited, protected, evidence = prepare_revision(original, doc, 'revision')
    assert edited['topics'][0]['source_ids'] == ['s']
    final = merge_revision(deepcopy(edited), original, old, edited, protected, ROWS, 'revision')
    assert final['summary'] == '' and final['tasks'] == []
    assert ROWS[0]['text'] == '小林负责设计，周五交稿。'


def seed(store):
    meeting_id = uuid4().hex
    store.start_work_meeting(meeting_id)
    store.save_work_segment(meeting_id, 0, uuid4().hex, ROWS[0]['text'])
    store.finish_work_meeting(meeting_id, 1, True)
    meeting = store.work_meeting(meeting_id)
    data = report(); source = store.work_meeting_evidence(meeting)[0]['id']
    for key in ('tasks', 'topics'):
        for item in data[key]: item['source_ids'] = [source]
    data.update(extraction='llm', meeting_id=meeting_id)
    store.save_work_summary(meeting_id, data, [r['id'] for r in meeting['sources']])
    return meeting_id


def test_revision_endpoint_preserves_draft_and_old_report_on_failure_and_rejects_stale_base(client, runtime, monkeypatch):
    meeting_id = seed(runtime.store)
    before = runtime.store.work_meeting(meeting_id)
    editor = client.get('/v1/work-meetings/' + meeting_id + '/revision').json()
    editor['document']['tasks'][0]['due'] = '下周一'
    async def fail(*args): raise MinutesFailure('timeout')
    monkeypatch.setattr(MeetingRevisions, 'generate', fail)
    body = {'request_id': uuid4().hex, **editor}
    url = '/v1/work-meetings/' + meeting_id + '/revise-summary'
    assert client.post(url, json=body).status_code == 200
    after = runtime.store.work_meeting(meeting_id)
    assert after['report'] == before['report'] and after['report_id'] == before['report_id']
    assert after['revision_request']['status'] == 'failed' and after['revision_request']['error'] == 'timeout'
    assert client.get('/v1/work-meetings/' + meeting_id + '/revision').json()['document'] == editor['document']
    assert client.post(url, json={**body, 'request_id': uuid4().hex, 'base_report_id': 'stale'}).status_code == 409
    other = client.post('/auth/register', json={'username': 'revision-other', 'password': 'x'}).json()
    assert client.get('/v1/work-meetings/' + meeting_id + '/revision', headers={'Authorization': 'Bearer ' + other['token']}).status_code == 404


@pytest.mark.asyncio
async def test_real_protocol_model_output_is_reconciled_and_quotas_charged(config, monkeypatch):
    config.text_key, config.text_model = 'offline', 'test'
    original = report(); doc = editable_document(original); doc['tasks'][0]['due'] = '周一'
    charges = []
    async def post(self, url, **kwargs):
        payload = json.loads(kwargs['json']['messages'][1]['content'])
        response = deepcopy(payload['edited'])
        response['summary'][1]['text'] = '安排周一交稿。'
        return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(response, ensure_ascii=False)}}]}, request=httpx.Request('POST', url))
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    result = await MeetingRevisions(config, lambda: charges.append(1) or True).generate(original, doc, 'revision', ROWS)
    assert result['tasks'][0]['due'] == '周一' and '周一' in result['summary'] and len(charges) == 1


def test_successful_revision_updates_report_and_tasks_once_preserving_manual_tasks(client, runtime, monkeypatch):
    meeting_id = seed(runtime.store)
    manual = runtime.store.create_action_task(uuid4().hex, '独立手动任务', '小王', '2030-01-01T00:00:00+00:00', 6)
    editor = client.get('/v1/work-meetings/' + meeting_id + '/revision').json()
    editor['document']['tasks'][0]['owner'] = '小王'
    editor['document']['tasks'][0]['due'] = '周一'
    seen = []
    async def generate(self, prior, doc, revision_id, rows):
        seen.append(revision_id)
        old, edited, protected, added = prepare_revision(prior, doc, revision_id)
        model = deepcopy(edited)
        model['summary'][0]['text'] = '小王负责设计。'
        model['summary'][1]['text'] = '安排周一交稿。'
        model['topics'][0].update(text='小王周一交稿', source_ids=[added[0]['id']])
        return merge_revision(model, prior, old, edited, protected, rows + added, revision_id)
    monkeypatch.setattr(MeetingRevisions, 'generate', generate)
    body = {'request_id': uuid4().hex, **editor}; url = '/v1/work-meetings/' + meeting_id + '/revise-summary'
    assert client.post(url, json=body).status_code == 200
    final = runtime.store.work_meeting(meeting_id)
    assert final['revision_request']['status'] == 'done' and final['report']['tasks'][0]['due'] == '周一'
    assert final['report_id'] != editor['base_report_id']
    assert client.post(url, json=body).status_code == 200 and len(seen) == 1
    assert client.post('/v1/work-meetings/' + meeting_id + '/retry-summary').status_code == 409
    tasks = runtime.store.list('action_tasks')
    assert len(tasks) == 2 and any(t['id'] == manual['id'] for t in tasks)
    assert any(t['owner'] == '小王' and t['due'] == '周一' for t in tasks)
    assert client.get('/v1/export').json()['work_meetings'][0]['report']['revision_id'] == body['request_id']


def test_a_late_revision_cannot_restore_a_cleared_meeting(client, runtime, monkeypatch):
    meeting_id = seed(runtime.store)
    editor = client.get('/v1/work-meetings/' + meeting_id + '/revision').json()
    async def late(self, prior, doc, revision_id, rows):
        runtime.store.clear()
        return {**prior, 'extraction': 'llm'}
    monkeypatch.setattr(MeetingRevisions, 'generate', late)
    assert client.post('/v1/work-meetings/' + meeting_id + '/revise-summary', json={'request_id': uuid4().hex, **editor}).status_code == 200
    assert runtime.store.work_meeting(meeting_id) is None and not runtime.store.list('action_tasks')
