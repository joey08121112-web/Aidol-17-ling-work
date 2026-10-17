import copy
import json

import httpx
import pytest

from app.services.work_meetings import WorkMeetingMinutes


ROWS = [{'id': 'source-1', 'text': '我们决定发布新版。小林负责明天十二点前交稿。',
         'speaker': '现场发言（未分离声纹）', 'mode': 'work'}]
REPORT = {'title': '新版发布', 'summary': '• 发布新版，小林负责交稿。',
          'tasks': [{'content': '交稿', 'owner': '小林', 'due': '明天十二点前', 'source_ids': ['source-1']}],
          'viewpoints': [], 'conflicts': [], 'events': [], 'topics': [], 'decisions': [], 'open_questions': []}


def provider(monkeypatch, responses):
    payloads = []

    class Client:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *_): pass
        async def post(self, url, **kwargs):
            payloads.append(copy.deepcopy(kwargs['json']))
            status, output = responses[min(len(payloads) - 1, len(responses) - 1)]
            if isinstance(output, Exception):
                raise output
            body = {'error': {'message': 'upstream-secret-do-not-display'}} if status != 200 else {
                'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(output, ensure_ascii=False)}}]}
            return httpx.Response(status, json=body, request=httpx.Request('POST', url))

    monkeypatch.setattr('app.services.work_meetings.httpx.AsyncClient', Client)
    return payloads


@pytest.mark.asyncio
async def test_repair_preserves_whole_meeting_and_exact_deadline_evidence(config, monkeypatch):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    invalid = copy.deepcopy(REPORT)
    invalid['tasks'][0]['due'] = '明天12点之前'
    payloads = provider(monkeypatch, [(200, invalid), (200, REPORT)])
    charges = []
    def charge():
        charges.append(True)
        return True
    result = await WorkMeetingMinutes(config, charge).generate(ROWS)
    assert result['extraction'] == 'llm'
    assert result['tasks'][0]['due'] == '明天十二点前'
    assert result['source_ids'] == ['source-1']
    assert len(payloads) == len(charges) == 2
    assert payloads[1]['messages'][:2] == payloads[0]['messages']
    assert payloads[1]['messages'][2]['role'] == 'assistant'
    assert '逐字' in payloads[1]['messages'][3]['content']


@pytest.mark.asyncio
async def test_failed_correction_still_rejects_unsupported_claims(config, monkeypatch):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    invalid = copy.deepcopy(REPORT)
    invalid['tasks'][0]['source_ids'] = ['other-meeting']
    payloads = provider(monkeypatch, [(200, invalid)])
    result = await WorkMeetingMinutes(config).generate(ROWS)
    assert len(payloads) == 2
    assert result['extraction'] == 'rules_fallback'
    assert result['failure_reason'] == 'invalid_output'
    assert '依据校验' in result['notice']
    assert 'other-meeting' not in result['source_ids']


@pytest.mark.asyncio
async def test_uncertain_task_fields_stay_blank_without_discarding_valid_minutes(config, monkeypatch):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    invalid = copy.deepcopy(REPORT)
    invalid['tasks'][0].update(owner='未知负责人', due='明天12点之前')
    payloads = provider(monkeypatch, [(200, invalid)])
    result = await WorkMeetingMinutes(config).generate(ROWS)
    assert result['extraction'] == 'llm' and len(payloads) == 2
    assert result['summary'] == REPORT['summary']
    assert result['tasks'][0] == {**REPORT['tasks'][0], 'owner': '', 'due': ''}
    assert result['metadata_omissions'] == ['task_deadline', 'task_owner']
    assert '已留空' in result['notice']


@pytest.mark.asyncio
async def test_unsupported_people_and_event_metadata_are_unknown_not_invented(config, monkeypatch):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    invalid = copy.deepcopy(REPORT)
    invalid['viewpoints'] = [{'person': '未出现的人', 'text': '讨论新版发布', 'source_ids': ['source-1']}]
    invalid['events'] = [{'content': '新版发布', 'time': '下周', 'location': '上海', 'task': '', 'source_ids': ['source-1']}]
    provider(monkeypatch, [(200, invalid)])
    result = await WorkMeetingMinutes(config).generate(ROWS)
    assert result['extraction'] == 'llm'
    assert result['viewpoints'][0]['person'] == ''
    assert result['events'][0]['time'] == result['events'][0]['location'] == ''
    assert result['metadata_omissions'] == ['event_location', 'event_time', 'viewpoint_person']
    assert result['tasks'][0] == REPORT['tasks'][0]


@pytest.mark.asyncio
@pytest.mark.parametrize('status,error,reason', [
    (401, {}, 'authentication'), (403, {}, 'authentication'),
    (429, {}, 'rate_limited'), (503, {}, 'provider_error'),
    (200, httpx.ReadTimeout('private upstream URL'), 'timeout'),
    (200, httpx.ConnectError('private upstream URL'), 'network'),
])
async def test_failure_notices_distinguish_provider_problems_without_leaking_payloads(config, monkeypatch, status, error, reason):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    payloads = provider(monkeypatch, [(status, error)])
    result = await WorkMeetingMinutes(config).generate(ROWS)
    assert result['failure_reason'] == reason
    assert result['extraction'] == 'rules_fallback' and len(payloads) == 1
    assert 'upstream-secret' not in json.dumps(result)
    assert 'private upstream URL' not in json.dumps(result)


@pytest.mark.asyncio
async def test_correction_cannot_bypass_quota(config, monkeypatch):
    config.text_key, config.text_model = 'offline-only', 'offline-model'
    invalid = copy.deepcopy(REPORT)
    invalid['tasks'][0]['due'] = '明天12点之前'
    payloads = provider(monkeypatch, [(200, invalid)])
    quota = iter([True, False])
    result = await WorkMeetingMinutes(config, lambda: next(quota)).generate(ROWS)
    assert len(payloads) == 1
    assert result['failure_reason'] == 'quota_exceeded'
    assert '额度' in result['notice']


@pytest.mark.asyncio
async def test_missing_configuration_is_not_reported_as_model_output_failure(config):
    result = await WorkMeetingMinutes(config).generate(ROWS)
    assert result['failure_reason'] == 'not_configured'
    assert '尚未配置' in result['notice']
