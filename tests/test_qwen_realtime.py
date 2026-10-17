"""Offline Qwen wire/lifecycle checks. No .env keys or network calls."""
import asyncio
import base64
from contextlib import asynccontextmanager
from dataclasses import replace
import json
from uuid import uuid4

import pytest

from app.providers.qwen_realtime import QwenRealtimeConfig, QwenRealtimeSession, QwenRealtimeProvider
from app.providers.volc_speech import AsrResult, SpeechProviderError


class Wire:
    def __init__(self, rounds=()):
        self.sent, self.rounds = [], list(rounds)
        self.incoming = asyncio.Queue()

    async def send(self, raw):
        message = json.loads(raw)
        self.sent.append(message)
        kind = message['type']
        if kind == 'session.update':
            await self.incoming.put({'type': 'session.updated'})
        elif kind == 'input_audio_buffer.commit':
            await self.incoming.put({'type': 'conversation.item.input_audio_transcription.completed', 'transcript': '你好'})
        elif kind == 'response.create':
            for event in self.rounds.pop(0):
                await self.incoming.put(event)

    async def recv(self):
        return json.dumps(await self.incoming.get())


def output(text='你好'):
    return [{'type': 'response.audio.delta', 'delta': base64.b64encode(b'\x01\x00' * 30).decode()},
            {'type': 'response.audio_transcript.delta', 'delta': text},
            {'type': 'response.audio_transcript.done', 'transcript': text},
            {'type': 'response.done', 'response': {'status': 'completed'}}]


async def no_tool(*args):
    raise AssertionError('Unexpected tool call')


@pytest.mark.asyncio
@pytest.mark.parametrize('fail_asr', [False, True])
async def test_duplex_transports_initialize_together_and_close_on_partial_failure(monkeypatch, fail_asr):
    provider = QwenRealtimeProvider(QwenRealtimeConfig(api_key='offline'))
    entered, closed = set(), set()
    both = asyncio.Event()

    @asynccontextmanager
    async def transport(name):
        entered.add(name)
        if len(entered) == 2:
            both.set()
        await asyncio.wait_for(both.wait(), 1)
        if name == 'asr' and fail_asr:
            raise SpeechProviderError('offline failure')
        try:
            yield QwenRealtimeSession(Wire(), provider.config)
        finally:
            closed.add(name)

    monkeypatch.setattr(provider, 'session', lambda *args: transport('base'))
    monkeypatch.setattr(provider, 'transcription_session', lambda: transport('asr'))
    if fail_asr:
        with pytest.raises(SpeechProviderError):
            async with provider.duplex_session('test'):
                pytest.fail('Partial preparation must not become ready')
        assert closed == {'base'}
    else:
        async with provider.duplex_session('test') as session:
            assert entered == {'base', 'asr'} and not closed
            assert session.reader is not None and session.asr_reader is not None
        assert closed == {'base', 'asr'}


@pytest.mark.asyncio
async def test_original_pcm_committed_before_native_generation():
    wire = Wire([output()])
    native = QwenRealtimeSession(wire, QwenRealtimeConfig(api_key='offline'))
    pcm = b'\x01\x00' * 1600
    async def chunks():
        yield pcm
        assert all(m['type'] != 'response.create' for m in wire.sent)
    results = [item async for item in native.stream_asr(chunks())]
    assert results[-1].text == '你好'
    assert base64.b64decode(wire.sent[0]['audio']) == pcm
    assert [m['type'] for m in wire.sent] == ['input_audio_buffer.append', 'input_audio_buffer.commit']
    events = [item async for item in native.reply('instructions', [], no_tool)]
    assert events[0]['audio'] == b'\x01\x00' * 30
    assert events[-1]['text'] == '你好'


@pytest.mark.asyncio
@pytest.mark.parametrize('maas', [True, False])
async def test_tool_dialects_and_duplicate_call_events_execute_once(maas):
    item = {'call_id': 'call-1', 'name': 'current_time', 'arguments': '{}'}
    event = {'type': 'response.function_call_arguments.done', **({'items': [item]} if maas else item)}
    wire = Wire([[event, {'type': 'response.done', 'response': {'status': 'completed',
                  'output': [{'type': 'function_call', **item}]}}], output('现在是星期六')])
    native = QwenRealtimeSession(wire, QwenRealtimeConfig(output_format='pcm24' if maas else 'pcm16'))
    calls = []
    async def tool(name, arguments):
        calls.append((name, arguments))
        return {'weekday': 6}
    events = [item async for item in native.reply('instructions', [], tool)]
    assert calls == [('current_time', {})]
    assert events[-1]['text'] == '现在是星期六'
    submitted = next(m['item'] for m in wire.sent if m['type'] == 'conversation.item.create')
    assert submitted['call_id'] == 'call-1'
    assert submitted.get('role') == 'tool' if maas else submitted['type'] == 'function_call_output'


@pytest.mark.asyncio
@pytest.mark.parametrize('bad', [
    {'type': 'error', 'error': {'message': 'private-upstream-body'}},
    {'type': 'response.audio.delta', 'delta': 'not-base64!'},
    {'type': 'response.audio.delta', 'delta': base64.b64encode(b'x').decode()},
    {'type': 'response.done', 'response': {'status': 'failed'}},
    {'type': 'response.done', 'response': {'status': 'completed'}},
])
async def test_bad_output_cannot_report_success_or_leak_body(bad):
    native = QwenRealtimeSession(Wire([[bad]]), QwenRealtimeConfig())
    with pytest.raises(SpeechProviderError) as error:
        _ = [event async for event in native.reply('', [], no_tool)]
    assert 'private-upstream-body' not in str(error.value)


class FakeNative:
    def __init__(self, text='你好，陪我聊聊', pause=False, fail=False):
        self.text, self.pause, self.fail = text, pause, fail
        self.cancelled = False
        self.generated = False
        self.closed = False
        self.pcm = bytearray()

    async def enable_endpoint(self, callback):
        self.endpoint_callback = callback

    async def stream_asr(self, chunks):
        async for chunk in chunks:
            self.pcm.extend(chunk)
        yield AsrResult(self.text, final=True)

    async def reply(self, instructions, schemas, tool):
        self.generated = True
        assert 'recall_memory' in str(schemas)
        yield {'audio': b'\x02\x00' * 16}
        yield {'text': '我在，想聊些什么？'}
        if self.pause:
            await asyncio.sleep(60)
        if self.fail:
            raise SpeechProviderError('private-upstream-body')

    async def cancel(self):
        self.cancelled = True


def configure(client, config, monkeypatch, native=None):
    native = native or FakeNative()
    config.qwen = QwenRealtimeConfig(api_key='offline')
    @asynccontextmanager
    async def session(*args):
        try:
            yield native
        finally:
            native.closed = True
    monkeypatch.setattr(client.app.state.qwen, 'session', session)
    async def forbidden(*args, **kwargs):
        raise AssertionError('Native audio must not use Volcano ASR or text generation')
    monkeypatch.setattr(client.app.state.speech, 'stream_asr', forbidden)
    return native


def path(turn_id=None):
    return '/v1/voice/stream?request_id=' + (turn_id or uuid4().hex) + '&interaction_source=direct_question&reply_audio=1&engine=qwen'


def collect(ws):
    events, pcm = [], []
    for _ in range(30):
        frame = ws.receive()
        if frame.get('bytes') is not None:
            pcm.append(frame['bytes'])
        else:
            event = json.loads(frame['text'])
            events.append(event)
            if event['type'] in {'stream.done', 'error'}:
                return events, pcm
    raise AssertionError('Stream never completed')


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_native_reply_uses_one_account_orchestrator_and_persists_matching_text(client, config, runtime, monkeypatch, mode):
    from app.domain import Mode
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode), 'auto_mode': False}))
    native = configure(client, config, monkeypatch)
    async def forbidden(*args):
        raise AssertionError('Must not invoke text reply model')
    monkeypatch.setattr(runtime.agent.conversation, 'reply', forbidden)
    turn_id = uuid4().hex
    with client.websocket_connect(path(turn_id)) as ws:
        assert ws.receive_json() == {'type': 'ready', 'sample_rate': 16000, 'engine': 'qwen'}
        ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, pcm = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert native.generated and native.closed and native.pcm == b'\x00' * 3200
    assert pcm and result['interaction'] == 'qwen_realtime' and result['response_complete']
    assert result['response'] == next(e['text'] for e in events if e['type'] == 'reply.text')
    assert client.get('/v1/turns/' + turn_id).json()['result']['response'] == result['response']
    stored = client.get('/v1/state').json()['transcripts']
    assert next(r['text'] for r in stored if r['speaker'] == 'assistant') == result['response']
    with client.websocket_connect(path(turn_id)) as ws:
        assert ws.receive_json()['status'] == 409


def test_native_capability_does_not_require_cascade_credentials(client, config):
    config.qwen = QwenRealtimeConfig(api_key='offline')
    caps = client.get('/v1/state').json()['capabilities']
    assert caps['voice_realtime'] and not caps['voice_reply_stream'] and not caps['asr']
    assert 'offline' not in str(caps)


def test_missing_key_and_ambient_requests_never_open_provider(client, config, monkeypatch):
    with client.websocket_connect(path()) as ws:
        assert ws.receive_json()['status'] == 503
    configure(client, config, monkeypatch)
    with client.websocket_connect(path().replace('direct_question', 'ambient_capture')) as ws:
        assert ws.receive_json()['status'] == 422


def test_native_memory_command_keeps_exact_receipt_without_second_reasoning(client, config, runtime, monkeypatch):
    native = configure(client, config, monkeypatch, FakeNative('记住我不吃香菜'))
    with client.websocket_connect(path()) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, pcm = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert not native.generated and not pcm
    assert result['interaction'] == 'memory_remember' and '已保存' in result['response']
    assert result['speak'] is False
    assert any('香菜' in row['content'] for row in runtime.store.list('memories', 20))


@pytest.mark.parametrize('fail', [False, True])
def test_interrupt_or_error_never_saves_partial_answer_as_complete_history(client, config, runtime, monkeypatch, fail):
    native = configure(client, config, monkeypatch, FakeNative(pause=not fail, fail=fail))
    with client.websocket_connect(path()) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        if not fail:
            while True:
                frame = ws.receive()
                if frame.get('text') and json.loads(frame['text'])['type'] == 'reply.text':
                    break
            ws.send_json({'type': 'interrupt'})
        events, _ = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert not result['response_complete']
    assert 'private-upstream-body' not in str(events)
    assert not any(row['speaker'] == 'assistant' for row in runtime.store.list('transcripts', 20))
    assert not any(e['type'] == 'reply.done' for e in events)
    assert native.closed
    if not fail:
        assert native.cancelled and result['voice_interrupted']


def test_disconnect_cancels_native_generation_and_releases_account(client, config, runtime, monkeypatch):
    native = configure(client, config, monkeypatch, FakeNative(pause=True))
    with client.websocket_connect(path()) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        while ws.receive().get('bytes') is None:
            pass
    assert native.closed
    assert not runtime.lock.locked()
    assert client.get('/v1/state').status_code == 200


def test_logout_during_audio_cannot_generate_or_commit(client, config, monkeypatch):
    native = configure(client, config, monkeypatch)
    with client.websocket_connect(path()) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200)
        assert client.post('/auth/logout').status_code == 200
        ws.send_json({'type': 'end'})
        events, _ = collect(ws)
    assert events[-1]['status'] == 401
    assert not native.generated


def test_native_invalid_audio_never_reaches_generation(client, config, monkeypatch):
    native = configure(client, config, monkeypatch)
    with client.websocket_connect(path()) as ws:
        ws.receive_json(); ws.send_bytes(b'x')
        events, _ = collect(ws)
    assert events[-1]['type'] == 'error' and not native.generated and native.closed


@pytest.mark.asyncio
async def test_input_partial_arrives_before_end_without_starting_generation():
    wire = Wire()
    native = QwenRealtimeSession(wire, QwenRealtimeConfig())
    release = asyncio.Event()
    async def chunks():
        yield b'\x00' * 3200
        await wire.incoming.put({'type': 'conversation.item.input_audio_transcription.delta',
                                 'text': '你', 'stash': '好'})
        await release.wait()
    stream = native.stream_asr(chunks())
    partial = await anext(stream)
    assert partial.text == '你好' and not partial.final
    assert all(event['type'] != 'response.create' for event in wire.sent)
    release.set()
    final = await anext(stream)
    assert final.final
    await stream.aclose()


@pytest.mark.asyncio
async def test_repeated_tool_requests_have_a_hard_bound():
    call = {'type': 'response.function_call_arguments.done', 'call_id': 'one',
            'name': 'current_time', 'arguments': '{}'}
    round_events = [call, {'type': 'response.done', 'response': {'status': 'completed'}}]
    native = QwenRealtimeSession(Wire([round_events] * 4), QwenRealtimeConfig())
    count = 0
    async def tool(*args):
        nonlocal count
        count += 1
        return {'weekday': 6}
    with pytest.raises(SpeechProviderError):
        _ = [event async for event in native.reply('', [], tool)]
    assert count == 3


def test_qwen_key_is_not_in_repr_and_only_tls_urls_are_eligible():
    value = QwenRealtimeConfig(api_key='secret-for-offline-test')
    assert value.ready and 'secret-for-offline-test' not in repr(value)
    for url in ['ws://example.com/realtime', 'wss://user:pass@example.com/realtime',
                'wss://example.com/realtime?key=secret']:
        assert not replace(value, url=url).ready


@pytest.mark.parametrize('text,interaction', [('我喜欢清淡饮食', 'qwen_realtime'),
    ('他说切换到工作模式', 'qwen_realtime'), ('讲个笑话', 'qwen_realtime'),
    ('切换到工作模式', 'mode_hint')])
def test_native_continuous_call_answers_without_addressing(client, config, runtime, monkeypatch, text, interaction):
    native = configure(client, config, monkeypatch, FakeNative(text))
    with client.websocket_connect(path().replace('direct_question', 'continuous_call')) as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        events, pcm = collect(ws)
    result = next(e['result'] for e in events if e['type'] == 'result')
    assert result['interaction'] == interaction
    assert native.generated is (interaction == 'qwen_realtime')
    if interaction == 'silent_capture':
        assert not result['speak'] and not pcm and not result['response']
    if interaction == 'mode_hint':
        assert runtime.store.settings().mode == 'life'


def test_work_rejects_native_reply_channel_before_provider_connection(client, config, runtime, monkeypatch):
    from app.domain import Mode
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode.work, 'auto_mode': False}))
    native = configure(client, config, monkeypatch)
    with client.websocket_connect(path()) as ws:
        error = ws.receive_json()
        assert error['type'] == 'error' and error['status'] == 409
    assert not native.generated


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_two_native_call_rounds_answer_without_name_and_keep_history(client, config, runtime, monkeypatch, mode):
    from app.domain import Mode
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode), 'auto_mode': False}))
    configure(client, config, monkeypatch)
    rounds, histories = [], []
    @asynccontextmanager
    async def session(instructions, history, schemas):
        histories.append(history)
        native = FakeNative('你好，陪我聊聊' if not rounds else '那接下来呢？')
        rounds.append(native)
        yield native
    monkeypatch.setattr(client.app.state.qwen, 'session', session)
    caps = client.get('/v1/state').json()['capabilities']
    assert caps['voice_continuous_qa'] and caps['voice_barge_in'] is True
    for _ in range(2):
        with client.websocket_connect(path().replace('direct_question', 'continuous_call')) as ws:
            assert ws.receive_json()['engine'] == 'qwen'
            ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
            events, audio = collect(ws)
        result = next(e['result'] for e in events if e['type'] == 'result')
        assert result['speak'] and result['mode'] == mode
        assert result['interaction'] == 'qwen_realtime' and audio
        assert any(e['type'] == 'reply.text' for e in events)
    assert all(r.generated for r in rounds)
    assert histories[0] == []
    assert any(r['role'] == 'assistant' for r in histories[1])
    assert any(r['content'] == '你好，陪我聊聊' for r in histories[1])


@pytest.mark.asyncio
async def test_cloud_endpoint_waits_for_phone_drain_and_never_double_commits():
    class CloudWire(Wire):
        def __init__(self):
            super().__init__([output()])
            self.emitted = False
        async def send(self, raw):
            await super().send(raw)
            if json.loads(raw)['type'] == 'input_audio_buffer.append' and not self.emitted:
                self.emitted = True
                for event in [
                    {'type': 'input_audio_buffer.speech_stopped'},
                    {'type': 'input_audio_buffer.speech_stopped'},
                    {'type': 'input_audio_buffer.committed'},
                    {'type': 'conversation.item.input_audio_transcription.completed', 'transcript': '你好'}]:
                    await self.incoming.put(event)
    wire = CloudWire()
    native = QwenRealtimeSession(wire, QwenRealtimeConfig(api_key='offline'))
    boundary, drain = asyncio.Event(), asyncio.Event()
    callbacks = []
    async def end():
        callbacks.append('end'); boundary.set()
    await native.enable_endpoint(end)
    settings = wire.sent[0]['session']['turn_detection']
    assert settings['create_response'] is False and settings['interrupt_response'] is False
    async def chunks():
        yield bytes(3200)
        await drain.wait()
        yield bytes(640)  # final PCM from the SDK's confirmed stop/drain
    async def recognize():
        return [r async for r in native.stream_asr(chunks())]
    task = asyncio.create_task(recognize())
    await asyncio.wait_for(boundary.wait(), 1)
    assert not task.done()  # no business turn until explicit phone end
    drain.set()
    results = await asyncio.wait_for(task, 1)
    assert results[-1].text == '你好' and callbacks == ['end']
    assert not any(m['type'] in {'input_audio_buffer.commit', 'response.create'} for m in wire.sent)
    events = [e async for e in native.reply('instructions', [], no_tool)]
    assert any('audio' in e for e in events)
    assert sum(m['type'] == 'response.create' for m in wire.sent) == 1


def test_stream_done_releases_account_and_provider_before_immediate_next_round(client, config, runtime, monkeypatch):
    configure(client, config, monkeypatch)
    cleanups = []
    @asynccontextmanager
    async def session(*args):
        yield FakeNative()
        await asyncio.sleep(.15)  # emulate a slow upstream close handshake
        cleanups.append(True)
    monkeypatch.setattr(client.app.state.qwen, 'session', session)
    for number in range(2):
        with client.websocket_connect(path().replace('direct_question', 'continuous_call')) as ws:
            assert ws.receive_json()['type'] == 'ready'
            ws.send_bytes(bytes(3200)); ws.send_json({'type': 'end'})
            events, audio = collect(ws)
            assert events[-1]['type'] == 'stream.done'
            assert len(cleanups) == number + 1
            assert not runtime.lock.locked()
        assert audio


@pytest.mark.asyncio
async def test_voice_waits_for_prior_cleanup_without_cancelling_other_business(config, tmp_path):
    from app.services.mobile import MobileRuntimes
    from fastapi import HTTPException
    pool = MobileRuntimes(config, tmp_path)
    entered = asyncio.Event()
    release = asyncio.Event()
    async def prior():
        async with pool.use('isolated'):
            entered.set()
            await release.wait()
    task = asyncio.create_task(prior())
    await entered.wait()
    with pytest.raises(HTTPException) as error:
        async with pool.use('isolated'):
            pass
    assert error.value.status_code == 409  # destructive/ordinary operations remain protected
    async def next_voice():
        async with pool.use('isolated', wait_seconds=1):
            return True
    waiting = asyncio.create_task(next_voice())
    await asyncio.sleep(0)
    assert not waiting.done() and not task.done()
    release.set()
    assert await waiting
    await task
