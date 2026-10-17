package com.aidol.demo;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DevelopmentSettingsTest {
    @Test public void migratesLegacyLocalPreferenceToUsbWithIndependentCredentialNamespace() {
        Map<String, Object> values = new HashMap<>();
        values.put("emulator_audio", true);
        values.put("local_backend", true);
        DevelopmentSettings settings = new DevelopmentSettings(preferences(values), false, true);
        assertEquals(AudioMode.BLUETOOTH, settings.audioMode());
        assertEquals(BackendTarget.USB_LOCAL, settings.backendTarget());
        assertEquals("BLUETOOTH", values.get("audio_mode"));
        assertEquals("USB_LOCAL", values.get("backend_target"));
        assertEquals(ServerEndpoint.USB_LOCAL, settings.serverUrl());
        assertNotEquals(ServerEndpoint.sessionNamespace(ServerEndpoint.EMULATOR_LOCAL), ServerEndpoint.sessionNamespace(settings.serverUrl()));
        values.put("local_backend", false);
        assertEquals(BackendTarget.USB_LOCAL, settings.backendTarget());
    }

    @Test public void sourceAndBackendCanBeSelectedIndependently() {
        DevelopmentSettings settings = new DevelopmentSettings(preferences(new HashMap<>()), false, true);
        for (AudioMode audio : new AudioMode[]{AudioMode.BLUETOOTH, AudioMode.RISE_SDK}) {
            for (BackendTarget backend : new BackendTarget[]{BackendTarget.USB_LOCAL, BackendTarget.DEPLOYED}) {
                settings.save(audio, backend);
                assertEquals(audio, settings.audioMode());
                assertEquals(backend, settings.backendTarget());
                assertEquals(backend.serverUrl(BuildConfig.SERVER_URL), settings.serverUrl());
            }
        }
    }

    @Test public void freshInstallsAlwaysDefaultToPhysicalAudioAndRemote() {
        DevelopmentSettings phone = new DevelopmentSettings(preferences(new HashMap<>()), false, true);
        DevelopmentSettings emulator = new DevelopmentSettings(preferences(new HashMap<>()), true, true);
        assertEquals(BackendTarget.DEPLOYED, phone.backendTarget());
        assertEquals(AudioMode.BLUETOOTH, phone.audioMode());
        assertEquals(BackendTarget.DEPLOYED, emulator.backendTarget());
        assertEquals(AudioMode.BLUETOOTH, emulator.audioMode());
    }

    @Test public void localComputerOnPhysicalPhoneUsesUsbRegardlessOfAudioSource() {
        DevelopmentSettings phone = new DevelopmentSettings(preferences(new HashMap<>()), false, true);
        assertEquals(BackendTarget.USB_LOCAL, phone.localBackendTarget());
        for (AudioMode audio : new AudioMode[]{AudioMode.BLUETOOTH, AudioMode.RISE_SDK}) {
            phone.save(audio, true);
            assertEquals(audio, phone.audioMode());
            assertTrue(phone.localBackend());
            assertEquals(ServerEndpoint.USB_LOCAL, phone.serverUrl());
            phone.save(audio, false);
            assertEquals(audio, phone.audioMode());
            assertFalse(phone.localBackend());
            assertEquals(BuildConfig.SERVER_URL, phone.serverUrl());
        }
    }

    @Test public void obsoleteEmulatorDeviceFlagCannotSelectRetiredTransport() {
        DevelopmentSettings emulator = new DevelopmentSettings(preferences(new HashMap<>()), true, true);
        assertEquals(BackendTarget.USB_LOCAL, emulator.localBackendTarget());
        emulator.save(AudioMode.EMULATOR, BackendTarget.EMULATOR_LOCAL);
        assertEquals(AudioMode.BLUETOOTH, emulator.audioMode());
        assertTrue(emulator.localBackend());
        assertEquals(ServerEndpoint.USB_LOCAL, emulator.serverUrl());
        assertThrows(IllegalStateException.class, () -> BackendTarget.EMULATOR_LOCAL.serverUrl(BuildConfig.SERVER_URL));
    }

    @Test public void retiredSavedEnumsMigrateAndExistingRiseChoiceSurvives() {
        Map<String, Object> values = new HashMap<>();
        values.put("backend_target", "EMULATOR_LOCAL");
        values.put("audio_mode", "EMULATOR");
        DevelopmentSettings phone = new DevelopmentSettings(preferences(values), false, true);
        assertEquals(ServerEndpoint.USB_LOCAL, phone.serverUrl());
        assertEquals(AudioMode.BLUETOOTH, phone.audioMode());
        assertEquals("USB_LOCAL", values.get("backend_target"));
        assertEquals("BLUETOOTH", values.get("audio_mode"));
        assertTrue(phone.localBackend());
        assertEquals(BackendTarget.USB_LOCAL, phone.localBackendTarget());
        phone.save(AudioMode.RISE_SDK, true);
        assertEquals(ServerEndpoint.USB_LOCAL, phone.serverUrl());
        DevelopmentSettings reopened = new DevelopmentSettings(preferences(values), false, true);
        assertEquals(ServerEndpoint.USB_LOCAL, reopened.serverUrl());
        assertEquals(AudioMode.RISE_SDK, reopened.audioMode());
    }

    @Test public void releaseIgnoresAndDoesNotWriteDebugPreferences() {
        Map<String, Object> values = new HashMap<>();
        values.put("emulator_audio", true);
        values.put("local_backend", true);
        values.put("audio_mode", "EMULATOR");
        values.put("backend_target", "USB_LOCAL");
        Map<String, Object> original = new HashMap<>(values);
        DevelopmentSettings settings = new DevelopmentSettings(preferences(values), true, false);
        settings.save(AudioMode.EMULATOR, BackendTarget.EMULATOR_LOCAL);
        settings.save(AudioMode.RISE_SDK, true);
        assertEquals(AudioMode.BLUETOOTH, settings.audioMode());
        assertEquals(BackendTarget.DEPLOYED, settings.backendTarget());
        assertFalse(settings.localBackend());
        assertEquals(BuildConfig.SERVER_URL, settings.serverUrl());
        assertEquals(original, values);
    }

    @Test public void unknownSavedValuesUseLegacyOrSafeDefaults() {
        Map<String, Object> values = new HashMap<>();
        values.put("backend_target", "REMOVED");
        values.put("audio_mode", "REMOVED");
        values.put("local_backend", false);
        DevelopmentSettings settings = new DevelopmentSettings(preferences(values), true, true);
        assertEquals(BackendTarget.DEPLOYED, settings.backendTarget());
        assertEquals(AudioMode.BLUETOOTH, settings.audioMode());
        assertEquals(BackendTarget.DEPLOYED, BackendTarget.resolve(false, true, "USB_LOCAL", true));
    }

    /** A small in-memory preferences store; no Android device or credentials. */
    private static SharedPreferences preferences(Map<String, Object> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("put")) { values.put((String) args[0], args[1]); return proxy; }
                if (method.getName().equals("apply")) return null;
                throw new UnsupportedOperationException(method.getName());
            });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "edit": return editor;
                    case "contains": return values.containsKey(args[0]);
                    case "getString": case "getBoolean": return values.getOrDefault(args[0], args[1]);
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}
