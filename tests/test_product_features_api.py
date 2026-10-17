"""Hosted meeting drafts, task state, export and memory recall with offline models."""
from datetime import datetime, timedelta, timezone

import pytest

from app.services.insights import MeetingInsights
from app.services.recall import RecallService


def add_transcript(runtime, text="我负责明天提交设计稿", *, mode="work", speaker="小林"):
    return runtime.store.add("transcripts", text=text, mode=mode, source="asr", speaker=speaker)


def mock_insights(monkeypatch):
    calls = []

    async def generate(self, rows):
        calls.append(rows)
        return {"summary": "会议讨论设计稿交付。", "decisions": [],
                "tasks": [{"content": "提交设计稿", "owner": "小林", "due": "明天", "source_ids": [rows[0]["id"]]}],
                "source_ids": [row["id"] for row in rows], "extraction": "llm"}

    monkeypatch.setattr(MeetingInsights, "generate", generate)
    return calls


def mock_recall(monkeypatch):
    calls = []

    async def answer(self, question, memories, transcripts):
        calls.append({"question": question, "memories": memories, "transcripts": transcripts})
        return {"answer": "测试检索回答", "citations": [], "extraction": "rules", "insufficient": True}

    monkeypatch.setattr(RecallService, "answer", answer)
    return calls


def test_meeting_empty_data_is_422_and_model_not_called(client, monkeypatch):
    calls = mock_insights(monkeypatch)
    response = client.post("/v1/meetings", json={})
    assert response.status_code == 422
    assert "工作模式" in response.json()["detail"]
    assert calls == []


def test_meeting_filters_modes_assistant_and_date_before_model(client, runtime, monkeypatch):
    calls = mock_insights(monkeypatch)
    real = add_transcript(runtime)
    add_transcript(runtime, "我来预订晚餐", mode="life")
    add_transcript(runtime, "预计十分钟到达", mode="commute")
    add_transcript(runtime, "这是模型回复，不是用户任务", speaker="assistant")
    old = add_transcript(runtime, "我来完成旧任务")
    with runtime.store.connect() as db:
        db.execute("UPDATE transcripts SET created_at=? WHERE id=?",
                   ((datetime.now(timezone.utc) - timedelta(days=3)).isoformat(), old["id"]))

    result = client.post("/v1/meetings", json={}).json()
    assert len(calls) == 1
    assert [row["id"] for row in calls[0]] == [real["id"]]
    assert result["source_ids"] == [real["id"]]
    assert [row["id"] for row in result["sources"]] == [real["id"]]


def test_repeated_generation_reuses_report_and_does_not_duplicate_tasks(client, runtime, monkeypatch):
    calls = mock_insights(monkeypatch)
    add_transcript(runtime)
    first = client.post("/v1/meetings", json={}).json()
    second = client.post("/v1/meetings", json={}).json()
    assert first == second
    assert len(calls) == 1
    state = client.get("/v1/state").json()
    assert state["meeting_report"]["id"] == first["id"]
    assert len(state["tasks"]) == 1
    task = state["tasks"][0]
    assert task["report_id"] == first["id"]
    assert task["source_ids"] == first["tasks"][0]["source_ids"]
    assert task["status"] == "open"


def test_task_can_complete_restore_and_reject_invalid_update(client, runtime, monkeypatch):
    mock_insights(monkeypatch)
    add_transcript(runtime)
    client.post("/v1/meetings", json={})
    task = client.get("/v1/state").json()["tasks"][0]
    endpoint = f"/v1/tasks/{task['id']}"
    assert client.patch(endpoint, json={"status": "done"}).json() == {"ok": True}
    assert client.get("/v1/state").json()["tasks"][0]["status"] == "done"
    assert client.patch(endpoint, json={"status": "open"}).status_code == 200
    assert client.get("/v1/state").json()["tasks"][0]["status"] == "open"
    assert client.patch(endpoint, json={"status": "deleted"}).status_code == 422
    assert client.patch("/v1/tasks/nonexistent", json={"status": "done"}).status_code == 404


def test_export_contains_reports_tasks_and_clear_removes_all(client, runtime, monkeypatch):
    mock_insights(monkeypatch)
    add_transcript(runtime)
    client.put("/v1/settings", json={"auto_mode": False, "mode": "work"})
    client.post("/v1/memories", json={"content": "喜欢清淡"})
    client.post("/v1/meetings", json={})
    store = runtime.store
    store.add("drafts", contact="小林", text="稍后到达")
    assert store.claim("test-event")
    exported = client.get("/v1/export").json()
    assert exported["version"] == 3
    assert len(exported["reports"]) == len(exported["tasks"]) == 1
    assert exported["tasks"][0]["report_id"] == exported["reports"][0]["id"]
    assert exported["memories"] and exported["transcripts"] and exported["drafts"]

    assert client.request("DELETE", "/v1/data", json={"password": "test-password-123"}).status_code == 200
    exported = client.get("/v1/export").json()
    for key in ("reports", "tasks", "memories", "transcripts", "drafts"):
        assert exported[key] == []
    assert exported["settings"]["mode"] == "work"
    assert client.get("/v1/state").json()["meeting_report"] is None
    assert store.list("events") == []
    assert store.claim("test-event"), "clear also releases processed-event deduplication keys"


def test_recall_receives_current_action_task_completion_status(client, runtime, monkeypatch):
    mock_insights(monkeypatch)
    calls = mock_recall(monkeypatch)
    add_transcript(runtime)
    client.post("/v1/meetings", json={})
    task = client.get("/v1/state").json()["tasks"][0]
    client.post("/v1/recall", json={"question": "还有什么待办"})
    context = calls[0]["memories"][0]
    assert context["source"] == "action_task"
    assert context["id"] == "task:" + task["id"]
    assert context["kind"] == "task"
    assert "完成状态：未完成" in context["content"]
    assert "负责人：小林" in context["content"]

    client.patch(f"/v1/tasks/{task['id']}", json={"status": "done"})
    client.post("/v1/recall", json={"question": "我的任务"})
    assert "完成状态：已完成" in calls[1]["memories"][0]["content"]


@pytest.mark.parametrize("path,payload", [
    ("/v1/meetings", {"hours": 0}),
    ("/v1/meetings", {"hours": 169}),
    ("/v1/recall", {"question": ""}),
    ("/v1/recall", {"question": " \n "}),
    ("/v1/recall", {"question": "x" * 501}),
])
def test_feature_input_boundaries_reject_before_services(client, monkeypatch, path, payload):
    meeting_calls = mock_insights(monkeypatch)
    recall_calls = mock_recall(monkeypatch)
    assert client.post(path, json=payload).status_code == 422
    assert meeting_calls == recall_calls == []
