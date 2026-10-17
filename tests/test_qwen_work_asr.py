import base64
import io
import json
import wave

import httpx
import pytest

from app.providers.qwen_work_asr import QwenWorkAsrConfig, QwenWorkAsrProvider
from app.providers.volc_speech import SpeechProviderError


def test_same_service_credential_reuse_only(monkeypatch):
    monkeypatch.delenv('QWEN_WORK_ASR_API_KEY', raising=False)
    monkeypatch.delenv('QWEN_WORK_ASR_URL', raising=False)
    monkeypatch.setenv('QWEN_OMNI_API_KEY', 'offline-omni')
    monkeypatch.setenv('QWEN_OMNI_WS_URL', 'wss://maas.qianwenaiapi.com/api-ws/v1/realtime')
    assert QwenWorkAsrConfig.from_env().api_key == 'offline-omni'
    monkeypatch.setenv('QWEN_WORK_ASR_URL', 'https://other.example/asr')
    assert not QwenWorkAsrConfig.from_env().ready
    monkeypatch.setenv('QWEN_WORK_ASR_API_KEY', 'offline-dedicated')
    assert QwenWorkAsrConfig.from_env().api_key == 'offline-dedicated'


@pytest.mark.asyncio
async def test_work_asr_wav_diarization_preserves_original(monkeypatch):
    def respond(request):
        body = json.loads(request.content)
        assert body['model'] == 'qwen-audio-3.1-asr-flash'
        assert body['parameters']['speaker_diarization_enabled'] is True
        data = body['input']['messages'][0]['content'][0]['input_audio']['data']
        with wave.open(io.BytesIO(base64.b64decode(data.split(',')[1]))) as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (16000, 1, 2)
            assert wav.readframes(1600) == b'\0' * 3200
        return httpx.Response(200, json={'output': {'text': '嗯，方案甲。不同意。', 'sentences': [
            {'text': '嗯，方案甲。', 'speaker_id': 0, 'begin_time': 0, 'end_time': 500},
            {'text': '不同意。', 'speaker_id': 1, 'begin_time': 500, 'end_time': 900}]}})
    client = httpx.AsyncClient
    monkeypatch.setattr('app.providers.qwen_work_asr.httpx.AsyncClient',
                        lambda **kwargs: client(transport=httpx.MockTransport(respond)))
    async def chunks():
        yield b'\0' * 3200
    results = [value async for value in QwenWorkAsrProvider(QwenWorkAsrConfig(api_key='offline')).stream_asr(chunks())]
    assert results[0].text == '嗯，方案甲。不同意。'
    assert results[0].utterances[1]['speaker_id'] == 1


@pytest.mark.asyncio
async def test_service_no_words_is_silence_receipt(monkeypatch):
    client = httpx.AsyncClient
    monkeypatch.setattr('app.providers.qwen_work_asr.httpx.AsyncClient', lambda **kwargs:
                        client(transport=httpx.MockTransport(lambda _: httpx.Response(400, json={
                            'code': 'CLIENT_ERROR', 'message': 'ASR_RESPONSE_HAVE_NO_WORDS'}))))
    async def chunks():
        yield bytes(3200)
    results = [r async for r in QwenWorkAsrProvider(QwenWorkAsrConfig(api_key='offline')).stream_asr(chunks())]
    assert len(results) == 1 and results[0].text == '' and results[0].final


@pytest.mark.asyncio
@pytest.mark.parametrize('status,payload', [(401, {'message': 'secret'}), (200, {'output': {}}),
                                          (200, {'output': {'text': None}})])
async def test_work_asr_errors_are_sanitized(monkeypatch, status, payload):
    client = httpx.AsyncClient
    monkeypatch.setattr('app.providers.qwen_work_asr.httpx.AsyncClient', lambda **kwargs:
                        client(transport=httpx.MockTransport(lambda _: httpx.Response(status, json=payload))))
    async def chunks():
        yield b'\0' * 3200
    with pytest.raises(SpeechProviderError, match='^Qwen work ASR failed$'):
        _ = [value async for value in QwenWorkAsrProvider(QwenWorkAsrConfig(api_key='offline')).stream_asr(chunks())]
