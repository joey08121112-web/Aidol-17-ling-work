from dotenv import dotenv_values
import pytest

from scripts.package_team_env import team_environment


def test_team_env_copies_provider_values_and_excludes_machine_secrets(tmp_path):
    source = tmp_path / ".env"
    source.write_text(
        "TEXT_API_KEY='synthetic key # with \\' quote and \\\\ slash'\n"
        "SPEECH_VOLC_API_KEY=synthetic-asr\n"
        "SSH_PASSWORD=must-not-copy\nAIDOL_KEYSTORE_PASSWORD=must-not-copy\n"
        "DATABASE_PATH=C:/private/accounts.db\nAIDOL_DATA_DIR=/production/data\n"
        "UPSTREAM_PROXY=http://localhost:12345\nHOST=0.0.0.0\n",
        encoding="utf-8",
    )
    rendered = team_environment(source)
    target = tmp_path / "received.env"
    target.write_text(rendered, encoding="utf-8")
    result = dotenv_values(target, interpolate=False)
    assert result["TEXT_API_KEY"] == dotenv_values(source, interpolate=False)["TEXT_API_KEY"]
    assert result["SPEECH_VOLC_API_KEY"] == "synthetic-asr"
    assert "must-not-copy" not in rendered
    assert "DATABASE_PATH" not in result
    assert result["AIDOL_DATA_DIR"] == "data/android-local"
    assert result["HOST"] == "127.0.0.1"
    assert result["UPSTREAM_PROXY"] == result["PROVIDER_PROXY"] == ""


def test_unresolved_provider_references_fail_without_exposing_values(tmp_path):
    source = tmp_path / ".env"
    source.write_text("TEXT_API_KEY=${PRIVATE_KEY}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="Resolve variable references") as error:
        team_environment(source)
    assert "PRIVATE_KEY" not in str(error.value)
