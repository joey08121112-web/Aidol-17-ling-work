"""Per-recording work meetings, offline ASR/models and real account SQLite stores."""
from dataclasses import replace
import json
from uuid import uuid4

import httpx
import pytest

from app.providers.volc_speech import AsrResult
from app.domain import Mode
from app.services.work_meetings import WorkMeetingMinutes, _validate_minutes, attach_times
from app.storage import _split_sentences


@pytest.fixture
def work(client, runtime, config, monkeypatch):
    config.speech = replace(config.speech, asr_api_key='offline-only')
    settings = runtime.store.settings()
    settings.mode, settings.retain_transcripts = Mode.work, False
    runtime.store.save_settings(settings)
    words = ['我们决定采用蓝色方案。小林负责周五交设计稿。']

    async def transcribe(chunks, **_):
        async for _ in chunks:
            pass
        yield AsrResult(words[0], final=True)

    monkeypatch.setattr(client.app.state.work_asr, 'stream_asr', transcribe)
    monkeypatch.setattr(client.app.state.speech, 'stream_asr', transcribe)
    calls = []

    async def generate(self, rows, progress):
        calls.append(rows)
        progress('analyzing', 1, 1)
        ids = [row['id'] for row in rows]
        return {'title': '设计稿讨论', 'summary': '采用蓝色方案，周五交设计稿。', 'topics': [],
                'decisions': [{'text': '采用蓝色方案', 'source_ids': ids}],
                'tasks': [{'content': '交设计稿', 'owner': '小林', 'due': '周五', 'source_ids': ids}],
                'open_questions': [], 'source_ids': ids, 'extraction': 'llm'}

    monkeypatch.setattr(WorkMeetingMinutes, 'generate', generate)
    return client, runtime, words, calls


def start(client):
    meeting_id = uuid4().hex
    response = client.post('/v1/work-meetings', json={'meeting_id': meeting_id})
    assert response.status_code == 200, response.text
    return meeting_id


def test_start_snapshot_survives_retries_and_summary(work, config, monkeypatch):
    from datetime import datetime, timezone
    client, runtime, words, calls = work
    config.amap_key = 'offline-only'
    async def reverse(self, location):
        return {'available': True, 'address': '测试会议地点'}
    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    meeting_id = uuid4().hex
    started_at = datetime.now(timezone.utc).isoformat()
    body = {'meeting_id': meeting_id, 'started_at': started_at,
            'location': {'longitude': 116.4, 'latitude': 39.9, 'accuracy': 30}}
    assert client.post('/v1/work-meetings', json=body).status_code == 200
    first = client.get('/v1/work-meetings/' + meeting_id).json()
    assert first['location']['address'] == '测试会议地点'
    again = client.post('/v1/work-meetings', json={'meeting_id': meeting_id}).json()
    assert again['started_at'] == started_at and again['location'] == first['location']
    assert upload(client, meeting_id).status_code == 200
    finish(client, meeting_id)
    meeting = client.get('/v1/work-meetings/' + meeting_id).json()
    assert meeting['report']['started_at'] == started_at
    assert meeting['report']['location'] == first['location']
    assert client.post('/v1/work-meetings', json={'meeting_id': uuid4().hex,
        'started_at': '2026-01-01T00:00:00'}).status_code == 422


def test_speaker_evidence_is_separate_from_original(work, monkeypatch):
    client, runtime, words, calls = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    async def asr(chunks):
        yield AsrResult('原始文本', final=True, utterances=({'text': '原始文本', 'speaker_id': 2},))
    monkeypatch.setattr(client.app.state.work_asr, 'stream_asr', asr)
    meeting_id = start(client)
    assert upload(client, meeting_id).status_code == 200
    source = client.get('/v1/work-meetings/' + meeting_id).json()['sources'][0]
    assert source['text'] == '原始文本' and source['utterances'][0]['speaker_id'] == 2
    memory = runtime.store.add('memories', content='测试记录', kind='fact', source='transcript:' + source['id'])
    runtime.store.forget_memory(memory['id'])
    with runtime.store.connect() as db:
        assert db.execute('SELECT utterances FROM work_meeting_segments WHERE meeting_id=?',
                          (meeting_id,)).fetchone()['utterances'] == '[]'


def upload(client, meeting_id, index=0, request_id=None):
    return client.post('/v1/voice', params={'request_id': request_id or uuid4().hex,
        'meeting_id': meeting_id, 'segment_index': index}, content=b'\0' * 3200,
        headers={'Content-Type': 'audio/pcm'})


def finish(client, meeting_id, count=1, complete=True):
    response = client.post('/v1/work-meetings/' + meeting_id + '/finish',
                           json={'segment_count': count, 'complete': complete})
    assert response.status_code == 200, response.text
    return response.json()


def test_each_recording_has_its_own_raw_text_minutes_and_progress(work):
    client, runtime, words, calls = work
    first = start(client)
    assert client.post('/v1/work-meetings', json={'meeting_id': first}).json()['id'] == first
    request_id = uuid4().hex
    saved = upload(client, first, request_id=request_id)
    assert saved.status_code == 200, saved.text
    assert upload(client, first, request_id=request_id).json() == saved.json()
    # Even with global chat retention disabled, deliberate meeting evidence is saved.
    assert len(runtime.store.list('transcripts')) == 1
    assert runtime.store.list('memories') == []  # Ambient meeting quotations are not personal facts.
    queued = finish(client, first)
    assert queued['summary_status'] == 'pending'
    assert queued['sources'][0]['text'] == words[0]
    first_detail = client.get('/v1/work-meetings/' + first).json()
    assert first_detail['summary_status'] == 'ready'
    assert first_detail['report']['title'] == '设计稿讨论'
    assert first_detail['processed_sections'] == first_detail['total_sections'] == 1
    assert first_detail['sources'][0]['id'] == saved.json()['transcript_id']
    assert first_detail['sources'][0]['speaker'] == '现场发言（未分离声纹）'
    assert finish(client, first)['report'] == first_detail['report']
    assert len(calls) == 1
    assert upload(client, first, 1).status_code == 409

    words[0] = '第二次会议讨论预算，和设计稿无关。'
    second = start(client)
    assert upload(client, second).status_code == 200
    finish(client, second)
    second_detail = client.get('/v1/work-meetings/' + second).json()
    assert [row['text'] for row in second_detail['sources']] == [words[0]]
    assert not set(second_detail['report']['source_ids']) & set(first_detail['report']['source_ids'])
    meetings = client.get('/v1/state').json()['work_meetings']
    assert [meeting['id'] for meeting in meetings] == [second, first]
    assert all(meeting['summary_status'] == 'ready' for meeting in meetings)


def test_missing_tail_and_empty_asr_never_claim_full_success(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    assert upload(client, meeting_id).status_code == 200
    partial = finish(client, meeting_id, 2)
    assert partial['status'] == 'partial'
    assert partial['sources'] and partial['error']
    assert client.get('/v1/work-meetings/' + meeting_id).json()['report']['capture_status'] == 'partial'
    assert client.post('/v1/work-meetings/' + meeting_id + '/finish',
                       json={'segment_count': 1}).status_code == 409
    words[0] = ''
    empty = start(client)
    assert upload(client, empty).json()['transcript_id'] is None
    assert finish(client, empty)['status'] == 'empty'
    assert len(calls) == 1
    assert client.post('/v1/work-meetings/' + empty + '/retry-summary').status_code == 409


def test_segment_order_conflicts_and_authentication(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    assert upload(client, meeting_id, 1).status_code == 200
    words[0] = '较早的第一段'
    assert upload(client, meeting_id, 0).status_code == 200
    assert upload(client, meeting_id, 0).status_code == 409
    assert finish(client, meeting_id, 2)['status'] == 'saved'
    detail = client.get('/v1/work-meetings/' + meeting_id).json()
    assert [row['segment_index'] for row in detail['sources']] == [0, 1]
    assert detail['sources'][0]['text'] == '较早的第一段'
    other = client.post('/auth/register', json={'username': 'other', 'password': 'test'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    assert client.get('/v1/work-meetings', headers=headers).json() == {'meetings': []}
    assert client.get('/v1/work-meetings/' + meeting_id, headers=headers).status_code == 404
    assert client.post('/v1/work-meetings/' + meeting_id + '/finish', headers=headers,
                       json={'segment_count': 2}).status_code == 404
    for method, suffix, body in [('GET', '', None), ('GET', '/' + meeting_id, None),
            ('POST', '', {'meeting_id': meeting_id}), ('POST', '/' + meeting_id + '/finish', {'segment_count': 1}),
            ('POST', '/' + meeting_id + '/retry-summary', {})]:
        assert client.request(method, '/v1/work-meetings' + suffix, headers={'Authorization': ''}, json=body).status_code == 401


def test_summary_retry_does_not_upload_audio_or_duplicate_tasks(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    upload(client, meeting_id)
    finish(client, meeting_id)
    task = runtime.store.list('action_tasks')[0]
    runtime.store.action_task_status(task['id'], 'done')
    assert client.post('/v1/work-meetings/' + meeting_id + '/retry-summary').status_code == 200
    assert len(calls) == 2
    tasks = runtime.store.list('action_tasks')
    assert len(tasks) == 1 and tasks[0]['status'] == 'done'
    assert len(runtime.store.list('transcripts')) == 1
    assert len(runtime.store.all_meeting_reports()) == 1


def test_restart_pending_job_resumes_and_forgotten_sources_are_not_republished(work, monkeypatch):
    client, runtime, words, calls = work
    meeting_id = start(client)
    upload(client, meeting_id)
    runtime.store.finish_work_meeting(meeting_id, 1, True)  # Simulate process exit before scheduling.
    assert runtime.store.work_meeting(meeting_id)['summary_status'] == 'pending'
    client.get('/v1/work-meetings')
    assert runtime.store.work_meeting(meeting_id)['summary_status'] == 'ready'
    source = runtime.store.work_meeting(meeting_id)['sources'][0]
    memory = runtime.store.remember_transcript(source['id'])

    async def withdrawn(self, rows, progress):
        runtime.store.forget_memory(memory['id'])
        return {'title': 'stale', 'summary': 'stale', 'source_ids': [source['id']], 'extraction': 'llm'}

    monkeypatch.setattr(WorkMeetingMinutes, 'generate', withdrawn)
    client.post('/v1/work-meetings/' + meeting_id + '/retry-summary')
    detail = client.get('/v1/work-meetings/' + meeting_id).json()
    assert detail['sources'] == [] and detail['report'] is None
    assert detail['summary_status'] == 'failed'
    assert runtime.store.list('action_tasks') == []


def test_progress_reads_are_available_during_voice_processing(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    # Simulate the account lock held by an ASR turn; SQLite read-only progress must remain available.
    client.portal.call(runtime.lock.acquire)
    try:
        assert client.get('/v1/work-meetings').status_code == 200
        assert client.get('/v1/work-meetings/' + meeting_id).status_code == 200
    finally:
        client.portal.call(runtime.lock.release)


def source(index, text):
    return {'id': str(index), 'text': text, 'mode': 'work', 'speaker': '现场发言（未分离声纹）',
            'created_at': f'2026-10-17T08:{index % 60:02}:00+00:00'}


@pytest.mark.asyncio
async def test_long_meeting_is_one_complete_document_and_one_model_request(config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'offline-model'
    rows = [source(i, f'议题{i}。' + '讨论内容。' * 100) for i in range(100)]
    rows.append(source(100, '开头' + '中间' * 15000 + '结尾'))
    progress, requests = [], []
    async def analyze(self, content, evidence):
        requests.append(content)
        assert evidence == rows
        return {'title': '完整会议', 'summary': '讨论要点', 'decisions': [], 'tasks': [], 'topics': [],
                'open_questions': [], 'viewpoints': [], 'conflicts': [], 'events': []}
    monkeypatch.setattr(WorkMeetingMinutes, '_analyze', analyze)
    result = await WorkMeetingMinutes(config).generate(rows, lambda *item: progress.append(item))
    assert len(requests) == 1
    assert requests[0]['document'] == '\n\n'.join('【原文依据 ' + row['id'] + '】\n' + row['text'] for row in rows)
    assert result['source_ids'] == [row['id'] for row in rows]
    assert result['extraction'] == 'llm'
    assert progress[-1][0] == 'synthesizing'


@pytest.mark.asyncio
async def test_minutes_model_payload_and_unsupported_citations_fall_back_to_visible_raw(config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'offline-model'
    rows = [source(0, '小林负责周五提交设计稿，在北京开评审会。小林认为蓝色更好。下一次是否采用红色还没确定。')]
    output = {'title': '设计讨论', 'summary': '周五提交设计稿，颜色待定。', 'decisions': [],
              'tasks': [{'content': '提交设计稿', 'owner': '小林', 'due': '周五', 'source_ids': ['0']}],
              'topics': [], 'open_questions': [{'text': '是否用红色', 'source_ids': ['0']}],
              'chapters': [{'title': '设计稿安排', 'summary': '周五交稿', 'source_ids': ['0']},
                           {'title': '超长' * 20, 'summary': '', 'source_ids': ['0']},          # Dropped: title too long.
                           {'title': '别的会', 'summary': '', 'source_ids': ['other-meeting']}],  # Dropped: unknown id.
              'highlights': [{'text': '小林负责周五提交设计稿', 'source_ids': ['0']},
                             {'text': '小林保证周五一定完成', 'source_ids': ['0']}],
              'viewpoints': [{'person': '小林', 'text': '认为蓝色更好', 'source_ids': ['0']}],
              'conflicts': [], 'events': [{'content': '计划开评审会', 'time': '周五', 'location': '北京',
                                         'task': '提交设计稿', 'source_ids': ['0']}]}
    payloads = []
    finish_reason = 'stop'
    class FakeClient:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *_): pass
        async def post(self, url, **kwargs):
            payloads.append(kwargs['json'])
            return httpx.Response(200, json={'choices': [{'finish_reason': finish_reason, 'message': {'content': json.dumps(output, ensure_ascii=False)}}]},
                                  request=httpx.Request('POST', url))
    monkeypatch.setattr('app.services.work_meetings.httpx.AsyncClient', FakeClient)
    result = await WorkMeetingMinutes(config).generate(rows)
    assert result['extraction'] == 'llm'
    assert result['open_questions'][0]['source_ids'] == ['0']
    assert [c['title'] for c in result['chapters']] == ['设计稿安排']
    assert [h['text'] for h in result['highlights']] == ['小林负责周五提交设计稿']
    assert '逐字摘录' in payloads[0]['messages'][0]['content']
    assert payloads[0]['response_format'] == {'type': 'json_object'}
    assert result['viewpoints'][0]['person'] == '小林'
    assert result['events'][0]['location'] == '北京'
    output['title'] = '标题：新版产品设计方案评审及后续任务安排'
    renamed = await WorkMeetingMinutes(config).generate(rows)
    assert renamed['extraction'] == 'llm'
    assert renamed['title'] == '新版产品设计方案评审及后'
    assert len(renamed['title']) == 12
    finish_reason = 'length'
    assert (await WorkMeetingMinutes(config).generate(rows))['extraction'] == 'rules_fallback'
    finish_reason = 'stop'
    assert '后来的更正或撤销优先' in payloads[0]['messages'][0]['content']
    output['tasks'][0]['owner'] = '不存在的人'
    repaired = await WorkMeetingMinutes(config).generate(rows)
    assert repaired['extraction'] == 'llm' and repaired['tasks'][0]['owner'] == ''
    assert repaired['metadata_omissions'] == ['task_owner']
    assert '原文' in repaired['notice'] and repaired['source_ids'] == ['0']
    output['tasks'][0]['owner'] = '小林'
    output['open_questions'][0]['source_ids'] = ['other-meeting']
    assert (await WorkMeetingMinutes(config).generate(rows))['extraction'] == 'rules_fallback'


@pytest.fixture
def timed(work, monkeypatch):
    """ASR that reports two timed sentences per segment, like Volc show_utterances."""
    client, runtime, words, calls = work

    async def transcribe(chunks, **_):
        async for _ in chunks:
            pass
        yield AsrResult('先说预算。再定排期。', final=True, utterances=(
            {'text': '先说预算。', 'start_time': 200, 'end_time': 900},
            {'text': '再定排期。', 'start_time': 1100, 'end_time': 99999},  # Clamped to the segment.
            {'text': '', 'start_time': 0, 'end_time': 10}))

    monkeypatch.setattr(client.app.state.speech, 'stream_asr', transcribe)
    return client, runtime, calls


def pcm_upload(client, meeting_id, index, pcm, offset_ms=None, request_id=None):
    params = {'request_id': request_id or uuid4().hex, 'meeting_id': meeting_id, 'segment_index': index}
    if offset_ms is not None:
        params['offset_ms'] = offset_ms
    return client.post('/v1/voice', params=params, content=pcm, headers={'Content-Type': 'audio/pcm'})


def test_sentences_carry_meeting_timeline_times_and_minutes_cite_them(timed):
    client, runtime, calls = timed
    meeting_id = start(client)
    # A pause flushed a 2 s segment, so the next segment starts at 2000 ms, not 20000 ms.
    assert pcm_upload(client, meeting_id, 0, b'\1\0' * 32000, offset_ms=0).status_code == 200
    assert pcm_upload(client, meeting_id, 1, b'\2\0' * 32000, offset_ms=2000).status_code == 200
    detail = finish(client, meeting_id, 2)
    assert [(u['start_ms'], u['end_ms'], u['text']) for u in detail['utterances']] == [
        (200, 900, '先说预算。'), (1100, 2000, '再定排期。'), (2200, 2900, '先说预算。'), (3100, 4000, '再定排期。')]
    first = detail['sources'][0]
    assert detail['utterances'][0]['id'] == first['id'] + '.0'
    assert [s['offset_ms'] for s in detail['sources']] == [0, 2000]
    # The model receives one row per sentence; its citations are sentence ids.
    assert [row['id'] for row in calls[0]] == [u['id'] for u in detail['utterances']]
    report = client.get('/v1/work-meetings/' + meeting_id).json()['report']
    assert report['decisions'][0]['source_ids'][0] == first['id'] + '.0'
    assert client.get('/v1/work-meetings').json()['meetings'][0]['duration_ms'] == 4000


def test_old_clients_without_offsets_and_untimed_asr_still_get_times(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    upload(client, meeting_id, 0)
    upload(client, meeting_id, 1)
    detail = finish(client, meeting_id, 2)
    # Untimed text is split at sentence ends with times estimated by character share.
    assert [(u['start_ms'], u['end_ms'], u['text']) for u in detail['utterances']] == [
        (0, 50, '我们决定采用蓝色方案。'), (50, 100, '小林负责周五交设计稿。'),
        (20000, 20050, '我们决定采用蓝色方案。'), (20050, 20100, '小林负责周五交设计稿。')]
    assert client.post('/v1/voice', params={'request_id': uuid4().hex, 'offset_ms': 0},
                       content=b'\0' * 3200, headers={'Content-Type': 'audio/pcm'}).status_code == 422


def test_audio_is_kept_per_account_assembled_by_offset_and_deletable(timed):
    client, runtime, calls = timed
    meeting_id = start(client)
    pcm_upload(client, meeting_id, 0, b'\1\0' * 16000, offset_ms=0)       # 1000 ms
    pcm_upload(client, meeting_id, 1, b'\2\0' * 16000, offset_ms=1000)    # 1000 ms
    detail = finish(client, meeting_id, 2)
    assert detail['audio']['status'] == 'saved' and detail['audio']['bytes'] == 64000
    assert detail['audio']['retention_days'] == 30 and detail['audio']['expires_at']
    folder = runtime.store.audio_root / meeting_id
    assert sorted(p.name for p in folder.iterdir()) == ['000000.pcm', '000001.pcm']
    clip = client.get('/v1/work-meetings/' + meeting_id + '/audio', params={'start_ms': 900, 'duration_ms': 200})
    assert clip.status_code == 200 and clip.headers['content-type'] == 'audio/wav'
    body = clip.content[44:]
    assert body == b'\1\0' * 1600 + b'\2\0' * 1600  # 100 ms from each segment.
    whole = client.get('/v1/work-meetings/' + meeting_id + '/audio').content[44:]
    assert len(whole) == 64000  # Clipped to the saved audio, not the 10-minute window.

    other = client.post('/auth/register', json={'username': 'other2', 'password': 'test'}).json()
    headers = {'Authorization': 'Bearer ' + other['token']}
    assert client.get('/v1/work-meetings/' + meeting_id + '/audio', headers=headers).status_code == 404
    assert client.delete('/v1/work-meetings/' + meeting_id + '/audio', headers=headers).status_code == 404

    deleted = client.delete('/v1/work-meetings/' + meeting_id + '/audio')
    assert deleted.status_code == 200 and deleted.json()['audio']['status'] == 'deleted'
    assert not folder.exists()
    assert deleted.json()['utterances'], 'text survives audio deletion'
    assert client.get('/v1/work-meetings/' + meeting_id + '/audio').status_code == 404


def test_retention_zero_stores_nothing_and_expiry_purges_files(timed):
    client, runtime, calls = timed
    settings = client.get('/v1/state').json()['settings']
    kept = start(client)
    pcm_upload(client, kept, 0, b'\1\0' * 16000, offset_ms=0)
    finish(client, kept, 1)
    with runtime.store.connect() as db:  # Ended long ago.
        db.execute("UPDATE work_meetings SET ended_at='2026-01-01T00:00:00+00:00' WHERE id=?", (kept,))
    assert client.get('/v1/work-meetings/' + kept).json()['audio']['status'] == 'expired'
    assert not (runtime.store.audio_root / kept).exists()

    settings['work_audio_retention_days'] = 0
    assert client.put('/v1/settings', json=settings).status_code == 200
    none = start(client)
    pcm_upload(client, none, 0, b'\1\0' * 16000, offset_ms=0)
    assert finish(client, none, 1)['audio']['status'] == 'none'
    assert not (runtime.store.audio_root / none).exists()
    settings['work_audio_retention_days'] = 366
    assert client.put('/v1/settings', json=settings).status_code == 422


def test_clearing_data_removes_meeting_audio(timed):
    client, runtime, calls = timed
    meeting_id = start(client)
    pcm_upload(client, meeting_id, 0, b'\1\0' * 16000, offset_ms=0)
    assert (runtime.store.audio_root / meeting_id).exists()
    assert client.request('DELETE', '/v1/data', json={'password': 'test-password-123'}).status_code == 200
    assert not runtime.store.audio_root.exists()


def test_pause_resume_and_marks_are_idempotent_and_only_while_recording(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    url = '/v1/work-meetings/' + meeting_id + '/events'
    pause = {'event_id': uuid4().hex, 'kind': 'pause', 'offset_ms': 5000}
    assert client.post(url, json=pause).status_code == 200
    assert client.post(url, json=pause).json()['kind'] == 'pause'  # Retry of the same event.
    assert client.post(url, json={**pause, 'event_id': uuid4().hex}).status_code == 409  # Already paused.
    assert client.post(url, json={**pause, 'offset_ms': 1}).status_code == 409  # Reused id, other content.
    mark = {'event_id': uuid4().hex, 'kind': 'mark', 'offset_ms': 5000, 'origin': 'headset'}
    assert client.post(url, json=mark).status_code == 200
    assert client.post(url, json={'event_id': uuid4().hex, 'kind': 'resume', 'offset_ms': 5000}).status_code == 200
    assert client.post(url, json={'event_id': uuid4().hex, 'kind': 'resume', 'offset_ms': 5000}).status_code == 409
    assert client.post(url, json={'event_id': uuid4().hex, 'kind': 'note', 'offset_ms': 1}).status_code == 422
    upload(client, meeting_id)
    detail = finish(client, meeting_id)
    assert [e['kind'] for e in detail['events']] == ['pause', 'mark', 'resume']
    assert detail['events'][1]['origin'] == 'headset'
    assert client.post(url, json={**mark, 'event_id': uuid4().hex}).status_code == 409
    assert client.post('/v1/work-meetings/' + uuid4().hex + '/events', json=mark).status_code == 404
    # Marks do not wait for the account lock held by an in-flight segment ASR.
    second = start(client)
    client.portal.call(runtime.lock.acquire)
    try:
        assert client.post('/v1/work-meetings/' + second + '/events',
                           json={**mark, 'event_id': uuid4().hex}).status_code == 200
    finally:
        client.portal.call(runtime.lock.release)


def test_sentence_split_uses_word_times_or_estimates_and_keeps_tiny_fillers():
    words = [{'text': ch, 'start_time': i * 100, 'end_time': i * 100 + 90} for i, ch in enumerate('先说预算再定排期')]
    assert _split_sentences('先说预算。再定排期。', 0, 800, words) == [(0, 390, '先说预算。'), (400, 790, '再定排期。')]
    # Words that do not match the text fall back to character-share estimates.
    assert _split_sentences('先说预算。再定排期。', 0, 800, words[:3]) == [(0, 400, '先说预算。'), (400, 800, '再定排期。')]
    assert _split_sentences('好的。嗯。下一步', 0, 600, None) == [(0, 300, '好的。嗯。'), (300, 600, '下一步')]
    assert _split_sentences('没有句号的一句话', 5, 9, None) == [(5, 9, '没有句号的一句话')]


def test_chapter_and_highlight_times_come_from_cited_sentences():
    meeting = {'sources': [{'id': 'old', 'offset_ms': 20000}],
               'utterances': [{'id': 't.0', 'start_ms': 300}, {'id': 't.1', 'start_ms': 9000}]}
    report = attach_times({'chapters': [{'title': '后', 'source_ids': ['t.1']}, {'title': '前', 'source_ids': ['t.1', 't.0']},
                                        {'title': '旧', 'source_ids': ['old']}, {'title': '无', 'source_ids': ['gone']}],
                           'highlights': [{'text': 'x', 'source_ids': ['t.1']}]}, meeting)
    assert [(c['title'], c['start_ms']) for c in report['chapters']] == [('前', 300), ('后', 9000), ('旧', 20000), ('无', None)]
    assert report['highlights'][0]['start_ms'] == 9000


def u(text, start, end, definite):
    return {'text': text, 'start_time': start, 'end_time': end, 'definite': definite}


@pytest.fixture
def live(work, monkeypatch):
    """Scripted streaming ASR: each upstream run pops a plan of results keyed by chunk count / 'end'."""
    client, runtime, words, calls = work
    plans, runs = [], []

    async def stream(chunks, **options):
        plan = plans.pop(0)
        runs.append(0)
        async for _ in chunks:
            runs[-1] += 1
            for result in plan.get(runs[-1], []):
                yield result
        for result in plan.get('end', []):
            yield result

    monkeypatch.setattr(client.app.state.speech, 'stream_asr', stream)
    return client, runtime, plans, runs, calls


def receive_until(ws, kind):
    seen = []
    while True:
        message = ws.receive_json()
        seen.append(message)
        if message['type'] == kind:
            return seen


def test_live_captions_stream_partials_and_save_final_sentences_on_the_timeline(live):
    client, runtime, plans, runs, calls = live
    meeting_id = start(client)
    plans.append({1: [AsrResult('先说', utterances=(u('先说', 0, 60, False),))],
                  2: [AsrResult('先说预算。再定排期。下一', utterances=(u('先说预算。再定排期。', 0, 180, True), u('下一', 190, 200, False)))],
                  'end': [AsrResult('', final=True, utterances=(u('先说预算。再定排期。', 0, 180, True), u('下一步。', 190, 200, False)))]})
    plans.append({1: [AsrResult('继续。', utterances=(u('继续。', 10, 90, True),))], 'end': [AsrResult('', final=True)]})
    headers = {'Authorization': client.headers['Authorization']}
    with client.websocket_connect(f'/v1/work-meetings/{meeting_id}/live?offset_ms=0', headers=headers) as ws:
        assert ws.receive_json() == {'type': 'ready', 'offset_ms': 0}
        ws.send_bytes(b'\0' * 3200)
        ws.send_bytes(b'\0' * 3200)
        ws.send_text(json.dumps({'type': 'pause'}))
        seen = receive_until(ws, 'paused')
        assert seen[0] == {'type': 'partial', 'text': '先说', 'start_ms': 0}
        finals = [s for m in seen if m['type'] == 'final' for s in m['sentences']]
        assert [(s['text'], s['start_ms'], s['end_ms']) for s in finals] == [
            ('先说预算。', 0, 90), ('再定排期。', 90, 180), ('下一步。', 190, 200)]  # Last one flushed by the pause.
        assert seen[-1] == {'type': 'paused', 'offset_ms': 200}
        ws.send_text(json.dumps({'type': 'resume', 'offset_ms': 5000}))  # Recorded time jumps only by recorded PCM.
        ws.send_bytes(b'\0' * 3200)
        ws.send_text(json.dumps({'type': 'end'}))
        seen = receive_until(ws, 'done')
        assert [s['start_ms'] for m in seen if m['type'] == 'final' for s in m['sentences']] == [5010]
        assert seen[-1] == {'type': 'done', 'offset_ms': 5100}
    assert runs == [2, 1]
    # The 20 s segment carries audio only: no second recognition, no request quota.
    response = client.post('/v1/voice', params={'request_id': uuid4().hex, 'meeting_id': meeting_id, 'segment_index': 0,
                                                'offset_ms': 0, 'recognize': 0},
                           content=b'\1\0' * 3200, headers={'Content-Type': 'audio/pcm'})
    assert response.status_code == 200 and response.json()['text'] == ''
    detail = finish(client, meeting_id)
    assert detail['status'] == 'saved' and detail['audio']['status'] == 'saved'
    assert [x['text'] for x in detail['utterances']] == ['先说预算。', '再定排期。', '下一步。', '继续。']
    assert len(detail['sources']) == 3 and detail['transcript_count'] == 3
    assert [row['id'] for row in calls[0]] == [x['id'] for x in detail['utterances']]  # Minutes cite live sentences.
    assert client.get('/v1/work-meetings').json()['meetings'][0]['transcript_count'] == 3


def test_segment_fallback_recognition_replaces_live_sentences_in_its_range(live, monkeypatch):
    client, runtime, plans, runs, calls = live
    meeting_id = start(client)
    runtime.store.save_live_sentence(meeting_id, '只听到一半', 1000, 1500)
    runtime.store.save_live_sentence(meeting_id, '下一段的话', 21000, 21500)

    async def segment_asr(chunks, **_):
        async for _ in chunks:
            pass
        yield AsrResult('完整的第一段。', final=True, utterances=(u('完整的第一段。', 0, 900, True),))

    monkeypatch.setattr(client.app.state.speech, 'stream_asr', segment_asr)
    # A 2 s segment [0, 2000) with server recognition (recognize defaults to 1).
    assert pcm_upload(client, meeting_id, 0, b'\0' * 64000, offset_ms=0).status_code == 200
    texts = [x['text'] for x in client.get('/v1/work-meetings/' + meeting_id).json()['utterances']]
    assert texts == ['完整的第一段。', '下一段的话']


def test_live_socket_rejects_bad_auth_ended_meetings_and_reports_provider_failure(live, monkeypatch):
    client, runtime, plans, runs, calls = live
    from starlette.websockets import WebSocketDisconnect
    from app.providers.volc_speech import SpeechProviderError
    meeting_id = start(client)
    with pytest.raises(WebSocketDisconnect) as denied:
        with client.websocket_connect(f'/v1/work-meetings/{meeting_id}/live', headers={'Authorization': 'Bearer nope'}):
            pass
    assert denied.value.code == 4401

    async def broken(chunks, **_):
        async for _ in chunks:
            raise SpeechProviderError('upstream gone')
        yield  # pragma: no cover

    monkeypatch.setattr(client.app.state.speech, 'stream_asr', broken)
    headers = {'Authorization': client.headers['Authorization']}
    with client.websocket_connect(f'/v1/work-meetings/{meeting_id}/live', headers=headers) as ws:
        ws.receive_json()
        ws.send_bytes(b'\0' * 3200)
        ws.send_bytes(b'\0' * 3200)
        message = ws.receive_json()
        assert message['type'] == 'error' and '整段识别' in message['message']
    finish(client, meeting_id, 0)
    with pytest.raises(WebSocketDisconnect) as ended:
        with client.websocket_connect(f'/v1/work-meetings/{meeting_id}/live', headers=headers):
            pass
    assert ended.value.code == 4409
    with pytest.raises(ValueError):
        runtime.store.save_live_sentence(meeting_id, '结束后的话', 0, 10)


def test_live_speakers_and_clock_times_to_the_second_skip_pauses(live):
    client, runtime, plans, runs, calls = live
    from app.storage import speaker_label, _wall_clock
    assert speaker_label({'additions': {'speaker_id': '1'}}) == '说话人2'
    assert speaker_label({'additions': {}}) == speaker_label({}) == ''
    meeting_id = start(client)
    first = dict(u('大家好。', 0, 900, True), additions={'speaker_id': '0'})
    second = dict(u('我建议推迟。', 1000, 1900, True), additions={'speaker_id': '1'})
    plans.append({1: [AsrResult('', utterances=(first,))], 2: [AsrResult('', utterances=(first, second))], 'end': [AsrResult('', final=True)]})
    headers = {'Authorization': client.headers['Authorization']}
    with client.websocket_connect(f'/v1/work-meetings/{meeting_id}/live', headers=headers) as ws:
        ws.receive_json()
        ws.send_bytes(b'\0' * 32000)
        ws.send_bytes(b'\0' * 32000)
        ws.send_text(json.dumps({'type': 'end'}))
        seen = receive_until(ws, 'done')
    assert [(s['speaker'], s['text']) for m in seen if m['type'] == 'final' for s in m['sentences']] == [
        ('说话人1', '大家好。'), ('说话人2', '我建议推迟。')]
    detail = client.get('/v1/work-meetings/' + meeting_id).json()
    assert detail['speakers'] == ['说话人1', '说话人2']
    assert [row['speaker'] for row in detail['sources']] == ['说话人1', '说话人2']  # The minutes see speaker labels.
    assert detail['audio_started_at'] and all(len(x['wall_at']) == 25 for x in detail['utterances'])  # ...T14:03:12+00:00
    # A 5-minute pause at 1000 ms pushes later sentences' clock times, not earlier ones.
    clock = _wall_clock('2026-10-17T06:00:00+00:00', [
        {'kind': 'pause', 'offset_ms': 1000, 'created_at': '2026-10-17T06:00:01+00:00'},
        {'kind': 'resume', 'offset_ms': 1000, 'created_at': '2026-10-17T06:05:01+00:00'}])
    assert clock(500) == '2026-10-17T06:00:00+00:00'
    assert clock(1000) == '2026-10-17T06:05:01+00:00'
    assert clock(62_000) == '2026-10-17T06:06:02+00:00'


def test_meeting_people_events_and_conflicts_require_evidence():
    rows = [source(0, '小林建议周五在北京评审。'), source(1, '小王不同意周五，认为周一更合适。')]
    report = {'title': '评审讨论', 'summary': '时间存在分歧', 'tasks': [], 'decisions': [],
              'topics': [], 'open_questions': [],
              'viewpoints': [{'person': '小林', 'text': '建议周五评审', 'source_ids': ['0']}],
              'conflicts': [{'text': '小林建议周五，小王建议周一；未解决。', 'source_ids': ['0', '1']}],
              'events': [{'content': '计划评审', 'time': '', 'location': '北京', 'task': '', 'source_ids': ['0']}]}
    assert _validate_minutes(report, rows)['events'][0]['time'] == ''
    report['viewpoints'][0]['person'] = '张三'
    with pytest.raises(ValueError):
        _validate_minutes(report, rows)
    report['viewpoints'][0]['person'] = ''
    report['events'][0]['location'] = '上海'
    with pytest.raises(ValueError):
        _validate_minutes(report, rows)
    report['events'][0]['location'] = '北京'
    report['conflicts'][0]['source_ids'] = ['other-meeting']
    with pytest.raises(ValueError):
        _validate_minutes(report, rows)


def test_whole_audio_waits_until_end_then_keeps_one_speaker_namespace(work, monkeypatch):
    import wave
    client, runtime, words, summaries = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    meeting_id = uuid4().hex
    started = client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    assert started.status_code == 200 and started.json()['whole_audio'] is True
    calls = []
    async def transcribe(wav, task_id, on_task):
        wav.seek(0)
        with wave.open(wav, 'rb') as audio:
            calls.append(audio.readframes(audio.getnframes()))
        on_task('offline-task')
        return [{'text': '我负责设计。', 'speaker': '发言人 1', 'speaker_id': 7, 'begin_time': 0, 'end_time': 1000},
                {'text': '我负责测试。', 'speaker': '发言人 2', 'speaker_id': 3, 'begin_time': 1000, 'end_time': 2000},
                {'text': '好的。', 'speaker': '发言人 1', 'speaker_id': 7, 'begin_time': 2000, 'end_time': 3000}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    for index in range(2):
        request_id = uuid4().hex
        params = {'upload_index': index, 'request_id': request_id}
        pcm = bytes([index]) * 640000  # The phone uploads 20 seconds per request.
        for _ in range(2):
            response = client.post('/v1/work-meetings/' + meeting_id + '/audio', params=params,
                                   content=pcm, headers={'Content-Type': 'audio/pcm'})
            assert response.status_code == 200
    assert calls == [] and summaries == []
    assert client.get('/v1/work-meetings/' + meeting_id).json()['sources'] == []
    finish(client, meeting_id, 2)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert calls == [bytes(640000) + bytes([1]) * 640000]
    assert result['transcription_status'] == 'ready' and result['summary_status'] == 'ready'
    assert [s['speaker'] for s in result['sources']] == ['说话人1', '说话人2', '说话人1']
    assert all(s['speaker_scope'] == 'meeting' for s in summaries[0])
    with runtime.store.connect() as db:
        assert db.execute('SELECT count(*) FROM work_audio_uploads').fetchone()[0] == 0
    assert client.get('/v1/work-meetings').json()['meetings'][0]['whole_audio'] is True


@pytest.mark.parametrize('size,status', [(1920000, 200), (1920002, 413)])
def test_whole_audio_upload_body_limit(work, size, status):
    client, runtime, _, _ = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    response = client.post('/v1/work-meetings/' + meeting_id + '/audio',
                           params={'upload_index': 0, 'request_id': uuid4().hex},
                           content=bytes(size), headers={'Content-Type': 'audio/pcm'})
    assert response.status_code == status
    with runtime.store.connect() as db:
        lengths = [row[0] for row in db.execute('SELECT audio_bytes FROM work_meeting_segments')]
    assert lengths == ([size] if status == 200 else [])
    assert client.post('/v1/work-meetings', content=bytes(65537)).status_code == 413


def test_whole_audio_failure_keeps_recording_for_explicit_retry(work, monkeypatch):
    from app.providers.qwen_meeting_asr import MeetingTaskFailed
    client, runtime, words, summaries = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    response = client.post('/v1/work-meetings/' + meeting_id + '/audio',
                           params={'upload_index': 0, 'request_id': uuid4().hex},
                           content=bytes(3200), headers={'Content-Type': 'audio/pcm'})
    assert response.status_code == 200
    attempts = []
    async def transcribe(wav, task_id, on_task):
        attempts.append(task_id)
        on_task('failed-task' if len(attempts) == 1 else 'success-task')
        if len(attempts) == 1:
            raise MeetingTaskFailed('offline failure')
        return [{'text': '会议内容', 'speaker': '发言人 1', 'speaker_id': 0, 'begin_time': 0, 'end_time': 100}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    finish(client, meeting_id)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert result['summary_status'] == 'failed' and result['asr_task_id'] == ''
    assert not summaries
    assert client.post('/v1/work-meetings/' + meeting_id + '/retry-summary').status_code == 200
    assert client.get('/v1/work-meetings/' + meeting_id).json()['summary_status'] == 'ready'
    assert attempts == ['', '']


def test_meeting_scoped_speakers_are_valid_for_viewpoints_and_first_person_tasks():
    rows = [{**source(0, '我负责设计。'), 'speaker': '发言人 1', 'speaker_scope': 'meeting'}]
    report = {'title': '设计分工', 'summary': '设计任务已分配', 'tasks': [
        {'content': '设计', 'owner': '发言人 1', 'due': '', 'source_ids': ['0']}],
        'viewpoints': [{'person': '发言人 1', 'text': '负责设计', 'source_ids': ['0']}],
        'events': [], 'conflicts': [], 'decisions': [], 'topics': [], 'open_questions': []}
    assert _validate_minutes(report, rows)['tasks'][0]['owner'] == '发言人 1'
    rows[0].pop('speaker_scope')
    with pytest.raises(ValueError):
        _validate_minutes(report, rows)


def test_live_recording_gets_whole_meeting_speakers_without_losing_audio_or_marks(work, monkeypatch):
    import wave
    client, runtime, _, summaries = work
    meeting_id = start(client)
    original = runtime.store.save_live_sentence(meeting_id, '实时字幕', 0, 100, speaker='说话人7')
    pcm = b'\1\0' * 1600
    assert client.post('/v1/voice', params={'meeting_id': meeting_id, 'segment_index': 0,
        'request_id': uuid4().hex, 'recognize': 0, 'offset_ms': 0}, content=pcm,
        headers={'Content-Type': 'audio/pcm'}).status_code == 200
    event = {'event_id': uuid4().hex, 'kind': 'mark', 'offset_ms': 50, 'origin': 'headset'}
    assert client.post('/v1/work-meetings/' + meeting_id + '/events', json=event).status_code == 200
    client.app.state.work_asr.config.api_key = 'offline-only'
    calls = []
    async def transcribe(wav, task_id, on_task):
        with wave.open(wav, 'rb') as audio:
            calls.append(audio.readframes(audio.getnframes()))
        return [{'text': '最终整场原文', 'speaker': '发言人 1', 'begin_time': 0, 'end_time': 100}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    finish(client, meeting_id)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert calls == [pcm]
    assert result['transcription_status'] == result['summary_status'] == 'ready'
    assert [(s['speaker'], s['text']) for s in result['sources']] == [('说话人1', '最终整场原文')]
    assert result['events'][0]['kind'] == 'mark'
    assert result['audio']['status'] == 'saved'
    assert client.get('/v1/work-meetings/' + meeting_id + '/audio').content[44:] == pcm
    assert summaries[0][0]['speaker_scope'] == 'meeting'
    assert summaries[0][0]['id'] != original[0]['id']
    assert len(runtime.store.list('transcripts')) == 1


def test_whole_audio_retention_zero_deletes_audio_after_transcription(work, monkeypatch):
    client, runtime, _, _ = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    settings = runtime.store.settings()
    settings.work_audio_retention_days = 0
    runtime.store.save_settings(settings)
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    assert client.post('/v1/work-meetings/' + meeting_id + '/audio', params={
        'upload_index': 0, 'request_id': uuid4().hex}, content=bytes(3200),
        headers={'Content-Type': 'audio/pcm'}).status_code == 200
    async def transcribe(wav, task_id, on_task):
        return [{'text': '只保留文字', 'speaker': '发言人 1', 'begin_time': 0, 'end_time': 100}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    finish(client, meeting_id)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert result['transcription_status'] == 'ready' and result['sources'][0]['text'] == '只保留文字'
    assert result['audio']['bytes'] == 0 and not (runtime.store.audio_root / meeting_id).exists()


def test_same_whole_audio_request_cannot_silently_replace_different_pcm(work):
    client, runtime, _, _ = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    params = {'upload_index': 0, 'request_id': uuid4().hex}
    endpoint = '/v1/work-meetings/' + meeting_id + '/audio'
    assert client.post(endpoint, params=params, content=bytes(3200), headers={'Content-Type': 'audio/pcm'}).status_code == 200
    assert client.post(endpoint, params=params, content=b'\1' * 3200, headers={'Content-Type': 'audio/pcm'}).status_code == 409
    assert runtime.store.work_audio(meeting_id, 0, 100) == bytes(3200)


def test_audio_window_after_last_saved_sample_returns_404(timed):
    client, runtime, _ = timed
    meeting_id = start(client)
    pcm_upload(client, meeting_id, 0, bytes(3200), offset_ms=0)
    assert client.get('/v1/work-meetings/' + meeting_id + '/audio?start_ms=1000').status_code == 404


def test_forgotten_live_sources_are_not_recreated_by_whole_transcription(work, monkeypatch):
    client, runtime, _, summaries = work
    meeting_id = start(client)
    assert upload(client, meeting_id).status_code == 200
    source_id = runtime.store.work_meeting(meeting_id)['sources'][0]['id']
    memory = runtime.store.remember_transcript(source_id)
    client.app.state.work_asr.config.api_key = 'offline-only'
    async def transcribe(wav, task_id, on_task):
        runtime.store.forget_memory(memory['id'])
        return [{'text': '不应恢复的内容', 'speaker': '发言人 1', 'begin_time': 0, 'end_time': 100}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    finish(client, meeting_id)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert result['sources'] == [] and result['summary_status'] == 'failed'
    assert summaries == []


def test_pending_sqlite_audio_from_previous_main_migrates_without_losing_bytes(work, monkeypatch):
    import hashlib
    import wave
    from app.storage import Store, now
    client, runtime, _, summaries = work
    client.app.state.work_asr.config.api_key = 'offline-only'
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    pcm = b'\1\0' * 1600
    request_id = uuid4().hex
    with runtime.store.connect() as db:
        db.execute('INSERT INTO work_audio_uploads VALUES(?,?,?,?,?,?)',
                   (meeting_id, 0, request_id, hashlib.sha256(pcm).hexdigest(), pcm, now()))
    Store(runtime.store.path)
    Store(runtime.store.path)  # The migration itself is idempotent.
    assert runtime.store.work_audio(meeting_id, 0, 100) == pcm
    async def transcribe(wav, task_id, on_task):
        with wave.open(wav, 'rb') as audio:
            assert audio.readframes(audio.getnframes()) == pcm
        return [{'text': '从旧录音恢复', 'speaker': '发言人 1', 'begin_time': 0, 'end_time': 100}]
    monkeypatch.setattr(client.app.state.meeting_asr, 'transcribe', transcribe)
    finish(client, meeting_id)
    result = client.get('/v1/work-meetings/' + meeting_id).json()
    assert result['status'] == 'saved' and result['summary_status'] == 'ready'
    assert result['sources'][0]['text'] == '从旧录音恢复'
    assert len(summaries) == 1


def test_call_boundaries_are_ordered_idempotent_and_independent_of_manual_pause(work):
    client, runtime, words, calls = work
    meeting_id = start(client)
    url = '/v1/work-meetings/' + meeting_id + '/events'
    def event(kind, offset=0):
        return {'event_id': uuid4().hex, 'kind': kind, 'offset_ms': offset, 'origin': 'headset'}
    assert client.post(url, json=event('call_end')).status_code == 409
    begin = event('call_start', 100)
    assert client.post(url, json=begin).status_code == 200
    assert client.post(url, json=begin).status_code == 200
    assert client.post(url, json=event('call_start', 100)).status_code == 409
    assert client.post(url, json=event('pause', 200)).status_code == 200
    assert client.post(url, json=event('call_end', 200)).status_code == 200
    assert client.post(url, json=event('resume', 200)).status_code == 200
    assert client.post(url, json=event('call_start', 300)).status_code == 200
    assert client.post(url, json=event('call_end', 400)).status_code == 200
    upload(client, meeting_id)
    detail = finish(client, meeting_id)
    assert [e['kind'] for e in detail['events']] == ['call_start', 'pause', 'call_end', 'resume', 'call_start', 'call_end']
    assert client.post(url, json=event('call_start', 500)).status_code == 409
    assert client.post('/v1/work-meetings/' + uuid4().hex + '/events', json=event('call_start')).status_code == 404


@pytest.mark.parametrize('cached', [False, True])
def test_observed_location_metadata_survives_address_summary_and_export(work, config, monkeypatch, cached):
    from datetime import datetime, timezone, timedelta
    client, runtime, _, _ = work
    config.amap_key = 'offline-only'
    async def reverse(self, location):
        return {'available': True, 'address': '测试会议地点'}
    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    observed = (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat()
    meeting_id = uuid4().hex
    point = {'longitude': 116.4, 'latitude': 39.9, 'accuracy': 30, 'source': 'gps',
             'cached': cached, 'observed_at': observed}
    response = client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'location': point})
    assert response.status_code == 200, response.text
    assert upload(client, meeting_id).status_code == 200
    finish(client, meeting_id)
    saved = client.get('/v1/work-meetings/' + meeting_id).json()
    for location in [saved['location'], saved['report']['location'],
                     client.get('/v1/state').json()['work_meetings'][0]['location'],
                     client.get('/v1/export').json()['work_meetings'][0]['location']]:
        assert location['cached'] is cached
        assert location['observed_at'] == observed
        assert location['address'] == '测试会议地点'
    assert runtime.store.work_meeting(meeting_id)['location'] == saved['location']


@pytest.mark.parametrize('extra', [
    {'cached': True}, {'cached': 'true', 'observed_at': '2026-10-18T01:00:00Z'},
    {'cached': True, 'observed_at': '2026-10-18T01:00:00'},
    {'cached': True, 'observed_at': '2099-01-01T01:00:00Z'},
])
def test_invalid_meeting_cache_metadata_is_rejected(work, extra):
    client, runtime, _, _ = work
    response = client.post('/v1/work-meetings', json={'meeting_id': uuid4().hex,
        'location': {'longitude': 116.4, 'latitude': 39.9, **extra}})
    assert response.status_code == 422
    assert not runtime.store.work_meetings()
