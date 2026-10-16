"""SQLite persistence. One connection per transaction; no shared mutable cursor."""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
from uuid import uuid4

from .domain import Settings


def now():
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS memories (
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL, content TEXT NOT NULL,
                  source TEXT NOT NULL, created_at TEXT NOT NULL,
                  pinned INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS transcripts (
                  id TEXT PRIMARY KEY, speaker TEXT NOT NULL, text TEXT NOT NULL,
                  mode TEXT NOT NULL, source TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS events (
                  id TEXT PRIMARY KEY, type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS drafts (
                  id TEXT PRIMARY KEY, contact TEXT NOT NULL, text TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS processed (id TEXT PRIMARY KEY, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS meeting_reports (
                  id TEXT PRIMARY KEY, signature TEXT NOT NULL UNIQUE,
                  payload TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS action_tasks (
                  id TEXT PRIMARY KEY, report_id TEXT NOT NULL, content TEXT NOT NULL,
                  owner TEXT NOT NULL, due TEXT NOT NULL, source_ids TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'open', created_at TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS memory_created ON memories(created_at);
            """)
            # Preserve existing local databases; serialize schema checks and ALTERs
            # so two startup instances cannot both add the same missing column.
            db.execute("BEGIN IMMEDIATE")
            memory_columns = {row["name"] for row in db.execute("PRAGMA table_info(memories)")}
            if "pinned" not in memory_columns:
                db.execute("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            if "updated_at" not in memory_columns:
                db.execute("ALTER TABLE memories ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''")
            db.execute("CREATE INDEX IF NOT EXISTS memory_pinned_created ON memories(pinned DESC, created_at DESC)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def settings(self) -> Settings:
        with self.connect() as db:
            row = db.execute("SELECT value FROM settings WHERE id=1").fetchone()
        return Settings.model_validate_json(row[0]) if row else Settings()

    def save_settings(self, value: Settings):
        with self.connect() as db:
            db.execute("INSERT INTO settings VALUES(1,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value",
                       (value.model_dump_json(),))

    def claim(self, event_id):
        with self.connect() as db:
            result = db.execute("INSERT OR IGNORE INTO processed VALUES(?,?)", (event_id, now()))
            db.execute("DELETE FROM processed WHERE created_at < datetime('now','-7 day')")
            return result.rowcount == 1

    def add(self, table, **fields):
        if table not in {"memories", "transcripts", "events", "drafts"}:
            raise ValueError("Unknown collection")
        row = {"id": uuid4().hex, **fields, "created_at": now()}
        if table == "memories":
            row.setdefault("pinned", 0)
            row.setdefault("updated_at", "")
        with self.connect() as db:
            db.execute(f"INSERT INTO {table} ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", tuple(row.values()))
        return row

    def list(self, table, limit=100, query=""):
        if table not in {"memories", "transcripts", "events", "drafts", "action_tasks"}:
            raise ValueError("Unknown collection")
        with self.connect() as db:
            if query and table == "memories":
                rows = db.execute("SELECT * FROM memories WHERE content LIKE ? ORDER BY pinned DESC, created_at DESC LIMIT ?",
                                  (f"%{query}%", limit)).fetchall()
            else:
                order = "pinned DESC, created_at DESC" if table == "memories" else "created_at DESC"
                rows = db.execute(f"SELECT * FROM {table} ORDER BY {order} LIMIT ?", (limit,)).fetchall()
        result = [dict(r) for r in rows]
        if table == "events":
            for row in result:
                row["payload"] = json.loads(row["payload"])
        if table == "action_tasks":
            for row in result:
                row["source_ids"] = json.loads(row["source_ids"])
        return result

    def meeting_report(self, signature=None):
        with self.connect() as db:
            row = db.execute("SELECT * FROM meeting_reports WHERE signature=?", (signature,)).fetchone() if signature else db.execute(
                "SELECT * FROM meeting_reports ORDER BY created_at DESC LIMIT 1").fetchone()
        if not row:
            return None
        return {"id": row["id"], "created_at": row["created_at"], **json.loads(row["payload"])}

    def save_meeting_report(self, signature, payload):
        """Commit a report and its action items together; retries never duplicate tasks."""
        with self.connect() as db:
            existing = db.execute("SELECT * FROM meeting_reports WHERE signature=?", (signature,)).fetchone()
            if existing:
                return {"id": existing["id"], "created_at": existing["created_at"], **json.loads(existing["payload"])}
            report_id, created = uuid4().hex, now()
            db.execute("INSERT INTO meeting_reports VALUES(?,?,?,?)", (report_id, signature, json.dumps(payload, ensure_ascii=False), created))
            for task in payload.get("tasks", []):
                db.execute("INSERT INTO action_tasks VALUES(?,?,?,?,?,?,?,?)", (
                    uuid4().hex, report_id, task["content"], task["owner"], task["due"],
                    json.dumps(task["source_ids"]), "open", created))
        return {"id": report_id, "created_at": created, **payload}

    def action_task_status(self, task_id, status):
        if status not in {"open", "done"}:
            raise ValueError("invalid action task status")
        with self.connect() as db:
            return db.execute("UPDATE action_tasks SET status=? WHERE id=?", (status, task_id)).rowcount > 0

    def all_meeting_reports(self):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM meeting_reports ORDER BY created_at DESC LIMIT 10000").fetchall()
        return [{"id": row["id"], "created_at": row["created_at"], **json.loads(row["payload"])} for row in rows]

    def delete_memory(self, memory_id):
        with self.connect() as db:
            return db.execute("DELETE FROM memories WHERE id=?", (memory_id,)).rowcount > 0

    def remember_transcript(self, transcript_id):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            transcript = db.execute("SELECT * FROM transcripts WHERE id=?", (transcript_id,)).fetchone()
            if not transcript:
                return None
            source = ("demo_transcript:" if transcript["source"] == "demo" else "transcript:") + transcript_id
            existing = db.execute("SELECT * FROM memories WHERE source=?", (source,)).fetchone()
            if existing:
                return dict(existing)
            row = {"id": uuid4().hex, "kind": "fact", "content": transcript["text"], "source": source,
                   "created_at": now(), "pinned": 0, "updated_at": ""}
            db.execute("INSERT INTO memories (id,kind,content,source,created_at,pinned,updated_at) VALUES(?,?,?,?,?,?,?)", tuple(row.values()))
        return row

    def memory_source(self, memory_id):
        with self.connect() as db:
            row = db.execute("SELECT source FROM memories WHERE id=?", (memory_id,)).fetchone()
            if not row or not row["source"].startswith(("transcript:", "demo_transcript:")):
                return None
            source = db.execute("SELECT * FROM transcripts WHERE id=?", (row["source"].split(":", 1)[1],)).fetchone()
        return dict(source) if source else None

    def update_memory(self, memory_id: str, content: str, kind: str, pinned: bool) -> bool:
        """Correct or pin an existing memory without changing its original date/source."""
        if not isinstance(content, str) or not content.strip() or len(content) > 6000:
            raise ValueError("memory content must contain 1–6000 characters")
        if kind not in {"preference", "task", "fact", "trip"}:
            raise ValueError("invalid memory kind")
        if not isinstance(pinned, bool):
            raise ValueError("pinned must be boolean")
        with self.connect() as db:
            return db.execute(
                "UPDATE memories SET content=?, kind=?, pinned=?, updated_at=? WHERE id=?",
                (content.strip(), kind, int(pinned), now(), memory_id)).rowcount > 0

    def draft_status(self, draft_id, status):
        if status not in {"copied", "dismissed"}:
            raise ValueError("invalid status")
        with self.connect() as db:
            return db.execute("UPDATE drafts SET status=? WHERE id=? AND status='pending'", (status, draft_id)).rowcount > 0

    def clear(self):
        with self.connect() as db:
            for table in ("memories", "transcripts", "events", "drafts", "processed", "meeting_reports", "action_tasks"):
                db.execute(f"DELETE FROM {table}")
