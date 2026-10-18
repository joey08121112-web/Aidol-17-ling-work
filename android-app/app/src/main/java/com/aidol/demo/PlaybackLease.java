package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;

/** Serialize output; only legacy turn-taking output also excludes capture. */
final class PlaybackLease implements AutoCloseable {
    private static final AtomicReference<Object> OUTPUT = new AtomicReference<>();
    /**
     * Output release is an event. Cross-class capture contention (SystemCaptureLease has no
     * signalling) and cancellation flags still need the bounded poll below.
     */
    private static final long POLL_MILLIS = 25;
    private static final EventSignal RELEASED = new EventSignal();
    private final Object owner = new Object();
    private final boolean excludesCapture;
    private PlaybackLease(boolean excludesCapture) { this.excludesCapture = excludesCapture; }

    /** Version-guarded monitor wake: a signal is never lost across a preceding condition check. */
    static final class EventSignal {
        private long version;
        synchronized void signal() { version++; notifyAll(); }
        synchronized long version() { return version; }
        /** True once the version already moved past observed, or when it moves within the timeout. */
        synchronized boolean await(long observed, long timeoutMillis) throws InterruptedException {
            if (version != observed) return true;
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            while (version == observed) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) return false;
                wait(remainingNanos / 1_000_000, (int) (remainingNanos % 1_000_000));
            }
            return true;
        }
    }

    static PlaybackLease acquire(BooleanSupplier cancelled, long timeoutMillis) throws IOException, InterruptedException {
        return acquire(cancelled, timeoutMillis, true);
    }

    static PlaybackLease acquireDuplex(BooleanSupplier cancelled, long timeoutMillis) throws IOException, InterruptedException {
        return acquire(cancelled, timeoutMillis, false);
    }

    private static PlaybackLease acquire(BooleanSupplier cancelled, long timeoutMillis, boolean excludesCapture) throws IOException, InterruptedException {
        PlaybackLease lease = new PlaybackLease(excludesCapture);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        long seen = RELEASED.version();
        while (true) {
            check(cancelled);
            if (OUTPUT.compareAndSet(null, lease.owner)) {
                if (!excludesCapture || SystemCaptureLease.acquire(lease.owner)) {
                    try { check(cancelled); return lease; }
                    catch (InterruptedException ended) { lease.close(); throw ended; }
                }
                OUTPUT.compareAndSet(lease.owner, null);
            }
            if (System.nanoTime() >= deadline) throw new IOException(excludesCapture
                ? "上一段音频尚未释放，未开始耳机播放，请稍后重试" : "上一段播放尚未释放，请检查耳机输出");
            RELEASED.await(seen, POLL_MILLIS);
            seen = RELEASED.version();
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("会话已取消");
    }
    @Override public void close() {
        if (excludesCapture) SystemCaptureLease.release(owner);
        OUTPUT.compareAndSet(owner, null);
        RELEASED.signal();
    }
}
