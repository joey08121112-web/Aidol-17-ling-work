package com.aidol.demo;

import org.json.JSONException;
import org.json.JSONObject;

/** A short retry window for an explicitly rejected settings update, never a voice or speech request. */
final class ModeChangeRetry {
    static final String BUSY_DETAIL = "你的上一项操作仍在进行，请稍后再试";
    private static final long WINDOW_MS = 4000;
    private static final long[] DELAYS_MS = {500, 1000, 1500};
    /** The target shown immediately, and the confirmed mode restored if the server never accepts it. */
    final String mode, previous;
    private final Object backend;
    private final long generation, deadline;
    private int retries;
    private boolean retriesAllowed = true;

    ModeChangeRetry(String mode, String previous, Object backend, long generation, long startedAt) {
        this.mode = mode; this.previous = previous; this.backend = backend; this.generation = generation;
        deadline = startedAt + WINDOW_MS;
    }
    boolean matches(Object currentBackend, long currentGeneration) {
        return backend == currentBackend && generation == currentGeneration;
    }
    void stopRetrying() { retriesAllowed = false; }
    boolean canAttempt(long now, Object currentBackend, long currentGeneration, boolean foreground) {
        return retriesAllowed && foreground && matches(currentBackend, currentGeneration) && now <= deadline;
    }
    long delayAfterFailure(int status, String detail, long now, Object currentBackend,
            long currentGeneration, boolean foreground) {
        if (!canAttempt(now, currentBackend, currentGeneration, foreground) || status != 409
                || !BUSY_DETAIL.equals(detail) || retries >= DELAYS_MS.length) return -1;
        long delay = DELAYS_MS[retries];
        if (now + delay > deadline) return -1;
        retries++;
        return delay;
    }

    /** Writes a mode into the local state snapshot (settings and agent) without a server round trip. */
    static void show(JSONObject state, String mode) {
        try {
            JSONObject settings = state.optJSONObject("settings");
            if (settings == null) state.put("settings", settings = new JSONObject());
            settings.put("mode", mode).put("auto_mode", false);
            JSONObject agent = state.optJSONObject("agent");
            if (agent == null) state.put("agent", agent = new JSONObject());
            agent.put("mode", mode);
        } catch (JSONException ignored) { /* Keys are constant and values are strings. */ }
    }
}
