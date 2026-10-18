package com.aidol.demo;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class SettingsUpdatesTest {
    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }
    private static int status(Exception error) { return error instanceof HttpFailure ? ((HttpFailure) error).status : 0; }

    @Test public void currentServerReceivesOnePatchOnly() throws Exception {
        List<String> methods = new ArrayList<>();
        JSONObject changes = new JSONObject().put("mode", "work"), response = new JSONObject().put("mode", "work");
        JSONObject result = SettingsUpdates.apply(changes, () -> "original-token", (method, path, body, token) -> {
            methods.add(method); assertEquals("/v1/settings", path); assertEquals("original-token", token);
            assertEquals(changes.toString(), body.toString()); assertNotSame(changes, body); return response;
        }, SettingsUpdatesTest::status);
        assertSame(response, result); assertEquals(Collections.singletonList("PATCH"), methods);
    }

    @Test public void oldServerMergesLatestSettingsAndPreservesUnchangedFields() throws Exception {
        for (int unsupported : new int[]{404, 405}) {
            List<String> methods = new ArrayList<>();
            JSONObject changes = new JSONObject().put("mode", "commute").put("auto_mode", false).put("optional", JSONObject.NULL);
            JSONObject latest = new JSONObject().put("mode", "life").put("auto_mode", true).put("work_audio_retention_days", 90)
                .put("destination_name", "saved-place").put("nested", new JSONObject().put("keep", true));
            String original = latest.toString(), originalChanges = changes.toString();
            JSONObject result = SettingsUpdates.apply(changes, () -> "captured", (method, path, body, token) -> {
                methods.add(method); assertEquals("captured", token);
                if (method.equals("PATCH")) throw new HttpFailure(unsupported);
                if (method.equals("GET")) {
                    assertEquals("/v1/state", path); assertNull(body); return new JSONObject().put("settings", latest);
                }
                assertEquals("/v1/settings", path); assertEquals(90, body.getInt("work_audio_retention_days"));
                assertEquals("saved-place", body.getString("destination_name")); assertTrue(body.getJSONObject("nested").getBoolean("keep"));
                assertEquals("commute", body.getString("mode")); assertFalse(body.getBoolean("auto_mode")); assertTrue(body.isNull("optional"));
                body.getJSONObject("nested").put("keep", false); return body;
            }, SettingsUpdatesTest::status);
            assertEquals(Arrays.asList("PATCH", "GET", "PUT"), methods); assertEquals("commute", result.getString("mode"));
            assertEquals(original, latest.toString()); assertEquals(originalChanges, changes.toString());
        }
    }

    @Test public void authConflictServerAndNetworkErrorsNeverFallBack() throws Exception {
        List<Exception> errors = Arrays.asList(new HttpFailure(401), new HttpFailure(403), new HttpFailure(409),
            new HttpFailure(429), new HttpFailure(500), new HttpFailure(503), new IOException("socket closed"), new SocketTimeoutException("uncertain write"));
        for (Exception expected : errors) {
            AtomicInteger requests = new AtomicInteger();
            Exception actual = assertThrows(Exception.class, () -> SettingsUpdates.apply(new JSONObject(), () -> "token", (method, path, body, token) -> {
                requests.incrementAndGet(); throw expected;
            }, SettingsUpdatesTest::status));
            assertSame(expected, actual); assertEquals(1, requests.get());
        }
    }

    @Test public void credentialChangeAfterUnsupportedPatchStopsBeforeRead() throws Exception {
        AtomicReference<String> credentials = new AtomicReference<>("first"); List<String> methods = new ArrayList<>();
        assertThrows(IOException.class, () -> SettingsUpdates.apply(new JSONObject(), credentials::get, (method, path, body, token) -> {
            methods.add(method); assertEquals("first", token); credentials.set("second"); throw new HttpFailure(405);
        }, SettingsUpdatesTest::status));
        assertEquals(Collections.singletonList("PATCH"), methods);
    }

    @Test public void credentialChangeAfterStateReadStopsBeforePut() throws Exception {
        AtomicReference<String> credentials = new AtomicReference<>("first"); List<String> methods = new ArrayList<>();
        assertThrows(IOException.class, () -> SettingsUpdates.apply(new JSONObject(), credentials::get, (method, path, body, token) -> {
            methods.add(method); assertEquals("first", token);
            if (method.equals("PATCH")) throw new HttpFailure(404);
            credentials.set("second"); return new JSONObject().put("settings", new JSONObject());
        }, SettingsUpdatesTest::status));
        assertEquals(Arrays.asList("PATCH", "GET"), methods);
    }

    @Test public void lateSuccessfulResponseDoesNotBelongToNewCredentials() throws Exception {
        AtomicReference<String> credentials = new AtomicReference<>("first"); AtomicInteger requests = new AtomicInteger();
        assertThrows(IOException.class, () -> SettingsUpdates.apply(new JSONObject(), credentials::get, (method, path, body, token) -> {
            requests.incrementAndGet(); credentials.set(""); return new JSONObject();
        }, SettingsUpdatesTest::status));
        assertEquals(1, requests.get());
    }

    @Test public void missingLatestSettingsDoesNotSendDefaultOrPartialPut() throws Exception {
        List<String> methods = new ArrayList<>();
        assertThrows(JSONException.class, () -> SettingsUpdates.apply(new JSONObject().put("mode", "work"), () -> "token", (method, path, body, token) -> {
            methods.add(method); if (method.equals("PATCH")) throw new HttpFailure(405); return new JSONObject();
        }, SettingsUpdatesTest::status));
        assertEquals(Arrays.asList("PATCH", "GET"), methods);
    }

    @Test public void fallbackReadOrWriteFailureIsNotRetried() throws Exception {
        for (String failsAt : new String[]{"GET", "PUT"}) {
            List<String> methods = new ArrayList<>(); HttpFailure expected = new HttpFailure(405);
            Exception actual = assertThrows(Exception.class, () -> SettingsUpdates.apply(new JSONObject(), () -> "token", (method, path, body, token) -> {
                methods.add(method); if (method.equals("PATCH") || method.equals(failsAt)) throw expected;
                return new JSONObject().put("settings", new JSONObject());
            }, SettingsUpdatesTest::status));
            assertSame(expected, actual);
            assertEquals(failsAt.equals("GET") ? Arrays.asList("PATCH", "GET") : Arrays.asList("PATCH", "GET", "PUT"), methods);
        }
    }

    @Test public void missingCredentialsNeverSendAnyRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        assertThrows(IOException.class, () -> SettingsUpdates.apply(new JSONObject(), () -> "", (method, path, body, token) -> {
            requests.incrementAndGet(); return new JSONObject();
        }, SettingsUpdatesTest::status));
        assertEquals(0, requests.get());
    }
}
