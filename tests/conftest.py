"""Offline fixtures: real .env credentials must never enter test services."""
from dataclasses import fields

import pytest

from app.config import Config
from app.providers.volc_speech import VolcSpeechConfig
from app.events import EventBus
from app.services.agent import Agent
from app.storage import Store


@pytest.fixture
def config(tmp_path):
    # Include future provider credentials automatically as adapters are added.
    credentials = {
        field.name: ""
        for field in fields(Config)
        if any(part in field.name for part in ("key", "token", "secret", "app_id"))
    }
    return Config(database_path=str(tmp_path / "test.sqlite3"), proxy=None, asr_mode="stream",
                  speech=VolcSpeechConfig(), **credentials)


@pytest.fixture
def store(config):
    return Store(config.database_path)


@pytest.fixture
def bus(store):
    return EventBus(store)


@pytest.fixture
def agent(config, store, bus):
    return Agent(config, store, bus)


@pytest.fixture
def client(config, tmp_path, monkeypatch):
    """Authenticated Android API client backed by a temporary account store."""
    monkeypatch.setenv("AIDOL_REGISTRATION_OPEN", "true")
    monkeypatch.setenv("AIDOL_MAX_USERS", "500")
    monkeypatch.setenv("AIDOL_DAILY_REQUESTS", "300")
    from fastapi.testclient import TestClient
    from app.main import create_hosted_app

    with TestClient(create_hosted_app(config, tmp_path / "hosted")) as value:
        registered = value.post("/auth/register", json={
            "username": "testuser", "password": "test-password-123"})
        assert registered.status_code == 201, registered.text
        value.headers["Authorization"] = "Bearer " + registered.json()["token"]
        yield value


@pytest.fixture
def runtime(client):
    state = client.get("/v1/state")
    assert state.status_code == 200, state.text
    return client.app.state.runtimes.items[state.json()["user"]["id"]]
