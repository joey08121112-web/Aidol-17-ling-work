import pytest
import asyncio
from unittest.mock import AsyncMock
from app.domain import Mode
from app.domain import LocationInput


@pytest.mark.asyncio
async def test_meeting_intent_clears_stale_commute_evidence(agent):
    assert await agent.decide("开始通勤") == Mode.commute
    assert await agent.decide("开始会议") == Mode.work
    assert await agent.decide("我负责完成接口") == Mode.work


def test_new_destination_cannot_reuse_old_eta(client):
    agent = client.app.state.agent
    agent.route = {"available": True, "eta_seconds": 120, "destination": "旧目的地"}
    agent.last_route, agent.last_notice = 12345, 12345
    settings = client.get('/api/state').json()['settings']
    response = client.put('/api/settings', json={**settings, 'destination_name': '新目的地'})
    assert response.status_code == 200
    assert agent.route is None and agent.last_route == 0 and agent.last_notice == 0


@pytest.mark.asyncio
async def test_inflight_route_cannot_restore_changed_destination(agent):
    settings = agent.store.settings().model_copy(update={"auto_mode": False, "mode": Mode.commute})
    agent.store.save_settings(settings)
    started, release = asyncio.Event(), asyncio.Event()

    async def delayed_route(location, settings):
        started.set()
        await release.wait()
        return {"available": True, "eta_seconds": 60, "destination": settings.destination_name}

    agent.maps.route = delayed_route
    agent.propose_arrival = AsyncMock()
    pending = asyncio.create_task(agent.update_location(LocationInput(longitude=121.4, latitude=31.2)))
    await started.wait()
    agent.store.save_settings(settings.model_copy(update={"destination_name": "新目的地"}))
    release.set()
    await pending
    assert agent.route is None
    assert agent.last_route == 0
    agent.propose_arrival.assert_not_awaited()
