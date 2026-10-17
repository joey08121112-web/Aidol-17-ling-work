import asyncio
from datetime import datetime as RealDatetime

import httpx
import pytest

from app.domain import LocationInput, Mode, Settings, TextInput
from app.services.memory import MemoryService


@pytest.mark.asyncio
async def test_manual_mode_overrides_speech(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    assert await agent.decide("下一站即将到站，去公司") == Mode.work
    assert agent.reason == "用户手动指定"


@pytest.mark.asyncio
async def test_intent_hysteresis_and_explicit_life_override(agent, monkeypatch):
    monkeypatch.setattr("app.services.agent.time.monotonic", lambda: 1000)
    assert await agent.decide("我正在坐地铁") == Mode.commute
    assert await agent.decide("刚才没听清楚") == Mode.commute
    assert await agent.decide("休息一下") == Mode.life
    assert await agent.decide("开始会议") == Mode.work


@pytest.mark.asyncio
async def test_schedule_uses_user_timezone_and_trip_memory(agent, store, monkeypatch):
    class Clock(RealDatetime):
        @classmethod
        def now(cls, tz=None):
            return cls(2026, 10, 19, 9, 30, tzinfo=tz)

    monkeypatch.setattr("app.services.agent.datetime", Clock)
    monkeypatch.setattr("app.services.agent.time.monotonic", lambda: 1000)
    assert await agent.decide() == Mode.work
    store.add("memories", content="今天乘坐地铁去公司", kind="trip", source="user")
    with store.connect() as db:
        db.execute("UPDATE memories SET created_at=?", ("2026-10-19T00:00:00+00:00",))
    assert await agent.decide() == Mode.commute


@pytest.mark.asyncio
async def test_concurrent_duplicate_transcript_saved_once(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    item = TextInput(text="小王负责周五完成接口联调。", speaker="小王", event_id="vendor-42")
    results = await asyncio.gather(agent.ingest(item), agent.ingest(item))
    assert sum(bool(result.get("duplicate")) for result in results) == 1
    assert len(store.list("transcripts")) == len(store.list("memories")) == 1
    assert store.list("transcripts")[0]["speaker"] == "小王"


@pytest.mark.asyncio
async def test_transcript_retention_off_still_extracts_memory(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="work", retain_transcripts=False))
    await agent.ingest(TextInput(text="我负责完成后端接口", speaker="开发者"))
    assert store.list("transcripts") == []
    assert store.list("memories")[0]["content"].startswith("开发者：")
    assert all("我负责完成后端接口" not in str(event) for event in store.list("events"))


@pytest.mark.asyncio
async def test_rules_filter_filler_and_preserve_different_speakers(config, store):
    memory = MemoryService(config, store)
    assert await memory.extract("嗯嗯，好的。", "甲", Mode.work) == ([], "rules")
    first, _ = await memory.extract("嗯嗯，我喜欢清淡的食物", "甲", Mode.life)
    repeat, _ = await memory.extract("我喜欢清淡的食物", "甲", Mode.life)
    other, _ = await memory.extract("我喜欢清淡的食物", "乙", Mode.life)
    assert first[0]["kind"] == "preference"
    assert repeat == []
    assert other[0]["content"].startswith("乙：")
    assert await memory.extract("今天的天空是蓝色的", "甲", Mode.life) == ([], "rules")


@pytest.mark.asyncio
@pytest.mark.parametrize("payload", [
    {"choices": [{"message": {"content": "not json"}}]},
    {"choices": [{"message": {"content": "[]"}}]},
    {"choices": []},
    {"choices": [{"message": {"content": '{"items": "invalid"}'}}]},
])
async def test_malformed_llm_response_falls_back_to_rules(config, store, monkeypatch, payload):
    config.text_key, config.text_model = "offline-test-key", "fake-model"
    real_client = httpx.AsyncClient
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json=payload))
    monkeypatch.setattr("app.services.memory.httpx.AsyncClient", lambda **kwargs: real_client(transport=transport))
    saved, provider = await MemoryService(config, store).extract("我负责周五完成演示", "队友", Mode.work)
    assert provider == "rules_fallback"
    assert saved[0]["kind"] == "task"


@pytest.mark.asyncio
async def test_llm_empty_items_is_respected_and_does_not_invent_memory(config, store, monkeypatch):
    config.text_key, config.text_model = "offline-test-key", "fake-model"
    real_client = httpx.AsyncClient
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": '{"items": []}'}}]}))
    monkeypatch.setattr("app.services.memory.httpx.AsyncClient", lambda **kwargs: real_client(transport=transport))
    assert await MemoryService(config, store).extract("其实也没什么事情", "用户", Mode.work) == ([], "llm")


@pytest.mark.asyncio
async def test_location_requests_route_and_creates_only_one_draft(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode="commute", destination_lon=121.6, destination_lat=31.3))
    monkeypatch.setattr("app.services.agent.time.monotonic", lambda: 1000)
    calls = []

    async def route(location, settings):
        calls.append(location)
        return {"available": True, "eta_seconds": 120, "source": "test"}

    monkeypatch.setattr(agent.maps, "route", route)
    location = LocationInput(longitude=121.5, latitude=31.2, accuracy=10, speed=10)
    await agent.update_location(location)
    await agent.update_location(location)
    await agent.propose_arrival("听到到站播报")
    assert len(calls) == 1
    drafts = store.list("drafts")
    assert len(drafts) == 1
    assert "2分钟" in drafts[0]["text"]
    assert drafts[0]["status"] == "pending"
