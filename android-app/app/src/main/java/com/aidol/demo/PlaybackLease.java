package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;

/** Serialize output; only legacy turn-taking output also excludes capture. */
final class PlaybackLease implements AutoCloseable {
    private static final AtomicReference<Object> OUTPUT = new AtomicReference<>();
    private final Object owner = new Object();
    private final boolean excludesCapture;
    private PlaybackLease(boolean excludesCapture) { this.excludesCapture = excludesCapture; }

    static PlaybackLease acquire(BooleanSupplier cancelled, long timeoutMillis) throws IOException, InterruptedException {
        return acquire(cancelled, timeoutMillis, true);
    }

    static PlaybackLease acquireDuplex(BooleanSupplier cancelled, long timeoutMillis) throws IOException, InterruptedException {
        return acquire(cancelled, timeoutMillis, false);
    }

    private static PlaybackLease acquire(BooleanSupplier cancelled, long timeoutMillis, boolean excludesCapture) throws IOException, InterruptedException {
        PlaybackLease lease = new PlaybackLease(excludesCapture);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
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
            Thread.sleep(25);
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("会话已取消");
    }
    @Override public void close() {
        if (excludesCapture) SystemCaptureLease.release(owner);
        OUTPUT.compareAndSet(owner, null);
    }
}
