package com.aidol.demo;

/** One foreground refresh at a time, independent of business/recording state. */
final class MaintenanceCadence {
    static final long INTERVAL_MS = 180_000;
    private long dueAt = Long.MAX_VALUE;
    private boolean active, running;

    boolean enter(long now) {
        active = true;
        if (running) return false;
        dueAt = now;
        return true;
    }
    boolean claim(long now) {
        if (!active || running || now < dueAt) return false;
        running = true;
        dueAt = now + INTERVAL_MS;
        return true;
    }
    void complete() { running = false; }
    void leave() { active = false; running = false; dueAt = Long.MAX_VALUE; }
}
