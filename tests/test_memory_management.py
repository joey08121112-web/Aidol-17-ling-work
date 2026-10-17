"""Memory corrections and favorites survive SQLite schema upgrades."""
import sqlite3

import pytest

from app.storage import Store


def add_memory(store, content="喜欢清淡", kind="preference"):
    return store.add("memories", content=content, kind=kind, source="manual")


def test_old_database_migration_preserves_memory_and_is_repeatable(tmp_path):
    path = tmp_path / "old.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE memories (id TEXT PRIMARY KEY, kind TEXT NOT NULL, content TEXT NOT NULL, source TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execute("INSERT INTO memories VALUES(?,?,?,?,?)", ("old-id", "fact", "之前记录", "rules", "2026-10-10T09:00:00Z"))
    first = Store(str(path))
    assert first.list("memories")[0] == {
        "id": "old-id", "kind": "fact", "content": "之前记录", "source": "rules",
        "created_at": "2026-10-10T09:00:00Z", "pinned": 0, "updated_at": ""}
    second = Store(str(path))
    assert second.list("memories") == first.list("memories")
    assert second.update_memory("old-id", "更正记录", "fact", True)


def test_correct_memory_preserves_id_creation_time_and_source(store):
    original = add_memory(store)
    assert original["pinned"] == 0 and original["updated_at"] == ""
    assert store.update_memory(original["id"], "  实际上不吃花生  ", "preference", True)
    updated = store.list("memories")[0]
    assert updated["content"] == "实际上不吃花生"
    assert updated["pinned"] == 1 and updated["updated_at"]
    assert updated["created_at"] == original["created_at"]
    assert updated["source"] == original["source"]
    assert updated["id"] == original["id"]


def test_pinned_order_applies_to_search_and_normal_list(store):
    old = add_memory(store, "项目旧信息", "fact")
    new = add_memory(store, "项目新信息", "fact")
    with store.connect() as db:
        db.execute("UPDATE memories SET created_at=? WHERE id=?", ("2026-10-10T09:00:00Z", old["id"]))
        db.execute("UPDATE memories SET created_at=? WHERE id=?", ("2026-10-11T09:00:00Z", new["id"]))
    assert store.list("memories")[0]["id"] == new["id"]
    store.update_memory(old["id"], old["content"], old["kind"], True)
    assert store.list("memories", limit=1)[0]["id"] == old["id"]
    assert store.list("memories", query="项目")[0]["id"] == old["id"]
    store.update_memory(old["id"], old["content"], "trip", False)
    assert store.list("memories")[0]["id"] == new["id"]
    assert store.list("memories")[1]["kind"] == "trip"


def test_deleted_or_missing_memory_cannot_be_updated(store):
    row = add_memory(store)
    assert store.delete_memory(row["id"])
    assert not store.update_memory(row["id"], "新内容", "fact", True)
    assert not store.delete_memory(row["id"])
    assert store.list("memories") == []


@pytest.mark.parametrize("content,kind,pinned", [
    ("", "fact", False), ("   ", "fact", False), (None, "fact", False),
    ("字" * 6001, "fact", False), ("内容", "invalid", False),
    ("内容", "fact", "false"), ("内容", "fact", 2),
], ids=["empty", "whitespace", "null", "too-long", "invalid-kind", "string-pin", "invalid-pin"])
def test_invalid_correction_does_not_mutate_record(store, content, kind, pinned):
    row = add_memory(store)
    with pytest.raises(ValueError):
        store.update_memory(row["id"], content, kind, pinned)
    assert store.list("memories")[0] == row


def test_memory_migration_does_not_affect_meeting_reports_or_tasks(store):
    report = store.save_meeting_report("test-signature", {"summary": "会议摘要", "tasks": [
        {"content": "提交材料", "owner": "小王", "due": "周五", "source_ids": ["transcript-1"]}]})
    task = store.list("action_tasks")[0]
    row = add_memory(store)
    restarted = Store(store.path)
    restarted.update_memory(row["id"], "更正偏好", "preference", True)
    assert restarted.meeting_report("test-signature") == report
    assert restarted.list("action_tasks") == [task]
    assert restarted.action_task_status(task["id"], "done")
    restarted.clear()
    assert restarted.list("memories") == []
    assert restarted.list("action_tasks") == []
    assert restarted.meeting_report() is None
