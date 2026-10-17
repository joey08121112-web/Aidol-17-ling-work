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
import hashlib
import wave
from uuid import uuid4
from zoneinfo import ZoneInfo

from .domain import Settings
from .commute_storage import CommuteStorage
from .search import index_text, query_terms, is_non_assertion


def now():
    return datetime.now(timezone.utc).isoformat()


WORK_SEGMENT_MS = 20000  # Older clients cut fixed 20 s segments and send no offset.
PCM_BYTES_PER_MS = 32    # 16 kHz mono PCM16.


_SENTENCE_END = re.compile(r'[^。！？!?；;\n]+[。！？!?；;]*')
_NOT_SPOKEN = re.compile(r'[\s，,。！？!?；;：:、“”"‘’（）()《》…—-]')


def _split_sentences(body, start, end, words):
    """Split one ASR utterance at sentence punctuation.

    Pieces are timed from word timings when the provider sends words matching the text;
    otherwise times are interpolated by character share (an estimate)."""
    pieces = [piece.strip() for piece in _SENTENCE_END.findall(body) if piece.strip()]
    merged = []
    for piece in pieces:  # A lone "嗯。" stays with its neighbour instead of becoming a line.
        if merged and len(_NOT_SPOKEN.sub('', piece)) < 2:
            merged[-1] += piece
        else:
            merged.append(piece)
    if len(merged) <= 1:
        return [(start, end, body)]
    sizes = [len(_NOT_SPOKEN.sub('', piece)) for piece in merged]
    total = sum(sizes)
    timed = []
    for word in words or ():
        if isinstance(word, dict) and type(word.get('start_time')) is int and type(word.get('end_time')) is int:
            for _ in _NOT_SPOKEN.sub('', str(word.get('text') or '')):
                timed.append((word['start_time'], word['end_time']))
    rows, consumed = [], 0
    for piece, size in zip(merged, sizes):
        if len(timed) == total and size:
            piece_start, piece_end = timed[consumed][0], timed[consumed + size - 1][1]
        else:
            piece_start = start + (end - start) * consumed // max(total, 1)
            piece_end = start + (end - start) * (consumed + size) // max(total, 1)
        consumed += size
        rows.append((piece_start, max(piece_start, piece_end), piece))
    return rows


def speaker_label(item):
    """Provider speaker cluster ("0", "1", ...) to a display label; '' when not reported."""
    if not isinstance(item, dict):
        return ''
    # Volcengine streaming: additions.speaker_id ("0"); Qwen Flash: speaker_id (0). Both are 0-based.
    value = item.get('speaker_id') if 'speaker_id' in item else (item.get('additions') or {}).get('speaker_id', '')
    value = '' if value is None or isinstance(value, bool) else str(value).strip()
    return f'说话人{int(value) + 1}' if value.isdigit() else ''


def _utterance_rows(transcript_id, offset_ms, duration_ms, text, utterances):
    """Normalise ASR sentences onto the meeting timeline; fall back to one segment-wide sentence."""
    rows = []
    for item in utterances or ():
        if not isinstance(item, dict):
            continue
        body = str(item.get('text') or '').strip()
        start, end = item.get('start_time', item.get('begin_time')), item.get('end_time')
        if not body or type(start) is not int or type(end) is not int or start < 0 or end < start:
            continue
        if duration_ms:
            start, end = min(start, duration_ms), min(end, duration_ms)
        speaker = speaker_label(item)
        rows.extend((*piece, speaker) for piece in _split_sentences(body, start, end, item.get('words')))
    if not rows and text.strip():
        rows = [(*piece, '') for piece in _split_sentences(text.strip(), 0, duration_ms, None)]
    rows.sort(key=lambda row: row[0])
    return [(f'{transcript_id}.{ordinal}', ordinal, offset_ms + start, offset_ms + end, body, speaker)
            for ordinal, (start, end, body, speaker) in enumerate(rows)]


def _wall_clock(anchor, events):
    """Timeline offset -> wall-clock ISO time: anchor + offset + every pause that ended before it."""
    try:
        base = datetime.fromisoformat(anchor)
    except (TypeError, ValueError):
        return lambda offset_ms: None
    pauses, paused = [], None
    for event in events:
        try:
            moment = datetime.fromisoformat(event['created_at'])
        except (TypeError, ValueError):
            continue
        if event['kind'] == 'pause':
            paused = (event['offset_ms'], moment)
        elif event['kind'] == 'resume' and paused:
            pauses.append((paused[0], max(timedelta(0), moment - paused[1])))
            paused = None
    return lambda offset_ms: (base + timedelta(milliseconds=offset_ms)
                              + sum((length for at, length in pauses if at <= offset_ms), timedelta(0))).isoformat(timespec='seconds')


class Store(CommuteStorage):
    def __init__(self, path: str):
        self.path = path
        # users/<uuid>.sqlite3 keeps its meeting audio in users/<uuid>/work-audio/<meeting>/.
        self.audio_root = Path(path).with_suffix('') / 'work-audio'
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS commute_data (key TEXT PRIMARY KEY, payload TEXT NOT NULL);
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
                CREATE TABLE IF NOT EXISTS action_task_dismissals (
                  scope TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(scope,fingerprint));
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
                CREATE TABLE IF NOT EXISTS work_meeting_deletions (
                  id TEXT PRIMARY KEY, task_ids TEXT NOT NULL DEFAULT '[]');
                CREATE TABLE IF NOT EXISTS location_records (
                  id TEXT PRIMARY KEY, observed_at TEXT NOT NULL, payload TEXT NOT NULL);
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
            task_columns = {row['name'] for row in db.execute('PRAGMA table_info(action_tasks)')}
            for name, definition in [('due_at', "TEXT NOT NULL DEFAULT ''"), ('reminder_hours', 'INTEGER NOT NULL DEFAULT 0')]:
                if name not in task_columns:
                    db.execute(f'ALTER TABLE action_tasks ADD COLUMN {name} {definition}')
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
            for name, definition in (('whole_audio', 'INTEGER NOT NULL DEFAULT 0'),
                                     ('transcription_status', "TEXT NOT NULL DEFAULT 'legacy'"),
                                     ('asr_task_id', "TEXT NOT NULL DEFAULT ''"),
                                     ('address_attempts', 'INTEGER NOT NULL DEFAULT 0'),
                                     ('address_retry_at', 'REAL'),
                                     ('revision_request', "TEXT NOT NULL DEFAULT '{}'"),
                                     ('pinned', 'INTEGER NOT NULL DEFAULT 0')):
                if name not in meeting_columns:
                    db.execute(f'ALTER TABLE work_meetings ADD COLUMN {name} {definition}')
            db.execute('CREATE TABLE IF NOT EXISTS work_audio_uploads (meeting_id TEXT NOT NULL, '
                       'upload_index INTEGER NOT NULL, request_id TEXT NOT NULL UNIQUE, digest TEXT NOT NULL, '
                       'pcm BLOB NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(meeting_id,upload_index))')
            db.execute("DELETE FROM work_audio_uploads WHERE julianday(created_at)<julianday('now','-1 day')")
            if 'location' not in meeting_columns:
                db.execute("ALTER TABLE work_meetings ADD COLUMN location TEXT NOT NULL DEFAULT '{}'")
            segment_columns = {row['name'] for row in db.execute('PRAGMA table_info(work_meeting_segments)')}
            if 'utterances' not in segment_columns:
                db.execute("ALTER TABLE work_meeting_segments ADD COLUMN utterances TEXT NOT NULL DEFAULT '[]'")
            for name in ('processed_sections', 'total_sections'):
                if name not in meeting_columns:
                    db.execute(f'ALTER TABLE work_meetings ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0')
            if 'summary_stage' not in meeting_columns:
                db.execute("ALTER TABLE work_meetings ADD COLUMN summary_stage TEXT NOT NULL DEFAULT 'waiting'")
            if 'audio_status' not in meeting_columns:
                db.execute("ALTER TABLE work_meetings ADD COLUMN audio_status TEXT NOT NULL DEFAULT 'none'")
            segment_columns = {row['name'] for row in db.execute('PRAGMA table_info(work_meeting_segments)')}
            for name, ddl in (('offset_ms', 'INTEGER'), ('duration_ms', 'INTEGER NOT NULL DEFAULT 0'),
                              ('audio_bytes', 'INTEGER NOT NULL DEFAULT 0'), ('audio_digest', "TEXT NOT NULL DEFAULT ''")):
                if name not in segment_columns:
                    db.execute(f'ALTER TABLE work_meeting_segments ADD COLUMN {name} {ddl}')
            # Sentence times are milliseconds on the meeting's audio timeline (pauses excluded).
            db.execute("CREATE TABLE IF NOT EXISTS work_utterances (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL, "
                       "transcript_id TEXT NOT NULL, segment_index INTEGER NOT NULL, ordinal INTEGER NOT NULL, "
                       "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS work_utterance_order ON work_utterances(meeting_id, start_ms)")
            if 'speaker' not in {row['name'] for row in db.execute('PRAGMA table_info(work_utterances)')}:
                db.execute("ALTER TABLE work_utterances ADD COLUMN speaker TEXT NOT NULL DEFAULT ''")
            if 'audio_anchor_at' not in meeting_columns:
                # Wall-clock time of timeline offset 0 (first recorded sample), for clock times to the second.
                db.execute("ALTER TABLE work_meetings ADD COLUMN audio_anchor_at TEXT")
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
            # Import unfinished whole-audio uploads written by the earlier main branch.
            # Keep its SQLite blobs until transcription succeeds; an interrupted copy is retryable.
            for upload in db.execute('SELECT u.* FROM work_audio_uploads u JOIN work_meetings m ON m.id=u.meeting_id '
                                     'WHERE m.whole_audio=1 ORDER BY u.meeting_id,u.upload_index').fetchall():
                existing = db.execute('SELECT 1 FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?',
                                      (upload['meeting_id'], upload['upload_index'])).fetchone()
                if existing:
                    continue
                offset = db.execute('SELECT COALESCE(sum(length(pcm)),0)/? FROM work_audio_uploads '
                                    'WHERE meeting_id=? AND upload_index<?',
                                    (PCM_BYTES_PER_MS, upload['meeting_id'], upload['upload_index'])).fetchone()[0]
                folder = self._work_audio_dir(upload['meeting_id'])
                folder.mkdir(parents=True, exist_ok=True)
                target = folder / f"{upload['upload_index']:06d}.pcm"
                temporary = folder / (uuid4().hex + '.tmp')
                temporary.write_bytes(upload['pcm'])
                os.replace(temporary, target)
                db.execute('INSERT INTO work_meeting_segments(meeting_id,segment_index,request_id,offset_ms,duration_ms,audio_bytes,audio_digest) '
                           'VALUES(?,?,?,?,?,?,?)', (upload['meeting_id'], upload['upload_index'], upload['request_id'], offset,
                                                     len(upload['pcm']) // PCM_BYTES_PER_MS, len(upload['pcm']), upload['digest']))

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
            addresses = self._commute_read(db, 'addresses', None)
        value = Settings.model_validate_json(row[0]) if row else Settings()
        if addresses is not None:
            for key in ['home', 'work']:
                place = next((a for a in addresses if a['tag'] == key), None)
                setattr(value, key + '_lon', place['longitude'] if place else None)
                setattr(value, key + '_lat', place['latitude'] if place else None)
                setattr(value, key + '_name', place['name'] if place else ('家' if key == 'home' else '公司'))
                setattr(value, key + '_city', place['city'] if place else '')
        return value

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
                db.execute("INSERT INTO action_tasks(id,report_id,content,owner,due,source_ids,status,created_at) VALUES(?,?,?,?,?,?,?,?)", (
                    uuid4().hex, report_id, task["content"], task["owner"], task["due"],
                    json.dumps(task["source_ids"]), "open", created))
        return {"id": report_id, "created_at": created, **payload}

    def action_task_status(self, task_id, status):
        if status not in {"open", "done"}:
            raise ValueError("invalid action task status")
        with self.connect() as db:
            return db.execute("UPDATE action_tasks SET status=? WHERE id=?", (status, task_id)).rowcount > 0

    def create_action_task(self, task_id, content, owner, due_at, reminder_hours):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT 1 FROM action_task_dismissals WHERE scope='manual' AND fingerprint=?", (task_id,)).fetchone():
                raise ValueError('该待办已删除，请重新添加')
            previous = db.execute('SELECT * FROM action_tasks WHERE id=?', (task_id,)).fetchone()
            if previous:
                if (previous['content'], previous['owner'], previous['due_at'], previous['reminder_hours']) != (content, owner, due_at, reminder_hours):
                    raise ValueError('待办编号已存在且内容不同')
            else:
                db.execute("INSERT INTO action_tasks(id,report_id,content,owner,due,source_ids,status,created_at,due_at,reminder_hours) "
                           "VALUES(?,'',?,?,?,'[]','open',?,?,?)", (task_id, content, owner, due_at, now(), due_at, reminder_hours))
        return next(row for row in self.list('action_tasks', 10000) if row['id'] == task_id)

    @staticmethod
    def task_fingerprint(task):
        return hashlib.sha256(json.dumps([task['content'], task['owner'], task['due']], ensure_ascii=False).encode()).hexdigest()

    def edit_action_task(self, task_id, content, owner, due, due_at, reminder_hours):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            task = db.execute('SELECT * FROM action_tasks WHERE id=?', (task_id,)).fetchone()
            if not task:
                return None
            if (task['content'], task['owner'], task['due'], task['due_at'], task['reminder_hours']) == (content, owner, due_at or due, due_at, reminder_hours):
                result = dict(task)
                result['source_ids'] = json.loads(result['source_ids'])
                return result
            report = db.execute('SELECT payload FROM meeting_reports WHERE id=?', (task['report_id'],)).fetchone()
            if report:
                # A user-edited action is independent of the report's generated snapshot.
                # Retain source_ids so forgetting source evidence still removes the action.
                scope = json.loads(report['payload']).get('meeting_id') or task['report_id']
                db.execute('INSERT OR IGNORE INTO action_task_dismissals VALUES(?,?)', (scope, self.task_fingerprint(task)))
            db.execute("UPDATE action_tasks SET content=?,owner=?,due=?,due_at=?,reminder_hours=?,report_id='' WHERE id=?",
                       (content, owner, due_at or due, due_at, reminder_hours, task_id))
            result = dict(db.execute('SELECT * FROM action_tasks WHERE id=?', (task_id,)).fetchone())
            result['source_ids'] = json.loads(result['source_ids'])
            return result

    def delete_action_task(self, task_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            task = db.execute('SELECT * FROM action_tasks WHERE id=?', (task_id,)).fetchone()
            if not task:
                return False
            report = db.execute('SELECT payload FROM meeting_reports WHERE id=?', (task['report_id'],)).fetchone()
            if report:
                scope = json.loads(report['payload']).get('meeting_id') or task['report_id']
                db.execute('INSERT OR IGNORE INTO action_task_dismissals VALUES(?,?)', (scope, self.task_fingerprint(task)))
            if not task['report_id']:
                db.execute("INSERT OR IGNORE INTO action_task_dismissals VALUES('manual',?)", (task_id,))
            db.execute('DELETE FROM action_tasks WHERE id=?', (task_id,))
        return True

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
            db.execute("DELETE FROM commute_data WHERE key='journey'")
            for table in ("work_meeting_deletions", "action_task_dismissals", "location_records", "work_audio_uploads", "work_utterances", "work_meeting_events", "work_meeting_segments", "work_meetings", "memories", "transcripts", "events", "drafts", "processed", "meeting_reports", "action_tasks", "mobile_turns", "recall_exclusions"):
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

    def start_work_meeting(self, meeting_id, started_at=None, location=None, whole_audio=False):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM work_meeting_deletions WHERE id=?', (meeting_id,)).fetchone():
                raise ValueError('这场会议已删除，请开始新的会议')
            db.execute("INSERT OR IGNORE INTO work_meetings(id,started_at,location,whole_audio,transcription_status) VALUES(?,?,?,?,?)",
                       (meeting_id, started_at or now(), json.dumps(location or {}, ensure_ascii=False),
                        int(whole_audio), 'recording' if whole_audio else 'legacy'))
        return self.work_meeting(meeting_id)

    def work_meetings(self, limit=100):
        with self.connect() as db:
            return [{**dict(row), 'pinned': bool(row['pinned']), 'whole_audio': bool(row['whole_audio']), 'location': json.loads(row['location']), 'revision_request': {k: v for k, v in json.loads(row['revision_request']).items() if k != 'document'}} for row in db.execute(
                "SELECT m.*,json_extract(r.payload,'$.title') AS title,json_extract(r.payload,'$.summary') AS summary, "
                "(SELECT count(*) FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id "
                "WHERE s.meeting_id=m.id) + (SELECT count(DISTINCT u.transcript_id) FROM work_utterances u JOIN transcripts t ON t.id=u.transcript_id WHERE u.meeting_id=m.id AND u.segment_index=-1) AS transcript_count, "
                "(SELECT COALESCE(max(COALESCE(s.offset_ms,s.segment_index*?)+s.duration_ms),0) FROM work_meeting_segments s "
                "WHERE s.meeting_id=m.id) AS duration_ms FROM work_meetings m LEFT JOIN meeting_reports r ON r.id=m.report_id "
                "ORDER BY m.pinned DESC,julianday(m.started_at) DESC,m.id DESC LIMIT ?", (WORK_SEGMENT_MS, limit))]

    def pin_work_meeting(self, meeting_id, pinned):
        with self.connect() as db:
            return db.execute('UPDATE work_meetings SET pinned=? WHERE id=?', (int(pinned), meeting_id)).rowcount > 0

    def delete_work_meeting(self, meeting_id):
        """Delete one ended meeting; durable receipts reject late start/upload/model results."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            receipt = db.execute('SELECT task_ids FROM work_meeting_deletions WHERE id=?', (meeting_id,)).fetchone()
            if receipt:
                return json.loads(receipt['task_ids'])
            meeting = db.execute('SELECT status,report_id FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if meeting is None:
                return None
            if meeting['status'] == 'recording':
                raise ValueError('请先结束会议，再删除会议记录')
            sources = {row[0] for row in db.execute('SELECT transcript_id FROM work_meeting_segments WHERE meeting_id=? '
                       'UNION SELECT transcript_id FROM work_utterances WHERE meeting_id=?', (meeting_id, meeting_id)) if row[0]}
            cites = lambda ids: any(item.split('.', 1)[0] in sources for item in ids)
            removed_tasks = []
            for task in db.execute('SELECT id,report_id,source_ids FROM action_tasks').fetchall():
                if (meeting['report_id'] and task['report_id'] == meeting['report_id']) or cites(json.loads(task['source_ids'])):
                    removed_tasks.append(task['id'])
                    db.execute('DELETE FROM action_tasks WHERE id=?', (task['id'],))
                    db.execute("INSERT OR IGNORE INTO action_task_dismissals VALUES('manual',?)", (task['id'],))
            # Invalidate derived history snapshots that cite removed evidence. Independently
            # saved memories and unrelated actions remain available in their own collections.
            for report in db.execute('SELECT id,payload FROM meeting_reports').fetchall():
                payload = json.loads(report['payload'])
                if report['id'] == meeting['report_id'] or payload.get('meeting_id') == meeting_id or cites(payload.get('source_ids', [])):
                    db.execute("UPDATE action_tasks SET report_id='' WHERE report_id=?", (report['id'],))
                    db.execute('DELETE FROM meeting_reports WHERE id=?', (report['id'],))
            for source in sources:
                db.execute('DELETE FROM transcripts WHERE id=?', (source,))
                db.execute('DELETE FROM recall_exclusions WHERE transcript_id=?', (source,))
            for table in ('work_audio_uploads', 'work_utterances', 'work_meeting_events', 'work_meeting_segments'):
                db.execute(f'DELETE FROM {table} WHERE meeting_id=?', (meeting_id,))
            db.execute('DELETE FROM work_meetings WHERE id=?', (meeting_id,))
            db.execute('DELETE FROM action_task_dismissals WHERE scope=?', (meeting_id,))
            db.execute('INSERT INTO work_meeting_deletions VALUES(?,?)', (meeting_id, json.dumps(removed_tasks)))
            if sources or meeting['report_id']:
                self._invalidate_recall(db, keep_conversation=True)
        shutil.rmtree(self._work_audio_dir(meeting_id), ignore_errors=True)
        return removed_tasks

    def save_work_meeting_address(self, meeting_id, expected_location, address):
        """Enrich the original snapshot, never recreate a deleted or replaced meeting."""
        if not Path(self.path).exists():
            return False
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT location,report_id FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if row is None:
                return False
            location = json.loads(row['location'] or '{}')
            if location != expected_location or location.get('address'):
                return False
            location['address'] = address
            encoded = json.dumps(location, ensure_ascii=False)
            db.execute('UPDATE work_meetings SET location=?,address_retry_at=NULL WHERE id=?', (encoded, meeting_id))
            if row['report_id']:
                db.execute("UPDATE meeting_reports SET payload=json_set(payload,'$.location',json(?)) WHERE id=?",
                           (encoded, row['report_id']))
        return True

    def claim_meeting_address(self, meeting_id, expected_location, stamp):
        """Persist an attempt before the request; one initial attempt plus three retries."""
        if not Path(self.path).exists():
            return None
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT location,address_attempts,address_retry_at FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if row is None or json.loads(row['location'] or '{}') != expected_location:
                return None
            if row['address_retry_at'] is not None and row['address_retry_at'] > stamp:
                return {'wait': row['address_retry_at'] - stamp}
            if row['address_attempts'] >= 4:
                # A crash during the final request must not leave the UI pending forever.
                db.execute('UPDATE work_meetings SET address_retry_at=NULL WHERE id=?', (meeting_id,))
                return None
            attempt = row['address_attempts'] + 1
            # A restart during a request keeps both the consumed attempt and its bounded lease.
            db.execute('UPDATE work_meetings SET address_attempts=?,address_retry_at=? WHERE id=?', (attempt, stamp + 42, meeting_id))
            return {'attempt': attempt}

    def finish_meeting_address_attempt(self, meeting_id, expected_location, attempt, retry_at):
        if not Path(self.path).exists():
            return False
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT location,address_attempts FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if row is None or row['address_attempts'] != attempt or json.loads(row['location'] or '{}') != expected_location:
                return False
            return db.execute('UPDATE work_meetings SET address_retry_at=? WHERE id=?', (retry_at, meeting_id)).rowcount > 0

    def save_location_record(self, record_id, observed_at, point):
        encoded = json.dumps(point, ensure_ascii=False, sort_keys=True)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            existing = db.execute('SELECT observed_at,payload FROM location_records WHERE id=?', (record_id,)).fetchone()
            if existing and (existing['observed_at'] != observed_at or existing['payload'] != encoded):
                raise ValueError('位置记录标识已存在且内容不同')
            db.execute('INSERT OR IGNORE INTO location_records VALUES(?,?,?)', (record_id, observed_at, encoded))
        return {'id': record_id, 'observed_at': observed_at, **point}

    def location_records(self, limit=100):
        with self.connect() as db:
            return [{'id': row['id'], 'observed_at': row['observed_at'], **json.loads(row['payload'])}
                    for row in db.execute('SELECT * FROM location_records ORDER BY observed_at DESC,id DESC LIMIT ?', (limit,))]

    def work_meeting(self, meeting_id):
        retention = self.settings().work_audio_retention_days
        with self.connect() as db:
            row = db.execute("SELECT * FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None:
                return None
            # Segment-recognised text plus live-caption sentences (segment_index -1), in timeline order.
            sources = [dict(item) for item in db.execute(
                "SELECT t.*,s.segment_index,COALESCE(s.offset_ms,s.segment_index*?) AS offset_ms,s.duration_ms,s.utterances AS segment_utterances "
                "FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id WHERE s.meeting_id=? "
                "UNION ALL SELECT t.*,NULL,min(u.start_ms),max(u.end_ms)-min(u.start_ms),'[]' FROM work_utterances u "
                "JOIN transcripts t ON t.id=u.transcript_id WHERE u.meeting_id=? AND u.segment_index=-1 GROUP BY t.id "
                "ORDER BY offset_ms", (WORK_SEGMENT_MS, meeting_id, meeting_id))]
            utterances = [dict(item) for item in db.execute(
                "SELECT u.id,u.transcript_id,u.segment_index,u.start_ms,u.end_ms,u.text,u.speaker FROM work_utterances u "
                "JOIN transcripts t ON t.id=u.transcript_id WHERE u.meeting_id=? ORDER BY u.start_ms,u.ordinal", (meeting_id,))]
            events = [dict(item) for item in db.execute(
                "SELECT id,kind,offset_ms,origin,created_at FROM work_meeting_events WHERE meeting_id=? "
                "ORDER BY offset_ms,created_at", (meeting_id,))]
            audio = db.execute("SELECT count(*) AS total,COALESCE(sum(audio_bytes>0),0) AS kept,COALESCE(sum(audio_bytes),0) AS bytes "
                               "FROM work_meeting_segments WHERE meeting_id=?", (meeting_id,)).fetchone()
            report = db.execute("SELECT payload FROM meeting_reports WHERE id=?", (row['report_id'],)).fetchone()
        # Whole-meeting diarization gives one speaker namespace for the meeting; live/segment labels do not.
        meeting_scope = row['transcription_status'] == 'ready'
        for source in sources:
            source['utterances'] = json.loads(source.pop('segment_utterances') or '[]')
            if meeting_scope:
                source['speaker_scope'] = 'meeting'
        clock = _wall_clock(row['audio_anchor_at'] or row['started_at'], events)
        for item in utterances:
            item['wall_at'] = clock(item['start_ms'])
        status = row['audio_status'] if row['audio_status'] in ('expired', 'deleted') else (
            'none' if not audio['bytes'] else 'saved' if audio['kept'] == audio['total'] else 'partial')
        expires = None
        if status in ('saved', 'partial') and row['ended_at']:
            expires = (datetime.fromisoformat(row['ended_at']) + timedelta(days=retention)).isoformat()
        return {**dict(row), 'pinned': bool(row['pinned']), 'whole_audio': bool(row['whole_audio']), 'location': json.loads(row['location'] or '{}'),
                'revision_request': json.loads(row['revision_request']),
                'sources': sources, 'transcript_count': len(sources),
                'received_segments': audio['total'], 'utterances': utterances, 'events': events,
                'speakers': sorted({item['speaker'] for item in utterances if item['speaker']},
                                   key=lambda label: int(label[3:]) if label[3:].isdigit() else 0),
                'audio_started_at': row['audio_anchor_at'] or row['started_at'],
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
            meeting = db.execute("SELECT status,whole_audio FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            segment = db.execute("SELECT request_id FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?",
                                 (meeting_id, segment_index)).fetchone()
        if meeting is None:
            raise ValueError('会议不存在')
        if meeting['whole_audio']:
            raise ValueError('整场会议不能单独转写上传数据')
        if meeting['status'] != 'recording':
            raise ValueError('会议已结束，不能追加录音')
        if segment is not None and segment['request_id'] != request_id:
            raise ValueError('会议片段编号已被使用')

    def save_work_segment(self, meeting_id, segment_index, request_id, text, *, offset_ms=None, duration_ms=0,
                          utterances=(), audio=None, recognized=True, keep_for_transcription=False):
        """Persist ASR evidence and session membership in the same transaction, including silence receipts.

        Audio is written after the rows commit; a failed write keeps the text and records no audio. Audio is kept
        per the retention setting, and also (until transcribed) when a whole-meeting transcription will need it."""
        keep_audio = bool(audio) and (self.settings().work_audio_retention_days > 0 or keep_for_transcription)
        offset = segment_index * WORK_SEGMENT_MS if offset_ms is None else offset_ms
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT status,audio_status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None or row['status'] != 'recording':
                raise ValueError('会议不存在或已结束')
            existing = db.execute("SELECT * FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?",
                                  (meeting_id, segment_index)).fetchone()
            if existing:
                if existing['request_id'] != request_id or (audio and existing['audio_digest'] and
                        existing['audio_digest'] != hashlib.sha256(audio).hexdigest()):
                    raise ValueError('会议片段编号已被使用')
                return existing['transcript_id']
            keep_audio = keep_audio and row['audio_status'] not in ('expired', 'deleted')
            if recognized:
                # Server recognition is the fallback when live captions missed part of this segment:
                # its text replaces any live sentences that started inside the segment.
                stale = [item[0] for item in db.execute(
                    "SELECT transcript_id FROM work_utterances WHERE meeting_id=? AND segment_index=-1 GROUP BY transcript_id "
                    "HAVING min(start_ms)>=? AND min(start_ms)<?", (meeting_id, offset, offset + max(duration_ms, 1)))]
                for stale_id in stale:
                    db.execute("DELETE FROM work_utterances WHERE transcript_id=?", (stale_id,))
                    db.execute("DELETE FROM transcripts WHERE id=?", (stale_id,))
            transcript_id = uuid4().hex if text.strip() else None
            if transcript_id:
                db.execute("INSERT INTO transcripts(id,speaker,text,mode,source,created_at,interaction_source) VALUES(?,?,?,?,?,?,?)",
                           (transcript_id, '现场发言（未分离声纹）', text, 'work', 'asr', now(), 'ambient_capture'))
                db.executemany("INSERT INTO work_utterances(id,meeting_id,transcript_id,segment_index,ordinal,start_ms,end_ms,text,speaker) "
                               "VALUES(?,?,?,?,?,?,?,?,?)",
                               [(uid, meeting_id, transcript_id, segment_index, ordinal, start, end, body, speaker)
                                for uid, ordinal, start, end, body, speaker in
                                _utterance_rows(transcript_id, offset, duration_ms, text, utterances)])
            db.execute("UPDATE work_meetings SET audio_anchor_at=? WHERE id=? AND audio_anchor_at IS NULL",
                       ((datetime.now(timezone.utc) - timedelta(milliseconds=offset + duration_ms)).isoformat(), meeting_id))
            db.execute("INSERT INTO work_meeting_segments(meeting_id,segment_index,request_id,transcript_id,offset_ms,duration_ms,audio_bytes,utterances) "
                       "VALUES(?,?,?,?,?,?,?,?)", (meeting_id, segment_index, request_id, transcript_id, offset, duration_ms,
                                                   len(audio) if keep_audio else 0, json.dumps(list(utterances), ensure_ascii=False)))
            if audio:
                db.execute("UPDATE work_meeting_segments SET audio_digest=? WHERE meeting_id=? AND segment_index=?",
                           (hashlib.sha256(audio).hexdigest(), meeting_id, segment_index))
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

    def anchor_work_audio(self, meeting_id, offset_ms):
        """First live audio frame: record when timeline offset 0 was, if not yet known."""
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET audio_anchor_at=? WHERE id=? AND audio_anchor_at IS NULL",
                       ((datetime.now(timezone.utc) - timedelta(milliseconds=offset_ms)).isoformat(), meeting_id))

    def save_live_sentence(self, meeting_id, text, start_ms, end_ms, words=(), speaker=''):
        """Persist one final live-caption utterance (split at sentence ends) while the meeting records."""
        if not text.strip():
            return []
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT status FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if row is None or row['status'] != 'recording':
                raise ValueError('会议不存在或已结束')
            transcript_id = uuid4().hex
            db.execute("INSERT INTO transcripts(id,speaker,text,mode,source,created_at,interaction_source) VALUES(?,?,?,?,?,?,?)",
                       (transcript_id, speaker or '现场发言（未分离声纹）', text, 'work', 'asr', now(), 'ambient_capture'))
            rows = [(f'{transcript_id}.{ordinal}', ordinal, start, end, body)
                    for ordinal, (start, end, body) in enumerate(_split_sentences(text, start_ms, end_ms, words))]
            db.executemany("INSERT INTO work_utterances(id,meeting_id,transcript_id,segment_index,ordinal,start_ms,end_ms,text,speaker) "
                           "VALUES(?,?,?,?,?,?,?,?,?)",
                           [(uid, meeting_id, transcript_id, -1, ordinal, start, end, body, speaker) for uid, ordinal, start, end, body in rows])
        return [{'id': uid, 'transcript_id': transcript_id, 'start_ms': start, 'end_ms': end, 'text': body, 'speaker': speaker}
                for uid, _, start, end, body in rows]

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
                "AND m.transcription_status!='pending' "  # Whole-meeting transcription still needs the audio.
                "AND NOT (m.transcription_status='failed' AND ?=0 AND m.ended_at>?) "
                "AND ((m.status!='recording' AND m.ended_at<?) OR (m.status='recording' AND m.started_at<?)) "
                "AND EXISTS (SELECT 1 FROM work_meeting_segments s WHERE s.meeting_id=m.id AND s.audio_bytes>0)",
                (days, (datetime.now(timezone.utc) - timedelta(days=1)).isoformat(),
                 cutoff.isoformat(), (cutoff - timedelta(days=1)).isoformat()))]
        for meeting_id in expired:
            self.delete_work_audio(meeting_id, 'expired')
        return len(expired)

    def add_work_event(self, meeting_id, event_id, kind, offset_ms, origin):
        """Idempotent pause/resume/mark/call boundaries; only while the meeting is recording."""
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
            if kind in ('call_start', 'call_end'):
                last = db.execute("SELECT kind FROM work_meeting_events WHERE meeting_id=? AND kind IN ('call_start','call_end') "
                                  "ORDER BY rowid DESC LIMIT 1", (meeting_id,)).fetchone()
                if (kind == 'call_start') == bool(last and last['kind'] == 'call_start'):
                    raise ValueError('已经在记录通话' if kind == 'call_start' else '没有正在记录的通话')
            row = {'id': event_id, 'meeting_id': meeting_id, 'kind': kind, 'offset_ms': offset_ms,
                   'origin': origin, 'created_at': now()}
            db.execute("INSERT INTO work_meeting_events VALUES(?,?,?,?,?,?)", tuple(row.values()))
            return row

    def finish_work_meeting(self, meeting_id, expected_segments, complete, transcribe=False):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            meeting = db.execute("SELECT * FROM work_meetings WHERE id=?", (meeting_id,)).fetchone()
            if meeting is None:
                return None
            if meeting['status'] == 'recording':
                indices = [row[0] for row in db.execute(
                    "SELECT segment_index FROM work_meeting_segments WHERE meeting_id=? ORDER BY segment_index", (meeting_id,))]
                count = db.execute("SELECT count(*) FROM work_meeting_segments s JOIN transcripts t ON t.id=s.transcript_id "
                                   "WHERE s.meeting_id=?", (meeting_id,)).fetchone()[0] + db.execute(
                    "SELECT count(DISTINCT transcript_id) FROM work_utterances WHERE meeting_id=? AND segment_index=-1",
                    (meeting_id,)).fetchone()[0]
                audio_kept = db.execute("SELECT COALESCE(sum(audio_bytes),0) FROM work_meeting_segments WHERE meeting_id=?",
                                        (meeting_id,)).fetchone()[0]
                # Whole-meeting diarization runs after the end when the saved audio and the provider allow it.
                transcribe_now = bool((transcribe or meeting['whole_audio']) and audio_kept)
                db.execute("UPDATE work_meetings SET transcription_status=? WHERE id=?",
                           ('pending' if transcribe_now else 'legacy' if not meeting['whole_audio'] else 'empty', meeting_id))
                intact = complete and indices == list(range(expected_segments))
                usable = count or transcribe_now  # Text may still come from the whole-meeting transcription.
                status = 'saved' if intact and usable else 'empty' if intact else 'partial'
                error = '' if status == 'saved' else '没有识别到可保存的讲话' if status == 'empty' else '录音或上传未完整结束，仅保留已确认保存的原文'
                db.execute("UPDATE work_meetings SET ended_at=?,status=?,expected_segments=?,summary_status=?,summary_stage=?,error=? WHERE id=?",
                           (now(), status, expected_segments, 'pending' if usable else 'unavailable', 'queued', error, meeting_id))
            elif meeting['expected_segments'] != expected_segments:
                raise ValueError('结束请求与已保存的会议不一致')
        return self.work_meeting(meeting_id)

    def save_work_audio(self, meeting_id, index, request_id, pcm):
        """Whole-audio clients: an upload is an audio-only segment in the shared per-account audio store."""
        with self.connect() as db:
            meeting = db.execute('SELECT * FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if not meeting or not meeting['whole_audio'] or meeting['status'] != 'recording':
                raise ValueError('会议不存在或已结束')
            previous = db.execute('SELECT request_id,audio_digest FROM work_meeting_segments WHERE meeting_id=? AND segment_index=?',
                                  (meeting_id, index)).fetchone()
            if previous:
                if previous['request_id'] != request_id or previous['audio_digest'] != hashlib.sha256(pcm).hexdigest():
                    raise ValueError('录音上传编号冲突')
                return
            earlier = db.execute('SELECT count(*),COALESCE(sum(duration_ms),0) FROM work_meeting_segments '
                                 'WHERE meeting_id=? AND segment_index<?', (meeting_id, index)).fetchone()
        if earlier[0] != index:
            raise ValueError('录音上传顺序不连续')  # Offsets come from the uploads before this one.
        if earlier[1] + len(pcm) // PCM_BYTES_PER_MS > 2 * 60 * 60 * 1000:
            raise ValueError('单场会议最多支持两小时，请结束当前会议')
        self.save_work_segment(meeting_id, index, request_id, '', offset_ms=earlier[1], duration_ms=len(pcm) // PCM_BYTES_PER_MS,
                               audio=pcm, recognized=False, keep_for_transcription=True)

    def write_work_wav(self, meeting_id, target):
        """One WAV of the meeting timeline from the saved audio, ten minutes at a time (bounded memory)."""
        with self.connect() as db:
            meeting = db.execute('SELECT status,expected_segments FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            indices = [row[0] for row in db.execute('SELECT segment_index FROM work_meeting_segments WHERE meeting_id=? '
                                                    'ORDER BY segment_index', (meeting_id,))]
            end = db.execute('SELECT COALESCE(max(COALESCE(offset_ms,segment_index*?)+audio_bytes/?),0) FROM work_meeting_segments '
                             'WHERE meeting_id=? AND audio_bytes>0', (WORK_SEGMENT_MS, PCM_BYTES_PER_MS, meeting_id)).fetchone()[0]
            legacy = db.execute('SELECT upload_index,pcm FROM work_audio_uploads WHERE meeting_id=? ORDER BY upload_index',
                                (meeting_id,)).fetchall() if not indices else []
            audio_sizes = db.execute('SELECT segment_index,audio_bytes FROM work_meeting_segments WHERE meeting_id=?',
                                     (meeting_id,)).fetchall()
        if legacy:
            if meeting is None or (meeting['status'] == 'saved' and
                    [row['upload_index'] for row in legacy] != list(range(meeting['expected_segments']))):
                raise ValueError('整场录音已不完整，不能生成完整转写')
            with wave.open(target, 'wb') as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                for row in legacy:
                    wav.writeframesraw(row['pcm'])
            return
        if not meeting or (meeting['status'] == 'saved' and indices != list(range(meeting['expected_segments']))):
            raise ValueError('整场录音已不完整，不能生成完整转写')
        if not end:
            raise ValueError('会议录音已不可用，请重新录制')
        for row in audio_sizes:
            path = self._work_audio_dir(meeting_id) / f"{row['segment_index']:06d}.pcm"
            if not row['audio_bytes'] or not path.is_file() or path.stat().st_size != row['audio_bytes']:
                raise ValueError('整场录音已不完整，保留已有文字')
        with wave.open(target, 'wb') as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            for start in range(0, end, 600_000):
                wav.writeframesraw(self.work_audio(meeting_id, start, min(end, start + 600_000)) or b'')

    def work_asr_task(self, meeting_id, task_id):
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET asr_task_id=? WHERE id=? AND transcription_status='pending'", (task_id, meeting_id))

    def fail_work_transcription(self, meeting_id, reset_task=False):
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET transcription_status='failed',summary_status='failed',"
                       "asr_task_id=CASE WHEN ? THEN '' ELSE asr_task_id END WHERE id=? AND transcription_status='pending'",
                       (reset_task, meeting_id))

    def save_work_dialogue(self, meeting_id, turns):
        """Whole-meeting diarization replaces live/segment sentences with one consistent speaker namespace.

        Turn times are on the WAV, which is the meeting timeline. Audio stays per the retention setting."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            meeting = db.execute('SELECT * FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if not meeting or meeting['transcription_status'] != 'pending':
                return False
            if not turns:
                # Keep whatever the live captions saved rather than erasing text on an empty result.
                kept = db.execute("SELECT count(*) FROM work_utterances WHERE meeting_id=?", (meeting_id,)).fetchone()[0]
                db.execute("UPDATE work_meetings SET transcription_status='empty',summary_stage='queued',summary_status=?,"
                           "status=CASE WHEN ?=0 THEN 'empty' ELSE status END WHERE id=?",
                           ('pending' if kept else 'unavailable', kept, meeting_id))
                return True
            old = {row[0] for row in db.execute("SELECT transcript_id FROM work_utterances WHERE meeting_id=?", (meeting_id,))}
            old |= {row[0] for row in db.execute("SELECT transcript_id FROM work_meeting_segments WHERE meeting_id=? "
                                                 "AND transcript_id IS NOT NULL", (meeting_id,))}
            db.execute("DELETE FROM work_utterances WHERE meeting_id=?", (meeting_id,))
            db.execute("UPDATE work_meeting_segments SET transcript_id=NULL WHERE meeting_id=?", (meeting_id,))
            for transcript_id in old:
                db.execute("DELETE FROM transcripts WHERE id=?", (transcript_id,))
            for turn in turns:
                label = re.sub(r'^发言人 ?(\d+)$', r'说话人\1', turn['speaker'])
                label = '' if label == '未识别发言人' else label
                transcript_id = uuid4().hex
                db.execute('INSERT INTO transcripts(id,speaker,text,mode,source,created_at,interaction_source) VALUES(?,?,?,?,?,?,?)',
                           (transcript_id, label or '未识别发言人', turn['text'], 'work', 'asr', now(), 'ambient_capture'))
                db.execute("INSERT INTO work_utterances(id,meeting_id,transcript_id,segment_index,ordinal,start_ms,end_ms,text,speaker) "
                           "VALUES(?,?,?,-1,0,?,?,?,?)", (transcript_id + '.0', meeting_id, transcript_id,
                                                          turn['begin_time'], turn['end_time'], turn['text'], label))
            db.execute("UPDATE work_meetings SET transcription_status='ready',summary_stage='queued',summary_status='pending' WHERE id=?",
                       (meeting_id,))
            db.execute('DELETE FROM work_audio_uploads WHERE meeting_id=?', (meeting_id,))  # Pre-merge uploads only.
        if self.settings().work_audio_retention_days == 0:
            self.delete_work_audio(meeting_id)
        return True

    def save_work_summary(self, meeting_id, payload, source_ids, revision=None):
        # Called while the account runtime lock is held. Never resurrect forgotten evidence.
        current = self.work_meeting(meeting_id)
        if current is None or [row['id'] for row in current['sources']] != source_ids:
            return False
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            # Address enrichment may finish while the model is generating the report.
            fresh = db.execute('SELECT location,report_id,revision_request FROM work_meetings WHERE id=?', (meeting_id,)).fetchone()
            if fresh is None:
                return False
            if revision is not None:
                pending = json.loads(fresh['revision_request'])
                if pending.get('id') != revision['id'] or pending.get('status') != 'pending' or fresh['report_id'] != revision['base_report_id']:
                    return False
            payload = {**payload, 'location': json.loads(fresh['location'] or '{}')}
            old_id = current['report_id']
            old_tasks = { (task['content'], task['owner'], task['due'], tuple(sorted(json.loads(task['source_ids'])))): dict(task)
                          for task in db.execute("SELECT * FROM action_tasks WHERE report_id=? AND report_id!=''", (old_id,)) }
            if old_id:
                db.execute('DELETE FROM action_tasks WHERE report_id=?', (old_id,))
                db.execute('DELETE FROM meeting_reports WHERE id=?', (old_id,))
            report_id, created = uuid4().hex, now()
            db.execute('INSERT INTO meeting_reports VALUES(?,?,?,?)',
                       (report_id, 'work-meeting:' + meeting_id + ':' + report_id, json.dumps(payload, ensure_ascii=False), created))
            for task in payload.get('tasks', []):
                if db.execute('SELECT 1 FROM action_task_dismissals WHERE scope=? AND fingerprint=?',
                              (meeting_id, self.task_fingerprint(task))).fetchone():
                    continue
                old_task = old_tasks.get((task['content'], task['owner'], task['due'], tuple(sorted(task['source_ids']))), {})
                db.execute('INSERT INTO action_tasks(id,report_id,content,owner,due,source_ids,status,created_at) VALUES(?,?,?,?,?,?,?,?)',
                           (old_task.get('id', uuid4().hex), report_id, task['content'], task['owner'], task['due'],
                            json.dumps(task['source_ids']), old_task.get('status', 'open'), old_task.get('created_at', created)))
            db.execute("UPDATE work_meetings SET report_id=?,summary_status=?,summary_stage='done',revision_request=? WHERE id=?",
                       (report_id, 'ready' if payload['extraction'] == 'llm' else 'fallback',
                        json.dumps(revision, ensure_ascii=False) if revision is not None else fresh['revision_request'], meeting_id))
        return True

    def work_summary_progress(self, meeting_id, stage, processed=0, total=0):
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET summary_stage=?,processed_sections=?,total_sections=? "
                       "WHERE id=? AND summary_status='pending'", (stage, processed, total, meeting_id))

    def retry_work_summary(self, meeting_id):
        with self.connect() as db:
            return db.execute("UPDATE work_meetings SET summary_status='pending',summary_stage='queued',processed_sections=0,total_sections=0,"
                              "transcription_status=CASE WHEN transcription_status='failed' THEN 'pending' ELSE transcription_status END "
                              "WHERE id=? AND status!='recording' "
                              "AND summary_status IN ('fallback','failed','ready')", (meeting_id,)).rowcount > 0

    def queue_work_revision(self, meeting_id, base_report_id, revision):
        with self.connect() as db:
            return db.execute("UPDATE work_meetings SET revision_request=?,summary_status='pending',summary_stage='queued' "
                              "WHERE id=? AND report_id=? AND summary_status!='pending' AND status!='recording'",
                              (json.dumps(revision, ensure_ascii=False), meeting_id, base_report_id)).rowcount > 0

    def finish_work_revision(self, meeting_id, revision_id, payload, source_ids):
        meeting = self.work_meeting(meeting_id)
        revision = meeting['revision_request'] if meeting else {}
        if revision.get('id') != revision_id or revision.get('status') != 'pending' or meeting['report_id'] != revision['base_report_id']:
            return False
        revision['status'] = 'done'
        return self.save_work_summary(meeting_id, payload, source_ids, revision=revision)

    def fail_work_revision(self, meeting_id, reason):
        meeting = self.work_meeting(meeting_id)
        if not meeting:
            return
        revision = meeting['revision_request']
        revision.update(status='failed', error=reason)
        status = 'ready' if (meeting['report'] or {}).get('extraction') == 'llm' else 'fallback'
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET revision_request=?,summary_status=?,summary_stage='done' WHERE id=?",
                       (json.dumps(revision, ensure_ascii=False), status, meeting_id))

    def fail_work_summary(self, meeting_id):
        meeting = self.work_meeting(meeting_id)
        if meeting and meeting['revision_request'].get('status') == 'pending':
            self.fail_work_revision(meeting_id, 'provider_error')
            return
        with self.connect() as db:
            db.execute("UPDATE work_meetings SET summary_status='failed' WHERE id=? AND summary_status='pending'", (meeting_id,))

    def get_memory(self, memory_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM memories WHERE id=?", (memory_id,)).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _invalidate_recall(db, *, keep_conversation=False):
        # Keep identities so a retry cannot execute an old mutation a second time.
        withdrawn = json.dumps({'text': '', 'response': '记录已变更，请重新查询。', 'speak': False,
                                'invalidated': True}, ensure_ascii=False)
        db.execute("UPDATE mobile_turns SET result=? WHERE result IS NOT NULL", (withdrawn,))
        if not keep_conversation:
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
                db.execute("UPDATE work_meeting_segments SET utterances='[]' WHERE transcript_id=?", (source_id,))
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
                        db.execute("UPDATE work_meetings SET report_id=NULL,summary_status='failed',summary_stage='done',revision_request='{}' WHERE report_id=?", (report['id'],))
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
