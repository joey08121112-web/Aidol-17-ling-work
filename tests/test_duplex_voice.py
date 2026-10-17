import asyncio
from contextlib import asynccontextmanager
import json
from uuid import uuid4

import pytest

from app.domain import Mode
from app.providers.qwen_realtime import QwenRealtimeConfig
from app.providers.volc_speech import SpeechProviderError


class Native:
    def __init__(self, interrupt=False):
        self.inputs = asyncio.Queue()
        self.interrupt = interrupt
        self.utterances = 0
        self.replies = 0
        self.cancels = 0
        self.bytes = 0
        self.closed = False
        self.settings = []
        self.history = None

    async def take(self, queue, timeout):
        return await queue.get()

    async def append(self, pcm):
        self.bytes += len(pcm)
        self.utterances += 1
        item = str(self.utterances)
        for event in [
            {'type': 'input_audio_buffer.speech_started', 'item_id': item},
            {'type': 'input_audio_buffer.speech_stopped', 'item_id': item},
            {'type': 'conversation.item.input_audio_transcription.completed',
             'transcript': '请讲一个简单例子' if self.utterances == 1 else '小万，换一个更短的例子', 'item_id': item}]:
            await self.inputs.put(event)

    async def text(self, text):
        pass

    async def cancel(self):
        self.cancels += 1

    async def update(self, **settings):
        self.settings.append(settings)

    async def seed_history(self, history):
        self.history = history

    async def reply(self, instructions, schemas, call_tool):
        self.replies += 1
        number = self.replies
        assert 'recall_memory' in str(schemas) and 'plan_route' in str(schemas)
        await call_tool('current_time', {})
        yield {'text': '这是旧回复。' if number == 1 else '这是新的简短回复。'}
        yield {'audio': b'\x01\x00' * 4800}
        if self.interrupt and number == 1:
            await asyncio.sleep(60)


def setup(client, config, monkeypatch, native):
    config.qwen = QwenRealtimeConfig(api_key='offline')
    @asynccontextmanager
    async def session(*args):
        try:
            yield native
        finally:
            native.closed = True
    monkeypatch.setattr(client.app.state.qwen, 'duplex_session', session)


def endpoint():
    return '/v1/voice/stream?request_id=' + uuid4().hex + '&engine=qwen&interaction_source=continuous_call&reply_audio=1&duplex=1'


def test_preparation_has_no_turn_or_lock_and_activation_uses_current_settings(client, config, runtime, monkeypatch):
    monkeypatch.setenv('AIDOL_DAILY_REQUESTS', '1')
    native = Native()
    setup(client, config, monkeypatch, native)
    path = endpoint()
    request_id = path.split('request_id=')[1].split('&')[0]
    with client.websocket_connect(path + '&prepare=1') as ws:
        assert ws.receive_json() == {'type': 'prepared', 'expires_in': 120}
        assert not runtime.lock.locked()
        assert runtime.store.mobile_turn_status(request_id) is None
        assert native.bytes == 0 and native.replies == 0
        settings = client.get('/v1/state').json()['settings']
        settings['mode'] = 'commute'
        assert client.put('/v1/settings', json=settings).status_code == 200
        ws.send_json({'type': 'start'})
        assert ws.receive_json()['type'] == 'ready'
        assert native.history is not None
        assert any('tools' in item for item in native.settings)
        ws.send_bytes(bytes(640))
        for _ in range(30):
            event = read_event(ws)
            if event['type'] == 'result':
                assert event['result']['mode'] == 'commute'
                break
        else:
            pytest.fail('No activated result')
        ws.send_json({'type': 'end'})
        assert ws.receive_json()['type'] == 'call.done'
    assert native.closed


@pytest.mark.parametrize('command', [None, {'type': 'end'}, {'type': 'unexpected'}])
def test_unused_preparation_closes_without_turn(client, config, runtime, monkeypatch, command):
    native = Native()
    setup(client, config, monkeypatch, native)
    path = endpoint()
    request_id = path.split('request_id=')[1].split('&')[0]
    with client.websocket_connect(path + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        if command is None:
            ws.send_bytes(bytes(640))
        else:
            ws.send_json(command)
        if command != {'type': 'end'}:
            assert ws.receive_json()['type'] == 'error'
        assert ws.receive()['type'] == 'websocket.close'
    assert native.closed and native.bytes == 0
    assert runtime.store.mobile_turn_status(request_id) is None
    assert not runtime.lock.locked()


def test_preparation_rechecks_authentication_at_activation(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        assert client.post('/auth/logout').status_code == 200
        ws.send_json({'type': 'start'})
        assert ws.receive_json()['status'] == 401
        assert ws.receive()['type'] == 'websocket.close'
    assert native.closed and native.bytes == 0


def test_preparation_cannot_be_duplicated_or_activate_after_switch_to_work(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        with client.websocket_connect(endpoint() + '&prepare=1') as duplicate:
            assert duplicate.receive_json()['status'] == 409
        runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode.work}))
        ws.send_json({'type': 'start'})
        assert ws.receive_json()['status'] == 409
        assert ws.receive()['type'] == 'websocket.close'
    assert native.closed and native.bytes == 0


def test_unchanged_preparation_needs_no_second_configuration_handshake(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        ws.send_json({'type': 'start'})
        assert ws.receive_json()['type'] == 'ready'
        assert native.settings == []
        assert native.history == runtime.conversation_history()
        ws.send_json({'type': 'end'})
        assert ws.receive_json()['type'] == 'call.done'
    assert native.closed


def test_failed_activation_closes_provider_before_releasing_account(client, config, runtime, monkeypatch):
    native = Native()
    config.qwen = QwenRealtimeConfig(api_key='offline')
    closed_under_lock = []

    @asynccontextmanager
    async def session(*args):
        try:
            yield native
        finally:
            closed_under_lock.append(runtime.lock.locked())
            native.closed = True

    async def failed_update(**settings):
        raise SpeechProviderError('offline activation failure')

    monkeypatch.setattr(client.app.state.qwen, 'duplex_session', session)
    monkeypatch.setattr(native, 'update', failed_update)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        settings = runtime.store.settings()
        runtime.store.save_settings(settings.model_copy(update={'preferences': '简短回答'}))
        ws.send_json({'type': 'start'})
        assert ws.receive_json()['status'] == 502
        assert ws.receive()['type'] == 'websocket.close'
    assert native.closed and closed_under_lock == [True]
    assert not runtime.lock.locked()


def test_expired_preparation_releases_slot_without_recording_a_turn(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    original_timeout = asyncio.timeout
    monkeypatch.setattr(asyncio, 'timeout', lambda seconds: original_timeout(.02 if seconds == 120 else seconds))
    for _ in range(2):
        path = endpoint()
        request_id = path.split('request_id=')[1].split('&')[0]
        with client.websocket_connect(path + '&prepare=1') as ws:
            assert ws.receive_json()['type'] == 'prepared'
            assert ws.receive_json()['status'] == 502
            assert ws.receive()['type'] == 'websocket.close'
        assert native.closed and native.bytes == 0
        assert runtime.store.mobile_turn_status(request_id) is None


def test_leaving_during_provider_initialization_cancels_it_immediately(client, config, runtime, monkeypatch):
    native = Native()
    config.qwen = QwenRealtimeConfig(api_key='offline')
    cancelled = []

    @asynccontextmanager
    async def slow_session(*args):
        try:
            await asyncio.sleep(60)
            yield native
        finally:
            cancelled.append(True)

    monkeypatch.setattr(client.app.state.qwen, 'duplex_session', slow_session)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        ws.send_json({'type': 'end'})
        assert ws.receive()['type'] == 'websocket.close'
    assert cancelled and not runtime.lock.locked()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        ws.send_json({'type': 'end'})
        assert ws.receive()['type'] == 'websocket.close'


def test_preparation_waits_for_previous_account_cleanup(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    client.portal.call(runtime.lock.acquire)

    async def previous_call_cleanup():
        await asyncio.sleep(.1)
        runtime.lock.release()

    cleanup = client.portal.start_task_soon(previous_call_cleanup)
    with client.websocket_connect(endpoint() + '&prepare=1') as ws:
        assert ws.receive_json()['type'] == 'prepared'
        assert not runtime.lock.locked()
        ws.send_json({'type': 'end'})
        assert ws.receive()['type'] == 'websocket.close'
    cleanup.result(timeout=1)


def read_event(ws):
    frame = ws.receive()
    return {'type': 'pcm', 'size': len(frame['bytes'])} if frame.get('bytes') else json.loads(frame['text'])


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_one_persistent_call_supports_multiple_rounds_and_tools(client, config, runtime, monkeypatch, mode):
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode)}))
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        assert ws.receive_json()['duplex'] is True
        for number in range(2):
            ws.send_bytes(bytes(640))
            events = []
            for _ in range(30):
                event = read_event(ws); events.append(event)
                if event['type'] == 'result':
                    break
            result = next(e['result'] for e in events if e['type'] == 'result')
            assert result['mode'] == mode and result['speak']
            assert result['tool_results'][0]['tool'] == 'current_time'
            assert any(e['type'] == 'pcm' for e in events)
            assert any(e['type'] == 'reply.done' for e in events)
            assert not native.closed and native.bytes == (number + 1) * 640
            ws.send_json({'type': 'playback.done', 'turn': number + 1})
        ws.send_json({'type': 'end'})
        assert ws.receive_json()['type'] == 'call.done'
    assert native.replies == 2 and native.closed


def test_new_speech_interrupts_output_and_new_audio_keeps_uploading(client, config, runtime, monkeypatch):
    native = Native(interrupt=True)
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        ws.receive_json()
        ws.send_bytes(bytes(640))
        for _ in range(20):
            if read_event(ws)['type'] == 'pcm':
                break
        ws.send_bytes(bytes(640))  # microphone is still live while old audio is being spoken
        interrupted = False
        results = []
        for _ in range(60):
            event = read_event(ws)
            if event['type'] == 'input.speech_start':
                interrupted = True
            if event['type'] == 'result':
                results.append(event['result'])
                if len(results) == 2:
                    break
        assert interrupted and native.bytes == 1280
        assert results[0]['voice_interrupted'] and not results[0]['response_complete']
        assert results[1]['response'] == '这是新的简短回复。' and results[1]['response_complete']
        ws.send_json({'type': 'end'})
        assert ws.receive_json()['type'] == 'call.done'
    assert native.closed


def test_work_cannot_open_duplex_ai_channel(client, config, runtime, monkeypatch):
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode.work}))
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        assert ws.receive_json()['status'] == 409
    assert native.replies == 0


def test_text_uses_same_persistent_voice_session(client, config, runtime, monkeypatch):
    native = Native()
    setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        ws.receive_json()
        ws.send_json({'type': 'text', 'text': '请讲一个公开的小例子'})
        for _ in range(30):
            event = read_event(ws)
            if event['type'] == 'result':
                break
        assert event['result']['speak'] and not native.closed
        rows = runtime.store.list('transcripts', 10)
        assert any(row['source'] == 'text' and row['speaker'] != 'assistant' for row in rows)
        ws.send_json({'type': 'end'})
        assert ws.receive_json()['type'] == 'call.done'


@pytest.mark.asyncio
async def test_duplex_dispatcher_reads_speech_while_reply_waits_and_drops_cancelled_audio(monkeypatch):
    import base64
    from app.providers.qwen_realtime import QwenDuplexSession
    class Wire:
        def __init__(self):
            self.incoming = asyncio.Queue()
            self.sent = []
        async def recv(self):
            return json.dumps(await self.incoming.get())
        async def send(self, raw):
            event = json.loads(raw); self.sent.append(event)
            if event['type'] == 'session.update':
                await self.incoming.put({'type': 'session.updated'})
    wire = Wire()
    native = QwenDuplexSession(wire, QwenRealtimeConfig())
    await native.start()
    async def generated(): pass
    monkeypatch.setattr(native, 'create_response', generated)
    async def push(*events):
        for event in events: await wire.incoming.put(event)
    first_audio = asyncio.Event()
    async def consume():
        async def no_tool(*args): raise AssertionError('unexpected tool')
        async for event in native.reply('instructions', [], no_tool):
            if 'audio' in event: first_audio.set()
    try:
        await push({'type': 'response.created', 'response': {'id': 'old'}},
            {'type': 'response.audio.delta', 'response_id': 'old', 'delta': base64.b64encode(bytes(640)).decode()})
        task = asyncio.create_task(consume())
        await asyncio.wait_for(first_audio.wait(), 1)
        await push({'type': 'input_audio_buffer.speech_started', 'item_id': 'new-user'})
        assert (await native.take(native.inputs, 1))['type'] == 'input_audio_buffer.speech_started'
        await native.cancel(); task.cancel(); await asyncio.gather(task, return_exceptions=True)
        await push({'type': 'response.audio.delta', 'response_id': 'old', 'delta': base64.b64encode(b'xx').decode()},
            {'type': 'response.done', 'response': {'id': 'old', 'status': 'cancelled'}},
            {'type': 'response.created', 'response': {'id': 'new'}},
            {'type': 'response.audio.delta', 'response_id': 'new', 'delta': base64.b64encode(b'\x02\x00').decode()},
            {'type': 'response.audio_transcript.delta', 'response_id': 'new', 'delta': '新回答'},
            {'type': 'response.done', 'response': {'id': 'new', 'status': 'completed'}})
        async def no_tool(*args): return {}
        events = [e async for e in native.reply('instructions', [], no_tool)]
        assert [e['audio'] for e in events if 'audio' in e] == [b'\x02\x00']
        assert events[-1]['text'] == '新回答'
        assert wire.sent[0]['session']['turn_detection'] is None
        assert native.response_buffered == 0
        assert native.active is False
    finally:
        await native.close_reader()


@pytest.mark.asyncio
async def test_explicit_generation_does_not_change_asr_or_enable_automatic_interruption():
    from app.providers.qwen_realtime import QwenDuplexSession
    class Wire:
        def __init__(self): self.incoming, self.sent = asyncio.Queue(), []
        async def recv(self): return json.dumps(await self.incoming.get())
        async def send(self, raw):
            e = json.loads(raw); self.sent.append(e)
            if e['type'] == 'session.update': await self.incoming.put({'type': 'session.updated'})
            if e['type'] == 'response.create':
                await self.incoming.put({'type': 'response.created', 'response': {'id': 'manual'}})
    wire = Wire(); native = QwenDuplexSession(wire, QwenRealtimeConfig())
    await native.start()
    try:
        await native.create_response()
        assert wire.sent[-2]['session']['turn_detection'] is None
        assert wire.sent[-1]['type'] == 'response.create'
    finally: await native.close_reader()


def test_playback_ignores_non_keyword_speech_until_keyword(client, config, runtime, monkeypatch):
    class GatedNative(Native):
        async def append(self, pcm):
            self.bytes += len(pcm); self.utterances += 1
            item = str(self.utterances)
            text = ['请讲一个简单例子', '停止播放，切换工作模式', '小万，换一个更短的例子'][self.utterances - 1]
            for e in [{'type': 'input_audio_buffer.speech_started', 'item_id': item},
                      {'type': 'conversation.item.input_audio_transcription.delta', 'text': text, 'item_id': item},
                      {'type': 'conversation.item.input_audio_transcription.completed', 'transcript': text, 'item_id': item}]:
                await self.inputs.put(e)
    native = GatedNative(interrupt=True); setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        ws.receive_json(); ws.send_bytes(bytes(640))
        while read_event(ws)['type'] != 'pcm': pass
        ws.send_bytes(bytes(640)); ws.send_bytes(bytes(640))
        results = []
        while len(results) < 2:
            event = read_event(ws)
            if event['type'] == 'result': results.append(event['result'])
        assert native.replies == 2
        assert results[0]['voice_interrupted']
        assert results[1]['text'] == '换一个更短的例子'
        assert results[1]['mode'] == 'life'
        ws.send_json({'type': 'end'}); assert ws.receive_json()['type'] == 'call.done'


@pytest.mark.parametrize('text,expected', [('小万', ''), ('小万，换一个', '换一个'), ('小 万，换一个', '换一个'), ('停一下', None)])
def test_interrupt_phrase(text, expected):
    from app.services.duplex_voice import interruption_request
    assert interruption_request(text) == expected


@pytest.mark.asyncio
async def test_continuous_call_does_not_exit_or_change_mode_on_spoken_command(runtime):
    result = await runtime.turn('切换到工作模式', 'asr', 'continuous_call')
    assert result['interaction'] == 'mode_hint'
    assert result['mode'] == 'life'
    assert runtime.store.settings().mode == 'life'


@pytest.mark.asyncio
async def test_microphone_uses_asr_connection_without_interrupting_output():
    from app.providers.qwen_realtime import QwenDuplexSession
    class Wire:
        def __init__(self): self.incoming, self.sent = asyncio.Queue(), []
        async def recv(self): return json.dumps(await self.incoming.get())
        async def send(self, raw):
            event = json.loads(raw); self.sent.append(event)
            if event['type'] == 'session.update': await self.incoming.put({'type': 'session.updated'})
    class Asr:
        def __init__(self): self.incoming, self.sent = asyncio.Queue(), []
        async def receive(self, timeout):
            assert timeout is None  # Silent input has no session expiry.
            return await self.incoming.get()
        async def send(self, kind, **values): self.sent.append({'type': kind, **values})
    wire, asr = Wire(), Asr()
    native = QwenDuplexSession(wire, QwenRealtimeConfig(), asr)
    await native.start()
    try:
        native.active = True
        await native.append(bytes(640))
        await asr.incoming.put({'type': 'input_audio_buffer.speech_started', 'item_id': 'ignored'})
        assert (await native.take(native.inputs, 1))['item_id'] == 'ignored'
        assert asr.sent[0]['type'] == 'input_audio_buffer.append'
        assert not any(e['type'] in {'response.cancel', 'input_audio_buffer.append'} for e in wire.sent)
    finally: await native.close_reader()


@pytest.mark.parametrize('mode', ['life', 'commute'])
def test_noise_during_thinking_does_not_starve_the_accepted_answer(client, config, runtime, monkeypatch, mode):
    runtime.store.save_settings(runtime.store.settings().model_copy(update={'mode': Mode(mode)}))
    class NoisyNative(Native):
        def __init__(self):
            super().__init__()
            self.noise = asyncio.Event()
            self.accepted = []
        async def text(self, text): self.accepted.append(text)
        async def append(self, pcm):
            if not self.utterances:
                await super().append(pcm)
            else:
                # The user question is already accepted, but the provider has not
                # produced reply.start/audio. Repeated false VAD starts used to
                # cancel it, resulting in endless zero-audio interrupted turns.
                for i in range(20):
                    item = 'noise-' + str(i)
                    await self.inputs.put({'type': 'input_audio_buffer.speech_started', 'item_id': item})
                    await self.inputs.put({'type': 'conversation.item.input_audio_transcription.completed',
                                           'item_id': item, 'transcript': '背景声音'})
                self.noise.set()
        async def reply(self, *args):
            await asyncio.wait_for(self.noise.wait(), 2)
            await asyncio.sleep(.02)
            async for event in super().reply(*args): yield event
    native = NoisyNative(); setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        ws.receive_json(); ws.send_bytes(bytes(640))
        while read_event(ws)['type'] != 'thinking': pass
        ws.send_bytes(bytes(640))
        frames = []
        while True:
            event = read_event(ws); frames.append(event)
            if event['type'] == 'result': break
        assert event['result']['response_complete']
        assert not event['result'].get('voice_interrupted')
        assert any(e['type'] == 'pcm' for e in frames)
        assert not any(e['type'] == 'reply.interrupted' for e in frames)
        assert native.accepted == ['请讲一个简单例子'] and native.replies == 1
        ws.send_json({'type': 'end'}); assert ws.receive_json()['type'] == 'call.done'


def test_cloud_vad_threshold_is_bounded():
    from dataclasses import replace
    config = QwenRealtimeConfig(api_key='offline')
    assert config.asr_vad_threshold == .65 and config.ready
    for invalid in [-1, 0, 1, 5, float('nan')]:
        assert not replace(config, asr_vad_threshold=invalid).ready


def test_keyword_still_interrupts_during_thinking(client, config, runtime, monkeypatch):
    class ThinkingNative(Native):
        async def reply(self, instructions, schemas, call_tool):
            self.replies += 1
            if self.replies == 1:
                await asyncio.sleep(10)
            yield {'text': '新问题的回答'}
            yield {'audio': bytes(640)}
    native = ThinkingNative(); setup(client, config, monkeypatch, native)
    with client.websocket_connect(endpoint()) as ws:
        ws.receive_json(); ws.send_bytes(bytes(640))
        while read_event(ws)['type'] != 'thinking': pass
        ws.send_bytes(bytes(640))  # the fake's second utterance starts with 小万
        results = []
        while len(results) < 2:
            event = read_event(ws)
            if event['type'] == 'result': results.append(event['result'])
        assert results[0]['voice_interrupted']
        assert results[1]['response_complete']
        ws.send_json({'type': 'end'}); assert ws.receive_json()['type'] == 'call.done'
