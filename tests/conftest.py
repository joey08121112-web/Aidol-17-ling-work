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
def client(config, monkeypatch):
    # main has a module-level app; ensure its default database is temporary too.
    monkeypatch.setenv("DATABASE_PATH", config.database_path)
    from fastapi.testclient import TestClient
    from app.main import create_app

    with TestClient(create_app(config)) as value:
        yield value
