"""USB helper tests never connect to a device, provider, or real account store."""
import pytest

from scripts import android_usb


def test_multiple_transports_require_exact_serial():
    devices = android_usb.parse_devices("List of devices attached\nphone-a\tdevice product:x\nphone-b\tunauthorized\n")
    with pytest.raises(RuntimeError, match="Multiple"):
        android_usb.select_device(devices)
    assert android_usb.select_device(devices, "phone-a") == "phone-a"
    with pytest.raises(RuntimeError, match="unauthorized"):
        android_usb.select_device(devices, "phone-b")
    with pytest.raises(RuntimeError, match="not listed"):
        android_usb.select_device(devices, "other-phone")


@pytest.mark.parametrize("devices,message", [
    ([], "No Android"),
    ([("phone", "offline")], "offline"),
    ([("emulator-5554", "device")], "USB"),
    ([("192.168.0.8:37853", "device")], "USB"),
    ([("adb-phone._adb-tls-connect._tcp", "device")], "USB"),
])
def test_no_emulator_wireless_or_unusable_device_is_selected(devices, message):
    with pytest.raises(RuntimeError, match=message):
        android_usb.select_device(devices)


def test_reverse_check_requires_both_exact_ports():
    assert android_usb.has_reverse("UsbFfs tcp:8108 tcp:8108")
    assert not android_usb.has_reverse("UsbFfs tcp:8108 tcp:8000")
    assert not android_usb.has_reverse("UsbFfs tcp:18108 tcp:18108")


def test_setup_uses_selected_serial_for_every_device_command(monkeypatch):
    calls = []
    monkeypatch.setattr(android_usb, "find_adb", lambda value: "adb")
    monkeypatch.setattr(android_usb, "check_backend", lambda: False)

    def adb(_executable, *args):
        calls.append(args)
        if args == ("devices", "-l"):
            return "List of devices attached\na device\nb device"
        assert args[:2] == ("-s", "b")
        if args[2:] == ("get-devpath",):
            return "usb:1-2"
        if args[2:] == ("reverse", "--list"):
            return "UsbFfs tcp:8108 tcp:8108"
        assert args[2:] == ("reverse", "tcp:8108", "tcp:8108")
        return ""

    monkeypatch.setattr(android_usb, "run_adb", adb)
    assert android_usb.main(["--serial", "b"]) == 0
    assert calls == [("devices", "-l"), ("-s", "b", "get-devpath"),
                     ("-s", "b", "reverse", "tcp:8108", "tcp:8108"),
                     ("-s", "b", "reverse", "--list")]


def test_check_does_not_add_forwarding(monkeypatch, capsys):
    monkeypatch.setattr(android_usb, "find_adb", lambda value: "adb")

    def adb(_executable, *args):
        if args == ("devices", "-l"):
            return "phone device"
        if args[2:] == ("get-devpath",):
            return "usb:test"
        assert args == ("-s", "phone", "reverse", "--list")
        return ""

    monkeypatch.setattr(android_usb, "run_adb", adb)
    assert android_usb.main(["--check"]) == 1
    assert "mapping is missing" in capsys.readouterr().err


def test_wireless_transport_cannot_be_mistaken_for_usb(monkeypatch, capsys):
    monkeypatch.setattr(android_usb, "find_adb", lambda value: "adb")

    def adb(_executable, *args):
        if args == ("devices", "-l"):
            return "phone device"
        if args == ("-d", "get-serialno"):
            return "other-usb-phone"
        assert args == ("-s", "phone", "get-devpath")
        return "unknown"

    monkeypatch.setattr(android_usb, "run_adb", adb)
    assert android_usb.main([]) == 1
    assert "did not confirm a USB transport" in capsys.readouterr().err


def test_windows_unknown_path_requires_matching_usb_only_selector(monkeypatch):
    calls = []
    monkeypatch.setattr(android_usb, "find_adb", lambda value: "adb")
    monkeypatch.setattr(android_usb, "check_backend", lambda: True)

    def adb(_executable, *args):
        calls.append(args)
        if args == ("devices", "-l"):
            return "phone device product:example transport_id:1\nemulator-5554 device"
        if args == ("-s", "phone", "get-devpath"):
            return "unknown"
        if args == ("-d", "get-serialno"):
            return "phone"
        assert args == ("-s", "phone", "reverse", "--list")
        return "UsbFfs tcp:8108 tcp:8108"

    monkeypatch.setattr(android_usb, "run_adb", adb)
    assert android_usb.main(["--serial", "phone", "--check"]) == 0
    assert calls == [("devices", "-l"), ("-s", "phone", "get-devpath"),
                     ("-d", "get-serialno"), ("-s", "phone", "reverse", "--list")]


def test_multiple_usb_devices_with_no_path_fail_before_forwarding(monkeypatch, capsys):
    monkeypatch.setattr(android_usb, "find_adb", lambda value: "adb")

    def adb(_executable, *args):
        if args == ("devices", "-l"):
            return "phone device\nsecond-phone device"
        if args == ("-s", "phone", "get-devpath"):
            return "unknown"
        assert args == ("-d", "get-serialno")
        raise RuntimeError("ADB failed: more than one device")

    monkeypatch.setattr(android_usb, "run_adb", adb)
    assert android_usb.main(["--serial", "phone"]) == 1
    assert "Disconnect other USB devices" in capsys.readouterr().err


def test_selected_device_usb_listing_field_is_sufficient_with_multiple_usb(monkeypatch):
    def adb(_executable, *args):
        assert args == ("-s", "phone", "get-devpath")
        return "unknown"

    monkeypatch.setattr(android_usb, "run_adb", adb)
    android_usb.verify_usb_transport("adb", "phone", "phone device usb:1-2\nsecond-phone device usb:1-3")


def test_other_devices_usb_listing_field_does_not_authorize_selected_transport(monkeypatch):
    def adb(_executable, *args):
        if args == ("-s", "phone", "get-devpath"):
            return "unknown"
        assert args == ("-d", "get-serialno")
        return "second-phone"

    monkeypatch.setattr(android_usb, "run_adb", adb)
    with pytest.raises(RuntimeError, match="did not match"):
        android_usb.verify_usb_transport("adb", "phone", "phone device\nsecond-phone device usb:1-3")
