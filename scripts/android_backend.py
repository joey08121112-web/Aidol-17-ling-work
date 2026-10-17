"""Local Android API launcher with accounts isolated from deployment data."""
import argparse
import os
from pathlib import Path


LOCAL_ENVIRONMENT = {
    "AIDOL_REGISTRATION_OPEN": "true",
}


def configure_local_backend(project_root: Path) -> Path:
    from dotenv import load_dotenv

    # Reuse provider credentials only on the backend. Deployment settings must
    # not redirect this local Android instance to a shared account store.
    load_dotenv(project_root / ".env")
    data_directory = project_root.resolve() / "data" / "android-local"
    os.environ.update(LOCAL_ENVIRONMENT)
    os.environ["AIDOL_DATA_DIR"] = str(data_directory)
    return data_directory


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Print configuration without loading .env or starting a server")
    parser.add_argument("--reload", action="store_true", help="Reload backend source changes (interrupts active requests; off by default)")
    args = parser.parse_args(argv)
    project_root = Path(__file__).resolve().parents[1]
    print("Local Android API: http://127.0.0.1:8108", flush=True)
    print("Android USB server: http://127.0.0.1:8108 (requires adb reverse tcp:8108 tcp:8108)", flush=True)
    print(f"Isolated local accounts and data: {project_root / 'data' / 'android-local'}", flush=True)
    if args.check:
        print("Configuration check only; no .env, databases, provider APIs or server were opened.")
        return 0

    os.chdir(project_root)
    configure_local_backend(project_root)
    import uvicorn

    print("Real AI/ASR/TTS requires backend provider credentials in .env. Press Ctrl+C to stop.", flush=True)
    options = dict(factory=True, host="127.0.0.1", port=8108, workers=1,
                   proxy_headers=False, ws_max_size=65536, ws_max_queue=8, reload=args.reload)
    if args.reload:
        print("Development reload enabled: editing backend source interrupts active requests.", flush=True)
        options["reload_dirs"] = [str(project_root / "app")]
    uvicorn.run("app.main:create_hosted_app", **options)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
