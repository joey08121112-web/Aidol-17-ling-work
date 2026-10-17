"""Android PCM upload and synthesized WAV contract, without provider calls."""
from dataclasses import replace
import io
from uuid import uuid4
import wave

import pytest

from app.providers.volc_speech import AsrResult, SpeechProviderError


def enable_speech(client):
    config = client.app.state.runtimes.config
    config.speech = replace(config.speech, asr_api_key="offline-asr-key",
                           tts_app_key="offline-app", tts_access_key="offline-tts-key")


def wav_bytes():
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24000)
        wav.writeframes(b"\0" * 640)
    return output.getvalue()


def voice(client, payload, content_type="audio/pcm"):
    return client.post("/v1/voice?request_id=" + uuid4().hex, content=payload,
                       headers={"Content-Type": content_type})


def test_speech_missing_config_returns_actionable_503(client):
    assert client.post("/v1/speech", json={"text": "测试"}).status_code == 503
    assert voice(client, b"\0" * 3200).status_code == 503
    capabilities = client.get("/v1/state").json()["capabilities"]
    assert not capabilities["asr"] and not capabilities["tts"]


def test_tts_returns_wav_and_rejects_empty_or_oversized_text(client, monkeypatch):
    enable_speech(client)
    expected = wav_bytes()
    calls = []

    async def synthesize(text):
        calls.append(text)
        return expected

    monkeypatch.setattr(client.app.state.speech, "synthesize_wav", synthesize)
    response = client.post("/v1/speech", json={"text": "你好，小聆"})
    assert response.status_code == 200 and response.content == expected
    assert response.headers["content-type"] == "audio/wav"
    assert response.headers["cache-control"] == "no-store"
    for text in ["", "长" * 1501]:
        assert client.post("/v1/speech", json={"text": text}).status_code == 422
    assert calls == ["你好，小聆"]


def test_asr_records_only_final_transcript_and_extracts_memory(client, runtime, monkeypatch):
    enable_speech(client)
    client.put("/v1/settings", json={"auto_mode": False, "mode": "work"})
    received = []

    async def asr(chunks):
        received.extend([chunk async for chunk in chunks])
        yield AsrResult(text="我负责", final=False)
        yield AsrResult(text="我负责完成接口测试", final=True)

    monkeypatch.setattr(client.app.state.speech, "stream_asr", asr)
    payload = b"\0" * 6400
    response = voice(client, payload)
    assert response.status_code == 200
    assert response.json()["text"] == "我负责完成接口测试"
    assert response.json()["speak"] is False
    assert b"".join(received) == payload
    transcripts = [row for row in runtime.store.list("transcripts") if row["speaker"] != "assistant"]
    assert len(transcripts) == 1 and transcripts[0]["source"] == "asr"
    assert transcripts[0]["text"] == "我负责完成接口测试"
    assert len(runtime.store.list("memories")) == 1


@pytest.mark.parametrize("payload,content_type,status", [
    (b"", "audio/pcm", 422),
    (b"\0" * 3198, "audio/pcm", 422),
    (b"\0" * 3201, "audio/pcm", 422),
    (b"webm-content", "audio/webm", 415),
    (wav_bytes(), "audio/wav", 415),
    (b"\0" * 1920002, "audio/pcm", 413),
], ids=["empty", "short", "odd", "webm", "wav", "oversized"])
def test_invalid_asr_input_never_calls_vendor(client, monkeypatch, payload, content_type, status):
    enable_speech(client)

    def forbidden(*args):
        pytest.fail("invalid audio reached vendor")

    monkeypatch.setattr(client.app.state.speech, "stream_asr", forbidden)
    assert voice(client, payload, content_type).status_code == status


def test_provider_failures_return_sanitized_bad_gateway(client, monkeypatch):
    enable_speech(client)

    async def failing_tts(text):
        raise SpeechProviderError("synthetic vendor details should stay private")

    monkeypatch.setattr(client.app.state.speech, "synthesize_wav", failing_tts)
    response = client.post("/v1/speech", json={"text": "测试失败"})
    assert response.status_code == 502
    assert "synthetic vendor" not in response.text
