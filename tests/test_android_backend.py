"""Local USB-phone startup must preserve hosted isolation and fail real speech honestly."""
from fastapi.testclient import TestClient
import pytest

from scripts.android_backend import configure_local_backend


@pytest.mark.parametrize("trusted_proxy", [None, "127.0.0.1"])
def test_default_launcher_cannot_reenable_single_user_service(monkeypatch, trusted_proxy):
    import run

    calls = []
    monkeypatch.setenv("AIDOL_HOSTED", "false")
    monkeypatch.delenv("HOST", raising=False)
    monkeypatch.delenv("PORT", raising=False)
    monkeypatch.delenv("FORWARDED_ALLOW_IPS", raising=False)
    if trusted_proxy:
        monkeypatch.setenv("FORWARDED_ALLOW_IPS", trusted_proxy)
    monkeypatch.setattr(run, "load_dotenv", lambda: None)
    monkeypatch.setattr(run.uvicorn, "run", lambda app, **options: calls.append((app, options)))
    run.main()
    assert calls == [("app.main:create_hosted_app", {
        "factory": True, "host": "127.0.0.1", "port": 8108,
        "workers": 1, "ws_max_size": 65536, "ws_max_queue": 8, "proxy_headers": bool(trusted_proxy),
        "forwarded_allow_ips": trusted_proxy or "",
    })]


def test_check_does_not_open_environment_or_start_server(monkeypatch, capsys):
    from scripts import android_backend

    def unexpected(*args, **kwargs):
        raise AssertionError("Check mode must not access backend configuration")

    monkeypatch.setattr(android_backend, "configure_local_backend", unexpected)
    assert android_backend.main(["--check", "--reload"]) == 0
    output = capsys.readouterr().out
    assert "127.0.0.1:8108" in output
    assert "adb reverse tcp:8108 tcp:8108" in output
    assert "10.0.2.2" not in output and "emulator" not in output.lower()


def test_reload_is_explicit_and_keeps_hosted_loopback_binding(monkeypatch):
    from scripts import android_backend
    import uvicorn

    calls = []
    monkeypatch.setattr(android_backend, "configure_local_backend", lambda root: root / "data/android-local")
    monkeypatch.setattr(uvicorn, "run", lambda app, **options: calls.append((app, options)))
    assert android_backend.main([]) == 0
    assert android_backend.main(["--reload"]) == 0
    for app, options in calls:
        assert app == "app.main:create_hosted_app"
        assert options["factory"] is True
        assert options["host"] == "127.0.0.1"
        assert options["port"] == 8108
        assert options["proxy_headers"] is False
    assert calls[0][1]["reload"] is False
    assert "reload_dirs" not in calls[0][1]
    assert calls[1][1]["reload"] is True
    assert calls[1][1]["reload_dirs"] == [str(android_backend.Path(android_backend.__file__).resolve().parents[1] / "app")]


def test_local_android_ignores_legacy_and_deployment_paths(config, tmp_path, monkeypatch):
    project = tmp_path / "checkout with spaces"
    project.mkdir()
    legacy = tmp_path / "legacy.sqlite3"
    deployed = tmp_path / "deployed"
    project.joinpath(".env").write_text(
        "AIDOL_HOSTED=false\nAIDOL_REGISTRATION_OPEN=false\n"
        f"AIDOL_DATA_DIR={deployed.as_posix()}\nDATABASE_PATH={legacy.as_posix()}\n",
        encoding="utf-8",
    )
    for name, value in {
        "AIDOL_HOSTED": "false", "AIDOL_REGISTRATION_OPEN": "false",
        "AIDOL_DATA_DIR": str(deployed), "DATABASE_PATH": str(legacy),
        "AIDOL_MAX_USERS": "500", "AIDOL_DAILY_REQUESTS": "300",
    }.items():
        monkeypatch.setenv(name, value)
    directory = configure_local_backend(project)
    config.database_path = str(legacy)
    from app.main import create_hosted_app

    with TestClient(create_hosted_app(config)) as client:
        assert client.get("/healthz").json() == {
            "ok": True, "version": "0.2.0", "work_meetings": True, "ready": False}
        headers = []
        for username in ("alice", "bob"):
            response = client.post("/auth/register", json={"username": username, "password": "local-test-123"})
            assert response.status_code == 201
            headers.append({"Authorization": "Bearer " + response.json()["token"]})
        assert client.post("/v1/memories", headers=headers[0], json={"content": "仅 Alice 的本地记忆"}).status_code == 200
        assert len(client.get("/v1/state", headers=headers[0]).json()["memories"]) == 1
        assert client.get("/v1/state", headers=headers[1]).json()["memories"] == []
        voice = client.post("/v1/voice?request_id=" + "a" * 16,
                            headers={**headers[0], "Content-Type": "audio/pcm"}, content=b"\0\0" * 8000)
        assert voice.status_code == 503
        assert voice.json()["detail"] == "服务器尚未配置语音识别"
        speech = client.post("/v1/speech", headers=headers[0], json={"text": "你好"})
        assert speech.status_code == 503
        assert speech.json()["detail"] == "服务器尚未配置语音合成"
        for path in ("/", "/api/state", "/docs"):
            assert client.get(path).status_code == 404
    assert directory == project / "data" / "android-local"
    assert (directory / "accounts.sqlite3").is_file()
    assert len(list((directory / "users").glob("*.sqlite3"))) == 2
    assert not legacy.exists()
    assert not deployed.exists()
    assert not (directory / "unused-single-user.sqlite3").exists()
