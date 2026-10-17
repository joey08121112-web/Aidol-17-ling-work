"""Offline regression for silent calls and explicit, durable mode controls."""
from uuid import uuid4

import pytest

from app.domain import Mode
from app.services.mobile import MobileRuntime
from app.services.voice_intent import mode_command


@pytest.mark.parametrize('text', ['不要切换到工作模式', '怎么切换到工作模式？',
    '他说切换到通勤模式', '“切换到工作模式”', '如果切换到工作模式会怎样',
    '记住切换到工作模式这句话', '我刚才说切换到工作模式'])
def test_quoted_negated_and_question_controls_are_not_commands(text):
    assert mode_command(text) is None


def turn(client, text, source='continuous_call', request_id=None):
    return client.post('/v1/chat', json={'text': text, 'interaction_source': source,
                                      'request_id': request_id or uuid4().hex})


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_every_call_utterance_answers_without_name(client, runtime, monkeypatch, mode):
    settings = runtime.store.settings()
    settings.mode = Mode(mode)
    runtime.store.save_settings(settings)
    calls = []
    async def reply(text, instructions):
        calls.append(text)
        return '我在，接着说。'
    monkeypatch.setattr(runtime.agent.conversation, 'reply', reply)
    for text in ['你好，陪我聊聊', '那后来呢？']:
        result = turn(client, text).json()
        assert result['speak'] and result['response'] == '我在，接着说。'
        assert result['mode'] == mode and result['interaction'] == 'text_llm'
    assert calls == ['你好，陪我聊聊', '那后来呢？']


@pytest.mark.parametrize('source', ['direct_question', 'continuous_call', 'ambient_capture'])
@pytest.mark.parametrize('text', ['切换到通勤模式', '忘记刚才那条', '几点了？', '请帮我记住密码是123'])
def test_work_audio_never_executes_commands_or_answers(client, runtime, monkeypatch, source, text):
    import asyncio
    settings = runtime.store.settings()
    settings.mode = Mode.work
    runtime.store.save_settings(settings)
    async def unexpected(*args, **kwargs):
        raise AssertionError('work recording must never call conversational tools or answer')
    monkeypatch.setattr(runtime.agent.conversation, 'reply', unexpected)
    monkeypatch.setattr(runtime.agent.memory_interaction, 'handle', unexpected)
    result = asyncio.run(runtime.turn(text, 'asr', source))
    assert result['mode'] == 'work' and runtime.store.settings().mode == 'work'
    assert not result['speak'] and result['response'] == ''
    assert result['interaction'] == 'silent_capture'
    rows = runtime.store.meeting_records('2000-01-01T00:00:00+00:00')
    assert len(rows) == 1 and rows[0]['text'] == text


def test_work_recording_saves_even_with_chat_history_disabled(client, runtime):
    import asyncio
    settings = runtime.store.settings()
    settings.mode = Mode.work
    settings.retain_transcripts = False
    runtime.store.save_settings(settings)
    asyncio.run(runtime.turn('周五交付接口', 'asr', 'ambient_capture'))
    assert runtime.store.list('transcripts', 10)[0]['text'] == '周五交付接口'


def test_named_settings_removed_and_legacy_values_are_ignored(client):
    settings = client.put('/v1/settings', json={'name': '旧称呼', 'auto_mode': False}).json()
    assert 'name' not in settings
    assert 'name' not in client.get('/v1/state').json()['settings']


def test_memory_commands_need_no_name_during_call(client):
    result = turn(client, '记住我喜欢清淡食物').json()
    assert result['interaction'] == 'memory_remember' and result['speak']
    assert turn(client, '忘记刚才那条').json()['interaction'] == 'memory_forget'


def test_same_request_cannot_change_interaction_semantics(client):
    request_id = uuid4().hex
    assert turn(client, '你好', request_id=request_id).status_code == 200
    assert turn(client, '你好', 'direct_question', request_id).status_code == 409


def test_mobile_modes_remain_manual_even_if_old_client_requests_auto(client):
    settings = client.put('/v1/settings', json={'mode': 'commute', 'auto_mode': True}).json()
    assert settings['mode'] == 'commute' and settings['auto_mode'] is False
