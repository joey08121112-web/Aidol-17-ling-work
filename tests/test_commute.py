"""Home/work commute direction, resolved locally without provider calls."""
from datetime import datetime

import pytest

from app.domain import LocationInput, Settings
from app.services.commute import commute_destination, with_destination

HOME, WORK = (121.40, 31.20), (121.50, 31.25)


def both(**update):
    return Settings(home_lon=HOME[0], home_lat=HOME[1], work_lon=WORK[0], work_lat=WORK[1],
                    work_city="上海", **update)


def test_auto_direction_follows_nearby_place_then_time_of_day():
    settings = both()
    assert commute_destination(settings, (HOME[0] + 0.002, HOME[1]))["target"] == "work"
    assert commute_destination(settings, WORK)["target"] == "home"
    # Away from both places, the morning goes to work and the evening goes home.
    assert commute_destination(settings, (121.0, 30.0), datetime(2026, 10, 19, 8))["target"] == "work"
    assert commute_destination(settings, None, datetime(2026, 10, 19, 19))["target"] == "home"


def test_fixed_target_custom_fallback_and_missing_places():
    assert commute_destination(both(commute_target="home"), HOME)["target"] == "home"
    assert commute_destination(Settings(commute_target="work"), None)["reason"] == "请先设置公司的位置"
    legacy = Settings(destination_name="客户", destination_lon=121.6, destination_lat=31.3)
    assert commute_destination(legacy, None)["name"] == "客户"
    assert commute_destination(Settings(), None)["target"] is None
    only_work = Settings(work_lon=WORK[0], work_lat=WORK[1])
    assert commute_destination(only_work, WORK)["target"] is None
    assert commute_destination(only_work, HOME)["target"] == "work"


def test_routed_settings_copy_leaves_saved_settings_unchanged():
    settings = both()
    routed = with_destination(settings, commute_destination(settings, HOME))
    assert (routed.destination_lon, routed.destination_name, routed.transit_city) == (WORK[0], "公司", "上海")
    assert settings.destination_lon is None


def test_home_and_work_coordinates_must_be_paired():
    with pytest.raises(ValueError):
        Settings(home_lon=121.4)


@pytest.mark.asyncio
async def test_location_routes_to_work_from_home(agent, store, monkeypatch):
    store.save_settings(both(auto_mode=False, mode="commute"))
    seen = []

    async def route(location, settings):
        seen.append(settings.destination_name)
        return {"available": True, "eta_seconds": 1800, "destination": settings.destination_name}

    monkeypatch.setattr(agent.maps, "route", route)
    await agent.update_location(LocationInput(longitude=HOME[0], latitude=HOME[1], accuracy=10))
    assert seen == ["公司"]
    assert agent.snapshot()["route"]["target"] == "work"
    assert agent.snapshot()["commute"]["basis"] == "near_home"


@pytest.mark.asyncio
async def test_plan_route_tool_accepts_home_target(agent, store, monkeypatch):
    from app.services.assistant_tools import AssistantTools
    store.save_settings(both())
    await agent.update_location(LocationInput(longitude=WORK[0], latitude=WORK[1], accuracy=10))

    async def route(location, settings):
        return {"available": True, "eta_seconds": 600, "destination": settings.destination_name}

    monkeypatch.setattr(agent.maps, "route", route)
    tools = AssistantTools(agent)
    assert (await tools.call("plan_route", {"target": "home"}))["destination"] == "家"
