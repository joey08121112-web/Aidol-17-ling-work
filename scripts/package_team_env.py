"""Prepare a private backend-only .env handoff; never print credential values."""
import argparse
from pathlib import Path
import zipfile

from dotenv import dotenv_values


PROVIDER_KEYS = (
    "TEXT_API_KEY", "TEXT_BASE_URL", "TEXT_MODEL", "AMAP_API_KEY", "BOCHA_API_KEY",
    "SPEECH_VOLC_APP_KEY", "SPEECH_VOLC_ACCESS_KEY", "SPEECH_VOLC_API_KEY",
    "SPEECH_VOLC_RESOURCE_ID", "SPEECH_VOLC_WS_URL", "SPEECH_VOLC_MODEL_NAME",
    "TTS_VOLC_APP_KEY", "TTS_VOLC_ACCESS_KEY", "TTS_VOLC_RESOURCE_ID",
    "TTS_VOLC_VOICE_TYPE", "TTS_VOLC_WS_URL",
    "QWEN_OMNI_API_KEY", "QWEN_OMNI_WS_URL", "QWEN_OMNI_MODEL", "QWEN_OMNI_VOICE",
    "QWEN_OMNI_TRANSCRIPTION_MODEL", "QWEN_OMNI_OUTPUT_FORMAT",
)


def team_environment(source: Path) -> str:
    values = dotenv_values(source, interpolate=False)
    lines = [
        "# PRIVATE: contains backend provider credentials. Never commit or upload publicly.",
        "# Use start-android-backend.ps1/.sh; each computer creates its own account database.",
        "HOST=127.0.0.1", "PORT=8108", "AIDOL_DATA_DIR=data/android-local",
        "AIDOL_REGISTRATION_OPEN=true", "SPEECH_ASR_MODE=stream",
        "# Configure a proxy for the receiving computer only if needed.",
        "UPSTREAM_PROXY=", "PROVIDER_PROXY=",
    ]
    for key in PROVIDER_KEYS:
        value = values.get(key)
        if value is None:
            continue
        if "${" in value:
            raise ValueError("Resolve variable references in the source provider configuration first.")
        # python-dotenv single-quoted values support escaped quotes and backslashes.
        quoted = value.replace("\\", "\\\\").replace("'", "\\'")
        lines.append(f"{key}='{quoted}'")
    return "\n".join(lines) + "\n"


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=root / ".env")
    parser.add_argument("--output", type=Path, default=root / "artifacts/aidol-team-env.private.zip")
    args = parser.parse_args()
    if not args.source.is_file():
        raise SystemExit("Source .env is missing. No package written.")
    output = args.output.resolve()
    if not output.is_relative_to((root / "artifacts").resolve()) or output.suffix.lower() != ".zip":
        raise SystemExit("Private output must be a ZIP inside this repository's ignored artifacts directory.")
    content = team_environment(args.source)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr(".env", content)
        bundle.writestr("READ-ME.txt", (
            "PRIVATE backend configuration. Share directly with the intended teammate only.\n"
            "This ZIP is not password-encrypted. Do not attach it to Git, a PR, public chat or website.\n"
            "Extract .env into the cloned Aidol repository root; preserve an existing .env first.\n"
            "Windows: powershell -File start-android-backend.ps1\n"
            "macOS: sh ./start-android-backend.sh\n"
            "Each computer creates its own accounts/data. No phone account passwords, databases, SSH credentials, signing keys or old demo keys are included.\n"
            "Provider usage uses the shared owner's quota; separate teammate credentials can replace these values.\n"
            "See docs/team-delivery.md for SDK import, USB connection and build instructions.\n"
        ))
    print(f"Private backend configuration prepared: {output}")
    print("Only provider settings and safe local defaults are included; credential values were not printed.")


if __name__ == "__main__":
    main()
