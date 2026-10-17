"""Offline evidence coverage, stable meeting tasks and commute freshness checks."""
from datetime import datetime, timezone
import time

import pytest

from app.domain import LocationInput, Mode, Settings, TextInput
from app.services.insights import MeetingInsights, meeting_coverage
from app.services.maps import MapService


def record(key, text="讨论设计方案", **extra):
    return {"id": key, "text": text, "speaker": "小林", "mode": "work",
            "created_at": "2026-10-17T08:00:00+00:00", **extra}


@pytest.mark.asyncio
async def test_coverage_discloses_record_and_text_budgets(config):
    records = [record(str(index), "内容" * 1500) for index in range(100)]
    report = await MeetingInsights(config).generate(records)
    coverage = meeting_coverage(records, report["source_ids"])
    assert coverage["available_records"] == 100
    assert coverage["summarized_records"] == len(report["source_ids"])
    assert coverage["omitted_records"] > 0
    assert coverage["truncated_source_ids"] == report["source_ids"]
    assert coverage["partial"]
    assert not coverage["continuous_capture_verified"]
    assert "缺段" in coverage["note"]


@pytest.mark.asyncio
async def test_full_saved_window_never_claims_gap_free_capture(config):
    records = [record("t1"), record("t2", created_at="2026-10-17T08:10:00+00:00")]
    report = await MeetingInsights(config).generate(records)
    coverage = meeting_coverage(records, report["source_ids"])
    assert not coverage["partial"]
    assert coverage["first_record_at"] == records[0]["created_at"]
    assert coverage["last_record_at"] == records[1]["created_at"]
    assert not coverage["continuous_capture_verified"]
    assert meeting_coverage(records, report["source_ids"], earlier_records_omitted=True)["partial"]


@pytest.mark.asyncio
async def test_assistant_answers_and_nested_quoted_commitments_not_tasks(config):
    records = [record("assistant", "我负责发送邮件", speaker="assistant"),
               record("quotation", "他说：“已经决定了。我负责发送邮件。明天完成。”")]
    report = await MeetingInsights(config).generate(records)
    assert report["source_ids"] == ["quotation"]
    assert report["tasks"] == []
    assert meeting_coverage(records, report["source_ids"])["available_records"] == 1


def task_payload(source_ids):
    return {"summary": "工作纪要", "tasks": [{"content": "提交方案", "owner": "小林",
            "due": "周五", "source_ids": source_ids}]}


def test_regenerating_extended_meeting_keeps_completed_task(store):
    store.save_meeting_report("first-window", task_payload(["original"]))
    original = store.list("action_tasks")[0]
    store.action_task_status(original["id"], "done")
    store.save_meeting_report("extended-window", task_payload(["original", "followup"]))
    tasks = store.list("action_tasks")
    assert len(tasks) == 1
    assert tasks[0]["id"] == original["id"]
    assert tasks[0]["status"] == "done"


def test_identical_tasks_in_distinct_meetings_are_not_merged(store):
    store.save_meeting_report("meeting-a", task_payload(["a"]))
    store.save_meeting_report("meeting-b", task_payload(["b"]))
    assert len(store.list("action_tasks")) == 2


@pytest.mark.asyncio
async def test_route_exposes_estimate_fetch_time(config, monkeypatch):
    config.amap_key = "test-only-key"
    service = MapService(config)

    async def fake_get(client, path, params):
        if path == "assistant/coordinate/convert":
            return {"locations": "121.505,31.202"}
        return {"route": {"paths": [{"duration": "300", "distance": "400"}]}}

    monkeypatch.setattr(service, "_get", fake_get)
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2),
                                 Settings(destination_lon=121.6, destination_lat=31.3))
    fetched = datetime.fromisoformat(result["fetched_at"])
    assert fetched.tzinfo is not None
    assert 0 <= (datetime.now(timezone.utc) - fetched).total_seconds() < 10


@pytest.mark.asyncio
@pytest.mark.parametrize("age,accuracy", [(1200, 10), (1, 500)])
async def test_stale_or_inaccurate_route_cannot_become_current_eta(agent, store, age, accuracy):
    agent.route = {"available": True, "eta_seconds": 300}
    agent.last_route = time.monotonic() - age
    agent.location = {"accuracy": accuracy}
    await agent.propose_arrival("用户请求到达时间草稿")
    assert "5分钟" not in store.list("drafts")[0]["text"]
    assert "确认" in store.list("drafts")[0]["text"]


@pytest.mark.asyncio
async def test_passive_next_station_words_do_not_create_arrival_draft(agent, store):
    store.save_settings(Settings(mode=Mode.commute, auto_mode=False))
    await agent.ingest(TextInput(text="上周听到下一站播报，差点坐过站", source="asr"))
    assert store.list("drafts") == []
