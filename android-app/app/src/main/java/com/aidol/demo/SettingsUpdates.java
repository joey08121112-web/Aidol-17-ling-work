package com.aidol.demo;

import java.io.IOException;
import java.util.Iterator;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.json.JSONObject;

/** Compatibility for servers predating partial settings updates. No retries of uncertain mutations. */
final class SettingsUpdates {
    interface Request {
        JSONObject run(String method, String path, JSONObject body, String token) throws Exception;
    }

    static JSONObject apply(JSONObject changes, Supplier<String> credentials, Request request,
                            ToIntFunction<Exception> status) throws Exception {
        if (changes == null) throw new IllegalArgumentException("Settings changes are required");
        JSONObject patch = new JSONObject(changes.toString());
        String token = credentials.get();
        ensureCurrent(token, credentials);
        JSONObject result;
        try {
            result = request.run("PATCH", "/v1/settings", patch, token);
        } catch (Exception error) {
            int code = status.applyAsInt(error);
            if (code != 404 && code != 405) throw error;
            ensureCurrent(token, credentials);
            JSONObject state = request.run("GET", "/v1/state", null, token);
            ensureCurrent(token, credentials);
            // A malformed state must fail rather than silently resetting unspecified settings.
            JSONObject merged = new JSONObject(state.getJSONObject("settings").toString());
            for (Iterator<String> keys = patch.keys(); keys.hasNext();) {
                String key = keys.next(); merged.put(key, patch.get(key));
            }
            ensureCurrent(token, credentials);
            result = request.run("PUT", "/v1/settings", merged, token);
        }
        ensureCurrent(token, credentials);
        return result;
    }

    private static void ensureCurrent(String token, Supplier<String> credentials) throws IOException {
        if (token == null || token.isEmpty() || !token.equals(credentials.get()))
            throw new IOException("登录状态已变化，请重新尝试");
    }

    private SettingsUpdates() {}
}
