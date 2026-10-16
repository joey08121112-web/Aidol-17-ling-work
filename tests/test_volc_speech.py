"""Protocol and lifecycle checks without credentials or billable API calls."""
import asyncio
import gzip
import io
import json
import struct
import wave

import httpx
import pytest

from app.providers import volc_speech as speech


def asr_response(body, *, flags=1, sequence=1, compressed=True):
    payload = json.dumps(body).encode()
    if compressed:
        payload = gzip.compress(payload)
    return bytes((0x11, 0x90 | flags, 0x11 if compressed else 0x10, 0)) + struct.pack(">iI", sequence, len(payload)) + payload


def tts_response(event, identity="session", payload=b"{}", *, kind=9, with_id=True):
    frame = bytes((0x11, kind << 4 | 4, 0x10, 0)) + struct.pack(">I", event)
    if with_id:
        encoded = identity.encode()
        frame += struct.pack(">I", len(encoded)) + encoded
    return frame + struct.pack(">I", len(payload)) + payload


def test_config_uses_reference_environment_without_revealing_credentials():
    cfg = speech.VolcSpeechConfig.from_env({"SPEECH_VOLC_APP_KEY": "private-app",
        "SPEECH_VOLC_ACCESS_KEY": "private-access", "TTS_VOLC_APP_KEY": "tts-app",
        "TTS_VOLC_ACCESS_KEY": "tts-access", "TTS_VOLC_VOICE_TYPE": "voice-a"})
    assert cfg.asr_ready and cfg.tts_ready
    assert cfg.tts_voice == "voice-a"
    assert "private" not in repr(cfg) and "tts-access" not in repr(cfg)
    assert cfg.tts_resource_id == "seed-tts-1.0"


def test_asr_request_carries_mono_pcm_contract():
    frame = speech.build_asr_start(speech.VolcSpeechConfig(), "test-user")
    assert frame[:4] == b"\x11\x10\x11\0"
    assert struct.unpack_from(">I", frame, 4)[0] == len(frame) - 8
    payload = json.loads(gzip.decompress(frame[8:]))
    assert payload["audio"]["rate"] == 16000
    assert payload["audio"]["bits"] == 16
    assert payload["audio"]["channel"] == 1
    assert payload["request"]["result_type"] == "full"
    assert payload["request"]["enable_nonstream"] is True
    assert payload["request"]["end_window_size"] == 800
    assert "vad_segment_duration" not in payload["request"]


def test_legacy_asr_endpoint_does_not_receive_unsupported_second_pass():
    cfg = speech.VolcSpeechConfig(asr_url="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")
    payload = json.loads(gzip.decompress(speech.build_asr_start(cfg)[8:]))
    assert "enable_nonstream" not in payload["request"]


def test_definite_utterance_is_distinct_from_session_final():
    result = speech.parse_asr_packet(asr_response({"result": {"text": "你好。", "utterances": [
        {"text": "你好。", "definite": True, "start_time": 0, "end_time": 600}]}}))
    assert result.final is False
    assert result.utterances[0]["definite"] is True


def test_asr_final_audio_and_alignment():
    frame = speech.build_asr_audio(b"\x01\x02", final=True)
    assert frame[1] == 0x22
    assert gzip.decompress(frame[8:]) == b"\x01\x02"
    with pytest.raises(ValueError, match="complete"):
        speech.build_asr_audio(b"x")


@pytest.mark.parametrize("flags,sequence,final", [(1, 1, False), (3, -3, True), (0, 1, False)])
def test_asr_response_preserves_utterance_metadata(flags, sequence, final):
    utterance = {"text": "下站到了", "definite": True, "start_time": 120, "speaker": "unknown"}
    result = speech.parse_asr_packet(asr_response({"result": {"text": "下站到了", "utterances": [utterance]}}, flags=flags, sequence=sequence))
    assert result.text == "下站到了"
    assert result.final is final
    assert result.utterances == (utterance,)
    assert result.sequence == sequence


def test_asr_response_without_sequence():
    payload = b'{"result":{"text":"ok"}}'
    result = speech.parse_asr_packet(b"\x11\x90\x10\0" + struct.pack(">I", len(payload)) + payload)
    assert result.text == "ok"


@pytest.mark.parametrize("packet", [b"", b"\x11\x90\x11\0", b"\x10\x90\x11\0", b"\x11\x93\x11\0" + b"\0" * 8, asr_response({"result": {}})[:-1]])
def test_asr_rejects_malformed_frame(packet):
    with pytest.raises(speech.SpeechProviderError):
        speech.parse_asr_packet(packet)


def test_errors_never_echo_remote_body():
    payload = b"secret echoed by provider"
    packet = b"\x11\xf0\x10\0" + struct.pack(">II", 45000000, len(payload)) + payload
    for parser in (speech.parse_asr_packet, speech.parse_tts_packet):
        with pytest.raises(speech.SpeechProviderError) as error:
            parser(packet)
        assert "45000000" in str(error.value)
        assert "secret" not in str(error.value)


def test_tts_voice_resource_mismatch_has_actionable_fixed_message():
    payload = b'{"message":"resource ID is mismatched with speaker related resource; secret"}'
    packet = b"\x11\xf0\x10\0" + struct.pack(">II", 55000000, len(payload)) + payload
    with pytest.raises(speech.SpeechProviderError) as error:
        speech.parse_tts_packet(packet)
    assert "seed-tts-1.0" in str(error.value)
    assert "secret" not in str(error.value)


def test_missing_streaming_entitlement_does_not_echo_provider_body():
    from types import SimpleNamespace
    error = RuntimeError("upstream error")
    error.response = SimpleNamespace(status_code=403, body=b'"secret requested resource not granted"')
    result = speech.VolcSpeechProvider._connection_error("ASR streaming", error)
    assert "resource is not granted" in str(result)
    assert "secret" not in str(result)


def test_tts_client_frame_uses_utf8_session_size():
    frame = speech.build_tts_frame(200, {"text": "你好"}, "中文")
    assert frame[:4] == b"\x11\x14\x10\0"
    assert struct.unpack_from(">I", frame, 8)[0] == 6
    assert json.loads(frame[22:]) == {"text": "你好"}


@pytest.mark.parametrize("with_id", [True, False])
def test_tts_connection_ack_variants(with_id):
    packet = speech.parse_tts_packet(tts_response(50, "connection-id", with_id=with_id))
    assert packet.event == 50
    assert not packet.is_audio


def test_tts_audio_and_invalid_lengths():
    packet = tts_response(352, payload=b"\x01\x02", kind=11)
    parsed = speech.parse_tts_packet(packet)
    assert parsed.is_audio and parsed.payload == b"\x01\x02"
    with pytest.raises(speech.SpeechProviderError):
        speech.parse_tts_packet(packet[:-1])
    with pytest.raises(speech.SpeechProviderError):
        speech.parse_tts_packet(packet + b"extra")
    with pytest.raises(speech.SpeechProviderError):
        speech.parse_tts_packet(tts_response(153))


def test_pcm_wav_is_browser_playable():
    with wave.open(io.BytesIO(speech.pcm_to_wav(b"\x01\x02" * 500)), "rb") as wav:
        assert wav.getnchannels() == 1
        assert wav.getsampwidth() == 2
        assert wav.getframerate() == 24000
        assert wav.getnframes() == 500


class FakeSocket:
    def __init__(self):
        self.responses = asyncio.Queue()
        self.sent = []
        self.closed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        self.closed = True

    async def send(self, frame):
        self.sent.append(frame)
        event = struct.unpack_from(">I", frame, 4)[0]
        if event == 1:
            self.responses.put_nowait(tts_response(50, "connect"))
        elif event == 100:
            length = struct.unpack_from(">I", frame, 8)[0]
            self.session_id = frame[12:12 + length].decode()
            self.responses.put_nowait(tts_response(150, self.session_id))
        elif event == 102:
            self.responses.put_nowait(tts_response(352, self.session_id, b"\0\0" * 12, kind=11))
            self.responses.put_nowait(tts_response(152, self.session_id))

    async def recv(self):
        return await self.responses.get()


@pytest.mark.asyncio
async def test_tts_complete_handshake_and_cleanup(monkeypatch):
    socket = FakeSocket()
    monkeypatch.setattr(speech, "connect", lambda *a, **kw: socket)
    provider = speech.VolcSpeechProvider(speech.VolcSpeechConfig(tts_app_key="app", tts_access_key="key"))
    audio = await provider.synthesize_wav("你好")
    assert audio.startswith(b"RIFF")
    assert socket.closed
    assert [struct.unpack_from(">I", frame, 4)[0] for frame in socket.sent] == [1, 100, 200, 102, 2]


@pytest.mark.asyncio
async def test_asr_sender_failure_is_propagated_and_socket_closed(monkeypatch):
    socket = FakeSocket()
    monkeypatch.setattr(speech, "connect", lambda *a, **kw: socket)
    async def chunks():
        yield b"bad"
    provider = speech.VolcSpeechProvider(speech.VolcSpeechConfig(asr_api_key="key", timeout=0.1))
    with pytest.raises(ValueError, match="complete"):
        _ = [result async for result in provider.stream_asr(chunks())]
    assert socket.closed


@pytest.mark.asyncio
async def test_http_asr_auth_and_request(monkeypatch):
    real_client = httpx.AsyncClient
    def respond(request):
        assert request.headers["X-Api-Key"] == "test-api"
        assert request.headers["X-Api-Resource-Id"] == "volc.bigasr.auc_turbo"
        assert request.headers["X-Api-Sequence"] == "-1"
        body = json.loads(request.content)
        assert body["audio"]["data"] == "YWJjZA=="
        return httpx.Response(200, headers={"X-Api-Status-Code": "20000000"}, json={"result": {"text": "测试"}})
    monkeypatch.setattr(speech.httpx, "AsyncClient", lambda **kw: real_client(transport=httpx.MockTransport(respond)))
    provider = speech.VolcSpeechProvider(speech.VolcSpeechConfig(asr_api_key="test-api"))
    result = await provider.recognize(b"abcd")
    assert result.text == "测试" and result.final


@pytest.mark.asyncio
async def test_asr_full_stream_flushes_and_preserves_partials(monkeypatch):
    class AsrSocket(FakeSocket):
        async def send(self, frame):
            self.sent.append(frame)
            if frame[1] == 0x20:
                self.responses.put_nowait(asr_response({"result": {"text": "你好"}}))
            elif frame[1] == 0x22:
                self.responses.put_nowait(asr_response({"result": {"text": "你好世界"}}, flags=3, sequence=-2))
    socket = AsrSocket()
    monkeypatch.setattr(speech, "connect", lambda *a, **kw: socket)
    async def chunks():
        yield b"\0\0" * 320
    provider = speech.VolcSpeechProvider(speech.VolcSpeechConfig(asr_api_key="key"))
    results = [result async for result in provider.stream_asr(chunks())]
    assert [result.text for result in results] == ["你好", "你好世界"]
    assert [result.final for result in results] == [False, True]
    assert socket.closed
    assert [frame[1] for frame in socket.sent] == [0x10, 0x20, 0x22]


@pytest.mark.asyncio
async def test_closing_asr_generator_stops_capture(monkeypatch):
    class AsrSocket(FakeSocket):
        async def send(self, frame):
            self.sent.append(frame)
            if frame[1] == 0x20:
                self.responses.put_nowait(asr_response({"result": {"text": "你好"}}))
    socket = AsrSocket()
    monkeypatch.setattr(speech, "connect", lambda *a, **kw: socket)
    capture_stopped = asyncio.Event()
    async def chunks():
        try:
            yield b"\0\0"
            await asyncio.Event().wait()
        finally:
            capture_stopped.set()
    provider = speech.VolcSpeechProvider(speech.VolcSpeechConfig(asr_api_key="key"))
    generator = provider.stream_asr(chunks())
    assert (await anext(generator)).text == "你好"
    await generator.aclose()
    assert capture_stopped.is_set()
    assert socket.closed
