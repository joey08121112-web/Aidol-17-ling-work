"""Whole-recording Qwen Filetrans: one task and one speaker namespace per meeting."""
import asyncio
import re
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from .volc_speech import SpeechProviderError


class MeetingTaskFailed(SpeechProviderError):
    """Terminal task failure: a deliberate retry must submit a new task."""


class QwenMeetingAsrProvider:
    model = 'qwen-audio-3.1-asr-flash-filetrans'

    def __init__(self, config):
        self.config = config
        parsed = urlsplit(config.url)
        self.base = f'{parsed.scheme}://{parsed.netloc}/api/v1'

    @staticmethod
    def _external_url(value):
        parsed = urlsplit(value)
        # Vendor-owned upload/result endpoints only; never send bearer keys to these URLs.
        if parsed.scheme != 'https' or parsed.username or parsed.password or not parsed.hostname:
            raise ValueError('Invalid resource URL')
        if not any(parsed.hostname.endswith('.' + domain) for domain in
                   ('aliyuncs.com', 'qianwenaiapi.com', 'aliyun.com')):
            raise ValueError('Unexpected resource host')
        return value

    async def transcribe(self, wav, task_id='', on_task=lambda value: None):
        if not self.config.ready:
            raise SpeechProviderError('Meeting ASR is not configured')
        auth = {'Authorization': 'Bearer ' + self.config.api_key}
        try:
            async with httpx.AsyncClient(timeout=120, proxy=self.config.proxy) as client:
                if not task_id:
                    response = await client.get(self.base + '/uploads', headers=auth,
                                                params={'action': 'getPolicy', 'model': self.model})
                    response.raise_for_status()
                    policy = response.json()['data']
                    name = uuid4().hex + '.wav'
                    key = policy['upload_dir'] + '/' + name
                    fields = {'OSSAccessKeyId': policy['oss_access_key_id'], 'Signature': policy['signature'],
                              'policy': policy['policy'], 'x-oss-object-acl': policy['x_oss_object_acl'],
                              'x-oss-forbid-overwrite': policy['x_oss_forbid_overwrite'],
                              'key': key, 'success_action_status': '200'}
                    wav.seek(0)
                    response = await client.post(self._external_url(policy['upload_host']),
                                                 data=fields, files={'file': (name, wav, 'audio/wav')})
                    response.raise_for_status()
                    response = await client.post(self.base + '/services/audio/asr/transcription',
                        headers={**auth, 'X-DashScope-Async': 'enable', 'X-DashScope-OssResourceResolve': 'enable'},
                        json={'model': self.model, 'input': {'file_urls': ['oss://' + key]},
                              'parameters': {'channel_id': [0], 'diarization_enabled': True}})
                    response.raise_for_status()
                    task_id = response.json()['output']['task_id']
                    if not isinstance(task_id, str) or not re.fullmatch(r'[\w-]{1,128}', task_id):
                        raise ValueError('Invalid task ID')
                    on_task(task_id)
                if not re.fullmatch(r'[\w-]{1,128}', task_id):
                    raise ValueError('Invalid saved task ID')
                async with asyncio.timeout(1800):
                    while True:
                        response = await client.get(self.base + '/tasks/' + task_id, headers=auth)
                        response.raise_for_status()
                        output = response.json()['output']
                        status = output['task_status']
                        if status == 'SUCCEEDED':
                            break
                        if status not in ('PENDING', 'RUNNING'):
                            raise MeetingTaskFailed('Transcription task failed')
                        await asyncio.sleep(3)
                results = output['results']
                if len(results) != 1 or results[0]['subtask_status'] != 'SUCCEEDED':
                    raise MeetingTaskFailed('Incomplete transcription')
                response = await client.get(self._external_url(results[0]['transcription_url']))
                response.raise_for_status()
                return self.parse(response.json())
        except (httpx.HTTPError, ValueError, KeyError, TypeError, IndexError, TimeoutError) as exc:
            raise SpeechProviderError('Whole meeting transcription failed') from exc

    @staticmethod
    def parse(body):
        transcripts = body['transcripts']
        if not isinstance(transcripts, list):
            raise ValueError('Invalid transcription')
        turns, labels = [], {}
        for transcript in transcripts:
            sentences = transcript.get('sentences') or []
            if not sentences and transcript.get('text', '').strip():
                raise ValueError('Speaker sentences missing')
            for sentence in sentences:
                text = sentence['text']
                if not isinstance(text, str):
                    raise ValueError('Invalid sentence')
                if not text.strip():
                    continue
                speaker_id = sentence.get('speaker_id')
                if speaker_id is not None and (not isinstance(speaker_id, int) or isinstance(speaker_id, bool) or speaker_id < 0):
                    raise ValueError('Invalid speaker ID')
                if speaker_id is None:
                    speaker = '未识别发言人'
                else:
                    labels.setdefault(speaker_id, '发言人 ' + str(len(labels) + 1))
                    speaker = labels[speaker_id]
                begin, end = sentence.get('begin_time'), sentence.get('end_time')
                if not isinstance(begin, int) or not isinstance(end, int) or not 0 <= begin <= end:
                    raise ValueError('Invalid sentence time')
                turns.append({'text': text, 'speaker': speaker, 'speaker_id': speaker_id,
                              'begin_time': begin, 'end_time': end})
        return turns
