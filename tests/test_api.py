"""Hosted API boundaries shared by Android product features."""
import os
from pathlib import Path
import subprocess
import sys

import pytest
from starlette.websockets import WebSocketDisconnect


def test_health_state_and_retired_browser_routes(client):
    assert client.get("/healthz").json()["ok"]
    response = client.get("/v1/state")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    state = response.json()
    assert not state["capabilities"]["text_llm"]
    assert state["capabilities"]["messaging"] == "draft_only"
    assert "text_key" not in state
    for path in ("/", "/api/health", "/api/state", "/api/events", "/audio/session.js"):
        assert client.get(path).status_code == 404
    assert client.post("/api/text", json={"text": "开始会议"}).status_code == 404
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/audio"):
            pytest.fail("retired browser audio endpoint accepted a connection")


def test_import_main_does_not_create_a_single_user_database(tmp_path):
    legacy = tmp_path / "must-not-be-created.sqlite3"
    hosted = tmp_path / "must-not-start-hosted"
    env = {**os.environ, "DATABASE_PATH": str(legacy), "AIDOL_DATA_DIR": str(hosted)}
    result = subprocess.run(
        [sys.executable, "-c", "import app.main"],
        cwd=Path(__file__).resolve().parents[1], env=env,
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert not legacy.exists()
    assert not hosted.exists()


@pytest.mark.parametrize("settings", [
    {"timezone": "not/a/timezone"},
    {"destination_lon": 120},
    {"work_start": 18, "work_end": 9},
    {"life_poll_seconds": 5},
    {"provider": "unknown"},
])
def test_invalid_settings_do_not_modify_saved_state(client, settings):
    before = client.get("/v1/state").json()["settings"]
    assert client.put("/v1/settings", json=settings).status_code == 422
    assert client.get("/v1/state").json()["settings"] == before


@pytest.mark.parametrize("length", ["0", "65536", "invalid"])
def test_request_size_uses_actual_body_instead_of_declared_length(client, length):
    response = client.post("/v1/chat", content="x" * 65537, headers={"content-length": length})
    assert response.status_code == 413
