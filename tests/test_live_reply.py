"""Mocked providers: reply latency ordering, evidence and cancellation contracts."""
import asyncio
from dataclasses import replace
from uuid import uuid4

import pytest

from app.domain import Mode
from app.providers.volc_speech import AsrResult, SpeechProviderError


def prepare(client, config, monkeypatch, text='你好，陪我聊聊'):
    config.speech = replace(config.speech, asr_api_key='offline', tts_app_key='offline', tts_access_key='offline')
    async def asr(chunks):
        async for _ in chunks:
            yield AsrResult(text, final=False)
        yield AsrResult(text, final=True)
    monkeypatch.setattr(client.app.state.speech, 'stream_asr', asr)


def open_turn(client):
    return client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex
        + '&interaction_source=direct_question&reply_audio=1')


def collect(ws):
    events, audio = [], []
    for _ in range(30):
        value = ws.receive()
        if value.get('bytes') is not None:
            audio.append(value['bytes'])
        else:
            import json
            message = json.loads(value['text'])
            events.append(message)
            if message['type'] == 'stream.done':
                return events, audio
    raise AssertionError('stream did not complete')


@pytest.mark.parametrize('text,speaks,mode', [
    ('我喜欢清淡食物', True, 'life'),
    ('他说切换到工作模式', True, 'life'),
    ('切换到工作模式', True, 'life'),
    ('讲个笑话', True, 'life'),
])
def test_continuous_call_streams_every_utterance(client, config, runtime, monkeypatch, text, speaks, mode):
    prepare(client, config, monkeypatch, text)
    async def tts(answer):
        assert speaks, 'silent capture must never synthesize speech'
        yield b'\x01\x00' * 16
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex
            + '&interaction_source=continuous_call&reply_audio=1') as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert result['speak'] is speaks and result['mode'] == mode
    assert bool(audio) is speaks
    assert any(e['type'] == 'reply.start' for e in events) is speaks


def test_reply_audio_starts_before_memory_extraction_finishes(client, config, runtime, monkeypatch):
    prepare(client, config, monkeypatch)
    started = asyncio.Event()
    async def extract(*args, **kwargs):
        await asyncio.wait_for(started.wait(), 2)
        return [], 'rules'
    async def reply(*args):
        return '我在，今天想聊什么？'
    async def tts(text):
        assert text == '我在，今天想聊什么？'
        yield b'\x01\x00' * 16
        started.set()
    monkeypatch.setattr(runtime.agent.memory, 'extract', extract)
    monkeypatch.setattr(runtime.agent.conversation, 'reply', reply)
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with open_turn(client) as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = collect(ws)
    assert audio == [b'\x01\x00' * 16]
    assert [e['type'] for e in events].count('reply.start') == 1
    assert any(e['type'] == 'reply.done' for e in events)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert result['speak'] and result['response'] == '我在，今天想聊什么？'


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_spoken_displayed_and_saved_answers_match_in_every_mode(client, config, runtime, monkeypatch, mode):
    prepare(client, config, monkeypatch)
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode), 'auto_mode': False}))
    answer = '完整回答🎧' * 400 + '这是结尾。'
    spoken = []
    async def reply(*args):
        return answer
    async def tts(text):
        assert len(text) <= 1500
        spoken.append(text)
        yield b'\x01\x00' * 16
    monkeypatch.setattr(runtime.agent.conversation, 'reply', reply)
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    preview = next(e['text'] for e in events if e['type'] == 'reply.start')
    saved = next(row['text'] for row in client.get('/v1/state').json()['transcripts'] if row['speaker'] == 'assistant')
    assert audio and result['speak']
    assert ''.join(spoken) == preview == result['response'] == saved == answer


def test_work_ambient_recording_never_starts_reply_audio(client, config, runtime, monkeypatch):
    prepare(client, config, monkeypatch, '会议讨论下周方案')
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode.work, 'auto_mode': False}))
    async def forbidden(*args):
        raise AssertionError('Ambient work must not start speech or a model answer')
    monkeypatch.setattr(runtime.agent.conversation, 'reply', forbidden)
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', forbidden)
    with client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex
            + '&interaction_source=ambient_capture') as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = [], []
        while True:
            message = ws.receive_json()
            events.append(message)
            if message['type'] == 'result':
                break
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert result['mode'] == 'work' and result['speak'] is False
    assert not audio and not any(e['type'].startswith('reply.') for e in events)


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_text_questions_have_a_nonempty_reply_and_matching_history_without_model(client, runtime, mode):
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode), 'auto_mode': False}))
    response = client.post('/v1/chat', json={'text': '你好，陪我聊聊', 'interaction_source': 'direct_question',
                                         'request_id': uuid4().hex})
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['mode'] == mode and result['response'] and result['speak']
    assert result['notice'] == '模型暂不可用，本次为基础规则回复'
    saved = next(row['text'] for row in client.get('/v1/state').json()['transcripts'] if row['speaker'] == 'assistant')
    assert saved == result['response']


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_every_mode_can_recall_memory_over_voice(client, config, runtime, monkeypatch, mode):
    prepare(client, config, monkeypatch, '我答应客户什么时候交方案？')
    settings = runtime.store.settings().model_copy(update={'mode': Mode(mode), 'auto_mode': False})
    runtime.store.save_settings(settings)
    row = runtime.store.add('memories', kind='task', content='客户方案周五提交', source='user')
    async def tts(text):
        assert '周五' in text
        yield b'\x01\x00' * 16
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert result['speak'] and result['interaction'] == 'memory_recall' and audio
    assert result['citations'][0]['id'] == row['id']


def test_voice_failure_keeps_text_result_and_never_sends_success_trailer(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    async def tts(text):
        yield b'\x01\x00' * 8
        raise SpeechProviderError('private vendor response')
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, _ = collect(ws)
    kinds = [e['type'] for e in events]
    assert 'result' in kinds and 'reply.error' in kinds and 'reply.done' not in kinds
    assert 'private vendor' not in str(events)


def test_disconnect_cancels_voice_and_pending_extraction_before_unlock(client, config, runtime, monkeypatch):
    prepare(client, config, monkeypatch)
    stopped = set()
    async def reply(*args):
        return '我在'
    async def extract(*args, **kwargs):
        try:
            await asyncio.sleep(60)
        finally:
            stopped.add('memory')
    async def tts(text):
        try:
            yield b'\x00\x00'
            await asyncio.sleep(60)
        finally:
            stopped.add('voice')
    monkeypatch.setattr(runtime.agent.conversation, 'reply', reply)
    monkeypatch.setattr(runtime.agent.memory, 'extract', extract)
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        while ws.receive().get('bytes') is None:
            pass
    assert stopped == {'memory', 'voice'}
    assert client.get('/v1/state').status_code == 200


def test_empty_asr_completes_without_opening_voice(client, config, monkeypatch):
    prepare(client, config, monkeypatch, '')
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, audio = collect(ws)
    assert not audio and [e['type'] for e in events] == ['result', 'stream.done']


def test_interrupt_stops_provider_and_keeps_exactly_one_business_result(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    stopped = []
    async def tts(text):
        try:
            yield b'\x01\x00' * 16
            await asyncio.sleep(60)
        finally:
            stopped.append(True)
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    import json
    with open_turn(client) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        prior = []
        while True:
            frame = ws.receive()
            if frame.get('bytes') is not None:
                break
            prior.append(json.loads(frame['text']))
        ws.send_json({'type': 'interrupt'})
        after, _ = collect(ws)
    kinds = [e['type'] for e in prior + after]
    assert stopped and kinds.count('result') == 1
    assert 'reply.interrupted' in kinds and 'reply.done' not in kinds
    assert kinds.index('reply.interrupted') < kinds.index('stream.done')


@pytest.mark.asyncio
async def test_model_can_retrieve_private_memory_without_external_quota(agent, store):
    from app.services.assistant_tools import AssistantTools
    row = store.add('memories', kind='preference', content='用户喜欢安静的咖啡馆', source='user')
    agent.tool_charge = lambda: False
    result = await AssistantTools(agent).call('recall_memory', {'question': '我喜欢什么咖啡馆'})
    assert result['available'] and result['citations'][0]['id'] == row['id']


@pytest.mark.parametrize('text', ['我心情不好怎么办', '你能陪我聊聊吗', '我该怎么学习英语？', '今天聊点什么？'])
def test_companion_questions_are_not_forced_into_memory_search(text):
    from app.services.memory_interaction import MemoryInteraction
    assert MemoryInteraction.intent(text) is None
