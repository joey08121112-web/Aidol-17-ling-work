import asyncio
from dataclasses import replace
import json
import struct

import pytest

from app.domain import Settings
from app.providers.volc_speech import AsrResult, SpeechProviderError
from app.services.cascade import UtteranceSegmenter, pcm_rms, serve_cascade


VOICE = struct.pack("<h", 8192) * 320  # 20 ms, RMS .25
SILENCE = b"\0\0" * 320


class Socket:
    def __init__(self, hello=None):
        self.hello = hello or {"type": "start"}
        self.input = asyncio.Queue()
        self.events = []
        self.audio = []
        self.closed = []
        self.changed = asyncio.Event()

    async def accept(self):
        pass

    async def receive_json(self):
        return self.hello

    async def receive(self):
        return await self.input.get()

    async def send_json(self, event):
        self.events.append(event)
        self.changed.set()

    async def send_bytes(self, pcm):
        self.audio.append(pcm)
        self.changed.set()

    async def close(self, code=1000):
        self.closed.append(code)

    def pcm(self, data):
        self.input.put_nowait({"type": "websocket.receive", "bytes": data})

    def stop(self):
        self.input.put_nowait({"type": "websocket.receive", "text": json.dumps({"type": "stop"})})

    async def until(self, condition):
        async with asyncio.timeout(2):
            while not condition():
                self.changed.clear()
                await self.changed.wait()


class Speech:
    def __init__(self):
        self.received = []
        self.spoken = []
        self.calls = []

    async def recognize(self, pcm, *, format, codec):
        self.calls.append("flash")
        self.received.append(pcm)
        assert format == "pcm" and codec == "raw"
        return AsrResult(text="我喜欢清淡的食物", final=True)

    async def stream_asr(self, chunks):
        self.calls.append("stream")
        self.received.append(b"".join([pcm async for pcm in chunks]))
        yield AsrResult(text="我喜欢清淡的食物", final=True)

    async def stream_tts(self, text):
        self.spoken.append(text)
        yield b"\1\0" * 100


@pytest.fixture
def speech(config, monkeypatch):
    config.speech = replace(config.speech, asr_api_key="offline-asr", tts_app_key="offline-app", tts_access_key="offline-tts")
    value = Speech()
    monkeypatch.setattr("app.services.cascade.VolcSpeechProvider", lambda config: value)
    return value


def test_segmenter_preserves_preroll_and_closes_after_800ms_silence():
    vad = UtteranceSegmenter(.025)
    for _ in range(30):
        assert vad.push(SILENCE) == (False, None)
    started, utterance = vad.push(VOICE)
    assert started and utterance is None
    for _ in range(39):
        assert vad.push(SILENCE)[1] is None
    utterance = vad.push(SILENCE)[1]
    assert utterance == SILENCE * 15 + VOICE + SILENCE * 40
    assert vad.flush() is None


def test_vad_rms_and_long_utterance_are_bounded():
    assert pcm_rms(SILENCE) == 0
    assert pcm_rms(VOICE) == .25
    vad = UtteranceSegmenter(.025)
    utterances = []
    for _ in range(751):
        _, complete = vad.push(VOICE)
        if complete:
            utterances.append(complete)
    assert len(utterances) == 1
    assert len(utterances[0]) == 15 * 16000 * 2
    assert len(vad.flush()) == 640


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["stream", "flash"])
async def test_cascade_runs_asr_agent_tts_and_closes_all_resources(agent, store, config, speech, mode):
    config.asr_mode = mode
    store.save_settings(Settings(auto_mode=False, mode="life"))
    websocket = Socket()
    serving = asyncio.create_task(serve_cascade(websocket, agent, config))
    await websocket.until(lambda: any(event["type"] == "ready" for event in websocket.events))
    websocket.pcm(VOICE)
    websocket.pcm(SILENCE * 40)
    await websocket.until(lambda: any(event["type"] == "response.done" for event in websocket.events))
    websocket.stop()
    await asyncio.wait_for(serving, 2)
    assert speech.calls == [mode]
    assert speech.received == [VOICE + SILENCE * 40]
    assert speech.spoken and websocket.audio
    assert store.list("transcripts")[0]["speaker"] == "现场发言（未分离声纹）"
    assert len(store.list("memories")) == 1
    assert agent.audio_clients == 0 and websocket.closed


@pytest.mark.asyncio
async def test_work_mode_retains_transcript_without_tts(agent, store, config, speech):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    websocket = Socket()
    websocket.pcm(VOICE)
    websocket.stop()  # Stop flushes the final short utterance without playing it.
    await asyncio.wait_for(serve_cascade(websocket, agent, config), 2)
    assert speech.received == [VOICE]
    assert len(store.list("transcripts")) == 1
    assert speech.spoken == [] and websocket.audio == []
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_new_speech_interrupts_and_discards_old_tts(agent, store, config, speech):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    gate = asyncio.Event()
    tts_closed = asyncio.Event()

    async def tts(text):
        try:
            yield b"\1\0"
            await gate.wait()
            yield b"\2\0"
        finally:
            tts_closed.set()

    speech.stream_tts = tts
    websocket = Socket()
    serving = asyncio.create_task(serve_cascade(websocket, agent, config))
    websocket.pcm(VOICE)
    websocket.pcm(SILENCE * 40)
    await websocket.until(lambda: len(websocket.audio) == 1)
    websocket.pcm(VOICE)
    await websocket.until(lambda: sum(event["type"] == "interrupt" for event in websocket.events) == 2)
    gate.set()
    await asyncio.wait_for(tts_closed.wait(), 2)
    websocket.stop()
    await asyncio.wait_for(serving, 2)
    assert websocket.audio == [b"\1\0"]
    assert len(store.list("transcripts")) == 2
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_auth_failure_and_missing_asr_never_start_worker(agent, config, monkeypatch):
    monkeypatch.setattr("app.services.cascade.VolcSpeechProvider", lambda config: pytest.fail("provider instantiated"))
    config.app_token = "offline-password"
    websocket = Socket()
    await serve_cascade(websocket, agent, config)
    assert 1008 in websocket.closed
    config.app_token = ""
    websocket = Socket()
    await serve_cascade(websocket, agent, config)
    assert websocket.events[0]["type"] == "error"
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_provider_failure_is_sanitized_and_releases_counter(agent, config, speech):
    async def broken(chunks):
        raise SpeechProviderError("sensitive upstream response")
        yield

    speech.stream_asr = broken
    websocket = Socket()
    websocket.pcm(VOICE)
    websocket.stop()
    await asyncio.wait_for(serve_cascade(websocket, agent, config), 2)
    assert any(event["type"] == "error" for event in websocket.events)
    assert "sensitive upstream" not in str(websocket.events)
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_queue_backpressure_ends_session_without_unbounded_audio(agent, config, speech):
    async def stalled(chunks):
        await asyncio.Event().wait()
        yield

    speech.stream_asr = stalled
    websocket = Socket()
    for _ in range(7):
        websocket.pcm(VOICE)
        websocket.pcm(SILENCE * 40)
    await asyncio.wait_for(serve_cascade(websocket, agent, config), 2)
    assert any("积压" in event.get("message", "") for event in websocket.events)
    assert agent.audio_clients == 0 and websocket.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("pcm", [b"\0", b"\0" * 32002], ids=["partial-sample", "oversized-frame"])
async def test_invalid_pcm_never_reaches_asr(agent, config, speech, pcm):
    websocket = Socket()
    websocket.pcm(pcm)
    await asyncio.wait_for(serve_cascade(websocket, agent, config), 2)
    assert speech.calls == []
    assert any(event["type"] == "error" for event in websocket.events)
    assert agent.audio_clients == 0
