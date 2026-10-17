package com.aidol.demo;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DevelopmentSettingsTest {
    @Test public void everyBuildDefaultsToRiseAndUsbLocal() {
        for (boolean debug : new boolean[]{true, false}) {
            Map<String,Object> values = new HashMap<>();
            DevelopmentSettings settings = new DevelopmentSettings(preferences(values), false, debug);
            assertEquals(AudioMode.RISE_SDK, settings.audioMode());
            assertEquals(BackendTarget.USB_LOCAL, settings.backendTarget());
            assertEquals(ServerEndpoint.USB_LOCAL, settings.serverUrl());
            assertEquals("RISE_SDK", values.get("audio_mode"));
        }
    }
    @Test public void oldAudioChoicesAlwaysMigrateToRiseWithoutMovingCredentials() {
        for (String source : new String[]{"BLUETOOTH", "EMULATOR", "REMOVED", "RISE_SDK"}) {
            Map<String,Object> values = new HashMap<>();
            values.put("audio_mode", source); values.put("backend_target", "EMULATOR_LOCAL");
            DevelopmentSettings settings = new DevelopmentSettings(preferences(values), true, true);
            assertEquals(AudioMode.RISE_SDK, settings.audioMode());
            assertEquals("RISE_SDK", values.get("audio_mode"));
            assertEquals(ServerEndpoint.USB_LOCAL, settings.serverUrl());
            assertNotEquals(ServerEndpoint.sessionNamespace(ServerEndpoint.EMULATOR_LOCAL), ServerEndpoint.sessionNamespace(settings.serverUrl()));
        }
    }
    @Test public void explicitBackendSelectionSurvivesRestartInBothBuilds() {
        for (boolean debug : new boolean[]{true, false}) {
            Map<String,Object> values = new HashMap<>();
            DevelopmentSettings settings = new DevelopmentSettings(preferences(values), false, debug);
            for (BackendTarget backend : new BackendTarget[]{BackendTarget.DEPLOYED, BackendTarget.USB_LOCAL}) {
                settings.save(AudioMode.BLUETOOTH, backend);
                settings = new DevelopmentSettings(preferences(values), false, debug);
                assertEquals(AudioMode.RISE_SDK, settings.audioMode());
                assertEquals(backend, settings.backendTarget());
                assertEquals(backend.serverUrl(BuildConfig.SERVER_URL), settings.serverUrl());
            }
        }
    }
    @Test public void legacyExplicitRemoteChoiceIsPreserved() {
        Map<String,Object> values = new HashMap<>(); values.put("local_backend", false);
        DevelopmentSettings settings = new DevelopmentSettings(preferences(values), false, true);
        assertEquals(BackendTarget.DEPLOYED, settings.backendTarget());
        settings.save(true, true);
        assertEquals(AudioMode.RISE_SDK, settings.audioMode());
        assertEquals(BackendTarget.USB_LOCAL, settings.backendTarget());
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
