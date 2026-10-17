"""Per-recording work meetings, offline ASR/models and real account SQLite stores."""
from dataclasses import replace
import json
from uuid import uuid4

import httpx
import pytest

from app.providers.volc_speech import AsrResult
from app.domain import Mode
from app.services.work_meetings import WorkMeetingMinutes, _validate_minutes


@pytest.fixture
def work(client, runtime, config, monkeypatch):
    config.work_asr.api_key = 'offline-only'
    settings = runtime.store.settings()
    settings.mode, settings.retain_transcripts = Mode.work, False
    runtime.store.save_settings(settings)
    words = ['我们决定采用蓝色方案。小林负责周五交设计稿。']

    async def transcribe(chunks):
        async for _ in chunks:
            pass
        yield AsrResult(words[0], final=True)

    monkeypatch.setattr(client.app.state.work_asr, 'stream_asr', transcribe)
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


def test_start_snapshot_survives_retries_and_summary(work, monkeypatch):
    from datetime import datetime, timezone
    client, runtime, words, calls = work
    async def reverse(self, location):
        return {'available': True, 'address': '测试会议地点'}
    monkeypatch.setattr('app.services.maps.MapService.reverse', reverse)
    meeting_id = uuid4().hex
    started_at = datetime.now(timezone.utc).isoformat()
    body = {'meeting_id': meeting_id, 'started_at': started_at,
            'location': {'longitude': 116.4, 'latitude': 39.9, 'accuracy': 30}}
    first = client.post('/v1/work-meetings', json=body).json()
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
    fallback = await WorkMeetingMinutes(config).generate(rows)
    assert fallback['extraction'] == 'rules_fallback'
    assert '原文' in fallback['notice'] and fallback['source_ids'] == ['0']
    output['tasks'][0]['owner'] = '小林'
    output['open_questions'][0]['source_ids'] = ['other-meeting']
    assert (await WorkMeetingMinutes(config).generate(rows))['extraction'] == 'rules_fallback'


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
    assert [s['speaker'] for s in result['sources']] == ['发言人 1', '发言人 2', '发言人 1']
    assert all(s['speaker_scope'] == 'meeting' for s in summaries[0])
    with runtime.store.connect() as db:
        assert db.execute('SELECT count(*) FROM work_audio_uploads').fetchone()[0] == 0
    assert client.get('/v1/work-meetings').json()['meetings'][0]['whole_audio'] is True


@pytest.mark.parametrize('size,status', [(1920000, 200), (1920002, 413)])
def test_whole_audio_upload_body_limit(work, size, status):
    client, runtime, _, _ = work
    meeting_id = uuid4().hex
    client.post('/v1/work-meetings', json={'meeting_id': meeting_id, 'whole_audio': True})
    response = client.post('/v1/work-meetings/' + meeting_id + '/audio',
                           params={'upload_index': 0, 'request_id': uuid4().hex},
                           content=bytes(size), headers={'Content-Type': 'audio/pcm'})
    assert response.status_code == status
    with runtime.store.connect() as db:
        lengths = [row[0] for row in db.execute('SELECT length(pcm) FROM work_audio_uploads')]
    assert lengths == ([size] if status == 200 else [])
    assert client.post('/v1/work-meetings', content=bytes(65537)).status_code == 413


def test_whole_audio_failure_keeps_recording_for_explicit_retry(work, monkeypatch):
    from app.providers.qwen_meeting_asr import MeetingTaskFailed
    client, runtime, words, summaries = work
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
