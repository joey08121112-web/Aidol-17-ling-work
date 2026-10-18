"""TOP-K prompt injection: pinned+relevant+recent inside a hard character budget."""
from app.storage import MEMORY_CONTEXT_LIMIT


def test_topk_tiers_are_pinned_relevance_then_recency(store):
    store.add('memories', content='置顶的旧收藏', kind='fact', source='user', pinned=1)
    for index in range(200):
        store.add('memories', content=f'早期填充{index}', kind='fact', source='user')
    relevant = store.add('memories', content='停车位是B2层A区168号', kind='fact', source='user')
    for index in range(200):
        store.add('memories', content=f'尾部填充{index}', kind='fact', source='user')
    rows = store.conversation_memories('找回我的停车记录')
    assert len(rows) == MEMORY_CONTEXT_LIMIT == 60
    assert rows[0]['content'] == '置顶的旧收藏'      # 收藏优先
    assert rows[1]['id'] == relevant['id']          # FTS5 相关性紧随其后
    assert rows[2]['content'] == '尾部填充199'       # 其余名额按近期补齐
    assert all(not row['content'].startswith('早期填充') for row in rows)
    assert rows == store.conversation_memories('找回我的停车记录')  # 同问重取结果确定


def test_prompt_injection_stays_inside_character_budget(agent, store):
    # 审计 2026-10-18：全量注入 1000 条 ≈ 15.2 万字符/轮。TOP-K 后无论账号积累
    # 多少条，注入体积由条数上限封顶；此守卫断言字符数，不依赖计时。
    for index in range(1000):
        store.add('memories', content=f'长记录{index}' + '满' * 120, kind='fact', source='user')
    prompt = agent.instructions(directed=True)
    assert len(prompt) < 24_000
    assert prompt.count('长记录') <= MEMORY_CONTEXT_LIMIT
    assert '并非全部历史' in prompt and 'recall_memory' in prompt
    assert f'最多 {MEMORY_CONTEXT_LIMIT} 条' in prompt
    questioned = agent.instructions(directed=True, question='长记录999讲了什么')
    assert len(questioned) < 24_000
    assert '长记录999' in questioned  # 与问题相关的记录经 FTS5 档入选


def test_zero_limit_returns_empty_and_short_questions_keep_recent(store):
    for index in range(80):
        store.add('memories', content=f'记录{index}', kind='fact', source='user')
    assert store.conversation_memories(limit=0) == []
    rows = store.conversation_memories(limit=3)
    assert [row['content'] for row in rows] == ['记录79', '记录78', '记录77']
