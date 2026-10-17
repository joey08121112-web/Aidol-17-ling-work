"""Hosted Android contract: real auth/SQLite, offline providers, isolated accounts."""
from dataclasses import replace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.providers.volc_speech import AsrResult, SpeechProviderError


PASSWORD = "test-password-123"


@pytest.fixture
def mobile(config, tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_PATH", config.database_path)
    monkeypatch.setenv("AIDOL_REGISTRATION_OPEN", "true")
    monkeypatch.setenv("AIDOL_MAX_USERS", "500")
    monkeypatch.setenv("AIDOL_DAILY_REQUESTS", "300")
    config.speech = replace(config.speech, asr_api_key="offline-test-only",
                            tts_app_key="offline-test-only", tts_access_key="offline-test-only")
    from app.main import create_hosted_app
    api = create_hosted_app(config, tmp_path / "hosted")

    async def transcribe(chunks):
        async for _ in chunks:
            pass
        yield AsrResult("请讲个笑话", final=True)

    async def synthesize(text):
        return b"RIFF-offline-test-WAVE"

    monkeypatch.setattr(api.state.speech, "stream_asr", transcribe)
    monkeypatch.setattr(api.state.speech, "synthesize_wav", synthesize)
    with TestClient(api) as client:
        yield client


def register(client, username="alice"):
    response = client.post("/auth/register", json={"username": username, "password": PASSWORD})
    assert response.status_code == 201, response.text
    result = response.json()
    return result, {"Authorization": "Bearer " + result["token"]}


def chat(client, headers, text="请讲个笑话", request_id=None):
    return client.post("/v1/chat", headers=headers,
                       json={"text": text, "request_id": request_id or uuid4().hex})


def test_registration_normalizes_unique_id_and_never_exposes_hashes(mobile):
    account, headers = register(mobile, "Alice_01")
    assert account["user"]["username"] == "alice_01"
    assert len(account["recovery_code"]) >= 24
    assert mobile.post("/auth/register", json={"username": "ALICE_01", "password": PASSWORD}).status_code == 409
    me = mobile.get("/auth/me", headers=headers)
    assert set(me.json()) == {"id", "username", "created_at"}
    assert me.json() == account["user"]
    assert me.headers["cache-control"] == "no-store"
    db = mobile.app.state.accounts
    with db.connect() as connection:
        row = dict(connection.execute("SELECT * FROM users").fetchone())
        session = dict(connection.execute("SELECT * FROM sessions").fetchone())
    assert row["password"] != PASSWORD and ":" in row["password"]
    assert row["recovery"] != account["recovery_code"]
    assert session["token"] != account["token"]


@pytest.mark.parametrize("username,password", [("ab", PASSWORD), ("a b", PASSWORD),
    ("账号一", PASSWORD), ("alice", "short"), ("a" * 25, PASSWORD), ("alice", "x" * 129)])
def test_invalid_credentials_are_rejected_without_echo(mobile, username, password):
    result = mobile.post("/auth/register", json={"username": username, "password": password})
    assert result.status_code == 422
    assert password not in result.text


def test_login_logout_and_expired_sessions(mobile):
    first, headers = register(mobile)
    unknown = mobile.post("/auth/login", json={"username": "missing", "password": PASSWORD})
    wrong = mobile.post("/auth/login", json={"username": "alice", "password": "wrong-password"})
    assert unknown.status_code == wrong.status_code == 401
    assert unknown.json() == wrong.json()
    login = mobile.post("/auth/login", json={"username": "ALICE", "password": PASSWORD})
    assert login.status_code == 200
    second_headers = {"Authorization": "Bearer " + login.json()["token"]}
    assert mobile.post("/auth/logout", headers=headers).status_code == 200
    assert mobile.get("/auth/me", headers=headers).status_code == 401
    assert mobile.get("/auth/me", headers=second_headers).status_code == 200
    with mobile.app.state.accounts.connect() as db:
        db.execute("UPDATE sessions SET expires=0")
    assert mobile.get("/auth/me", headers=second_headers).status_code == 401


def test_password_change_revokes_all_previous_sessions(mobile):
    _, headers = register(mobile)
    other = mobile.post("/auth/login", json={"username": "alice", "password": PASSWORD}).json()
    failed = mobile.put("/auth/password", headers=headers,
                        json={"old_password": "incorrect", "new_password": "new-password-456"})
    assert failed.status_code == 401
    response = mobile.put("/auth/password", headers=headers,
                          json={"old_password": PASSWORD, "new_password": "new-password-456"})
    assert response.status_code == 200
    for token in (headers["Authorization"], "Bearer " + other["token"]):
        assert mobile.get("/auth/me", headers={"Authorization": token}).status_code == 401
    assert mobile.get("/auth/me", headers={"Authorization": "Bearer " + response.json()["token"]}).status_code == 200
    assert mobile.post("/auth/login", json={"username": "alice", "password": PASSWORD}).status_code == 401
    assert mobile.post("/auth/login", json={"username": "alice", "password": "new-password-456"}).status_code == 200


def test_recovery_rotates_code_password_and_sessions(mobile):
    account, headers = register(mobile)
    payload = {"username": "ALICE", "password": "replacement-password", "recovery_code": "x" * 32}
    assert mobile.post("/auth/recover", json=payload).status_code == 401
    payload["recovery_code"] = account["recovery_code"]
    recovered = mobile.post("/auth/recover", json=payload)
    assert recovered.status_code == 200
    assert recovered.json()["recovery_code"] != account["recovery_code"]
    assert mobile.get("/auth/me", headers=headers).status_code == 401
    assert mobile.post("/auth/recover", json=payload).status_code == 401
    assert mobile.post("/auth/login", json={"username": "alice", "password": PASSWORD}).status_code == 401
    assert mobile.post("/auth/login", json={"username": "alice", "password": payload["password"]}).status_code == 200


@pytest.mark.parametrize("method,path,payload", [
    ("GET", "/auth/me", None), ("POST", "/auth/logout", None),
    ("PUT", "/auth/password", {"old_password": PASSWORD, "new_password": PASSWORD}),
    ("DELETE", "/auth/account", {"password": PASSWORD}), ("GET", "/v1/state", None),
    ("PUT", "/v1/settings", {}), ("POST", "/v1/chat", {"text": "hello", "request_id": "a" * 16}),
    ("POST", "/v1/voice?request_id=" + "a" * 16, None),
    ("POST", "/v1/speech", {"text": "hello"}), ("POST", "/v1/memories", {"content": "hello"}),
    ("DELETE", "/v1/memories/foreign", None), ("PUT", "/v1/memories/foreign", {"content": "hello"}),
    ("POST", "/v1/recall", {"question": "hello"}), ("POST", "/v1/meetings", {}),
    ("PATCH", "/v1/tasks/foreign", {"status": "done"}), ("GET", "/v1/export", None),
    ("DELETE", "/v1/data", {"password": PASSWORD}),
    ("POST", "/v1/memories/from-transcript/foreign", None),
    ("GET", "/v1/memories/foreign/source", None),
    ("GET", "/v1/maps/search?q=公园", None), ("GET", "/v1/maps/weather?city=310000", None),
    ("POST", "/v1/location", {"longitude": 121.5, "latitude": 31.2}),
    ("POST", "/v1/maps/nearby", {"longitude": 121.5, "latitude": 31.2}),
    ("POST", "/v1/drafts/foreign/copied", None)])
def test_every_private_route_requires_authentication(mobile, method, path, payload):
    assert mobile.request(method, path, json=payload).status_code == 401


def test_public_host_never_mounts_single_user_api(mobile):
    for path in ("/api/state", "/api/export", "/", "/docs", "/openapi.json"):
        assert mobile.get(path).status_code == 404
    assert mobile.get("/healthz").json()["ok"] is True


def test_chat_is_idempotent_and_payload_conflicts_are_rejected(mobile):
    _, headers = register(mobile)
    request_id = uuid4().hex
    first = chat(mobile, headers, request_id=request_id)
    retry = chat(mobile, headers, request_id=request_id)
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()
    state = mobile.get("/v1/state", headers=headers).json()
    assert len(state["transcripts"]) == 2
    assert [row["speaker"] for row in state["transcripts"]] == ["用户", "assistant"]
    assert chat(mobile, headers, "different", request_id).status_code == 409


def test_voice_idempotency_formats_and_speech_failure(mobile, monkeypatch):
    _, headers = register(mobile)
    pcm_headers = {**headers, "Content-Type": "audio/pcm"}
    url = "/v1/voice?request_id=" + uuid4().hex
    pcm = b"\x00\x00" * 1600
    first = mobile.post(url, headers=pcm_headers, content=pcm)
    assert first.status_code == 200
    assert first.json()["text"] == "请讲个笑话"
    assert mobile.post(url, headers=pcm_headers, content=pcm).json() == first.json()
    assert mobile.post(url, headers=pcm_headers, content=b"\x01\x00" * 1600).status_code == 409
    assert mobile.post(url, headers=headers, content=pcm).status_code == 415
    assert mobile.post(url, headers=pcm_headers, content=b"\x00" * 3199).status_code == 422
    spoken = mobile.post("/v1/speech", headers=headers, json={"text": "hello"})
    assert spoken.status_code == 200 and spoken.headers["content-type"] == "audio/wav"

    async def fail(text):
        raise SpeechProviderError("private-provider-detail")

    monkeypatch.setattr(mobile.app.state.speech, "synthesize_wav", fail)
    failure = mobile.post("/v1/speech", headers=headers, json={"text": "hello"})
    assert failure.status_code == 502 and "private-provider-detail" not in failure.text


def test_voice_source_and_saved_memory_stay_with_current_account(mobile):
    _, alice = register(mobile)
    _, bob = register(mobile, "bob")
    request_id = uuid4().hex
    pcm_headers = {**alice, "Content-Type": "audio/pcm"}
    response = mobile.post("/v1/voice?request_id=" + request_id, headers=pcm_headers,
                           content=b"\x01\x00" * 1600)
    assert response.status_code == 200
    alice_state = mobile.get("/v1/state", headers=alice).json()
    source = next(row for row in alice_state["transcripts"] if row["speaker"] == "用户")
    assert source["source"] == "asr" and source["text"] == response.json()["text"]
    saved = mobile.post("/v1/memories/from-transcript/" + source["id"], headers=alice)
    assert saved.status_code == 200
    source_path = "/v1/memories/" + saved.json()["id"] + "/source"
    assert mobile.get(source_path, headers=alice).json()["id"] == source["id"]
    assert mobile.get(source_path, headers=bob).status_code == 404
    bob_state = mobile.get("/v1/state", headers=bob).json()
    assert bob_state["memories"] == [] and bob_state["transcripts"] == []


def test_turn_and_speech_diagnostics_correlate_without_private_content(mobile, caplog):
    caplog.set_level("INFO", logger="uvicorn.error.aidol.mobile")
    account, headers = register(mobile)
    request_id = uuid4().hex
    text = "private diagnostic test utterance"
    assert chat(mobile, headers, text, request_id).status_code == 200
    assert chat(mobile, headers, text, request_id).status_code == 200
    assert chat(mobile, headers, "conflicting private utterance", request_id).status_code == 409
    spoken = mobile.post("/v1/speech", headers={**headers, "X-Aidol-Request-ID": request_id}, json={"text": text})
    assert spoken.status_code == 200
    logs = [row.getMessage() for row in caplog.records if row.name == "uvicorn.error.aidol.mobile"]
    assert all("request_id=" + request_id in row and "elapsed_ms=" in row for row in logs)
    for event in ("stage=cache status=miss", "stage=business status=done", "stage=cache status=hit",
                  "stage=cache status=conflict", "stage=turn status=failed", "stage=tts status=done"):
        assert any(event in row for row in logs)
    for private in (text, "conflicting private utterance", account["token"], account["user"]["id"],
                    account["user"]["username"], account["recovery_code"], "offline-test-only"):
        assert all(private not in row for row in logs)


def test_diagnostics_report_asr_failure_without_exception_details(mobile, monkeypatch, caplog):
    caplog.set_level("INFO", logger="uvicorn.error.aidol.mobile")
    _, headers = register(mobile)

    async def fail(chunks):
        raise SpeechProviderError("private-provider-key-and-payload")
        yield  # Makes the mock an async generator like stream_asr.

    monkeypatch.setattr(mobile.app.state.speech, "stream_asr", fail)
    request_id = uuid4().hex
    response = mobile.post("/v1/voice?request_id=" + request_id,
                           headers={**headers, "Content-Type": "audio/pcm"}, content=b"\0\0" * 1600)
    assert response.status_code == 502
    logs = [row.getMessage() for row in caplog.records if row.name == "uvicorn.error.aidol.mobile"]
    assert any("stage=asr status=failed" in row for row in logs)
    assert any("stage=turn status=failed" in row for row in logs)
    assert all("request_id=" + request_id in row for row in logs)
    assert all("private-provider-key-and-payload" not in row for row in logs)


@pytest.mark.parametrize("diagnostic_id", [None, "invalid private diagnostic value", "x" * 65])
def test_missing_or_invalid_speech_diagnostic_id_does_not_change_contract(mobile, caplog, diagnostic_id):
    import re

    caplog.set_level("INFO", logger="uvicorn.error.aidol.mobile")
    _, headers = register(mobile)
    if diagnostic_id is not None:
        headers["X-Aidol-Request-ID"] = diagnostic_id
    response = mobile.post("/v1/speech", headers=headers, json={"text": "synthetic speech"})
    assert response.status_code == 200 and response.headers["content-type"] == "audio/wav"
    logs = [row.getMessage() for row in caplog.records if row.name == "uvicorn.error.aidol.mobile"]
    assert len(logs) == 1 and re.search(r"request_id=[a-f0-9]{32} stage=tts status=done", logs[0])
    if diagnostic_id is not None:
        assert diagnostic_id not in logs[0]


def test_work_life_modes_and_recording_privacy(mobile):
    _, headers = register(mobile)
    state = mobile.get("/v1/state", headers=headers).json()
    assert state["settings"]["auto_mode"] is False
    assert state["settings"]["preferences"] == ""
    response = mobile.put("/v1/settings", headers=headers, json={"auto_mode": False,
        "mode": "work", "provider": "demo", "life_poll_enabled": True})
    assert response.json()["provider"] == "cascade"
    assert response.json()["life_poll_enabled"] is False
    work = chat(mobile, headers, "我负责提交文档")
    assert work.status_code == 200 and work.json()["speak"] is False
    assert work.json()["mode"] == "work"
    mobile.put("/v1/settings", headers=headers, json={"auto_mode": False, "mode": "life",
                                                     "retain_transcripts": False})
    before = mobile.get("/v1/state", headers=headers).json()["transcripts"]
    life = chat(mobile, headers)
    assert life.status_code == 200 and life.json()["speak"] is True
    assert mobile.get("/v1/state", headers=headers).json()["transcripts"] == before


def test_tenant_isolation_memory_chat_recall_reports_tasks_and_export(mobile):
    alice, ah = register(mobile)
    bob, bh = register(mobile, "bob")
    memory = mobile.post("/v1/memories", headers=ah,
                         json={"content": "Alice 喜欢金桔柠檬", "kind": "preference"}).json()
    same_id = uuid4().hex
    assert chat(mobile, ah, "Alice 私密记录", same_id).status_code == 200
    assert chat(mobile, bh, "Bob 私密记录", same_id).status_code == 200
    mobile.put("/v1/settings", headers=ah, json={"auto_mode": False, "mode": "work", "name": "Alice助手"})
    assert chat(mobile, ah, "我负责提交文档").status_code == 200
    report = mobile.post("/v1/meetings", headers=ah, json={}).json()
    assert report["tasks"]
    assert mobile.post("/v1/meetings", headers=ah, json={}).json()["id"] == report["id"]
    assert mobile.post("/v1/meetings", headers=bh, json={}).status_code == 422
    task = mobile.get("/v1/state", headers=ah).json()["tasks"][0]
    assert mobile.patch("/v1/tasks/" + task["id"], headers=bh, json={"status": "done"}).status_code == 404
    assert mobile.patch("/v1/tasks/" + task["id"], headers=ah, json={"status": "done"}).status_code == 200
    for method, payload in (("PUT", {"content": "changed"}), ("DELETE", None)):
        assert mobile.request(method, "/v1/memories/" + memory["id"], headers=bh, json=payload).status_code == 404
    assert mobile.put("/v1/memories/" + memory["id"], headers=ah,
                      json={"content": "Alice 喜欢金桔柠檬", "pinned": True}).status_code == 200
    ar = mobile.post("/v1/recall", headers=ah, json={"question": "金桔柠檬"}).json()
    br = mobile.post("/v1/recall", headers=bh, json={"question": "金桔柠檬"}).json()
    assert ar["citations"] and not br["citations"]
    ae = mobile.get("/v1/export", headers=ah)
    be = mobile.get("/v1/export", headers=bh)
    assert "Bob 私密记录" not in ae.text and "Alice" not in be.text
    assert ae.json()["reports"] and not be.json()["reports"]
    assert ae.json()["tasks"] and not be.json()["tasks"]
    assert ae.json()["settings"]["name"] != be.json()["settings"]["name"]
    assert ae.json()["user"]["id"] == alice["user"]["id"]
    assert be.json()["user"]["id"] == bob["user"]["id"]


def test_clear_data_preserves_account_and_other_tenants(mobile):
    _, ah = register(mobile)
    _, bh = register(mobile, "bob")
    for headers in (ah, bh):
        mobile.post("/v1/memories", headers=headers, json={"content": "private preference"})
        assert chat(mobile, headers).status_code == 200
    assert mobile.request("DELETE", "/v1/data", headers=ah, json={"password": "incorrect"}).status_code == 401
    assert mobile.request("DELETE", "/v1/data", headers=ah, json={"password": PASSWORD}).status_code == 200
    assert mobile.get("/auth/me", headers=ah).status_code == 200
    ae = mobile.get("/v1/export", headers=ah).json()
    assert all(ae[key] == [] for key in ("memories", "transcripts", "reports", "tasks"))
    be = mobile.get("/v1/export", headers=bh).json()
    assert be["memories"] and be["transcripts"]


def test_account_deletion_removes_database_and_revokes_identity(mobile):
    account, headers = register(mobile)
    _, bh = register(mobile, "bob")
    assert chat(mobile, headers).status_code == 200
    path = mobile.app.state.runtimes.directory / (account["user"]["id"] + ".sqlite3")
    assert path.exists()
    assert mobile.request("DELETE", "/auth/account", headers=headers, json={"password": "wrong"}).status_code == 401
    assert path.exists()
    assert mobile.request("DELETE", "/auth/account", headers=headers, json={"password": PASSWORD}).status_code == 200
    assert not path.exists()
    assert mobile.get("/v1/state", headers=headers).status_code == 401
    assert mobile.post("/auth/login", json={"username": "alice", "password": PASSWORD}).status_code == 401
    assert mobile.get("/auth/me", headers=bh).status_code == 200
    replacement, _ = register(mobile)
    assert replacement["user"]["id"] != account["user"]["id"]


def test_streamed_and_declared_request_bodies_are_bounded(mobile):
    _, headers = register(mobile)
    for body in (b"x" * 65537, iter([b"x" * 32768, b"x" * 32769])):
        assert mobile.post("/v1/chat", headers=headers, content=body).status_code == 413
    assert mobile.post("/v1/voice?request_id=" + uuid4().hex, headers={**headers, "Content-Type": "audio/pcm"},
                       content=b"\0" * 1920002).status_code == 413
    assert chat(mobile, headers, " " * 10).status_code == 422
    assert chat(mobile, headers, "x" * 6001).status_code == 422


def test_account_rate_limit_is_persistent_and_untrusted_xff_does_not_bypass(mobile):
    register(mobile)
    statuses = []
    for index in range(15):
        response = mobile.post("/auth/login", headers={"X-Forwarded-For": "198.51.100." + str(index)},
                               json={"username": "ALICE", "password": "incorrect-password"})
        statuses.append(response.status_code)
    assert statuses == [401] * 14 + [429]


def test_registration_can_be_closed_or_capacity_limited(mobile, monkeypatch):
    monkeypatch.setenv("AIDOL_REGISTRATION_OPEN", "false")
    assert mobile.post("/auth/register", json={"username": "alice", "password": PASSWORD}).status_code == 403
    monkeypatch.setenv("AIDOL_REGISTRATION_OPEN", "true")
    monkeypatch.setenv("AIDOL_MAX_USERS", "1")
    register(mobile)
    assert mobile.post("/auth/register", json={"username": "bob", "password": PASSWORD}).status_code == 403


def test_daily_usage_is_per_account(mobile, monkeypatch):
    monkeypatch.setenv("AIDOL_DAILY_REQUESTS", "1")
    _, ah = register(mobile)
    _, bh = register(mobile, "bob")
    assert chat(mobile, ah).status_code == 200
    assert chat(mobile, ah).status_code == 429
    assert chat(mobile, bh).status_code == 200


def test_completed_retry_does_not_consume_daily_usage_twice(mobile, monkeypatch):
    monkeypatch.setenv("AIDOL_DAILY_REQUESTS", "1")
    _, headers = register(mobile)
    request_id = uuid4().hex
    first = chat(mobile, headers, request_id=request_id)
    assert first.status_code == 200
    retry = chat(mobile, headers, request_id=request_id)
    assert retry.status_code == 200
    assert retry.json() == first.json()
    assert chat(mobile, headers).status_code == 429


def test_whitespace_recall_is_validation_error(mobile):
    _, headers = register(mobile)
    result = mobile.post("/v1/recall", headers=headers, json={"question": "  \n  "})
    assert result.status_code == 422


def test_password_rotation_during_login_cannot_issue_stale_session(mobile, monkeypatch):
    """Simulate reset after login fetched its password hash, before it issues token."""
    from app.services.accounts import password_hash, password_matches
    account, _ = register(mobile)
    accounts = mobile.app.state.accounts
    original_user = accounts.user
    replacement = password_hash("replacement-password")
    rotated = False

    def fetch_then_rotate(username):
        nonlocal rotated
        snapshot = original_user(username)
        if snapshot and not rotated:
            rotated = True
            assert accounts.rotate(snapshot["id"], snapshot["password"], replacement)
        return snapshot

    monkeypatch.setattr(accounts, "user", fetch_then_rotate)
    login = mobile.post("/auth/login", json={"username": "alice", "password": PASSWORD})
    assert password_matches("replacement-password", original_user("alice")["password"])
    assert login.status_code in (401, 409)
    with accounts.connect() as db:
        assert db.execute("SELECT count(*) FROM sessions WHERE user_id=?", (account["user"]["id"],)).fetchone()[0] == 0


def test_active_turn_blocks_destructive_actions_but_not_another_account(mobile, monkeypatch):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    from app.services.mobile import MobileRuntime
    alice, ah = register(mobile)
    _, bh = register(mobile, "bob")
    entered, release = threading.Event(), threading.Event()
    original = MobileRuntime.turn

    async def blocked(rt, text, source):
        import asyncio
        if text == "slow request":
            entered.set()
            await asyncio.to_thread(release.wait, 5)
        return await original(rt, text, source)

    monkeypatch.setattr(MobileRuntime, "turn", blocked)
    with ThreadPoolExecutor(max_workers=1) as executor:
        pending = executor.submit(chat, mobile, ah, "slow request")
        try:
            assert entered.wait(5), "turn did not begin"
            assert mobile.request("DELETE", "/v1/data", headers=ah, json={"password": PASSWORD}).status_code == 409
            assert chat(mobile, ah).status_code == 409
            assert chat(mobile, bh).status_code == 200
        finally:
            release.set()
        assert pending.result(timeout=10).status_code == 200


def test_voice_no_speech_and_provider_failure_are_safe(mobile, monkeypatch):
    _, headers = register(mobile)
    headers = {**headers, "Content-Type": "audio/pcm"}

    async def silence(chunks):
        yield AsrResult("", final=True)

    monkeypatch.setattr(mobile.app.state.speech, "stream_asr", silence)
    empty = mobile.post("/v1/voice?request_id=" + uuid4().hex, headers=headers, content=b"\0" * 3200)
    assert empty.status_code == 200 and empty.json()["speak"] is False
    assert mobile.get("/v1/state", headers=headers).json()["transcripts"] == []

    async def failure(chunks):
        raise SpeechProviderError("provider-private-credential")
        yield  # make the mock an async iterator

    monkeypatch.setattr(mobile.app.state.speech, "stream_asr", failure)
    url = "/v1/voice?request_id=" + uuid4().hex
    result = mobile.post(url, headers=headers, content=b"\0" * 3200)
    assert result.status_code == 502
    assert "provider-private-credential" not in result.text
    assert mobile.get("/v1/state", headers=headers).json()["transcripts"] == []


def test_memory_provenance_is_idempotent_and_private(mobile):
    _, ah = register(mobile)
    _, bh = register(mobile, "bob")
    assert chat(mobile, ah, "我们的会议定于周四举行").status_code == 200
    source = mobile.get("/v1/state", headers=ah).json()["transcripts"][0]
    url = "/v1/memories/from-transcript/" + source["id"]
    assert mobile.post(url, headers=bh).status_code == 404
    memory = mobile.post(url, headers=ah).json()
    assert mobile.post(url, headers=ah).json()["id"] == memory["id"]
    assert memory["content"] == source["text"]
    assert mobile.get("/v1/memories/" + memory["id"] + "/source", headers=ah).json() == source
    assert mobile.get("/v1/memories/" + memory["id"] + "/source", headers=bh).status_code == 404


def test_unfinished_task_recall_tracks_completion_and_tenant(mobile):
    _, ah = register(mobile)
    _, bh = register(mobile, "bob")
    mobile.put("/v1/settings", headers=ah, json={"auto_mode": False, "mode": "work"})
    assert chat(mobile, ah, "我负责提交项目文档").status_code == 200
    assert mobile.post("/v1/meetings", headers=ah, json={}).status_code == 200
    task = mobile.get("/v1/state", headers=ah).json()["tasks"][0]
    payload = {"question": "有哪些未完成待办"}
    recalled = mobile.post("/v1/recall", headers=ah, json=payload).json()
    assert recalled["citations"]
    assert "提交项目文档" in recalled["answer"]
    assert not mobile.post("/v1/recall", headers=bh, json=payload).json()["citations"]
    assert mobile.patch("/v1/tasks/" + task["id"], headers=ah, json={"status": "done"}).status_code == 200
    assert not mobile.post("/v1/recall", headers=ah, json=payload).json()["citations"]


def test_location_route_reset_and_arrival_drafts_are_isolated(mobile, monkeypatch):
    from app.services.maps import MapService
    _, ah = register(mobile)
    _, bh = register(mobile, "bob")
    settings = {"auto_mode": False, "mode": "commute", "destination_lon": 121.5,
                "destination_lat": 31.2, "destination_name": "人民广场", "contact": "测试同事"}

    async def route(self, location, current_settings):
        return {"available": True, "eta_seconds": 120, "distance_meters": 600}

    monkeypatch.setattr(MapService, "route", route)
    assert mobile.put("/v1/settings", headers=ah, json=settings).status_code == 200
    position = {"longitude": 121.49, "latitude": 31.23, "accuracy": 10, "speed": 0}
    located = mobile.post("/v1/location", headers=ah, json=position)
    assert located.status_code == 200 and located.json()["route"]["eta_seconds"] == 120
    state = mobile.get("/v1/state", headers=ah).json()
    other = mobile.get("/v1/state", headers=bh).json()
    assert other["agent"]["location"] is None and other["agent"]["route"] is None and not other["drafts"]
    draft = state["drafts"][0]
    assert draft["status"] == "pending" and "测试同事" in draft["text"]
    url = "/v1/drafts/" + draft["id"]
    assert mobile.post(url + "/copied", headers=bh).status_code == 404
    assert mobile.post(url + "/sent", headers=ah).status_code == 400
    copied = mobile.post(url + "/copied", headers=ah)
    assert copied.status_code == 200 and copied.json()["sent"] is False
    assert mobile.post(url + "/dismissed", headers=ah).status_code == 404
    assert mobile.get("/v1/export", headers=ah).json()["drafts"][0]["status"] == "copied"
    assert not mobile.get("/v1/export", headers=bh).json()["drafts"]
    settings["destination_name"] = "上海博物馆"
    settings["destination_lon"] = 121.47
    assert mobile.put("/v1/settings", headers=ah, json=settings).status_code == 200
    assert mobile.get("/v1/state", headers=ah).json()["agent"]["route"] is None


def test_maps_adapter_contract_and_sanitized_failures(mobile, monkeypatch):
    from app.services.maps import MapService
    _, headers = register(mobile)
    calls = []

    async def search(self, query, city):
        calls.append((query, city))
        return {"available": True, "places": [{"name": "人民广场", "longitude": 121.5, "latitude": 31.2}]}

    async def nearby(self, location, keyword):
        assert location.longitude == 121.5 and keyword == "咖啡"
        return {"available": True, "places": []}

    async def weather(self, city):
        assert city == "310000"
        return {"available": True, "weather": "晴"}

    monkeypatch.setattr(MapService, "search", search)
    monkeypatch.setattr(MapService, "nearby", nearby)
    monkeypatch.setattr(MapService, "weather", weather)
    assert mobile.get("/v1/maps/search", headers=headers, params={"q": "人民广场", "city": "上海"}).json()["available"]
    assert calls == [("人民广场", "上海")]
    assert mobile.post("/v1/maps/nearby", headers=headers,
                       json={"longitude": 121.5, "latitude": 31.2, "keyword": "咖啡"}).status_code == 200
    assert mobile.get("/v1/maps/weather?city=310000", headers=headers).json()["weather"] == "晴"
    assert mobile.get("/v1/maps/weather?city=arbitrary", headers=headers).status_code == 422
    assert mobile.post("/v1/location", headers=headers, json={"longitude": 200, "latitude": 0}).status_code == 422

    async def fail(self, query, city):
        raise RuntimeError("private-map-key-and-provider-url")

    monkeypatch.setattr(MapService, "search", fail)
    failed = mobile.get("/v1/maps/search?q=公园", headers=headers)
    assert failed.status_code == 502 and "private-map-key" not in failed.text


def test_weather_resolves_saved_destination_then_requests_forecast(mobile, monkeypatch):
    """A POI without adcode can still supply weather via its saved GCJ-02 point."""
    import httpx
    _, headers = register(mobile)
    _, other_headers = register(mobile, "bob")
    mobile.app.state.runtimes.config.amap_key = "offline-map-key"
    saved = mobile.put("/v1/settings", headers=headers, json={"auto_mode": False,
        "destination_name": "人民广场", "destination_lon": 121.475, "destination_lat": 31.23})
    assert saved.status_code == 200
    requests = []

    def handler(request):
        requests.append(request)
        assert request.url.params["key"] == "offline-map-key"
        if request.url.path.endswith("geocode/regeo"):
            assert request.url.params["location"] == "121.475,31.23"
            return httpx.Response(200, json={"status": "1", "regeocode": {
                "addressComponent": {"adcode": "310101"}}})
        assert request.url.path.endswith("weather/weatherInfo")
        assert request.url.params["city"] == "310101"
        return httpx.Response(200, json={"status": "1", "lives": [{
            "city": "黄浦区", "adcode": "310101", "weather": "晴", "temperature": "22"}]})

    real_client = httpx.AsyncClient
    monkeypatch.setattr("app.services.maps.httpx.AsyncClient",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler)))
    result = mobile.get("/v1/maps/weather", headers=headers)
    assert result.status_code == 200 and result.json()["available"] is True
    assert result.json()["weather"] == "晴"
    assert len(requests) == 2
    # Another account must not inherit this destination or make an upstream call.
    assert mobile.get("/v1/maps/weather", headers=other_headers).status_code == 422
    assert len(requests) == 2


def test_weather_without_city_or_destination_requires_selection(mobile):
    _, headers = register(mobile)
    for path in ("/v1/maps/weather", "/v1/maps/weather?city="):
        result = mobile.get(path, headers=headers)
        assert result.status_code == 422 and "目的地" in result.json()["detail"]
