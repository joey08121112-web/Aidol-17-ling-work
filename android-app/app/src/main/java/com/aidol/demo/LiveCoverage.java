package com.aidol.demo;

/**
 * Decides whether a recorded segment's text already came from live captions (upload audio only)
 * or must be recognised by the server. Times are SystemClock.elapsedRealtime milliseconds.
 */
final class LiveCoverage {
    /** Wait this long after a segment closes, so a sentence spanning its end can be finalised live. */
    static final long SETTLE_MS = 5000;

    private LiveCoverage() {}

    /** Monotonic milliseconds shared by the audio buffer and the caption socket (JVM-testable). */
    static long now() { return System.nanoTime() / 1_000_000; }

    /** True when the segment may be uploaded now (settled, or capture over and live captions closed). */
    static boolean settled(long segmentEndedAt, long now, boolean captureEnded) {
        return captureEnded || now - segmentEndedAt >= SETTLE_MS;
    }

    /**
     * Live captions cover a segment when one socket was accepting audio from before the segment's
     * first sample and is still healthy now (or, at the end, confirmed saving its last sentences).
     */
    static boolean covered(long segmentStartedAt, long liveHealthySince, boolean liveStillGood) {
        return liveStillGood && liveHealthySince <= segmentStartedAt;
    }
}
