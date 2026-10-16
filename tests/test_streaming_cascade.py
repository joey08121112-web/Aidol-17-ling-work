import asyncio
from dataclasses import replace
import json
import struct

import pytest

from app.domain import Settings
from app.providers.volc_speech import AsrResult, SpeechProviderError
from app.services.streaming_cascade import FinalUtterances, serve_streaming_cascade


VOICE = struct.pack("<h", 8192) * 320
SILENCE = b"\0\0" * 320


async def eventually(condition):
    async with asyncio.timeout(2):
        while not condition():
            await asyncio.sleep(.001)


class Socket:
    def __init__(self, hello=None):
        self.hello = hello or {"type": "start", "duration": 120}
        self.input = asyncio.Queue()
        self.events, self.audio, self.closed = [], [], []

    async def accept(self):
        pass

    async def receive_json(self):
        return self.hello

    async def receive(self):
        return await self.input.get()

    async def send_json(self, event):
        self.events.append(event)

    async def send_bytes(self, pcm):
        self.audio.append(pcm)

    async def close(self, code=1000):
        self.closed.append(code)

    def pcm(self, pcm):
        self.input.put_nowait({"type": "websocket.receive", "bytes": pcm})

    def stop(self):
        self.input.put_nowait({"type": "websocket.receive", "text": json.dumps({"type": "stop"})})

    def disconnect(self):
        self.input.put_nowait({"type": "websocket.disconnect"})


class StreamingSpeech:
    def __init__(self):
        self.configs = []
        self.received = []
        self.results = asyncio.Queue()
        self.final = AsrResult(text="", final=True)
        self.closed = False
        self.asr_calls = 0
        self.spoken = []

    async def stream_asr(self, chunks):
        self.asr_calls += 1

        async def consume():
            async for pcm in chunks:
                self.received.append(pcm)
            await self.results.put(self.final)

        sender = asyncio.create_task(consume())
        try:
            while True:
                result = await self.results.get()
                if isinstance(result, Exception):
                    raise result
                yield result
                if result.final:
                    break
        finally:
            sender.cancel()
            await asyncio.gather(sender, return_exceptions=True)
            self.closed = True

    async def stream_tts(self, text):
        self.spoken.append(text)
        yield b"\1\0"


@pytest.fixture
def speech(config, monkeypatch):
    config.speech = replace(config.speech, asr_api_key="offline-asr", tts_app_key="offline-app", tts_access_key="offline-tts")
    value = StreamingSpeech()

    def factory(config):
        value.configs.append(config)
        return value

    monkeypatch.setattr("app.services.streaming_cascade.VolcSpeechProvider", factory)
    return value


def utterance(text, start=0, end=800, definite=True):
    return {"text": text, "start_time": start, "end_time": end, "definite": definite}


def test_definite_utterances_deduplicate_cumulative_responses_by_time():
    tracker = FinalUtterances()
    assert tracker.accept(AsrResult("第一句", utterances=(utterance("第一句", definite=False),))) == []
    assert tracker.accept(AsrResult("第一句。", utterances=(utterance("第一句。"),))) == ["第一句。"]
    # Punctuation changes at the same stable boundary cannot create duplicates.
    assert tracker.accept(AsrResult("第一句！", utterances=(utterance("第一句！"),))) == []
    assert tracker.accept(AsrResult("第一句。第一句。", utterances=(
        utterance("第一句。"), utterance("第一句。", 1000, 1800)))) == ["第一句。"]
    assert tracker.accept(AsrResult("第一句。第一句。", final=True)) == []


def test_final_without_utterance_metadata_is_ingested_only_once():
    tracker = FinalUtterances()
    assert tracker.accept(AsrResult("连续识别的临时文本")) == []
    assert tracker.accept(AsrResult("最后确认的整段文本", final=True)) == ["最后确认的整段文本"]
    assert tracker.accept(AsrResult("最后确认的整段文本", final=True)) == []


def test_final_cumulative_fallback_only_adds_uncommitted_tail():
    tracker = FinalUtterances()
    assert tracker.accept(AsrResult("已经确认的句子。", utterances=(utterance("已经确认的句子。"),))) == ["已经确认的句子。"]
    assert tracker.accept(AsrResult("已经确认的句子。最后一句。", final=True)) == ["最后一句。"]


@pytest.mark.asyncio
async def test_audio_reaches_one_asr_stream_before_silence_and_partial_is_immediate(agent, store, config, speech):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    websocket = Socket()
    serving = asyncio.create_task(serve_streaming_cascade(websocket, agent, config))
    websocket.pcm(VOICE)
    await eventually(lambda: speech.received == [VOICE])
    assert speech.asr_calls == 1
    speech.results.put_nowait(AsrResult("我负责", utterances=(utterance("我负责", definite=False),)))
    await eventually(lambda: any(event["type"] == "transcript.delta" for event in websocket.events))
    assert store.list("transcripts") == []
    definite = AsrResult("我负责完成接口测试", utterances=(utterance("我负责完成接口测试"),))
    speech.results.put_nowait(definite)
    await eventually(lambda: len(store.list("transcripts")) == 1)
    speech.results.put_nowait(definite)
    websocket.pcm(SILENCE)
    await eventually(lambda: len(speech.received) == 2)
    websocket.stop()
    await asyncio.wait_for(serving, 2)
    assert len(store.list("transcripts")) == 1
    assert speech.spoken == [] and websocket.audio == []
    assert speech.configs[0].timeout == 135
    assert config.speech.timeout != 135
    assert agent.audio_clients == 0 and speech.closed and websocket.closed


@pytest.mark.asyncio
async def test_stop_flushes_final_fallback_without_repeating_partial(agent, config, speech, store):
    speech.final = AsrResult("我喜欢清淡的食物", final=True)
    websocket = Socket()
    serving = asyncio.create_task(serve_streaming_cascade(websocket, agent, config))
    websocket.pcm(VOICE)
    await eventually(lambda: len(speech.received) == 1)
    speech.results.put_nowait(AsrResult("我喜欢"))
    await eventually(lambda: any(event["type"] == "transcript.delta" for event in websocket.events))
    websocket.stop()
    await asyncio.wait_for(serving, 2)
    assert [row["text"] for row in store.list("transcripts")] == ["我喜欢清淡的食物"]
    assert len(store.list("memories")) == 1
    assert not speech.spoken


@pytest.mark.asyncio
async def test_new_speech_cancels_waiting_tts_while_asr_keeps_receiving(agent, store, config, speech):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    cancelled = asyncio.Event()

    async def waiting_tts(text):
        try:
            yield b"\1\0"
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    speech.stream_tts = waiting_tts
    websocket = Socket()
    serving = asyncio.create_task(serve_streaming_cascade(websocket, agent, config))
    websocket.pcm(VOICE)
    websocket.pcm(SILENCE * 40)
    await eventually(lambda: len(speech.received) == 2)
    speech.results.put_nowait(AsrResult("讲个笑话", utterances=(utterance("讲个笑话"),)))
    await eventually(lambda: websocket.audio == [b"\1\0"])
    websocket.pcm(VOICE)
    await asyncio.wait_for(cancelled.wait(), 2)
    await eventually(lambda: len(speech.received) == 3)
    assert sum(event["type"] == "interrupt" for event in websocket.events) == 2
    websocket.disconnect()
    await asyncio.wait_for(serving, 2)
    assert agent.audio_clients == 0 and speech.closed


@pytest.mark.asyncio
async def test_mode_can_change_to_work_without_reopening_asr(agent, store, config, speech):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    websocket = Socket()
    serving = asyncio.create_task(serve_streaming_cascade(websocket, agent, config))
    websocket.pcm(VOICE)
    await eventually(lambda: speech.received)
    speech.results.put_nowait(AsrResult("讲个笑话", utterances=(utterance("讲个笑话"),)))
    await eventually(lambda: len(speech.spoken) == 1)
    store.save_settings(Settings(auto_mode=False, mode="work"))
    await agent.decide()
    speech.results.put_nowait(AsrResult("我负责完成任务", utterances=(utterance("我负责完成任务", 1000, 1800),)))
    await eventually(lambda: len(store.list("transcripts")) == 2)
    websocket.stop()
    await asyncio.wait_for(serving, 2)
    assert len(speech.spoken) == 1
    assert speech.asr_calls == 1


@pytest.mark.asyncio
async def test_upstream_error_is_sanitized_and_closes_continuous_stream(agent, config, speech):
    websocket = Socket()
    speech.results.put_nowait(SpeechProviderError("vendor-secret-details"))
    await asyncio.wait_for(serve_streaming_cascade(websocket, agent, config), 2)
    assert any(event["type"] == "error" for event in websocket.events)
    assert "vendor-secret-details" not in str(websocket.events)
    assert agent.audio_clients == 0 and speech.closed


@pytest.mark.asyncio
async def test_audio_backpressure_stops_instead_of_accumulating_frames(agent, config, speech, monkeypatch):
    monkeypatch.setattr("app.services.streaming_cascade.AUDIO_QUEUE_SIZE", 2)

    async def stalled(chunks):
        await asyncio.Event().wait()
        yield

    speech.stream_asr = stalled
    websocket = Socket()
    for _ in range(4):
        websocket.pcm(SILENCE)
    await asyncio.wait_for(serve_streaming_cascade(websocket, agent, config), 2)
    assert any("上传积压" in event.get("message", "") for event in websocket.events)
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_transcript_backpressure_stops_without_silent_data_loss(agent, config, speech, monkeypatch):
    monkeypatch.setattr("app.services.streaming_cascade.TRANSCRIPT_QUEUE_SIZE", 2)

    async def stalled_ingest(*args, **kwargs):
        await asyncio.Event().wait()

    monkeypatch.setattr(agent, "ingest", stalled_ingest)
    for index in range(5):
        speech.results.put_nowait(AsrResult(f"任务{index}", utterances=(utterance(f"任务{index}", index * 1000, index * 1000 + 800),)))
    websocket = Socket()
    await asyncio.wait_for(serve_streaming_cascade(websocket, agent, config), 2)
    assert any("记忆处理积压" in event.get("message", "") for event in websocket.events)
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_invalid_auth_never_instantiates_upstream(agent, config, monkeypatch):
    config.app_token = "offline-password"
    monkeypatch.setattr("app.services.streaming_cascade.VolcSpeechProvider", lambda config: pytest.fail("upstream called"))
    websocket = Socket()
    await serve_streaming_cascade(websocket, agent, config)
    assert 1008 in websocket.closed
    assert agent.audio_clients == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("duration, timeout", [(-1, 20), (99999, 3615)])
async def test_session_duration_and_upstream_timeout_are_bounded(agent, config, speech, duration, timeout):
    websocket = Socket({"type": "start", "duration": duration})
    websocket.disconnect()
    await asyncio.wait_for(serve_streaming_cascade(websocket, agent, config), 2)
    assert speech.configs[0].timeout == timeout
    assert agent.audio_clients == 0


@pytest.mark.asyncio
async def test_external_cancellation_closes_asr_and_restores_audio_counter(agent, config, speech):
    websocket = Socket()
    serving = asyncio.create_task(serve_streaming_cascade(websocket, agent, config))
    websocket.pcm(SILENCE)
    await eventually(lambda: speech.received)
    serving.cancel()
    with pytest.raises(asyncio.CancelledError):
        await serving
    assert speech.closed and websocket.closed
    assert agent.audio_clients == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("pcm", [b"\0", b"\0" * 32002], ids=["partial-sample", "oversized"])
async def test_invalid_audio_never_reaches_stream(agent, config, speech, pcm):
    websocket = Socket()
    websocket.pcm(pcm)
    await asyncio.wait_for(serve_streaming_cascade(websocket, agent, config), 2)
    assert speech.received == []
    assert any(event["type"] == "error" for event in websocket.events)
    assert agent.audio_clients == 0
