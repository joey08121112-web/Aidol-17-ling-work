"""Offline acceptance for explicit headset memory interactions and evidence safety."""
from datetime import datetime, timezone

import pytest

from app.domain import Settings, TextInput
from app.services.memory import MemoryService
from app.services.recall import RecallService


async def ask(agent, text, directed=True):
    return await agent.ingest(TextInput(text=text, source="asr"), respond=True, directed=directed)


@pytest.mark.asyncio
async def test_remember_recall_correct_forget_same_memory(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    remembered = await ask(agent, "记住，我答应客户周三交方案")
    original_id = remembered["memories"][0]["id"]
    assert "尚未设置" in remembered["response"]
    assert store.memory_source(original_id)["text"] == "记住，我答应客户周三交方案"
    recalled = await ask(agent, "我答应客户什么时候交方案？")
    assert recalled["interaction"] == "memory_recall"
    assert [c["id"] for c in recalled["citations"]] == [original_id]
    corrected = await ask(agent, "后来改成周五了")
    assert corrected["memories"][0]["id"] == original_id
    assert "周五" in corrected["response"] and "周三" not in corrected["response"]
    again = await ask(agent, "我答应客户什么时候交方案？")
    assert "周五" in again["response"] and "周三" not in again["response"]
    forgotten = await ask(agent, "忘记刚才那条")
    assert forgotten["deletion"]["memories"] == 1
    assert not store.list("memories")
    assert not store.memory_source(original_id)
    missing = await ask(agent, "我答应客户什么时候交方案？")
    assert missing["insufficient"] and not missing["citations"]


@pytest.mark.asyncio
@pytest.mark.parametrize("text,speaker", [
    ("我是不是对花生过敏？", "用户"),
    ("我是不是对花生过敏", "用户"),
    ("我对花生过敏吗", "用户"),
    ("如果我对花生过敏就不吃了", "用户"),
    ("我可能喜欢清淡口味", "用户"),
    ("你对花生过敏", "assistant"),
])
async def test_nonassertions_never_become_facts(config, store, text, speaker):
    saved, _ = await MemoryService(config, store).extract(text, speaker, "work")
    assert not saved and not store.list("memories")


@pytest.mark.asyncio
async def test_question_not_own_evidence_or_auto_memory(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    result = await ask(agent, "我是不是对花生过敏？")
    assert result["insufficient"]
    assert not store.list("memories") and not store.list("transcripts")
    rejected = await ask(agent, "记住，我是不是对花生过敏？")
    assert not rejected["memories"]
    result = await ask(agent, "我是不是对花生过敏？")
    assert result["insufficient"]


@pytest.mark.asyncio
async def test_work_question_answers_without_saving_question(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    row = store.add("memories", content="客户方案周五提交", kind="task", source="user")
    result = await ask(agent, "我答应客户什么时候交方案？")
    assert result["explicit_reply"] and result["mode"] == "work"
    assert result["citations"][0]["id"] == row["id"]
    assert "周五" in result["response"]
    assert len(store.list("memories")) == 1


@pytest.mark.asyncio
async def test_ambient_command_cannot_forget_memory(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="work"))
    row = (await ask(agent, "记住，我答应客户周三交方案"))["memories"][0]
    result = await ask(agent, "忘记刚才那条", directed=False)
    assert not result["explicit_reply"]
    assert store.get_memory(row["id"]) is not None


@pytest.mark.asyncio
async def test_ambiguous_recall_cannot_delete(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    for day in ("周三", "周五"):
        store.add("memories", content=f"我答应客户{day}交方案", kind="task", source="user")
    recalled = await ask(agent, "我答应客户什么时候交方案？")
    assert len(recalled["citations"]) == 2
    response = await ask(agent, "忘掉它")
    assert "不能确定" in response["response"] and len(store.list("memories")) == 2


@pytest.mark.asyncio
async def test_expired_context_cannot_modify(agent, store, monkeypatch):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    await ask(agent, "记住，我答应客户周三交方案")
    agent.memory_interaction.context_at -= 601
    result = await ask(agent, "改成周五")
    assert "不能确定" in result["response"]
    assert "周三" in store.list("memories")[0]["content"]


@pytest.mark.asyncio
async def test_multi_date_correction_asks_for_full_text(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    await ask(agent, "记住，周三讨论方案，周四提交方案")
    result = await ask(agent, "改成周五")
    assert "完整的新约定" in result["response"]
    assert "周三" in store.list("memories")[0]["content"]


@pytest.mark.asyncio
async def test_recall_rejects_assistant_and_question_as_evidence(config):
    rows = [
        {"id": "q", "text": "我是不是对花生过敏？", "speaker": "用户"},
        {"id": "a", "text": "你对花生过敏", "speaker": "assistant"},
    ]
    result = await RecallService(config).answer("我对花生过敏吗？", [], rows)
    assert result["insufficient"]


@pytest.mark.asyncio
async def test_recall_keeps_long_record_tail(config):
    row = {"id": "tail", "content": "背景" * 1200 + "和姐姐去了蓝鲸咖啡馆", "kind": "fact"}
    result = await RecallService(config).answer("蓝鲸咖啡馆", [row], [])
    assert "蓝鲸咖啡馆" in result["answer"]


@pytest.mark.asyncio
async def test_recall_followup_uses_prior_topic_and_previous_month(agent, store):
    store.save_settings(Settings(auto_mode=False, mode="life"))
    now = datetime.now(timezone.utc)
    prior_year, prior_month = (now.year - 1, 12) if now.month == 1 else (now.year, now.month - 1)
    old = store.add("memories", content="和姐姐去了蓝鲸咖啡馆", kind="fact", source="user")
    store.add("memories", content="和姐姐去了白云咖啡馆", kind="fact", source="user")
    with store.connect() as db:
        db.execute("UPDATE memories SET created_at=? WHERE id=?", (f"{prior_year}-{prior_month:02}-12T08:00:00Z", old["id"]))
    await ask(agent, "上次和姐姐去的咖啡馆叫什么？")
    result = await ask(agent, "不是这次，是上个月那次")
    assert [row["id"] for row in result["citations"]] == [old["id"]]
    assert "蓝鲸" in result["response"] and "白云" not in result["response"]

@pytest.mark.asyncio
async def test_model_paraphrase_cannot_erase_person_negation_or_time(config, store, monkeypatch):
    import httpx
    config.text_key, config.text_model = "offline", "mock"
    original = httpx.AsyncClient
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json={
        "choices": [{"message": {"content": '{"items":[{"kind":"preference","content":"用户喜欢咖啡"}]}'}}]}))
    monkeypatch.setattr("app.services.memory.httpx.AsyncClient", lambda **kwargs: original(transport=transport))
    text = "姐姐说最近不喜欢咖啡"
    saved, _ = await MemoryService(config, store).extract(text, "用户", "life")
    assert saved[0]["content"] == "用户：" + text

def test_http_memory_lifecycle_including_assistant_history(client, runtime):
    def turn(text, request_id):
        result = client.post('/v1/chat', json={'text': text, 'request_id': 'integration-' + request_id,
                                             'interaction_source': 'direct_question'})
        assert result.status_code == 200, result.text
        return result.json()

    client.put('/v1/settings', json={'mode': 'work', 'auto_mode': False})
    saved = turn('记住，我答应客户周三交方案', 'memory-save')
    memory_id = saved['memories'][0]['id']
    assert saved['speak']
    assert any(row['speaker'] == 'assistant' for row in runtime.store.list('transcripts'))
    recalled = turn('我答应客户什么时候交方案？', 'memory-recall')
    assert recalled['speak'] and [row['id'] for row in recalled['citations']] == [memory_id]
    assert turn('后来改成周五了', 'memory-correct')['memories'][0]['id'] == memory_id
    after = turn('我答应客户什么时候交方案？', 'memory-recall-2')
    assert '周五' in after['response'] and '周三' not in after['response']
    assert all('周三' not in row['text'] for row in runtime.store.conversation_history())
    assert turn('忘记刚才那条', 'memory-forget')['deletion']['memories'] == 1
    missing = turn('我答应客户什么时候交方案？', 'memory-recall-3')
    assert missing['insufficient'] and not missing['citations']
    assert not runtime.store.list('memories')
    assert all('周五' not in row['text'] for row in runtime.store.conversation_history())
    assert client.post('/v1/recall', json={'question': '客户方案'}).json()['insufficient']
    retried = turn('记住，我答应客户周三交方案', 'memory-save')
    assert retried['invalidated']
    assert not runtime.store.list('memories')

@pytest.mark.asyncio
async def test_linked_default_speaker_retains_exact_transcript(config, store):
    original = "嗯嗯，我负责周五提交项目方案。"
    transcript = store.add('transcripts', speaker='用户', text=original, mode='work', source='asr')
    saved, _ = await MemoryService(config, store).extract(original, '用户', 'work', 'asr', transcript_id=transcript['id'])
    assert saved[0]['content'] == original
    assert store.remember_transcript(transcript['id'])['id'] == saved[0]['id']
    assert store.memory_source(saved[0]['id'])['text'] == original

@pytest.mark.asyncio
async def test_rejected_new_remember_does_not_leave_old_delete_target(agent, store):
    store.save_settings(Settings(auto_mode=False, mode='life'))
    await ask(agent, '记住，我负责周三提交方案')
    await ask(agent, '记住，我是不是对花生过敏')
    result = await ask(agent, '忘记刚才那条')
    assert '不能确定' in result['response']
    assert '提交方案' in store.list('memories')[0]['content']
