import io
import json
import httpx
import pytest
from app.providers.qwen_work_asr import QwenWorkAsrConfig
from app.providers.qwen_meeting_asr import QwenMeetingAsrProvider


def test_whole_recording_speaker_ids_are_consistent():
    turns = QwenMeetingAsrProvider.parse({'transcripts': [{'sentences': [
        {'text': '甲发言', 'speaker_id': 8, 'begin_time': 0, 'end_time': 1000},
        {'text': '乙发言', 'speaker_id': 2, 'begin_time': 1000, 'end_time': 2000},
        {'text': '甲再次发言', 'speaker_id': 8, 'begin_time': 2000, 'end_time': 3000},
        {'text': '身份不清', 'speaker_id': None, 'begin_time': 3000, 'end_time': 4000}]}]})
    assert [t['speaker'] for t in turns] == ['发言人 1', '发言人 2', '发言人 1', '未识别发言人']
    assert [t['text'] for t in turns] == ['甲发言', '乙发言', '甲再次发言', '身份不清']


@pytest.mark.asyncio
@pytest.mark.parametrize('resume', [False, True])
async def test_filetrans_upload_poll_and_resume_without_resubmitting(monkeypatch, resume):
    paths, saved = [], []
    def respond(request):
        paths.append(request.url.path)
        if request.url.path == '/api/v1/uploads':
            assert request.url.params['model'] == QwenMeetingAsrProvider.model
            return httpx.Response(200, json={'data': {'upload_host': 'https://bucket.oss-cn-beijing.aliyuncs.com/',
                'upload_dir': 'temporary', 'oss_access_key_id': 'offline', 'signature': 'offline',
                'policy': 'offline', 'x_oss_object_acl': 'private', 'x_oss_forbid_overwrite': 'true'}})
        if request.url.host == 'bucket.oss-cn-beijing.aliyuncs.com':
            assert 'authorization' not in request.headers
            return httpx.Response(200)
        if request.url.path.endswith('/transcription'):
            payload = json.loads(request.content)
            assert payload['model'] == 'qwen-audio-3.1-asr-flash-filetrans'
            assert payload['parameters']['diarization_enabled'] is True
            assert len(payload['input']['file_urls']) == 1
            assert request.headers['X-DashScope-OssResourceResolve'] == 'enable'
            return httpx.Response(200, json={'output': {'task_id': 'offline-task'}})
        if request.url.path.endswith('/offline-task'):
            return httpx.Response(200, json={'output': {'task_status': 'SUCCEEDED', 'results': [{
                'subtask_status': 'SUCCEEDED', 'transcription_url': 'https://result.oss-cn-beijing.aliyuncs.com/result.json'}]}})
        assert request.url.host == 'result.oss-cn-beijing.aliyuncs.com'
        assert 'authorization' not in request.headers
        return httpx.Response(200, json={'transcripts': [{'sentences': [
            {'speaker_id': 3, 'text': '原话', 'begin_time': 0, 'end_time': 100}]}]})
    client = httpx.AsyncClient
    monkeypatch.setattr('app.providers.qwen_meeting_asr.httpx.AsyncClient',
                        lambda **kwargs: client(transport=httpx.MockTransport(respond)))
    turns = await QwenMeetingAsrProvider(QwenWorkAsrConfig(api_key='offline')).transcribe(
        io.BytesIO(b'RIFF'), 'offline-task' if resume else '', saved.append)
    assert turns[0]['speaker'] == '发言人 1'
    assert saved == ([] if resume else ['offline-task'])
    assert ('/api/v1/uploads' in paths) is not resume


def test_no_sentences_does_not_claim_speaker_transcription_success():
    with pytest.raises(ValueError):
        QwenMeetingAsrProvider.parse({'transcripts': [{'text': '原文但没有发言人句子'}]})
