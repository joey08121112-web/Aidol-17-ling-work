"""SQLite persistence. One connection per transaction; no shared mutable cursor."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import sqlite3
import time
import re
from uuid import uuid4
from zoneinfo import ZoneInfo

from .domain import Settings
from .search import index_text, query_terms, is_non_assertion


def now():
    return datetime.now(timezone.utc).isoformat()


WORK_SEGMENT_MS = 20000  # Older clients cut fixed 20 s segments and send no offset.
PCM_BYTES_PER_MS = 32    # 16 kHz mono PCM16.


def _utterance_rows(transcript_id, offset_ms, duration_ms, text, utterances):
    """Normalise ASR sentences onto the meeting timeline; fall back to one segment-wide sentence."""
    rows = []
    for item in utterances or ():
        if not isinstance(item, dict):
            continue
        body, start, end = str(item.get('text') or '').strip(), item.get('start_time'), item.get('end_time')
        if not body or type(start) is not int or type(end) is not int or start < 0 or end < start:
            continue
        if duration_ms:
            start, end = min(start, duration_ms), min(end, duration_ms)
        rows.append((start, end, body))
    if not rows:
        rows = [(0, duration_ms, text.strip())]
    rows.sort(key=lambda row: row[0])
    return [(f'{transcript_id}.{ordinal}', ordinal, offset_ms + start, offset_ms + end, body)
            for ordinal, (start, end, body) in enumerate(rows)]


class Store:
    def __init__(self, path: str):
        self.path = path
        # users/<uuid>.sqlite3 keeps its meeting audio in users/<uuid>/work-audio/<meeting>/.
        self.audio_root = Path(path).with_suffix('') / 'work-audio'
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
                CREATE TABLE IF NOT EXISTS work_meetings (
                  id TEXT PRIMARY KEY, started_at TEXT NOT NULL, ended_at TEXT,
                  status TEXT NOT NULL DEFAULT 'recording', expected_segments INTEGER,
                  summary_status TEXT NOT NULL DEFAULT 'waiting', report_id TEXT,
                  error TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS work_meeting_segments (
                  meeting_id TEXT NOT NULL, segment_index INTEGER NOT NULL,
                  request_id TEXT NOT NULL UNIQUE, transcript_id TEXT,
                  PRIMARY KEY(meeting_id, segment_index));
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
            turn_columns = {row["name"] for row in db.execute("PRAGMA table_info(mobile_turns)")}
            if "status" not in turn_columns:
                db.execute("ALTER TABLE mobile_turns ADD COLUMN status TEXT NOT NULL DEFAULT 'processing'")
                db.execute("UPDATE mobile_turns SET status='completed' WHERE result IS NOT NULL")
            if "stage" not in turn_columns:
                db.execute("ALTER TABLE mobile_turns ADD COLUMN stage TEXT NOT NULL DEFAULT 'business'")
            transcript_columns = {row["name"] for row in db.execute("PRAGMA table_info(transcripts)")}
            if 'interaction_source' not in transcript_columns:
                db.execute("ALTER TABLE transcripts ADD COLUMN interaction_source TEXT NOT NULL DEFAULT 'ambient_capture'")
            db.execute("CREATE TABLE IF NOT EXISTS recall_exclusions (transcript_id TEXT PRIMARY KEY)")
            meeting_columns = {row['name'] for row in db.execute('PRAGMA table_info(work_meetings)')}
            for name in ('processed_sections', 'total_sections'):
                if name not in meeting_columns:
                    db.execute(f'ALTER TABLE work_meetings ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0')
            if 'summary_stage' not in meeting_columns:
                db.execute("ALTER TABLE work_meetings ADD COLUMN summary_stage TEXT NOT NULL DEFAULT 'waiting'")
            if 'audio_status' not in meeting_columns:
                db.execute("ALTER TABLE work_meetings ADD COLUMN audio_status TEXT NOT NULL DEFAULT 'none'")
            segment_columns = {row['name'] for row in db.execute('PRAGMA table_info(work_meeting_segments)')}
            for name, ddl in (('offset_ms', 'INTEGER'), ('duration_ms', 'INTEGER NOT NULL DEFAULT 0'),
                              ('audio_bytes', 'INTEGER NOT NULL DEFAULT 0')):
                if name not in segment_columns:
                    db.execute(f'ALTER TABLE work_meeting_segments ADD COLUMN {name} {ddl}')
            # Sentence times are milliseconds on the meeting's audio timeline (pauses excluded).
            db.execute("CREATE TABLE IF NOT EXISTS work_utterances (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL, "
                       "transcript_id TEXT NOT NULL, segment_index INTEGER NOT NULL, ordinal INTEGER NOT NULL, "
                       "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS work_utterance_order ON work_utterances(meeting_id, start_ms)")
            db.execute("CREATE TABLE IF NOT EXISTS work_meeting_events (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL, "
                       "kind TEXT NOT NULL, offset_ms INTEGER NOT NULL, origin TEXT NOT NULL, created_at TEXT NOT NULL)")
            # One transactional migration indexes existing history, then triggers keep
            # updates/deletes in sync, including writes inside report transactions.
            indexed = db.execute("SELECT 1 FROM sqlite_master WHERE name='recall_index'").fetchone()
            db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS recall_index USING fts5(collection UNINDEXED, record_id UNINDEXED, terms)")
            for table, expression in (("memories", "content"), ("transcripts", "text"),
                                      ("action_tasks", "content || ' ' || owner || ' ' || due || ' 任务 待办 完成'")):
                # These expressions are static identifiers, never user SQL.
                new_expression = expression if table == "action_tasks" else "new." + expression
                if table == "action_tasks":
                    new_expression = "new.content || ' ' || new.owner || ' ' || new.due || ' 任务 待办 完成'"
                db.execute(f"CREATE TRIGGER IF NOT EXISTS recall_{table}_insert AFTER INSERT ON {table} BEGIN INSERT INTO recall_index VALUES('{table}',new.id,aidol_terms({new_expression})); END")
                db.execute(f"CREATE TRIGGER IF NOT EXISTS recall_{table}_delete AFTER DELETE ON {table} BEGIN DELETE FROM recall_index WHERE collection='{table}' AND record_id=old.id; END")
                db.execute(f"CREATE TRIGGER IF NOT EXISTS recall_{table}_update AFTER UPDATE ON {table} BEGIN DELETE FROM recall_index WHERE collection='{table}' AND record_id=old.id; INSERT INTO recall_index VALUES('{table}',new.id,aidol_terms({new_expression})); END")
                if not indexed:
                    db.execute(f"INSERT INTO recall_index SELECT ?,id,aidol_terms({expression}) FROM {table}", (table,))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.create_function("aidol_terms", 1, index_text, deterministic=True)
        db.create_function("aidol_non_assertion", 2, is_non_assertion, deterministic=True)
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

    def conversation_memories(self):
        """Demo context: all saved memories in this account, without list limits."""
        with self.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT * FROM memories ORDER BY created_at,id")]

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
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM meeting_reports WHERE signature=?", (signature,)).fetchone()
            if existing:
                return {"id": existing["id"], "created_at": existing["created_at"], **json.loads(existing["payload"])}
            report_id, created = uuid4().hex, now()
            db.execute("INSERT INTO meeting_reports VALUES(?,?,?,?)", (report_id, signature, json.dumps(payload, ensure_ascii=False), created))
            for task in payload.get("tasks", []):
                candidates = db.execute("SELECT source_ids FROM action_tasks WHERE content=? AND owner=? AND due=?",
                                        (task["content"], task["owner"], task["due"])).fetchall()
                if any(set(json.loads(row["source_ids"])) & set(task["source_ids"]) for row in candidates):
                    continue
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
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT source FROM memories WHERE id=?', (memory_id,)).fetchone()
            if row:
                if row['source'].startswith(('transcript:', 'demo_transcript:')):
                    db.execute('INSERT OR IGNORE INTO recall_exclusions VALUES(?)', (row['source'].split(':', 1)[1],))
                self._invalidate_recall(db)
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
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute('SELECT content,source FROM memories WHERE id=?', (memory_id,)).fetchone()
            if previous and previous['content'] != content.strip():
                if previous['source'].startswith(('transcript:', 'demo_transcript:')):
                    db.execute('INSERT OR IGNORE INTO recall_exclusions VALUES(?)', (previous['source'].split(':', 1)[1],))
                self._invalidate_recall(db)
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
            for table in ("work_utterances", "work_meeting_events", "work_meeting_segments", "work_meetings", "memories", "transcripts", "events", "drafts", "processed", "meeting_reports", "action_tasks", "mobile_turns", "recall_exclusions"):
                db.execute(f"DELETE FROM {table}")
        shutil.rmtree(self.audio_root, ignore_errors=True)

    def mobile_turn(self, turn_id, fingerprint, reserve=True):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT fingerprint,result,status,stage FROM mobile_turns WHERE id=?", (turn_id,)).fetchone()
            if row:
                if row['fingerprint'] != fingerprint:
                    raise ValueError("该请求已提交或内容不一致，请查看历史后重新发起")
                if row['result'] is not None:
                    return json.loads(row['result'])
                if row['stage'] == 'asr':
                    if reserve:
                        db.execute("UPDATE mobile_turns SET status='processing' WHERE id=?", (turn_id,))
                    return None
                raise ValueError("该请求可能已产生记录，请查询请求状态和历史，不要重复执行")
            if reserve:
                stage = 'asr' if fingerprint.startswith('pcm:') else 'business'
                db.execute("INSERT INTO mobile_turns (id,fingerprint,result,created_at,status,stage) VALUES(?,?,NULL,?,'processing',?)", (turn_id, fingerprint, now(), stage))

    def finish_mobile_turn(self, turn_id, result):
        with self.connect() as db:
            db.execute("UPDATE mobile_turns SET result=?,status='completed',stage='completed' WHERE id=?", (json.dumps(result, ensure_ascii=False), turn_id))

    def mobile_turn_stage(self, turn_id, stage):
        with self.connect() as db:
            db.execute("UPDATE mobile_turns SET stage=? WHERE id=?", (stage, turn_id))

    def fail_mobile_turn(self, turn_id):
        with self.connect() as db:
            db.execute("UPDATE mobile_turns SET status='failed' WHERE id=? AND result IS NULL", (turn_id,))

    def mobile_turn_status(self, turn_id):
        with self.connect() as db:
            row = db.execute("SELECT id,status,stage,result FROM mobile_turns WHERE id=?", (turn_id,)).fetchone()
        if row is None:
            return None
        return {"request_id": row['id'], "status": row['status'], "stage": row['stage'],
                "retryable": row['stage'] == 'asr',
                "result": json.loads(row['result']) if row['result'] else None}

    def recall_records(self, question):
        """Search all local history first; bound only the evidence sent to recall."""
        terms = query_terms(question)
        if not terms:
            return [], []
        match = ' OR '.join('"' + term + '"' for term in sorted(terms))
        found = {}
        month_bounds = None
        if '上个月' in question:
            current = datetime.now(ZoneInfo(self.settings().timezone))
            end = current.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            year, month = (end.year - 1, 12) if end.month == 1 else (end.year, end.month - 1)
            start = end.replace(year=year, month=month)
            month_bounds = (start.astimezone(timezone.utc).isoformat(), end.astimezone(timezone.utc).isoformat())
        pending_only = bool(re.search(r'未完成|没完成|待办|还没做|还有什么.*做', question))
        with self.connect() as db:
            for table in ('action_tasks', 'memories', 'transcripts'):
                params = [match, table]
                extra = (" AND aidol_non_assertion(r.text,r.speaker)=0 AND NOT EXISTS (SELECT 1 FROM recall_exclusions e WHERE e.transcript_id=r.id)"
                         if table == 'transcripts' else '')
                if table == 'memories':
                    extra = " AND aidol_non_assertion(r.content,'用户')=0"
                if table == 'action_tasks':
                    extra = " AND NOT EXISTS (SELECT 1 FROM json_each(r.source_ids) s JOIN recall_exclusions e ON e.transcript_id=s.value)"
                    if pending_only:
                        extra += " AND r.status='open'"
                if month_bounds:
                    extra += ' AND julianday(r.created_at)>=julianday(?) AND julianday(r.created_at)<julianday(?)'
                    params.extend(month_bounds)
                rows = db.execute(f"SELECT r.* FROM recall_index JOIN {table} r ON r.id=recall_index.record_id "
                                  f"WHERE recall_index MATCH ? AND collection=?{extra} "
                                  "ORDER BY bm25(recall_index),r.created_at DESC LIMIT 100", params).fetchall()
                found[table] = [dict(row) for row in rows]
        tasks = [{'id': 'task:' + row['id'], 'kind': 'task', 'source': 'action_task',
                  'created_at': row['created_at'], 'content':
                  f"{row['content']}；负责人：{row['owner']}；期限：{row['due']}；完成状态：{'已完成' if row['status'] == 'done' else '未完成'}"}
                 for row in found['action_tasks']]
        return tasks + found['memories'], found['transcripts']

    def conversation_history(self, limit=12):
        with self.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT * FROM transcripts r WHERE NOT EXISTS (SELECT 1 FROM recall_exclusions e WHERE e.transcript_id=r.id) ORDER BY created_at DESC LIMIT ?", (limit,))]

    def meeting_records(self, cutoff):
        with self.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT * FROM transcripts r WHERE mode='work' AND speaker!='assistant' "
                "AND interaction_source='ambient_capture' AND created_at>=? "
                "AND NOT EXISTS (SELECT 1 FROM recall_exclusions e WHERE e.transcript_id=r.id) ORDER BY created_at DESC", (cutoff,))]

    def start_work_meeting(self, meeting_id):
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO work_meetings(id,started_at) VALUES(?,?)", (meeting_id, now()))
        return self.work_meeting(meeting_id)

    def work_meetings(self, limit=100):
        with self.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT m.*,json_extract(r.payload,'$.title') AS title,json_extract(r.payload,'$.summary') AS summary, "
                "(SELECT count(*) FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id "
                "WHERE s.meeting_id=m.id) AS transcript_count, "
                "(SELECT COALESCE(max(COALESCE(s.offset_ms,s.segment_index*?)+s.duration_ms),0) FROM work_meeting_segments s "
                "WHERE s.meeting_id=m.id) AS duration_ms FROM work_meetings m LEFT JOIN meeting_reports r ON r.id=m.report_id "
                "ORDER BY m.started_at DESC LIMIT ?", (WORK_SEGMENT_MS, limit))]

    def work_meeting(self, meeting_id):
        retention = self.settings().work_audio_retention_days
        with self.connect() as db:
            row = db.execute("SELECT * FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None:
                return None
            sources = [dict(item) for item in db.execute(
                "SELECT t.*,s.segment_index,COALESCE(s.offset_ms,s.segment_index*?) AS offset_ms,s.duration_ms "
                "FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id "
                "WHERE s.meeting_id=? ORDER BY s.segment_index", (WORK_SEGMENT_MS, meeting_id))]
            utterances = [dict(item) for item in db.execute(
                "SELECT u.id,u.transcript_id,u.segment_index,u.start_ms,u.end_ms,u.text FROM work_utterances u "
                "JOIN transcripts t ON t.id=u.transcript_id WHERE u.meeting_id=? ORDER BY u.segment_index,u.ordinal", (meeting_id,))]
            events = [dict(item) for item in db.execute(
                "SELECT id,kind,offset_ms,origin,created_at FROM work_meeting_events WHERE meeting_id=? "
                "ORDER BY offset_ms,created_at", (meeting_id,))]
            audio = db.execute("SELECT count(*) AS total,COALESCE(sum(audio_bytes>0),0) AS kept,COALESCE(sum(audio_bytes),0) AS bytes "
                               "FROM work_meeting_segments WHERE meeting_id=?", (meeting_id,)).fetchone()
            report = db.execute("SELECT payload FROM meeting_reports WHERE id=?", (row['report_id'],)).fetchone()
        status = row['audio_status'] if row['audio_status'] in ('expired', 'deleted') else (
            'none' if not audio['bytes'] else 'saved' if audio['kept'] == audio['total'] else 'partial')
        expires = None
        if status in ('saved', 'partial') and row['ended_at']:
            expires = (datetime.fromisoformat(row['ended_at']) + timedelta(days=retention)).isoformat()
        return {**dict(row), 'sources': sources, 'transcript_count': len(sources),
                'received_segments': audio['total'], 'utterances': utterances, 'events': events,
                'audio': {'status': status, 'bytes': audio['bytes'], 'segments_with_audio': audio['kept'],
                          'retention_days': retention, 'expires_at': expires},
                'report': json.loads(report['payload']) if report else None}

    @staticmethod
    def work_meeting_evidence(meeting):
        """Model input: one row per saved sentence, so citations point at timed transcript lines."""
        by_transcript = {}
        for item in meeting['utterances']:
            by_transcript.setdefault(item['transcript_id'], []).append(item)
        rows = []
        for source in meeting['sources']:
            parts = by_transcript.get(source['id'])
            if not parts:
                rows.append(source)  # Saved before sentence timing existed.
                continue
            rows.extend({**source, 'id': part['id'], 'text': part['text'], 'start_ms': part['start_ms']} for part in parts)
        return rows

    def _work_audio_dir(self, meeting_id):
        if not re.fullmatch(r'[a-zA-Z0-9-]{16,64}', meeting_id):
            raise ValueError('invalid meeting id')
        return self.audio_root / meeting_id

    def check_work_segment(self, meeting_id, segment_index, request_id):
        with self.connect() as db:
            meeting = db.execute("SELECT status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            segment = db.execute("SELECT request_id FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?",
                                 (meeting_id, segment_index)).fetchone()
        if meeting is None:
            raise ValueError('会议不存在')
        if meeting['status'] != 'recording':
            raise ValueError('会议已结束，不能追加录音')
        if segment is not None and segment['request_id'] != request_id:
            raise ValueError('会议片段编号已被使用')

    def save_work_segment(self, meeting_id, segment_index, request_id, text, *, offset_ms=None, duration_ms=0,
                          utterances=(), audio=None):
        """Persist ASR evidence and session membership in the same transaction, including silence receipts.

        Audio is written after the rows commit; a failed write keeps the text and records no audio."""
        keep_audio = bool(audio) and self.settings().work_audio_retention_days > 0
        offset = segment_index * WORK_SEGMENT_MS if offset_ms is None else offset_ms
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT status,audio_status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None or row['status'] != 'recording':
                raise ValueError('会议不存在或已结束')
            existing = db.execute("SELECT * FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?",
                                  (meeting_id, segment_index)).fetchone()
            if existing:
                if existing['request_id'] != request_id:
                    raise ValueError('会议片段编号已被使用')
                return existing['transcript_id']
            keep_audio = keep_audio and row['audio_status'] not in ('expired', 'deleted')
            transcript_id = uuid4().hex if text.strip() else None
            if transcript_id:
                db.execute("INSERT INTO transcripts(id,speaker,text,mode,source,created_at,interaction_source) VALUES(?,?,?,?,?,?,?)",
                           (transcript_id, '现场发言（未分离声纹）', text, 'work', 'asr', now(), 'ambient_capture'))
                db.executemany("INSERT INTO work_utterances VALUES(?,?,?,?,?,?,?,?)",
                               [(uid, meeting_id, transcript_id, segment_index, ordinal, start, end, body)
                                for uid, ordinal, start, end, body in
                                _utterance_rows(transcript_id, offset, duration_ms, text, utterances)])
            db.execute("INSERT INTO work_meeting_segments(meeting_id,segment_index,request_id,transcript_id,offset_ms,duration_ms,audio_bytes) "
                       "VALUES(?,?,?,?,?,?,?)", (meeting_id, segment_index, request_id, transcript_id, offset, duration_ms,
                                                 len(audio) if keep_audio else 0))
        if keep_audio:
            target = self._work_audio_dir(meeting_id) / f'{segment_index:06d}.pcm'
            try:
                target.parent.mkdir(parents=True, exist_ok=True)
                temporary = target.with_suffix('.tmp')
                temporary.write_bytes(audio)
                os.replace(temporary, target)
            except OSError:
                with self.connect() as db:
                    db.execute("UPDATE work_meeting_segments SET audio_bytes=0 WHERE meeting_id=? AND segment_index=?",
                               (meeting_id, segment_index))
        return transcript_id

    def work_audio(self, meeting_id, start_ms, end_ms):
        """PCM16 for [start_ms, end_ms) of the meeting timeline; gaps without audio are silence."""
        with self.connect() as db:
            segments = [dict(row) for row in db.execute(
                "SELECT segment_index,COALESCE(offset_ms,segment_index*?) AS offset_ms,audio_bytes FROM work_meeting_segments "
                "WHERE meeting_id=? AND audio_bytes>0 ORDER BY offset_ms", (WORK_SEGMENT_MS, meeting_id))]
        if not segments:
            return None
        last = segments[-1]
        end_ms = min(end_ms, last['offset_ms'] + last['audio_bytes'] // PCM_BYTES_PER_MS)
        if end_ms <= start_ms:
            return b''
        out = bytearray((end_ms - start_ms) * PCM_BYTES_PER_MS)
        folder = self._work_audio_dir(meeting_id)
        for i, segment in enumerate(segments):
            begin = segment['offset_ms']
            # A short padded tail may overlap the next segment; the later segment wins.
            finish = begin + segment['audio_bytes'] // PCM_BYTES_PER_MS
            if i + 1 < len(segments):
                finish = min(finish, segments[i + 1]['offset_ms'])
            low, high = max(start_ms, begin), min(end_ms, finish)
            if low >= high:
                continue
            try:
                with open(folder / f"{segment['segment_index']:06d}.pcm", 'rb') as source:
                    source.seek((low - begin) * PCM_BYTES_PER_MS)
                    chunk = source.read((high - low) * PCM_BYTES_PER_MS)
            except OSError:
                continue
            position = (low - start_ms) * PCM_BYTES_PER_MS
            out[position:position + len(chunk)] = chunk
        return bytes(out)

    def delete_work_audio(self, meeting_id, status='deleted'):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None:
                return None
            if row['status'] == 'recording' and status == 'deleted':
                raise ValueError('请先结束会议，再删除录音')
            db.execute("UPDATE work_meetings SET audio_status=? WHERE id=?", (status, meeting_id))
            db.execute("UPDATE work_meeting_segments SET audio_bytes=0 WHERE meeting_id=?", (meeting_id,))
        shutil.rmtree(self._work_audio_dir(meeting_id), ignore_errors=True)
        return True

    def purge_work_audio(self):
        """Apply the account's current retention to finished (or long-abandoned) meetings."""
        days = self.settings().work_audio_retention_days
        cutoff = datetime.now(timezone.utc) - timedelta(days=days)
        with self.connect() as db:
            expired = [row['id'] for row in db.execute(
                "SELECT m.id FROM work_meetings m WHERE m.audio_status NOT IN ('expired','deleted') "
                "AND ((m.status!='recording' AND m.ended_at<?) OR (m.status='recording' AND m.started_at<?)) "
                "AND EXISTS (SELECT 1 FROM work_meeting_segments s WHERE s.meeting_id=m.id AND s.audio_bytes>0)",
                (cutoff.isoformat(), (cutoff - timedelta(days=1)).isoformat()))]
        for meeting_id in expired:
            self.delete_work_audio(meeting_id, 'expired')
        return len(expired)

    def add_work_event(self, meeting_id, event_id, kind, offset_ms, origin):
        """Idempotent pause/resume/mark on the audio timeline; only while the meeting is recording."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            meeting = db.execute("SELECT status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if meeting is None:
                return None
            existing = db.execute("SELECT * FROM work_meeting_events WHERE id=?", (event_id,)).fetchone()
            if existing:
                if existing['meeting_id'] != meeting_id or existing['kind'] != kind or existing['offset_ms'] != offset_ms:
                    raise ValueError('事件编号已被使用')
                return dict(existing)
            if meeting['status'] != 'recording':
                raise ValueError('会议已结束，不能再暂停或标记')
            if kind in ('pause', 'resume'):
                last = db.execute("SELECT kind FROM work_meeting_events WHERE meeting_id=? AND kind IN ('pause','resume') "
                                  "ORDER BY rowid DESC LIMIT 1", (meeting_id,)).fetchone()
                if (kind == 'pause') == bool(last and last['kind'] == 'pause'):
                    raise ValueError('录音已经暂停' if kind == 'pause' else '录音没有暂停')
            row = {'id': event_id, 'meeting_id': meeting_id, 'kind': kind, 'offset_ms': offset_ms,
                   'origin': origin, 'created_at': now()}
            db.execute("INSERT INTO work_meeting_events VALUES(?,?,?,?,?,?)", tuple(row.values()))
            return row

    def finish_work_meeting(self, meeting_id, expected_segments, complete):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            meeting = db.execute("SELECT * FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if meeting is None:
                return None
            if meeting['status'] == 'recording':
                indices = [row[0] for row in db.execute(
                    "SELECT segment_index FROM work_meeting_segments WHERE meeting_id=? ORDER BY segment_index", (meeting_id,))]
                count = db.execute("SELECT count(*) FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id "
                                   "WHERE s.meeting_id=?", (meeting_id,)).fetchone()[0]
                intact = complete and indices == list(range(expected_segments))
                status = 'saved' if intact and count else 'empty' if intact else 'partial'
                error = '' if status == 'saved' else '没有识别到可保存的讲话' if status == 'empty' else '录音或上传未完整结束，仅保留已确认保存的原文'
                db.execute("UPDATE work_meetings SET ended_at=?,status=?,expected_segments=?,summary_status=?,summary_stage=?,error=? WHERE id=?",
                           (now(), status, expected_segments, 'pending' if count else 'unavailable', 'queued', error, meeting_id))
            elif meeting['expected_segments'] != expected_segments:
                raise ValueError('结束请求与已保存的会议不一致')
        return self.work_meeting(meeting_id)

    def save_work_summary(self, meeting_id, payload, source_ids):
        # Called while the account runtime lock is held. Never resurrect forgotten evidence.
        current = self.work_meeting(meeting_id)
        if current is None or [row['id'] for row in current['sources']] != source_ids:
            return False
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old_id = current['report_id']
            old_tasks = { (task['content'], task['owner'], task['due'], tuple(sorted(json.loads(task['source_ids'])))): dict(task)
                          for task in db.execute('SELECT * FROM action_tasks WHERE report_id=?', (old_id,)) }
            if old_id:
                db.execute('DELETE FROM action_tasks WHERE report_id=?', (old_id,))
                db.execute('DELETE FROM meeting_reports WHERE id=?', (old_id,))
            report_id, created = uuid4().hex, now()
            db.execute('INSERT INTO meeting_reports VALUES(?,?,?,?)',
                       (report_id, 'work-meeting:' + meeting_id + ':' + report_id, json.dumps(payload, ensure_ascii=False), created))
            for task in payload.get('tasks', []):
                old_task = old_tasks.get((task['content'], task['owner'], task['due'], tuple(sorted(task['source_ids']))), {})
                db.execute('INSERT INTO action_tasks VALUES(?,?,?,?,?,?,?,?)',
                           (old_task.get('id', uuid4().hex), report_id, task['content'], task['owner'], task['due'],
                            json.dumps(task['source_ids']), old_task.get('status', 'open'), old_task.get('created_at', created)))
            db.execute("UPDATE work_meetings SET report_id=?,summary_status=?,summary_stage='done' WHERE id=?",
                       (report_id, 'ready' if payload['extraction'] == 'llm' else 'fallback', meeting_id))
        return True

    def work_summary_progress(self, meeting_id, stage, processed=0, total=0):
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET summary_stage=?,processed_sections=?,total_sections=? "
                       "WHERE id=? AND summary_status='pending'", (stage, processed, total, meeting_id))

    def retry_work_summary(self, meeting_id):
        with self.connect() as db:
            return db.execute("UPDATE work_meetings SET summary_status='pending',summary_stage='queued',processed_sections=0,total_sections=0 WHERE id=? AND status!='recording' "
                              "AND summary_status IN ('fallback','failed','ready')", (meeting_id,)).rowcount > 0

    def fail_work_summary(self, meeting_id):
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET summary_status='failed' WHERE id=? AND summary_status='pending'", (meeting_id,))

    def get_memory(self, memory_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM memories WHERE id=?", (memory_id,)).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _invalidate_recall(db):
        # Keep identities so a retry cannot execute an old mutation a second time.
        withdrawn = json.dumps({'text': '', 'response': '记录已变更，请重新查询。', 'speak': False,
                                'invalidated': True}, ensure_ascii=False)
        db.execute("UPDATE mobile_turns SET result=? WHERE result IS NOT NULL", (withdrawn,))
        db.execute("DELETE FROM transcripts WHERE speaker='assistant'")

    def correct_memory(self, memory_id, content):
        if not isinstance(content, str) or not 1 <= len(content.strip()) <= 6000:
            raise ValueError('memory content must contain 1–6000 characters')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM memories WHERE id=?', (memory_id,)).fetchone()
            if not row:
                return None
            db.execute('UPDATE memories SET content=?,updated_at=? WHERE id=?', (content.strip(), now(), memory_id))
            if row['source'].startswith(('transcript:', 'demo_transcript:')):
                db.execute('INSERT OR IGNORE INTO recall_exclusions VALUES(?)', (row['source'].split(':', 1)[1],))
            self._invalidate_recall(db)
            return dict(db.execute('SELECT * FROM memories WHERE id=?', (memory_id,)).fetchone())

    def forget_memory(self, memory_id):
        """Forget one memory and its directly linked source, not an entire topic."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM memories WHERE id=?', (memory_id,)).fetchone()
            if not row:
                return None
            removed_memories = db.execute('DELETE FROM memories WHERE id=?', (memory_id,)).rowcount
            removed_transcripts = removed_reports = removed_tasks = 0
            if row['source'].startswith(('transcript:', 'demo_transcript:')):
                source_id = row['source'].split(':', 1)[1]
                removed_memories += db.execute('DELETE FROM memories WHERE source=?', (row['source'],)).rowcount
                removed_transcripts = db.execute('DELETE FROM transcripts WHERE id=?', (source_id,)).rowcount
                db.execute('DELETE FROM recall_exclusions WHERE transcript_id=?', (source_id,))
                # Work minutes cite sentences "<transcript id>.<n>"; they belong to the same source.
                cites = lambda ids: any(item == source_id or item.startswith(source_id + '.') for item in ids)
                for task in db.execute('SELECT id,source_ids FROM action_tasks').fetchall():
                    if cites(json.loads(task['source_ids'])):
                        removed_tasks += db.execute('DELETE FROM action_tasks WHERE id=?', (task['id'],)).rowcount
                for report in db.execute('SELECT id,payload FROM meeting_reports').fetchall():
                    payload = json.loads(report['payload'])
                    source_ids = set(payload.get('source_ids', [])) | {s['id'] for s in payload.get('sources', [])}
                    if cites(source_ids):
                        db.execute("UPDATE work_meetings SET report_id=NULL,summary_status='failed',summary_stage='done' WHERE report_id=?", (report['id'],))
                        removed_reports += db.execute('DELETE FROM meeting_reports WHERE id=?', (report['id'],)).rowcount
            self._invalidate_recall(db)
            return {'memories': removed_memories, 'transcripts': removed_transcripts,
                    'reports': removed_reports, 'tasks': removed_tasks, 'scope': 'memory_and_linked_source'}


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
