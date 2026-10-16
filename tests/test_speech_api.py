from dataclasses import replace
import io
import wave

import pytest

from app.providers.volc_speech import AsrResult, SpeechProviderError


def enable_speech(client):
    config = client.app.state.agent.config
    config.speech = replace(config.speech, asr_api_key="offline-asr-key",
                           tts_app_key="offline-app", tts_access_key="offline-tts-key")


def wav_bytes(rate=16000, channels=1, width=2):
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(width)
        wav.setframerate(rate)
        wav.writeframes(b"\0" * (320 * channels * width))
    return output.getvalue()


def test_speech_missing_config_returns_actionable_503(client):
    assert client.post("/api/speech/synthesize", json={"text": "测试"}).status_code == 503
    assert client.post("/api/speech/transcribe", content=b"\0\0", headers={"Content-Type": "audio/pcm"}).status_code == 503
    capabilities = client.get("/api/state").json()["capabilities"]
    assert not capabilities["asr"] and not capabilities["tts"]


def test_tts_returns_wav_and_rejects_empty_or_oversized_text(client, monkeypatch):
    enable_speech(client)
    expected = wav_bytes(rate=24000)
    calls = []

    async def synthesize(text):
        calls.append(text)
        return expected

    monkeypatch.setattr(client.app.state.speech, "synthesize_wav", synthesize)
    response = client.post("/api/speech/synthesize", json={"text": "你好，小聆"})
    assert response.status_code == 200 and response.content == expected
    assert response.headers["content-type"] == "audio/wav"
    assert response.headers["cache-control"] == "no-store"
    for text in ["", "长" * 1501]:
        assert client.post("/api/speech/synthesize", json={"text": text}).status_code == 422
    assert calls == ["你好，小聆"]


@pytest.mark.parametrize("kind", ["pcm", "wav"])
def test_asr_records_final_transcript_and_extracts_memory(client, monkeypatch, kind):
    enable_speech(client)
    received = []

    async def asr(chunks):
        received.extend([chunk async for chunk in chunks])
        yield AsrResult(text="我负责", final=False)
        yield AsrResult(text="我负责完成接口测试", final=True)

    monkeypatch.setattr(client.app.state.speech, "stream_asr", asr)
    payload = b"\0\0" * 320 if kind == "pcm" else wav_bytes()
    response = client.post("/api/speech/transcribe", content=payload,
                           headers={"Content-Type": "audio/pcm" if kind == "pcm" else "audio/wav"})
    assert response.status_code == 200
    assert response.json()["text"] == "我负责完成接口测试"
    assert b"".join(received) == b"\0\0" * 320
    transcripts = client.app.state.store.list("transcripts")
    assert len(transcripts) == 1 and transcripts[0]["source"] == "asr"
    assert len(client.app.state.store.list("memories")) == 1


@pytest.mark.parametrize("payload, content_type, status", [
    (b"", "audio/pcm", 422),
    (b"\0", "audio/pcm", 422),
    (b"webm-content", "audio/webm", 415),
    (b"RIFFbroken", "audio/wav", 422),
    (wav_bytes(rate=24000), "audio/wav", 422),
    (wav_bytes(channels=2), "audio/wav", 422),
    (wav_bytes(width=1), "audio/wav", 422),
    (b"\0" * (2 * 1024 * 1024 + 1), "audio/pcm", 413),
], ids=["empty", "odd", "webm", "broken-wav", "wrong-rate", "stereo", "8-bit", "oversized"])
def test_invalid_asr_input_never_calls_vendor(client, monkeypatch, payload, content_type, status):
    enable_speech(client)

    def forbidden(*args):
        pytest.fail("invalid audio reached vendor")

    monkeypatch.setattr(client.app.state.speech, "stream_asr", forbidden)
    assert client.post("/api/speech/transcribe", content=payload,
                       headers={"Content-Type": content_type}).status_code == status


def test_provider_failures_return_sanitized_bad_gateway(client, monkeypatch):
    enable_speech(client)

    async def failing_tts(text):
        raise SpeechProviderError("synthetic vendor details should stay private")

    monkeypatch.setattr(client.app.state.speech, "synthesize_wav", failing_tts)
    response = client.post("/api/speech/synthesize", json={"text": "测试失败"})
    assert response.status_code == 502
    assert "synthetic vendor" not in response.text


def test_flash_asr_uses_complete_pcm_without_streaming_permission(client, monkeypatch):
    enable_speech(client)
    client.app.state.agent.config.asr_mode = "flash"
    received = []

    async def recognize(pcm, *, format, codec):
        received.append((pcm, format, codec))
        return AsrResult(text="我需要完成接口联调", final=True)

    monkeypatch.setattr(client.app.state.speech, "recognize", recognize)
    response = client.post("/api/speech/transcribe", content=wav_bytes(), headers={"Content-Type": "audio/wav"})
    assert response.status_code == 200
    assert response.json()["text"] == "我需要完成接口联调"
    assert received == [(b"\0\0" * 320, "pcm", "raw")]
