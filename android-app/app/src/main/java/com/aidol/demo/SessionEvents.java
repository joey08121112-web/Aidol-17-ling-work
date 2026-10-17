package com.aidol.demo;

import java.util.concurrent.atomic.AtomicLong;

/** Invalidates every queued event, including completion, when its session is cancelled. */
final class SessionEvents {
    private final AtomicLong generation = new AtomicLong();
    long generation() { return generation.get(); }
    void invalidate() { generation.incrementAndGet(); }
    void deliver(long expected, Runnable event) {
        // UI callbacks run on one Looper. Never hold a monitor while invoking client code,
        // which may cancel capture while the PCM producer queues another UI event.
        if (generation.get() == expected) event.run();
    }
}
