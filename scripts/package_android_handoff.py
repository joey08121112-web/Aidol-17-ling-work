"""Package the current source tree plus the official Rise references, never local secrets."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

SDK_SHA256 = "e074506fd3a46346272fdd2ab632a59b4cef2597f4cb37fd160737eb536e2b2c"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official-doc", required=True, type=Path)
    parser.add_argument("--demo-zip", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("artifacts/aidol-m0-m1-handoff.zip"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]

    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=root)

    with zipfile.ZipFile(args.demo_zip) as demo:
        aar = demo.read("demo_6_7/app/libs/headsetSdk-release-6.7.aar")
        if hashlib.sha256(aar).hexdigest() != SDK_SHA256:
            raise SystemExit("The official SDK checksum does not match 6.7; no package written.")
    if not args.official_doc.is_file() or args.official_doc.suffix.lower() != ".docx":
        raise SystemExit("Supply the original official Word document.")
    blocked_parts = {".git", ".venv", "data", "artifacts", "node_modules", "build", ".gradle", ".idea", "__pycache__"}
    blocked_suffixes = {".db", ".sqlite", ".sqlite3", ".apk", ".aar", ".jks", ".keystore", ".log", ".wav", ".mp3", ".pcm"}
    paths = sorted(set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode("utf-8").split("\0")) - {""})
    included, skipped = [], []
    for name in paths:
        relative = Path(name)
        path = (root / relative).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            skipped.append(name)
            continue
        if (set(relative.parts) & blocked_parts or relative.suffix.lower() in blocked_suffixes
                or (relative.name.startswith(".env") and relative.name != ".env.example")
                or relative.name == "local.properties"):
            skipped.append(name)
            continue
        included.append((name, path))
    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "baseline_commit": git("rev-parse", "HEAD").decode().strip(),
        "branch": git("branch", "--show-current").decode().strip(),
        "snapshot_includes_uncommitted_and_untracked_source": True,
        "sdk_sha256": SDK_SHA256,
        "excluded": skipped,
        "files": {},
    }
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, path in included:
            target = "aidol-source/" + name
            data = path.read_bytes()
            manifest["files"][target] = hashlib.sha256(data).hexdigest()
            bundle.writestr(target, data)
        for path, target in ((args.official_doc, "reference/耳机SDK Android 文档.docx"),
                             (args.demo_zip, "reference/demo_6_7.zip")):
            data = path.read_bytes()
            manifest["files"][target] = hashlib.sha256(data).hexdigest()
            bundle.writestr(target, data)
        bundle.writestr("git-status.txt", git("status", "--short"))
        # Diff supplements the complete snapshot. Untracked source is already in aidol-source/.
        changed_names = set(git("diff", "--name-only", "HEAD").decode().splitlines())
        changed = [name for name, _ in included if name in changed_names]
        bundle.writestr("working-tree.patch", git("diff", "--binary", "HEAD", "--", *changed) if changed else b"")
        bundle.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        bundle.writestr("READ-ME.txt", "Extract to a NEW directory. aidol-source is the current working tree, including uncommitted source.\n"
                         "Read docs/android-m0-m1-handoff.md and docs/android-local-testing.md.\n"
                         "Official Word and demo_6_7.zip are in reference/. Import the AAR with the supplied script.\n"
                         "Recreate .venv and configure your own .env; no databases, credentials, APK or signing keys are included.\n"
                         "On macOS run: chmod +x android-app/gradlew (ZIP extraction may not preserve permissions).\n")
    with zipfile.ZipFile(output) as bundle:
        for target, expected in manifest["files"].items():
            if hashlib.sha256(bundle.read(target)).hexdigest() != expected:
                raise SystemExit("Package verification failed: " + target)
    print(f"Verified {len(included)} source files + 2 official references: {output}")
    print("SHA-256: " + hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
