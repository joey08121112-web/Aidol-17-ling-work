"""Demo TOP-K memory context, using isolated accounts and offline reply providers."""
import json

import httpx
import pytest

from app.domain import Settings, TextInput
from app.services.agent import Agent
from app.events import EventBus
from app.storage import Store
from app.services.qwen_voice import realtime_instructions


def test_topk_context_keeps_newest_rows_beyond_snapshot_limit_and_refreshes(agent, store, tmp_path):
    for index in range(305):
        store.add('memories', content=f'旧记录{index}', kind='fact', source='user')
    rows = [store.add('memories', content=content, kind=kind, source='user')
            for kind, content in [('preference', '不吃香菜'), ('task', '周五交方案'),
                                  ('trip', '下周去上海')]]
    context = agent.instructions(directed=True)
    injected = store.conversation_memories()
    # 注入上限 60：最新三类保留，远期旧记录淘汰出每轮提示词。
    assert len(injected) == 60
    for row in injected:
        assert row['content'] in context
    assert '旧记录304' in context and '旧记录0' not in context and '旧记录240' not in context
    other = Store(str(tmp_path / 'other.sqlite3'))
    other.add('memories', content='另一个账号的秘密', kind='fact', source='user')
    assert '另一个账号的秘密' not in context
    store.correct_memory(rows[1]['id'], '改为周六交方案')
    store.delete_memory(rows[2]['id'])
    refreshed = agent.instructions(directed=True)
    assert '改为周六交方案' in refreshed and '周五交方案' not in refreshed
    assert '下周去上海' not in refreshed


@pytest.mark.asyncio
@pytest.mark.parametrize('question', ['我之前停在什么位置？', '找回我的停车记录'])
@pytest.mark.parametrize('mode', ['life', 'commute'])
async def test_text_recall_uses_full_context_without_keyword_lookup(agent, config, store, monkeypatch, question, mode):
    store.save_settings(Settings(auto_mode=False, mode=mode))
    store.add('memories', content='停车位是B2层A区168号', kind='fact', source='user')
    config.text_key, config.text_model = 'offline', 'mock'
    original = httpx.AsyncClient
    calls = []

    def handler(request):
        body = json.loads(request.content)
        calls.append(body)
        assert '停车位是B2层A区168号' in body['messages'][0]['content']
        return httpx.Response(200, json={'choices': [{'message': {'content': '你停在B2层A区168号。'}}]})

    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kwargs: original(transport=httpx.MockTransport(handler)))
    def forbidden(*args):
        raise AssertionError('Conversation must not require keyword retrieval')
    monkeypatch.setattr(store, 'recall_records', forbidden)
    result = await agent.ingest(TextInput(text=question), directed=True)
    assert result['interaction'] == 'text_llm'
    assert result['response'] == '你停在B2层A区168号。'
    assert len(calls) == 1 and len(store.list('memories')) == 1
    # Model answers do not establish a unique deletion target.
    forgotten = await agent.ingest(TextInput(text='忘记刚才那条'), directed=True)
    assert '不能确定' in forgotten['response'] and len(store.list('memories')) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('mode', ['life', 'commute'])
async def test_native_voice_has_memories_after_runtime_restart(agent, config, store, mode):
    store.save_settings(Settings(auto_mode=False, mode=mode))
    await agent.ingest(TextInput(text='帮我记一下，停车位是B2层A区168号'), directed=True)
    restarted = Agent(config, Store(store.path), EventBus(store))

    async def native(instructions, toolkit):
        context = realtime_instructions(instructions)
        assert '停车位是B2层A区168号' in context
        assert '用户历史事实先调用 recall_memory' not in context
        return {'text': '你停在B2层A区168号。', 'complete': True}

    result = await restarted.ingest(TextInput(text='我之前停在什么位置？', source='asr'),
                                    directed=True, response_provider=native)
    assert result['interaction'] == 'qwen_realtime'
    assert result['response'] == '你停在B2层A区168号。'


@pytest.mark.asyncio
async def test_failed_text_model_still_returns_evidence(agent, config, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode='life'))
    store.add('memories', content='我答应客户周五交方案', kind='task', source='user')
    config.text_key, config.text_model = 'offline', 'mock'
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kwargs: original(
        transport=httpx.MockTransport(lambda request: httpx.Response(503))))
    async def unavailable(*args):
        return None
    monkeypatch.setattr(agent.conversation, 'reply', unavailable)
    result = await agent.ingest(TextInput(text='我答应客户什么时候交方案？'), directed=True)
    assert result['interaction'] == 'memory_recall'
    assert '周五' in result['response'] and result['citations']


def test_life_queries_are_answered_by_the_assistant_not_deflected(agent):
    for directed in (True, False):
        context = realtime_instructions(agent.instructions(directed=directed))
        assert '绝不能让用户自己去查' in context and 'web_search' in context
        assert '实际下单需用户在官方渠道完成' not in context
