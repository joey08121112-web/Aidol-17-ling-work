package com.aidol.demo;

import android.media.AudioDeviceInfo;
import org.junit.Test;
import static org.junit.Assert.*;

/** Device-type policy tests; Android hardware routing still requires physical acceptance. */
public class AudioModeTest {
    @Test public void riseUsesSdkInputAndRequiresTargetIdentityForBluetoothOutput() {
        assertTrue(AudioMode.RISE_SDK.requiresBluetooth());
        assertTrue(AudioMode.RISE_SDK.requiresRecordAudioPermission());
        assertFalse(AudioMode.RISE_SDK.usesSystemCapture());
        assertTrue(AudioMode.RISE_SDK.requiresVerifiedHeadsetOutput());
        assertTrue(AudioMode.RISE_SDK.requiresTargetIdentity());
        assertFalse(AudioMode.RISE_SDK.acceptsInputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertFalse(AudioMode.RISE_SDK.acceptsInputType(AudioDeviceInfo.TYPE_BUILTIN_MIC));
        assertFalse(AudioMode.RISE_SDK.acceptsOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertTrue(AudioMode.RISE_SDK.acceptsOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse(AudioMode.BLUETOOTH.requiresTargetIdentity());
        assertFalse(AudioMode.EMULATOR.usesSystemCapture());
    }
    @Test public void legacyEmulatorValueCannotEnableDefaultMicrophoneOrSpeaker() {
        assertFalse(AudioMode.EMULATOR.isPhysicalSource());
        assertTrue(AudioMode.EMULATOR.requiresBluetooth());
        assertTrue(AudioMode.EMULATOR.requiresVerifiedHeadsetOutput());
        assertFalse(AudioMode.EMULATOR.acceptsInputType(AudioDeviceInfo.TYPE_BUILTIN_MIC));
        assertFalse(AudioMode.EMULATOR.acceptsInputType(AudioDeviceInfo.TYPE_UNKNOWN));
        assertFalse(AudioMode.EMULATOR.acceptsInputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertFalse(AudioMode.EMULATOR.acceptsOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertFalse(AudioMode.EMULATOR.acceptsOutputType(AudioDeviceInfo.TYPE_UNKNOWN));
        assertFalse(AudioMode.EMULATOR.acceptsOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertThrows(IllegalArgumentException.class, () -> AudioMode.requirePhysical(AudioMode.EMULATOR));
        assertThrows(IllegalArgumentException.class, () -> AudioMode.requirePhysical(null));
    }

    @Test public void constructorEntrypointsRejectEmulatorBeforeCreatingAndroidResources() {
        assertThrows(IllegalArgumentException.class, () -> new HostedSession(null, null, AudioMode.EMULATOR, null));
        assertThrows(IllegalArgumentException.class, () -> new HostedSession(null, null, AudioMode.EMULATOR, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ReplyPlayer(null, AudioMode.EMULATOR));
        assertThrows(IllegalArgumentException.class, () -> new ReplyPlayer(null, AudioMode.EMULATOR, device -> true));
        assertThrows(IllegalArgumentException.class, () -> new HostedSession(null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ReplyPlayer(null, null));
    }

    @Test public void physicalModesKeepExplicitHeadsetPolicy() {
        assertSame(AudioMode.BLUETOOTH, AudioMode.requirePhysical(AudioMode.BLUETOOTH));
        assertSame(AudioMode.RISE_SDK, AudioMode.requirePhysical(AudioMode.RISE_SDK));
        for (AudioMode mode : AudioMode.values()) {
            assertTrue(mode.requiresVerifiedHeadsetOutput());
            assertFalse(mode.acceptsOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        }
    }

    @Test public void physicalModeRejectsHandsetUsbAndWiredFallbacks() {
        assertTrue(AudioMode.BLUETOOTH.requiresBluetooth());
        int[] nonBluetooth = {AudioDeviceInfo.TYPE_UNKNOWN, AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET};
        for (int type : nonBluetooth) {
            assertFalse("input type " + type, AudioMode.BLUETOOTH.acceptsInputType(type));
            assertFalse("output type " + type, AudioMode.BLUETOOTH.acceptsOutputType(type));
        }
    }

    @Test public void mediaOnlyBluetoothDoesNotQualifyAsAnInput() {
        assertFalse(AudioMode.BLUETOOTH.acceptsInputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse(AudioMode.BLUETOOTH.acceptsInputType(AudioDeviceInfo.TYPE_BLE_SPEAKER));
        assertTrue(AudioMode.BLUETOOTH.acceptsOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertTrue(AudioMode.BLUETOOTH.acceptsOutputType(AudioDeviceInfo.TYPE_BLE_SPEAKER));
        assertTrue(AudioMode.BLUETOOTH.acceptsInputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertTrue(AudioMode.BLUETOOTH.acceptsInputType(AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertTrue(AudioMode.BLUETOOTH.acceptsOutputType(AudioDeviceInfo.TYPE_BLE_HEADSET));
    }
}
