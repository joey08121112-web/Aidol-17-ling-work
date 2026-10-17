"""Per-recording work meetings, offline ASR/models and real account SQLite stores."""
from dataclasses import replace
import json
from uuid import uuid4

import httpx
import pytest

from app.providers.volc_speech import AsrResult
from app.domain import Mode
from app.services.work_meetings import WorkMeetingMinutes, _batches


@pytest.fixture
def work(client, runtime, config, monkeypatch):
    config.speech = replace(config.speech, asr_api_key='offline-only')
    settings = runtime.store.settings()
    settings.mode, settings.retain_transcripts = Mode.work, False
    runtime.store.save_settings(settings)
    words = ['我们决定采用蓝色方案。小林负责周五交设计稿。']

    async def transcribe(chunks):
        async for _ in chunks:
            pass
        yield AsrResult(words[0], final=True)

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
async def test_long_meeting_covers_beginning_middle_and_end_with_hierarchical_synthesis(config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'offline-model'
    rows = [source(i, f'议题{i}。' + '讨论内容。' * 100) for i in range(100)]
    progress, seen, merges = [], [], []
    async def analyze(self, content, evidence):
        if 'transcripts' in content:
            seen.extend(content['transcripts'])
        else:
            merges.append(content)
        return {'title': '完整会议', 'summary': '讨论要点', 'decisions': [], 'tasks': [], 'topics': [], 'open_questions': []}
    monkeypatch.setattr(WorkMeetingMinutes, '_analyze', analyze)
    result = await WorkMeetingMinutes(config).generate(rows, lambda *item: progress.append(item))
    assert [row['text'] for row in seen] == [row['text'] for row in rows]
    assert result['source_ids'] == [row['id'] for row in rows]
    assert result['extraction'] == 'llm' and merges
    assert progress[-1][0] == 'synthesizing'
    long_row = source(0, '开头' + '中间' * 15000 + '结尾')
    assert ''.join(row['text'] for batch in _batches([long_row]) for row in batch) == long_row['text']


@pytest.mark.asyncio
async def test_minutes_model_payload_and_unsupported_citations_fall_back_to_visible_raw(config, monkeypatch):
    config.text_key, config.text_model = 'offline-key', 'offline-model'
    rows = [source(0, '小林负责周五提交设计稿。下一次是否采用红色还没确定。')]
    output = {'title': '设计讨论', 'summary': '周五提交设计稿，颜色待定。', 'decisions': [],
              'tasks': [{'content': '提交设计稿', 'owner': '小林', 'due': '周五', 'source_ids': ['0']}],
              'topics': [], 'open_questions': [{'text': '是否用红色', 'source_ids': ['0']}]}
    payloads = []
    class FakeClient:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *_): pass
        async def post(self, url, **kwargs):
            payloads.append(kwargs['json'])
            return httpx.Response(200, json={'choices': [{'message': {'content': json.dumps(output, ensure_ascii=False)}}]},
                                  request=httpx.Request('POST', url))
    monkeypatch.setattr('app.services.work_meetings.httpx.AsyncClient', FakeClient)
    result = await WorkMeetingMinutes(config).generate(rows)
    assert result['extraction'] == 'llm'
    assert result['open_questions'][0]['source_ids'] == ['0']
    assert payloads[0]['response_format'] == {'type': 'json_object'}
    assert '后来的更正或撤销优先' in payloads[0]['messages'][0]['content']
    output['tasks'][0]['owner'] = '不存在的人'
    fallback = await WorkMeetingMinutes(config).generate(rows)
    assert fallback['extraction'] == 'rules_fallback'
    assert '原文' in fallback['notice'] and fallback['source_ids'] == ['0']
    output['tasks'][0]['owner'] = '小林'
    output['open_questions'][0]['source_ids'] = ['other-meeting']
    assert (await WorkMeetingMinutes(config).generate(rows))['extraction'] == 'rules_fallback'


@pytest.fixture
def timed(work, monkeypatch):
    """ASR that reports two timed sentences per segment, like Volc show_utterances."""
    client, runtime, words, calls = work

    async def transcribe(chunks):
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
    assert [(u['start_ms'], u['end_ms'], u['text']) for u in detail['utterances']] == [
        (0, 100, words[0]), (20000, 20100, words[0])]
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
