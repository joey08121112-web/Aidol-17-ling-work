import pytest


def test_health_state_and_static_dashboard(client):
    assert client.get("/api/health").json()["ok"]
    response = client.get("/api/state")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    state = response.json()
    assert not state["capabilities"]["doubao"]
    assert state["capabilities"]["messaging"] == "draft_only"
    assert "text_key" not in state
    assert client.get("/").status_code == 200


def test_auth_and_cross_origin_mutations(client):
    client.app.state.agent.config.app_token = "local-test-password"
    assert client.get("/api/health").status_code == 200
    assert client.get("/api/state").status_code == 401
    headers = {"Authorization": "Bearer local-test-password"}
    assert client.get("/api/state", headers=headers).status_code == 200
    headers["Origin"] = "https://untrusted.example"
    assert client.post("/api/text", json={"text": "开始会议"}, headers=headers).status_code == 403
    headers["Origin"] = "http://testserver"
    assert client.post("/api/text", json={"text": "开始会议"}, headers=headers).status_code == 200


@pytest.mark.parametrize("settings", [
    {"timezone": "not/a/timezone"},
    {"destination_lon": 120},
    {"work_start": 18, "work_end": 9},
    {"life_poll_seconds": 5},
    {"provider": "unknown"},
])
def test_invalid_settings_do_not_modify_saved_state(client, settings):
    before = client.get("/api/state").json()["settings"]
    assert client.put("/api/settings", json=settings).status_code == 422
    assert client.get("/api/state").json()["settings"] == before


def test_memory_crud_export_and_clear(client):
    response = client.post("/api/memories", json={"content": "我对花生过敏", "kind": "preference"})
    assert response.status_code == 200
    memory_id = response.json()["id"]
    assert client.get("/api/memories", params={"q": "花生"}).json()[0]["id"] == memory_id
    assert client.get("/api/export").json()["memories"][0]["id"] == memory_id
    assert client.delete(f"/api/memories/{memory_id}").status_code == 200
    assert client.delete(f"/api/memories/{memory_id}").status_code == 404
    client.put("/api/settings", json={"auto_mode": False, "mode": "work"})
    client.post("/api/text", json={"text": "小王负责完成演示", "event_id": "api-event"})
    assert client.post("/api/text", json={"text": "小王负责完成演示", "event_id": "api-event"}).json()["duplicate"]
    assert client.delete("/api/data").status_code == 200
    state = client.get("/api/state").json()
    assert not state["transcripts"] and not state["memories"] and not state["events"]
    assert state["settings"]["mode"] == "work"


def test_clear_is_blocked_during_audio_and_drafts_never_send(client):
    client.app.state.agent.audio_clients = 1
    assert client.delete("/api/data").status_code == 409
    client.app.state.agent.audio_clients = 0
    draft = client.app.state.store.add("drafts", contact="老板", text="约五分钟到", status="pending")
    assert client.post(f"/api/drafts/{draft['id']}/sent").status_code == 400
    result = client.post(f"/api/drafts/{draft['id']}/copied")
    assert result.json() == {"ok": True, "status": "copied", "sent": False}
    assert client.post(f"/api/drafts/{draft['id']}/dismissed").status_code == 404


def test_request_size_and_invalid_content_length(client):
    assert client.post("/api/text", content="x" * 65537).status_code == 413
    assert client.post("/api/text", content="{}", headers={"content-length": "invalid"}).status_code == 400


def test_audio_demo_reports_unavailable_without_network(client):
    with client.websocket_connect("/ws/audio") as websocket:
        websocket.send_json({"type": "start"})
        assert websocket.receive_json()["type"] == "error"
    assert client.app.state.agent.audio_clients == 0
