package com.aidol.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * PCM16 of one meeting recording from a start position, fetched as consecutive WAV windows
 * (the server returns at most 10 minutes per request). The next window is requested shortly
 * before the current one runs out, so playback crosses window boundaries without a long gap.
 */
final class MeetingAudioStream extends InputStream {
    static final int SAMPLE_RATE = 16000;
    static final long BYTES_PER_MS = SAMPLE_RATE * 2 / 1000;
    static final long WINDOW_MS = 600_000L;
    /** The server's smallest accepted duration_ms. */
    static final long MIN_WINDOW_MS = 100L;
    /** Ask for the next window when about 10 s of the current one remain unread. */
    static final long PREFETCH_BYTES = 10_000L * BYTES_PER_MS;

    interface Windows {
        /** A WAV for [startMs, startMs + durationMs), or null when the meeting has no audio there (404). */
        InputStream open(long startMs, long durationMs) throws IOException;
    }

    private final Windows windows;
    private final long endMs, windowMs;
    private final Executor prefetch;
    private InputStream current;
    private long windowStart, windowDuration, remaining, received;
    private boolean opened, finished;
    private CompletableFuture<InputStream> pending;
    private long pendingStart = -1;
    private volatile long delivered;

    /** endMs is the end of saved audio on the timeline, or 0 when unknown (the server then trims). */
    MeetingAudioStream(Windows windows, long startMs, long endMs, long windowMs, Executor prefetch) {
        this.windows = windows; this.endMs = endMs; this.windowMs = windowMs; this.prefetch = prefetch;
        windowStart = Math.max(0, startMs);
        finished = endMs > 0 && windowStart >= endMs;
    }

    /** Duration to request for a window starting at startMs: a full window, cut to the known end. */
    static long windowDuration(long startMs, long endMs, long windowMs) {
        long duration = endMs > startMs ? Math.min(windowMs, endMs - startMs) : windowMs;
        return Math.max(MIN_WINDOW_MS, duration);
    }

    /**
     * Start of the window after one that delivered receivedBytes, or -1 at the end of the recording:
     * the server trims a window to the last saved audio, so a short window is the last one.
     */
    static long nextWindowStart(long windowStart, long requestedMs, long receivedBytes, long endMs) {
        if (receivedBytes < requestedMs * BYTES_PER_MS) return -1;
        long next = windowStart + requestedMs;
        return endMs > 0 && next >= endMs ? -1 : next;
    }

    /** Timeline position heard now, from frames the track played, never beyond the PCM handed to it. */
    static long positionMs(long startMs, long playedFrames, long deliveredBytes) {
        long frames = Math.min(Math.max(0, playedFrames), deliveredBytes / 2);
        return startMs + frames * 1000 / SAMPLE_RATE;
    }

    /** PCM bytes returned to the reader so far; safe to read from another thread. */
    long delivered() { return delivered; }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        while (true) {
            if (current == null && !openWindow()) return -1;
            if (remaining > 0) {
                int count = current.read(buffer, offset, (int) Math.min(length, remaining));
                if (count < 0) throw new IOException("会议录音传输中断，请重试");
                remaining -= count; received += count; delivered += count;
                if (remaining <= PREFETCH_BYTES) startPrefetch();
                return count;
            }
            closeCurrent();
            long next = nextWindowStart(windowStart, windowDuration, received, endMs);
            if (next < 0) { finished = true; return -1; }
            windowStart = next;
        }
    }

    private boolean openWindow() throws IOException {
        if (finished) return false;
        windowDuration = windowDuration(windowStart, endMs, windowMs);
        InputStream wav = take(windowStart);
        if (wav == null) {
            // A later window can vanish when retention deletes the recording mid-playback.
            if (!opened) throw new IOException("本场录音已不可用（可能已删除或过期）");
            finished = true; return false;
        }
        current = wav; received = 0;
        try { remaining = WavAudio.openPcm(wav, SAMPLE_RATE); }
        catch (IOException invalid) { closeCurrent(); throw invalid; }
        opened = true;
        if (remaining <= PREFETCH_BYTES) startPrefetch();
        return true;
    }

    private InputStream take(long start) throws IOException {
        CompletableFuture<InputStream> ready = pending;
        if (ready == null || pendingStart != start) return windows.open(start, windowDuration);
        pending = null; pendingStart = -1;
        try { return ready.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("已停止播放", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() instanceof CompletionException ? failed.getCause().getCause() : failed.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("会议录音加载失败，请重试", cause);
        }
    }

    private void startPrefetch() {
        if (pending != null) return;
        long expected = windowStart + windowDuration;
        // Only a full window can be followed by another; a trimmed one is the end.
        if (remaining + received < windowDuration * BYTES_PER_MS || endMs > 0 && expected >= endMs) return;
        long duration = windowDuration(expected, endMs, windowMs);
        pendingStart = expected;
        pending = CompletableFuture.supplyAsync(() -> {
            try { return windows.open(expected, duration); }
            catch (IOException e) { throw new CompletionException(e); }
        }, prefetch);
    }

    private void closeCurrent() {
        InputStream stream = current; current = null; remaining = 0;
        if (stream != null) { try { stream.close(); } catch (IOException ignored) {} }
    }

    @Override public void close() {
        finished = true;
        closeCurrent();
        CompletableFuture<InputStream> unused = pending; pending = null;
        if (unused != null) unused.thenAccept(stream -> {
            if (stream != null) { try { stream.close(); } catch (IOException ignored) {} }
        });
    }
}
