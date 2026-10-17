package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** M1 playback waits for real capture cleanup, then excludes a new recording until it ends. */
final class PlaybackLease implements AutoCloseable {
    private final Object owner = new Object();
    private PlaybackLease() {}

    static PlaybackLease acquire(BooleanSupplier cancelled, long timeoutMillis) throws IOException, InterruptedException {
        PlaybackLease lease = new PlaybackLease();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (true) {
            check(cancelled);
            if (SystemCaptureLease.acquire(lease.owner)) {
                try { check(cancelled); return lease; }
                catch (InterruptedException ended) { lease.close(); throw ended; }
            }
            if (System.nanoTime() >= deadline) throw new IOException("上一段录音尚未释放，未开始耳机播放，请稍后重试");
            Thread.sleep(25);
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("会话已取消");
    }
    @Override public void close() { SystemCaptureLease.release(owner); }
}
