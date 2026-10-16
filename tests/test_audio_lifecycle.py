import asyncio

import pytest

from app.domain import Mode, Settings
from app.services.audio import serve_audio


class FakeSocket:
    def __init__(self, hello=None, frames=None):
        self.hello = hello or {"type": "start"}
        self.frames = frames or []
        self.events = []
        self.audio = []
        self.closed = []
        self.accepted = False

    async def accept(self):
        self.accepted = True

    async def receive_json(self):
        return self.hello

    async def receive(self):
        if self.frames:
            return self.frames.pop(0)
        await asyncio.Event().wait()

    async def send_json(self, event):
        self.events.append(event)

    async def send_bytes(self, data):
        self.audio.append(data)

    async def close(self, code=1000):
        self.closed.append(code)


class FakeProvider:
    def __init__(self, name, config, events=None, fail_open=False, fail_close=False):
        self.name = name
        self.output = events or []
        self.fail_open = fail_open
        self.fail_close = fail_close
        self.closed = False
        self.input = []
        self.updated = []

    async def open(self, instructions):
        if self.fail_open:
            raise ConnectionError("offline synthetic failure")

    async def close(self):
        self.closed = True
        if self.fail_close:
            raise ConnectionError("offline close failure")

    async def audio(self, pcm):
        self.input.append(pcm)

    async def text(self, text):
        self.input.append(text)

    async def update(self, instructions):
        self.updated.append(instructions)

    async def events(self):
        for event in self.output:
            yield event
        # End of vendor stream ends the session and drains queued transcripts.


def install_provider(monkeypatch, provider):
    monkeypatch.setattr("app.services.audio.candidates", lambda *args: [provider.name])
    monkeypatch.setattr("app.services.audio.RealtimeProvider", lambda *args: provider)


@pytest.mark.asyncio
@pytest.mark.parametrize("mode, expected_audio", [(Mode.life, [b"\x00\x00"]), (Mode.work, [])])
async def test_audio_session_drains_transcript_and_respects_work_silence(agent, config, store, monkeypatch, mode, expected_audio):
    store.save_settings(Settings(auto_mode=False, mode=mode))
    agent.mode = mode
    provider = FakeProvider("fake", config, events=[
        {"type": "transcript", "text": "我负责完成接口测试", "event_id": "asr-final-1"},
        {"type": "audio", "pcm": b"\x00\x00"},
    ])
    install_provider(monkeypatch, provider)
    websocket = FakeSocket()
    await asyncio.wait_for(serve_audio(websocket, agent, config), 2)
    assert websocket.accepted and websocket.closed
    assert websocket.events[0]["type"] == "ready"
    assert websocket.audio == expected_audio
    assert provider.closed and agent.audio_clients == 0
    assert store.list("transcripts")[0]["speaker"] == "现场发言（未分离声纹）"
    assert len(store.list("memories")) == 1


@pytest.mark.asyncio
async def test_authentication_failure_never_opens_provider(agent, config, monkeypatch):
    config.app_token = "offline-password"
    monkeypatch.setattr("app.services.audio.candidates", lambda *args: pytest.fail("provider selection before authentication"))
    websocket = FakeSocket({"type": "start", "token": "wrong"})
    await serve_audio(websocket, agent, config)
    assert 1008 in websocket.closed
    assert agent.audio_clients == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_close", [False, True], ids=["normal-close", "broken-close"])
async def test_failed_provider_falls_back_and_both_sessions_close(agent, config, monkeypatch, fail_close):
    first = FakeProvider("doubao", config, fail_open=True, fail_close=fail_close)
    second = FakeProvider("qwen", config)
    monkeypatch.setattr("app.services.audio.candidates", lambda *args: ["doubao", "qwen"])
    monkeypatch.setattr("app.services.audio.RealtimeProvider", lambda name, config: {"doubao": first, "qwen": second}[name])
    websocket = FakeSocket()
    await asyncio.wait_for(serve_audio(websocket, agent, config), 2)
    assert [event["type"] for event in websocket.events] == ["provider.failed", "ready"]
    assert first.closed and second.closed and agent.audio_clients == 0


@pytest.mark.asyncio
async def test_close_failure_does_not_leak_session_counter(agent, config, monkeypatch):
    provider = FakeProvider("fake", config, fail_close=True)
    install_provider(monkeypatch, provider)
    websocket = FakeSocket()
    await asyncio.wait_for(serve_audio(websocket, agent, config), 2)
    assert agent.audio_clients == 0
    assert websocket.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("pcm", [b"\x00", b"\x00" * 32002], ids=["odd-byte-count", "oversized-frame"])
async def test_invalid_pcm_frame_is_rejected(agent, config, monkeypatch, pcm):
    class WaitingProvider(FakeProvider):
        async def events(self):
            await asyncio.Event().wait()
            yield {}

    provider = WaitingProvider("fake", config)
    install_provider(monkeypatch, provider)
    websocket = FakeSocket(frames=[{"type": "websocket.receive", "bytes": pcm}])
    await asyncio.wait_for(serve_audio(websocket, agent, config), 2)
    assert any(event["type"] == "error" for event in websocket.events)
    assert provider.input == []
    assert provider.closed and agent.audio_clients == 0
