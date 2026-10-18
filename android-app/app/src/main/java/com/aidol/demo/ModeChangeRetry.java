package com.aidol.demo;

/** A short retry window for an explicitly rejected settings update, never a voice or speech request. */
final class ModeChangeRetry {
    static final String BUSY_DETAIL = "你的上一项操作仍在进行，请稍后再试";
    /** The backend's answer when its bounded wait for the account lock (e.g. a call closing) times out. */
    static final String SETTLING_DETAIL = "上一项操作仍在收尾，请稍后再开始";
    // A settings request may itself wait ~2 s for the account lock, so the window must fit several waits.
    private static final long WINDOW_MS = 8000;
    private static final long[] DELAYS_MS = {300, 600, 1000};
    final String mode;
    private final Object backend;
    private final long generation, deadline;
    private int retries;
    private boolean retriesAllowed = true;

    ModeChangeRetry(String mode, Object backend, long generation, long startedAt) {
        this.mode = mode; this.backend = backend; this.generation = generation;
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
                || !(BUSY_DETAIL.equals(detail) || SETTLING_DETAIL.equals(detail)) || retries >= DELAYS_MS.length) return -1;
        long delay = DELAYS_MS[retries];
        if (now + delay > deadline) return -1;
        retries++;
        return delay;
    }
}
