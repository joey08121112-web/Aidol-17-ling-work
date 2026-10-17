"""Independent regression checks for cross-feature memory lifecycle boundaries."""
from datetime import datetime, timezone

import pytest

from app.services.recall import RecallService
from app.domain import Settings, TextInput


def linked_memory(store):
    transcript = store.add("transcripts", speaker="用户", text="我负责周三提交方案", mode="work", source="asr")
    memory = store.add("memories", kind="task", content="用户：我负责周三提交方案",
                       source="transcript:" + transcript["id"])
    store.save_meeting_report("initial", {"summary": "周三提交方案", "source_ids": [transcript["id"]], "tasks": [
        {"content": "提交方案", "owner": "", "due": "周三", "source_ids": [transcript["id"]]}]})
    return memory


@pytest.mark.asyncio
async def test_correcting_linked_memory_does_not_recall_obsolete_task_deadline(config, store):
    memory = linked_memory(store)
    store.correct_memory(memory["id"], "用户：我负责周五提交方案")
    memories, transcripts = store.recall_records("我负责什么时候提交方案？")
    result = await RecallService(config).answer("我负责什么时候提交方案？", memories, transcripts)
    assert "周五" in result["answer"]
    assert "周三" not in result["answer"]


@pytest.mark.asyncio
async def test_pending_task_not_hidden_by_hundred_completed_tasks(config, store):
    for index in range(105):
        report = store.save_meeting_report(str(index), {"tasks": [
            {"content": "提交方案", "owner": "", "due": "", "source_ids": [str(index)]}]})
        if index:
            with store.connect() as db:
                task_id = db.execute("SELECT id FROM action_tasks WHERE report_id=?", (report["id"],)).fetchone()[0]
            store.action_task_status(task_id, "done")
        else:
            with store.connect() as db:
                db.execute("UPDATE action_tasks SET created_at='2025-01-01T00:00:00Z' WHERE report_id=?", (report["id"],))
    memories, transcripts = store.recall_records("有哪些未完成待办？")
    result = await RecallService(config).answer("有哪些未完成待办？", memories, transcripts)
    assert result["citations"]
    assert "未完成" in result["answer"]


@pytest.mark.asyncio
async def test_previous_month_filter_runs_before_candidate_limit(config, store):
    current = datetime.now(timezone.utc)
    year, month = (current.year - 1, 12) if current.month == 1 else (current.year, current.month - 1)
    old = store.add("memories", content="和姐姐去了蓝鲸咖啡馆", kind="fact", source="user")
    with store.connect() as db:
        db.execute("UPDATE memories SET created_at=? WHERE id=?", (f"{year}-{month:02}-12T08:00:00Z", old["id"]))
    for _ in range(105):
        store.add("memories", content="和姐姐去了蓝鲸咖啡馆", kind="fact", source="user")
    question = "上个月和姐姐去的咖啡馆是什么？"
    memories, transcripts = store.recall_records(question)
    result = await RecallService(config).answer(question, memories, transcripts)
    assert [row["id"] for row in result["citations"]] == [old["id"]]


@pytest.mark.asyncio
async def test_new_auto_saved_fact_invalidates_old_deletion_pronoun(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    first = await agent.ingest(TextInput(text="记住，我负责周三提交方案", source="asr"), directed=True)
    first_id = first["memories"][0]["id"]
    second = await agent.ingest(TextInput(text="我负责周六购买牛奶", source="asr"), directed=True)
    assert second["memories"]
    await agent.ingest(TextInput(text="忘记刚才那条", source="asr"), directed=True)
    # Either target the freshly saved record or ask to disambiguate; silently
    # deleting an earlier unrelated promise is never the meaning of "刚才那条".
    assert store.get_memory(first_id) is not None


def test_phone_save_cannot_leave_old_voice_deletion_target(client, runtime):
    original = client.post("/v1/chat", json={"request_id": "review-remember-0001", "interaction_source": "direct_question",
                           "text": "记住，我负责周三提交方案"}).json()["memories"][0]
    saved = client.post("/v1/memories", json={"content": "周六购买牛奶", "kind": "task"})
    assert saved.status_code == 200
    forgotten = client.post("/v1/chat", json={"request_id": "review-forget-000001", "interaction_source": "direct_question",
                             "text": "忘记刚才那条"})
    assert forgotten.status_code == 200
    assert runtime.store.get_memory(original["id"]) is not None


def test_forget_removes_source_tasks_reports_and_cached_private_text(store):
    memory = linked_memory(store)
    secret = memory["content"]
    store.mobile_turn("original-turn", "text:original")
    store.finish_mobile_turn("original-turn", {"text": secret, "response": secret, "speak": True})
    store.add("transcripts", speaker="assistant", text=secret, mode="work", source="asr")
    deleted = store.forget_memory(memory["id"])
    assert deleted["transcripts"] == deleted["tasks"] == deleted["reports"] == 1
    assert not store.list("memories") and not store.list("transcripts")
    assert not store.list("action_tasks") and not store.all_meeting_reports()
    assert store.recall_records("周三提交方案") == ([], [])
    cached = store.mobile_turn("original-turn", "text:original")
    assert cached["invalidated"] and secret not in str(cached)
