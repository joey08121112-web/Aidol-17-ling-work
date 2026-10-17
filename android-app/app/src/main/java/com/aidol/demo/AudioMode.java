package com.aidol.demo;

import android.media.AudioDeviceInfo;

/** Input, permission and output policies are intentionally separate. */
public enum AudioMode {
    /** Historical preference value only; never an available capture or playback mode. */
    @Deprecated EMULATOR("已停用的模拟器来源"),
    BLUETOOTH("系统蓝牙兼容"),
    RISE_SDK("Rise SDK 麦克风");

    private final String label;
    AudioMode(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isPhysicalSource() { return this == BLUETOOTH || this == RISE_SDK; }
    public static AudioMode requirePhysical(AudioMode mode) {
        if (mode == null || !mode.isPhysicalSource())
            throw new IllegalArgumentException("仅支持安卓真机耳机来源，请选择 Rise SDK 或系统蓝牙");
        return mode;
    }
    /** Permission/UI compatibility helper; never use this to choose a capture implementation. */
    public boolean requiresBluetooth() { return true; }
    public boolean requiresRecordAudioPermission() { return true; }
    public boolean usesSystemCapture() { return this == BLUETOOTH; }
    public boolean requiresVerifiedHeadsetOutput() { return true; }
    public boolean requiresTargetIdentity() { return this == RISE_SDK; }

    public boolean acceptsInputType(int type) {
        if (this != BLUETOOTH) return false; // SDK PCM is not an AudioRecord route; legacy mode is disabled.
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    public boolean acceptsOutputType(int type) {
        return isPhysicalSource() && (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }
}
