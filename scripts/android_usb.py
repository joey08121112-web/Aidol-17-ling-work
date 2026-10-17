"""Set up and verify one Android USB device's local Aidol backend forwarding.

Uses only the Python standard library, on Windows and macOS. It never installs an
APK, starts recording, changes a backend binding, or accesses account credentials.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener


PORT = "tcp:8108"


def find_adb(explicit=None):
    if explicit:
        resolved = shutil.which(explicit)
        if resolved:
            return resolved
        path = Path(explicit).expanduser()
        if path.is_file():
            return str(path.resolve())
        raise RuntimeError("ADB was not found at --adb. Pass the platform-tools/adb executable.")
    executable = "adb.exe" if os.name == "nt" else "adb"
    found = shutil.which(executable)
    if found:
        return found
    project = Path(__file__).resolve().parents[1]
    roots = [Path(value) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if (value := os.getenv(key))]
    roots += [Path.home() / "Library/Android/sdk", Path.home() / "AppData/Local/Android/Sdk",
              project / "artifacts/android-toolchain/sdk"]
    for root in roots:
        candidate = root / "platform-tools" / executable
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("ADB not found. Install Android SDK Platform-Tools and set ANDROID_HOME, "
                       "add platform-tools to PATH, or pass --adb.")


def run_adb(adb, *args):
    try:
        result = subprocess.run([adb, *args], capture_output=True, text=True, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("ADB could not finish. Check the USB cable, debugging authorization and ADB installation.") from exc
    if result.returncode:
        raise RuntimeError("ADB failed: " + (result.stderr.strip() or result.stdout.strip() or "unknown error"))
    return result.stdout.strip()


def parse_devices(output):
    devices = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and not line.startswith(("List of devices", "*")):
            devices.append((fields[0], fields[1]))
    return devices


def select_device(devices, serial=None):
    if serial:
        matches = [device for device in devices if device[0] == serial]
        if not matches:
            raise RuntimeError("The requested --serial is not listed by adb devices -l.")
        selected = matches[0]
    else:
        if not devices:
            raise RuntimeError("No Android device detected. Connect a USB data cable, enable USB debugging, "
                               "unlock the phone and accept this computer's debugging authorization.")
        if len(devices) != 1:
            raise RuntimeError("Multiple Android devices/transports detected. Run adb devices -l and pass --serial; "
                               "no device was selected or changed.")
        selected = devices[0]
    device_serial, state = selected
    if state != "device":
        if state == "unauthorized":
            raise RuntimeError("The selected phone is unauthorized. Unlock it and accept USB debugging, then retry.")
        raise RuntimeError(f"The selected device is {state}; reconnect USB and check adb devices -l.")
    if device_serial.startswith("emulator-") or ":" in device_serial or "._adb-tls-connect." in device_serial:
        raise RuntimeError("Select the phone's USB transport. Emulator and wireless ADB transports are not USB acceptance.")
    return device_serial


def has_reverse(output):
    return any(line.split()[-2:] == [PORT, PORT] for line in output.splitlines())


def verify_usb_transport(adb, serial, device_listing):
    if run_adb(adb, "-s", serial, "get-devpath").startswith("usb:"):
        return
    # Some ADB USB backends expose the path only in `devices -l`.
    for line in device_listing.splitlines():
        fields = line.split()
        if (len(fields) >= 2 and fields[:2] == [serial, "device"]
                and any(field.startswith("usb:") and len(field) > 4 for field in fields[2:])):
            return
    # Windows' native LIBADBUSB can report `unknown` for a real wired phone.
    # ADB's -d selector is USB-only: exact identity is affirmative evidence,
    # whereas `unknown`, the server's USB backend type, or a serial's shape is not.
    try:
        usb_serial = run_adb(adb, "-d", "get-serialno")
    except RuntimeError as exc:
        raise RuntimeError("ADB did not confirm a USB transport for this serial. This backend exposes no USB path, "
                           "and the USB-only selector could not identify one phone. Disconnect other USB devices, "
                           "keep the target phone connected, and retry with --serial.") from exc
    if usb_serial != serial:
        raise RuntimeError("ADB did not confirm a USB transport for this serial: the USB-only selector did not "
                           "match the selected device. Choose its wired entry from adb devices -l.")


def check_backend():
    # A proxy must never redirect this local readiness probe outside the computer.
    try:
        with build_opener(ProxyHandler({})).open("http://127.0.0.1:8108/healthz", timeout=5) as response:
            payload = json.load(response)
        if not isinstance(payload, dict) or payload.get("ok") is not True:
            raise ValueError("not an Aidol health response")
    except (OSError, URLError, ValueError) as exc:
        raise RuntimeError("Local backend health check failed. Run start-android-backend.ps1/.sh "
                           "and keep it running on 127.0.0.1:8108, then retry.") from exc
    return payload.get("ready") is True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Exact USB serial from adb devices -l (required when multiple transports are connected)")
    parser.add_argument("--adb", help="Path to Android SDK platform-tools/adb[.exe]")
    parser.add_argument("--check", action="store_true", help="Inspect existing forwarding only; do not add or replace a reverse mapping")
    args = parser.parse_args(argv)
    try:
        adb = find_adb(args.adb)
        device_listing = run_adb(adb, "devices", "-l")
        serial = select_device(parse_devices(device_listing), args.serial)
        verify_usb_transport(adb, serial, device_listing)
        print("Selected one authorized physical Android device.")
        if not args.check:
            run_adb(adb, "-s", serial, "reverse", PORT, PORT)
        if not has_reverse(run_adb(adb, "-s", serial, "reverse", "--list")):
            raise RuntimeError("USB reverse mapping is missing. Run this helper without --check after every USB reconnect.")
        print("Selected-device forwarding verified: tcp:8108 -> tcp:8108")
        ready = check_backend()
        print("Computer backend /healthz is reachable (provider configuration ready: " + str(ready).lower() + ").")
        print("In the debug APK choose USB local backend: http://127.0.0.1:8108; select audio input independently.")
        print("Manual acceptance still required: disable phone Wi-Fi/mobile data, then test sign-in, text and short voice.")
        print("This check verifies ADB forwarding and computer health, not a phone API request or real headset audio.")
        return 0
    except RuntimeError as exc:
        print("USB setup failed: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
