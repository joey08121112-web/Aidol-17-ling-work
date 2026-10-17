package com.aidol.demo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Pure Java owner of one bounded recording, including its asynchronous stop tail. */
final class CaptureTurn {
    enum Phase { NEW, RECORDING, STOP_REQUESTED, DRAINING, ENDED, CANCELLED, FAILED }
    static final int MAX_BYTES = 1920000;
    static final long STOP_BUDGET_MS = 3000;
    private final long durationMs;
    private final int byteLimit;
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private Phase phase = Phase.NEW;
    private long startedAt, stopAt;
    private String failure;

    CaptureTurn(int seconds) {
        int bounded = Math.max(5, Math.min(60, seconds));
        durationMs = bounded * 1000L;
        byteLimit = Math.min(MAX_BYTES, bounded * 32000);
    }
    synchronized boolean recording(long now) {
        if (phase != Phase.NEW) return false;
        startedAt = now; phase = Phase.RECORDING; return true;
    }
    synchronized boolean append(byte[] bytes, long now) throws IOException {
        if (phase != Phase.RECORDING && phase != Phase.STOP_REQUESTED) return false;
        if (now > startedAt + durationMs || (phase == Phase.STOP_REQUESTED && now > stopAt + STOP_BUDGET_MS))
            throw fail("录音停止超过时限，已丢弃本轮，请恢复或重连耳机");
        if ((bytes.length & 1) != 0) throw fail("录音 PCM16 样本不完整，已丢弃本轮");
        if (bytes.length > byteLimit - audio.size())
            throw fail("录音及尾帧超过本轮上限，已丢弃本轮，请重试");
        audio.write(bytes, 0, bytes.length); return true;
    }
    synchronized boolean shouldStop(long now) {
        return phase == Phase.RECORDING && (now >= startedAt + durationMs - STOP_BUDGET_MS
            || audio.size() >= byteLimit - STOP_BUDGET_MS * 32);
    }
    synchronized boolean requestStop(long now) {
        if (phase != Phase.RECORDING) return false;
        stopAt = now; phase = Phase.STOP_REQUESTED; return true;
    }
    synchronized long remainingStopMillis(long now) {
        return Math.max(0, Math.min(startedAt + durationMs, stopAt + STOP_BUDGET_MS) - now);
    }
    synchronized boolean drained(long now) throws IOException {
        if (phase != Phase.STOP_REQUESTED) return false;
        if (now > startedAt + durationMs || now > stopAt + STOP_BUDGET_MS)
            throw fail("录音停止未在时限内确认，已丢弃本轮，请恢复或重连耳机");
        phase = Phase.DRAINING; return true;
    }
    synchronized byte[] finish() throws IOException {
        if (failure != null) throw new IOException(failure);
        if (phase != Phase.DRAINING) throw new IOException("录音未完成停止与排空，不能提交");
        byte[] snapshot = audio.toByteArray(); audio.reset(); phase = Phase.ENDED; return snapshot;
    }
    synchronized IOException fail(String message) {
        if (phase == Phase.CANCELLED || phase == Phase.ENDED) return new IOException(message);
        failure = message; phase = Phase.FAILED; audio.reset(); return new IOException(message);
    }
    synchronized void cancel() { phase = Phase.CANCELLED; audio.reset(); }
    synchronized boolean cancelRecording() {
        if (phase != Phase.RECORDING) return false;
        phase = Phase.CANCELLED; audio.reset(); return true;
    }
    synchronized Phase phase() { return phase; }
    synchronized int size() { return audio.size(); }
    synchronized String failure() { return failure; }
    long recordingBudgetMillis() { return durationMs - STOP_BUDGET_MS; }
}
