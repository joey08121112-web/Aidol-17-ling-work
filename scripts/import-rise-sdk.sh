#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo 'Usage: bash scripts/import-rise-sdk.sh /path/to/demo_6_7.zip' >&2
  exit 2
fi
repository_path="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$1" "$repository_path/android-app/app/libs/headsetSdk-release-6.7.aar" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

expected = "e074506fd3a46346272fdd2ab632a59b4cef2597f4cb37fd160737eb536e2b2c"
with zipfile.ZipFile(sys.argv[1]) as archive:
    data = archive.read("demo_6_7/app/libs/headsetSdk-release-6.7.aar")
actual = hashlib.sha256(data).hexdigest()
if actual != expected:
    raise SystemExit("SDK checksum mismatch. No project file has been changed.")
destination = pathlib.Path(sys.argv[2])
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_bytes(data)
print(f"Imported SDK 6.7: {destination}\nSHA-256: {actual}")
PY
