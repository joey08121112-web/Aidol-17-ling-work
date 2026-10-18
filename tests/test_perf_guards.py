"""Deterministic performance guards (no wall-clock assertions, query plans only)."""
import tempfile, os

from app.storage import Store


def test_conversation_history_uses_created_at_index():
    with tempfile.TemporaryDirectory() as directory:
        store = Store(os.path.join(directory, 'u.sqlite3'))
        with store.connect() as db:
            plan = [row['detail'] for row in db.execute(
                "EXPLAIN QUERY PLAN SELECT * FROM transcripts r WHERE NOT EXISTS"
                "(SELECT 1 FROM recall_exclusions e WHERE e.transcript_id=r.id) "
                "ORDER BY created_at DESC LIMIT 12")]
        # Without this index every turn pays a full-table TEMP B-TREE sort
        # (measured 8 ms at 20k transcripts vs 0.6 ms with it).
        assert any('USING INDEX transcript_created' in detail for detail in plan), plan
        assert not any('TEMP B-TREE' in detail for detail in plan), plan
