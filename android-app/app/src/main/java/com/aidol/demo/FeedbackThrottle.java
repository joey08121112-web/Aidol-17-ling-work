package com.aidol.demo;

/** Main-thread policy: repeated taps must not become repeated sounds. */
final class FeedbackThrottle {
    private String last = "";
    private long lastAt = Long.MIN_VALUE;

    boolean accept(String key, long now) {
        if (lastAt != Long.MIN_VALUE && (now - lastAt < 1000
            || (last.equals(key) && now - lastAt < 4000))) return false;
        last = key;
        lastAt = now;
        return true;
    }
}
