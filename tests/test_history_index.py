"""Whole-history indexed candidates and mutation/cache consistency, offline only."""
import sqlite3

from app.storage import Store


def memory(store, content):
    return store.add('memories', kind='fact', content=content, source='user')


def test_old_memory_survives_thousands_of_recent_records_and_tasks(store):
    old = memory(store, '姐姐上个月带我去白桦咖啡馆')
    old_transcript = store.add('transcripts', text='姐姐喜欢白桦咖啡馆', speaker='用户', mode='life', source='asr')
    with store.connect() as db:
        for index in range(1100):
            db.execute('INSERT INTO memories (id,kind,content,source,created_at) VALUES(?,?,?,?,?)',
                       (str(index), 'fact', '今天购买面包', 'user', '2099-01-01'))
            db.execute('INSERT INTO transcripts (id,speaker,text,mode,source,created_at) VALUES(?,?,?,?,?,?)',
                       (str(index), '用户', '下午去散步', 'life', 'asr', '2099-01-01'))
            db.execute('INSERT INTO action_tasks(id,report_id,content,owner,due,source_ids,status,created_at) VALUES(?,?,?,?,?,?,?,?)',
                       (str(index), 'report', '准备文档', '', '', '[]', 'open', '2099-01-01'))
    memories, transcripts = store.recall_records('和姐姐去的白桦咖啡馆叫什么？')
    assert old['id'] in {row['id'] for row in memories}
    assert old_transcript['id'] in {row['id'] for row in transcripts}
    assert len(memories) <= 200 and len(transcripts) <= 100


def test_existing_history_is_indexed_on_upgrade_once(tmp_path):
    path = tmp_path / 'legacy.sqlite3'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE memories (id TEXT PRIMARY KEY,kind TEXT,content TEXT,source TEXT,created_at TEXT)')
        db.execute("INSERT INTO memories VALUES('old','fact','白桦咖啡馆','rules','2020-01-01')")
    for _ in range(2):
        store = Store(str(path))
        records, _ = store.recall_records('白桦咖啡馆')
        assert len(records) == 1 and records[0]['id'] == 'old'


def test_correction_keeps_source_inspectable_but_not_current_evidence(store):
    source = store.add('transcripts', text='周四交星光方案', mode='work', source='asr', speaker='用户')
    saved = store.remember_transcript(source['id'])
    store.add('transcripts', text='你说周四交星光方案', mode='work', source='asr', speaker='assistant')
    store.mobile_turn('request-1', 'text:original')
    store.finish_mobile_turn('request-1', {'response': '周四交星光方案'})
    corrected = store.correct_memory(saved['id'], '周五交星光方案')
    assert corrected['id'] == saved['id'] and corrected['created_at'] == saved['created_at']
    memories, transcripts = store.recall_records('星光方案')
    assert [row['content'] for row in memories] == ['周五交星光方案']
    assert transcripts == []
    assert store.memory_source(saved['id'])['text'] == source['text']
    assert store.conversation_history() == []
    assert store.mobile_turn('request-1', 'text:original')['invalidated']


def test_forget_removes_source_derivatives_and_prevents_cache_replay(store):
    source = store.add('transcripts', text='周四交星光方案', mode='work', source='asr', speaker='用户')
    saved = store.remember_transcript(source['id'])
    store.save_meeting_report('report-signature', {'source_ids': [source['id']], 'tasks': [
        {'content': source['text'], 'owner': '', 'due': '周四', 'source_ids': [source['id']]}]})
    store.mobile_turn('request-1', 'text:original')
    store.finish_mobile_turn('request-1', {'response': source['text']})
    receipt = store.forget_memory(saved['id'])
    assert receipt == {'memories': 1, 'transcripts': 1, 'reports': 1, 'tasks': 1, 'scope': 'memory_and_linked_source'}
    assert store.recall_records('星光方案') == ([], [])
    assert store.mobile_turn('request-1', 'text:original')['invalidated']
    assert store.list('action_tasks') == [] and store.all_meeting_reports() == []
    assert store.forget_memory(saved['id']) is None
    assert Store(store.path).recall_records('星光方案') == ([], [])


def test_query_syntax_is_data_and_clear_empties_index(store):
    memory(store, '白桦咖啡馆')
    assert store.recall_records('" OR * NOT (咖啡馆)')[0]
    store.clear()
    assert store.recall_records('咖啡馆') == ([], [])
    with store.connect() as db:
        assert db.execute('SELECT count(*) FROM recall_index').fetchone()[0] == 0


def test_private_work_question_is_not_meeting_material(store):
    store.add('transcripts', text='我答应客户周五交方案吗？', mode='work', speaker='用户', source='asr',
              interaction_source='direct_question')
    ambient = store.add('transcripts', text='我答应客户周五交方案', mode='work', speaker='用户', source='asr')
    assert [row['id'] for row in store.meeting_records('2020-01-01')] == [ambient['id']]


def test_questions_do_not_crowd_old_evidence_out_of_candidate_window(store):
    fact = store.add('transcripts', text='姐姐对花生过敏', mode='life', speaker='用户', source='asr')
    for _ in range(105):
        store.add('transcripts', text='姐姐是不是对花生过敏？', mode='life', speaker='用户', source='asr')
    _, transcripts = store.recall_records('姐姐是不是对花生过敏？')
    assert [row['id'] for row in transcripts] == [fact['id']]


def test_legacy_question_memories_are_not_returned_as_facts(store):
    memory(store, '用户：我是不是对花生过敏？')
    assert store.recall_records('我是不是对花生过敏？') == ([], [])
