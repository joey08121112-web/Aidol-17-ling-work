"""Offline integration coverage for meeting drafts, task state and memory recall."""
from datetime import datetime, timedelta, timezone

import pytest


def add_transcript(client, text="我负责明天提交设计稿", *, mode="work", source="asr"):
    return client.app.state.store.add("transcripts", text=text, mode=mode, source=source, speaker="小林")


def mock_insights(client, monkeypatch):
    calls = []

    async def generate(rows):
        calls.append(rows)
        return {"summary": "会议讨论设计稿交付。", "decisions": [],
                "tasks": [{"content": "提交设计稿", "owner": "小林", "due": "明天", "source_ids": [rows[0]["id"]]}],
                "source_ids": [row["id"] for row in rows], "extraction": "llm"}

    monkeypatch.setattr(client.app.state.insights, "generate", generate)
    return calls


def mock_recall(client, monkeypatch, *, error=False):
    calls = []

    async def answer(question, memories, transcripts):
        calls.append({"question": question, "memories": memories, "transcripts": transcripts})
        if error:
            raise ValueError("test invalid question")
        return {"answer": "测试检索回答", "citations": [], "extraction": "rules", "insufficient": True}

    monkeypatch.setattr(client.app.state.recall, "answer", answer)
    return calls


def test_meeting_empty_data_is_422_and_model_not_called(client, monkeypatch):
    calls = mock_insights(client, monkeypatch)
    response = client.post("/api/meetings/summarize", json={})
    assert response.status_code == 422
    assert "没有工作转写" in response.json()["detail"]
    assert calls == []


def test_meeting_filters_modes_demo_and_date_before_model(client, monkeypatch):
    calls = mock_insights(client, monkeypatch)
    real = add_transcript(client)
    add_transcript(client, "我来预订晚餐", mode="life")
    add_transcript(client, "预计十分钟到达", mode="commute")
    demo = add_transcript(client, "我来完成模拟任务", source="demo")
    old = add_transcript(client, "我来完成旧任务")
    store = client.app.state.store
    with store.connect() as db:
        db.execute("UPDATE transcripts SET created_at=? WHERE id=?",
                   ((datetime.now(timezone.utc) - timedelta(days=3)).isoformat(), old["id"]))

    result = client.post("/api/meetings/summarize", json={}).json()
    assert len(calls) == 1
    assert [row["id"] for row in calls[0]] == [real["id"]]
    assert result["includes_demo"] is False
    assert result["source_ids"] == [real["id"]]
    assert [row["id"] for row in result["sources"]] == [real["id"]]

    inclusive = client.post("/api/meetings/summarize", json={"include_demo": True}).json()
    assert {row["id"] for row in calls[1]} == {real["id"], demo["id"]}
    assert inclusive["includes_demo"] is True
    assert inclusive["cached"] is False


def test_only_demo_needs_explicit_opt_in(client, monkeypatch):
    calls = mock_insights(client, monkeypatch)
    add_transcript(client, source="demo")
    assert client.post("/api/meetings/summarize", json={}).status_code == 422
    assert calls == []
    assert client.post("/api/meetings/summarize", json={"include_demo": True}).status_code == 200
    assert len(calls) == 1


def test_repeated_generation_reuses_report_and_does_not_duplicate_tasks(client, monkeypatch):
    calls = mock_insights(client, monkeypatch)
    add_transcript(client)
    first = client.post("/api/meetings/summarize", json={}).json()
    second = client.post("/api/meetings/summarize", json={}).json()
    assert first["cached"] is False
    assert second["cached"] is True
    assert first["id"] == second["id"]
    assert len(calls) == 1
    state = client.get("/api/state").json()
    assert state["meeting_report"]["id"] == first["id"]
    assert len(state["action_tasks"]) == 1
    task = state["action_tasks"][0]
    assert task["report_id"] == first["id"]
    assert task["source_ids"] == first["tasks"][0]["source_ids"]
    assert task["status"] == "open"


def test_task_can_complete_restore_and_reject_invalid_update(client, monkeypatch):
    mock_insights(client, monkeypatch)
    add_transcript(client)
    client.post("/api/meetings/summarize", json={})
    task = client.get("/api/state").json()["action_tasks"][0]
    endpoint = f"/api/tasks/{task['id']}"
    assert client.patch(endpoint, json={"status": "done"}).json() == {"ok": True, "status": "done"}
    assert client.get("/api/state").json()["action_tasks"][0]["status"] == "done"
    assert client.patch(endpoint, json={"status": "open"}).status_code == 200
    assert client.get("/api/state").json()["action_tasks"][0]["status"] == "open"
    assert client.patch(endpoint, json={"status": "deleted"}).status_code == 422
    assert client.patch("/api/tasks/nonexistent", json={"status": "done"}).status_code == 404


def test_export_version_two_contains_reports_tasks_and_clear_removes_all(client, monkeypatch):
    mock_insights(client, monkeypatch)
    add_transcript(client)
    client.post("/api/memories", json={"content": "喜欢清淡"})
    client.post("/api/meetings/summarize", json={})
    store = client.app.state.store
    store.add("drafts", contact="小林", text="稍后到达")
    assert store.claim("test-event")
    exported = client.get("/api/export").json()
    assert exported["version"] == 2
    assert len(exported["meeting_reports"]) == len(exported["action_tasks"]) == 1
    assert exported["action_tasks"][0]["report_id"] == exported["meeting_reports"][0]["id"]
    assert exported["memories"] and exported["transcripts"] and exported["drafts"]

    assert client.delete("/api/data").status_code == 200
    exported = client.get("/api/export").json()
    for key in ("meeting_reports", "action_tasks", "memories", "transcripts", "drafts"):
        assert exported[key] == []
    assert client.get("/api/state").json()["meeting_report"] is None
    assert store.list("events") == []
    assert store.claim("test-event"), "clear also releases processed-event deduplication keys"


def test_recall_defaults_exclude_demo_transcripts_and_memories(client, monkeypatch):
    calls = mock_recall(client, monkeypatch)
    real = add_transcript(client, "我喜欢清淡", mode="life")
    demo = add_transcript(client, "模拟偏好", mode="life", source="demo")
    store = client.app.state.store
    memory = store.add("memories", kind="preference", content="清淡", source="llm")
    demo_memory = store.add("memories", kind="preference", content="模拟清淡", source="demo_rules")
    response = client.post("/api/recall", json={"question": "我的饮食偏好"})
    assert response.status_code == 200
    assert response.json()["answer"] == "测试检索回答"
    assert [row["id"] for row in calls[0]["memories"]] == [memory["id"]]
    assert [row["id"] for row in calls[0]["transcripts"]] == [real["id"]]

    client.post("/api/recall", json={"question": "饮食偏好", "include_demo": True})
    assert {row["id"] for row in calls[1]["memories"]} == {memory["id"], demo_memory["id"]}
    assert {row["id"] for row in calls[1]["transcripts"]} == {real["id"], demo["id"]}


def test_recall_receives_current_action_task_completion_status(client, monkeypatch):
    mock_insights(client, monkeypatch)
    calls = mock_recall(client, monkeypatch)
    add_transcript(client)
    client.post("/api/meetings/summarize", json={})
    task = client.get("/api/state").json()["action_tasks"][0]
    client.post("/api/recall", json={"question": "还有什么待办"})
    context = calls[0]["memories"][0]
    assert context["source"] == "action_task"
    assert context["id"] == "task:" + task["id"]
    assert context["kind"] == "task"
    assert "完成状态：未完成" in context["content"]
    assert "负责人：小林" in context["content"]

    client.patch(f"/api/tasks/{task['id']}", json={"status": "done"})
    client.post("/api/recall", json={"question": "我的任务"})
    assert "完成状态：已完成" in calls[1]["memories"][0]["content"]


def test_recall_excludes_action_tasks_from_demo_report_by_default(client, monkeypatch):
    mock_insights(client, monkeypatch)
    calls = mock_recall(client, monkeypatch)
    add_transcript(client, source="demo")
    client.post("/api/meetings/summarize", json={"include_demo": True})
    client.post("/api/recall", json={"question": "有什么待办"})
    assert calls[0]["memories"] == calls[0]["transcripts"] == []
    client.post("/api/recall", json={"question": "有什么待办", "include_demo": True})
    assert calls[1]["memories"][0]["source"] == "action_task"
    assert calls[1]["transcripts"][0]["source"] == "demo"


def test_recall_service_value_error_becomes_422(client, monkeypatch):
    calls = mock_recall(client, monkeypatch, error=True)
    response = client.post("/api/recall", json={"question": " "})
    assert response.status_code == 422
    assert "有效字符" in response.json()["detail"]
    assert len(calls) == 1


@pytest.mark.parametrize("path,payload", [
    ("/api/meetings/summarize", {"hours": 0}),
    ("/api/meetings/summarize", {"hours": 169}),
    ("/api/recall", {"question": ""}),
    ("/api/recall", {"question": "x" * 501}),
])
def test_feature_input_boundaries_reject_before_services(client, monkeypatch, path, payload):
    meeting_calls = mock_insights(client, monkeypatch)
    recall_calls = mock_recall(client, monkeypatch)
    assert client.post(path, json=payload).status_code == 422
    assert meeting_calls == recall_calls == []
