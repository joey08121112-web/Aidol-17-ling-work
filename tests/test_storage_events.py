import asyncio

import pytest

from app.domain import Settings


def test_settings_survive_reopening_and_clear_preserves_preferences(store):
    from app.storage import Store

    settings = Settings(preferences="队友的偏好", auto_mode=False, mode="work")
    store.save_settings(settings)
    store.add("memories", content="周五交付", kind="task", source="user")
    assert store.claim("asr-1")
    assert not store.claim("asr-1")
    reopened = Store(store.path)
    assert reopened.settings() == settings
    assert len(reopened.list("memories")) == 1
    reopened.clear()
    assert reopened.list("memories") == []
    assert reopened.settings() == settings
    assert reopened.claim("asr-1")


def test_memory_search_is_parameterized_and_delete_reports_missing(store):
    memory = store.add("memories", content="喜欢清淡食物", kind="preference", source="user")
    assert store.list("memories", query="清淡")[0]["id"] == memory["id"]
    assert store.list("memories", query="' OR 1=1 --") == []
    assert store.delete_memory(memory["id"])
    assert not store.delete_memory(memory["id"])
    with pytest.raises(ValueError):
        store.list("settings; DROP TABLE memories")


def test_draft_can_only_be_processed_once(store):
    draft = store.add("drafts", contact="同事", text="约五分钟到", status="pending")
    assert store.draft_status(draft["id"], "copied")
    assert not store.draft_status(draft["id"], "dismissed")
    with pytest.raises(ValueError):
        store.draft_status(draft["id"], "sent")


@pytest.mark.asyncio
async def test_backpressure_drops_oldest_without_blocking_audio(bus, store):
    slow = bus.subscribe()
    fast = bus.subscribe()
    for sequence in range(140):
        event = await asyncio.wait_for(bus.emit("metric", {"sequence": sequence}, persist=False), 0.5)
        assert (await fast.get())["id"] == event["id"]
    assert slow.qsize() == 128
    assert (await slow.get())["payload"]["sequence"] == 12
    assert store.list("events") == []
    bus.unsubscribe(slow)
    bus.unsubscribe(fast)
    assert not bus.subscribers


@pytest.mark.asyncio
async def test_persisted_event_payload_roundtrips_unicode(bus, store):
    await bus.emit("mode.changed", {"reason": "会议开始", "mode": "work"})
    assert store.list("events")[0]["payload"] == {"reason": "会议开始", "mode": "work"}


@pytest.mark.asyncio
async def test_agent_flow_emits_without_persisting_events(agent, store):
    # events 表只写不读且无界增长（审计 2026-10-18 W4）；agent 全部事件改为
    # 仅投递订阅者，-events 表在完整对话流程后保持为空。
    from app.domain import TextInput
    result = await agent.ingest(TextInput(text='现在几点了'), directed=True)
    assert result['interaction'] == 'intent_shortcut'
    await agent.decide('开始通勤，下一站到了')      # mode.changed + hardware.notice
    await agent.propose_arrival('测试直达')          # notification.proposed
    assert store.list("events") == []
