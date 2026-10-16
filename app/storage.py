"""SQLite persistence. One connection per transaction; no shared mutable cursor."""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import time
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
                CREATE TABLE IF NOT EXISTS mobile_turns (
                  id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL);
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
            for table in ("memories", "transcripts", "events", "drafts", "processed", "meeting_reports", "action_tasks", "mobile_turns"):
                db.execute(f"DELETE FROM {table}")

    def mobile_turn(self, turn_id, fingerprint, reserve=True):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT fingerprint,result FROM mobile_turns WHERE id=?", (turn_id,)).fetchone()
            if row:
                if row['fingerprint'] != fingerprint or row['result'] is None:
                    raise ValueError("该请求已提交或内容不一致，请查看历史后重新发起")
                return json.loads(row['result'])
            if reserve:
                db.execute("INSERT INTO mobile_turns VALUES(?,?,NULL,?)", (turn_id, fingerprint, now()))

    def finish_mobile_turn(self, turn_id, result):
        with self.connect() as db:
            db.execute("UPDATE mobile_turns SET result=? WHERE id=?", (json.dumps(result, ensure_ascii=False), turn_id))


class AccountStore:
    """Shared identity database; conversation data lives in separate UUID databases."""
    connect = Store.connect

    def __init__(self, path):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                    password TEXT NOT NULL, recovery TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (
                    token TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
                CREATE TABLE IF NOT EXISTS limits (
                    key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires REAL NOT NULL);
            """)

    def user(self, username):
        with self.connect() as db:
            row = db.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        return dict(row) if row else None

    def register(self, username, password, recovery, max_users):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT count(*) FROM users").fetchone()[0] >= max_users:
                raise OverflowError("测试名额已满")
            user = dict(id=uuid4().hex, username=username, created_at=now())
            db.execute("INSERT INTO users VALUES(?,?,?,?,?)", (user['id'], username, password, recovery, user['created_at']))
            return user

    def issue_session(self, user_id, token, days=30, expected_password=None):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            user = db.execute("SELECT password FROM users WHERE id=?", (user_id,)).fetchone()
            if not user or (expected_password is not None and user[0] != expected_password):
                return False
            db.execute("DELETE FROM sessions WHERE expires < ?", (time.time(),))
            db.execute("INSERT INTO sessions VALUES(?,?,?)", (token, user_id, time.time() + days * 86400))
            # Keep a bounded number of signed-in devices per account.
            db.execute("DELETE FROM sessions WHERE user_id=? AND token NOT IN "
                       "(SELECT token FROM sessions WHERE user_id=? ORDER BY expires DESC LIMIT 5)", (user_id, user_id))
            return True

    def authenticate(self, token):
        with self.connect() as db:
            row = db.execute("SELECT users.id, username, created_at FROM users JOIN sessions ON users.id=sessions.user_id "
                             "WHERE token=? AND expires>?", (token, time.time())).fetchone()
        return dict(row) if row else None

    def logout(self, token):
        with self.connect() as db:
            db.execute("DELETE FROM sessions WHERE token=?", (token,))

    def rotate(self, user_id, old_hash, password, recovery=None):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            changed = db.execute("UPDATE users SET password=?, recovery=COALESCE(?,recovery) WHERE id=? AND password=?",
                                 (password, recovery, user_id, old_hash)).rowcount
            if changed:
                db.execute("DELETE FROM sessions WHERE user_id=?", (user_id,))
            return bool(changed)

    def delete(self, user_id):
        with self.connect() as db:
            db.execute("DELETE FROM sessions WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM users WHERE id=?", (user_id,))

    def consume(self, key, maximum, seconds):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            stamp = time.time()
            db.execute("DELETE FROM limits WHERE expires<=?", (stamp,))
            row = db.execute("SELECT count FROM limits WHERE key=?", (key,)).fetchone()
            if row and row[0] >= maximum:
                return False
            db.execute("INSERT INTO limits VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1", (key, stamp + seconds))
            return True
