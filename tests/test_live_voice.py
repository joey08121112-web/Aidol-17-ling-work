"""Live capture contract: mocked providers, real authenticated account runtimes."""
import asyncio
import struct
from dataclasses import replace
from uuid import uuid4

import pytest
from starlette.websockets import WebSocketDisconnect

from app.providers.volc_speech import AsrResult, SpeechProviderError


def prepare(client, config, monkeypatch):
    config.speech = replace(config.speech, asr_api_key='offline', tts_app_key='offline', tts_access_key='offline')
    async def asr(chunks):
        async for chunk in chunks:
            yield AsrResult('你好', final=False)
        yield AsrResult('你好', final=True)
    monkeypatch.setattr(client.app.state.speech, 'stream_asr', asr)


def result(ws):
    for _ in range(20):
        message = ws.receive_json()
        if message['type'] != 'transcript.partial':
            return message
    raise AssertionError('too many partials')


def test_live_partial_arrives_before_end_and_turn_is_single_use(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    turn_id = uuid4().hex
    path = '/v1/voice/stream?request_id=' + turn_id + '&interaction_source=direct_question'
    with client.websocket_connect(path) as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(b'\x01\x00' * 1600)
        assert ws.receive_json() == {'type': 'transcript.partial', 'text': '你好'}
        status = client.get('/v1/turns/' + turn_id).json()
        assert status['stage'] == 'streaming' and status['result'] is None
        ws.send_json({'type': 'end'})
        reply = result(ws)
        assert reply['type'] == 'result' and reply['result']['text'] == '你好'
    assert client.get('/v1/turns/' + turn_id).json()['status'] == 'completed'
    with client.websocket_connect(path) as ws:
        assert ws.receive_json()['status'] == 409
    assert len(client.get('/v1/state').json()['transcripts']) == 2


@pytest.mark.parametrize('frame', [b'x', b'\x00' * 32002, b''], ids=['odd', 'oversized', 'empty'])
def test_invalid_live_frame_never_enters_business(client, config, monkeypatch, frame):
    prepare(client, config, monkeypatch)
    turn_id = uuid4().hex
    with client.websocket_connect('/v1/voice/stream?request_id=' + turn_id) as ws:
        ws.receive_json()
        ws.send_bytes(frame)
        assert result(ws)['type'] == 'error'
    assert not client.get('/v1/state').json()['transcripts']
    assert client.get('/v1/turns/' + turn_id).json()['status'] == 'failed'


def test_live_disconnect_cancels_provider_and_releases_account(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    stopped = []
    async def asr(chunks):
        try:
            async for _ in chunks:
                yield AsrResult('partial', final=False)
        finally:
            stopped.append(True)
    monkeypatch.setattr(client.app.state.speech, 'stream_asr', asr)
    with client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex) as ws:
        ws.receive_json()
        ws.send_bytes(b'\x00' * 3200)
        ws.receive_json()
    assert stopped
    assert not client.get('/v1/state').json()['transcripts']


def test_live_requires_header_auth(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    with pytest.raises(WebSocketDisconnect) as error:
        with client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex, headers={'Authorization': ''}):
            pass
    assert error.value.code == 4401


def test_live_revoked_session_cannot_commit(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    with client.websocket_connect('/v1/voice/stream?request_id=' + uuid4().hex) as ws:
        ws.receive_json()
        ws.send_bytes(b'\x00' * 3200)
        ws.receive_json()
        assert client.post('/auth/logout').status_code == 200
        ws.send_json({'type': 'end'})
        assert result(ws)['status'] == 401


def test_live_id_is_scoped_to_account(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    turn_id = uuid4().hex
    with client.websocket_connect('/v1/voice/stream?request_id=' + turn_id) as ws:
        ws.receive_json(); ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        assert result(ws)['type'] == 'result'
    account = client.post('/auth/register', json={'username': 'other', 'password': 'test-password-123'}).json()
    headers = {'Authorization': 'Bearer ' + account['token']}
    assert client.get('/v1/turns/' + turn_id, headers=headers).status_code == 404
    with client.websocket_connect('/v1/voice/stream?request_id=' + turn_id, headers=headers) as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(b'\x00' * 3200); ws.send_json({'type': 'end'})
        assert result(ws)['type'] == 'result'


def test_streamed_tts_has_success_trailer(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    async def tts(text):
        yield b'\x01\x00' * 8
        yield b'\x02\x00' * 4
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    response = client.post('/v1/speech/stream', json={'text': '测试'})
    assert response.status_code == 200 and response.headers['x-aidol-audio-rate'] == '24000'
    assert response.content == struct.pack('>I', 16) + b'\x01\x00' * 8 + struct.pack('>I', 8) + b'\x02\x00' * 4 + b'\x00' * 4
    assert client.get('/v1/state').status_code == 200


def test_streamed_tts_failure_has_no_success_trailer(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    async def tts(text):
        yield b'\x01\x00'
        raise SpeechProviderError('vendor secret')
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    response = client.post('/v1/speech/stream', json={'text': '测试'})
    assert response.content == struct.pack('>I', 2) + b'\x01\x00'
    assert client.get('/v1/state').status_code == 200


def test_streamed_tts_failure_before_headers_is_sanitized(client, config, monkeypatch):
    prepare(client, config, monkeypatch)
    async def tts(text):
        raise SpeechProviderError('vendor secret')
        yield b''
    monkeypatch.setattr(client.app.state.speech, 'stream_tts', tts)
    response = client.post('/v1/speech/stream', json={'text': '测试'})
    assert response.status_code == 502 and 'secret' not in response.text
    assert client.get('/v1/state').status_code == 200
