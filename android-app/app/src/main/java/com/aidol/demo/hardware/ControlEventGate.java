package com.aidol.demo.hardware;

/** Pure Java boundary for one acknowledged listener and device; never infers firmware support. */
final class ControlEventGate {
    private long generation, sequence;
    private String address;
    private boolean registered;

    synchronized long begin(String targetAddress) {
        generation++; address = targetAddress; registered = false; return generation;
    }
    synchronized boolean confirm(long token) {
        if (token != generation || address == null) return false;
        registered = true; return true;
    }
    synchronized void invalidate() { generation++; registered = false; address = null; }
    synchronized HeadsetController.ControlEvent accept(long token, String deviceAddress, int code, long receivedAtMillis) {
        if (!registered || token != generation || address == null || deviceAddress == null
                || !address.equalsIgnoreCase(deviceAddress)) return null;
        HeadsetController.ControlKind kind = kind(code);
        return kind == null ? null : new HeadsetController.ControlEvent(kind, code, receivedAtMillis, ++sequence);
    }
    static HeadsetController.ControlKind kind(int code) {
        // Verified against SDK 6.7 AuraTestEvent.getCode(), protocol 0x560F (not 0x5D07).
        switch (code) {
            case 0x95: return HeadsetController.ControlKind.TAP;
            case 0x90: return HeadsetController.ControlKind.DOUBLE_TAP;
            case 0x91: return HeadsetController.ControlKind.TRIPLE_TAP;
            case 0x96: return HeadsetController.ControlKind.SHORT_PRESS;
            case 0x92: return HeadsetController.ControlKind.LONG_PRESS;
            case 0x97: return HeadsetController.ControlKind.RELEASE;
            case 0x93: return HeadsetController.ControlKind.SLIDE_FORWARD;
            case 0x94: return HeadsetController.ControlKind.SLIDE_BACKWARD;
            case 0x80: return HeadsetController.ControlKind.PRESS_SINGLE;
            case 0x81: return HeadsetController.ControlKind.PRESS_DOUBLE;
            case 0x82: return HeadsetController.ControlKind.PRESS_TRIPLE;
            case 0x83: return HeadsetController.ControlKind.PRESS_LONG;
            case 0x84: return HeadsetController.ControlKind.PRESS_CLICK_HOLD;
            case 0x85: return HeadsetController.ControlKind.PRESS_QUADRUPLE;
            case 0x86: return HeadsetController.ControlKind.PRESS_QUINTUPLE;
            default: return null; // IMU, head gestures and unknown values have no product action.
        }
    }
}
