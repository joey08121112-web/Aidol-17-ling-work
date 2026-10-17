package com.aidol.demo;

import android.media.AudioDeviceInfo;

/** Input, permission and output policies are intentionally separate. */
public enum AudioMode {
    /** Historical preference value only; never an available capture or playback mode. */
    @Deprecated EMULATOR("已停用的模拟器来源"),
    @Deprecated BLUETOOTH("已停用的系统蓝牙来源"),
    RISE_SDK("Rise SDK 麦克风");

    private final String label;
    AudioMode(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isPhysicalSource() { return this == RISE_SDK; }
    public static AudioMode requirePhysical(AudioMode mode) {
        if (mode == null || !mode.isPhysicalSource())
            throw new IllegalArgumentException("仅支持 Rise SDK 麦克风，请先连接 Rise 耳机");
        return mode;
    }
    /** Permission/UI compatibility helper; never use this to choose a capture implementation. */
    public boolean requiresBluetooth() { return true; }
    public boolean requiresRecordAudioPermission() { return true; }
    public boolean usesSystemCapture() { return false; }
    public boolean requiresVerifiedHeadsetOutput() { return true; }
    public boolean requiresTargetIdentity() { return this == RISE_SDK; }

    public boolean acceptsInputType(int type) {
        return false; // Only vendor PCM; no AudioRecord route is a valid input.
    }

    public boolean acceptsOutputType(int type) {
        return isPhysicalSource() && (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }
}
