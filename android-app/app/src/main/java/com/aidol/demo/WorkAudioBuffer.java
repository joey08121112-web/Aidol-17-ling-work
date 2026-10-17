package com.aidol.demo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * One uninterrupted capture, bounded segments queued independently of network latency.
 * Offsets count recorded PCM only (16 kHz mono PCM16 = 32 bytes/ms), so pauses do not advance them.
 */
final class WorkAudioBuffer {
    static final int SEGMENT_BYTES = 20 * 32000;
    static final int BYTES_PER_MS = 32;
    private static final int MIN_UPLOAD_BYTES = 3200;

    static final class Segment {
        final byte[] pcm;
        final long offsetMs;
        Segment(byte[] pcm, long offsetMs) { this.pcm = pcm; this.offsetMs = offsetMs; }
    }

    private final ArrayDeque<Segment> ready = new ArrayDeque<>();
    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private boolean ended, paused;
    private long bytes, segmentStart;
    synchronized void append(byte[] pcm) throws IOException {
        if (ended || paused) return;  // Paused audio is never buffered or uploaded.
        if ((pcm.length & 1) != 0) throw new IOException("录音格式错误，记录未完整保存");
        for (int pos = 0; pos < pcm.length;) {
            int count = Math.min(pcm.length - pos, SEGMENT_BYTES - current.size());
            current.write(pcm, pos, count); pos += count; bytes += count;
            if (current.size() == SEGMENT_BYTES) enqueue();
        }
    }
    private void enqueue() throws IOException {
        if (ready.size() >= 6) throw new IOException("转写积压，记录未完整保存；已提交部分可查看历史，请检查网络");
        ready.add(new Segment(current.toByteArray(), segmentStart / BYTES_PER_MS)); current.reset(); segmentStart = bytes; notifyAll();
    }
    /** Queue the partial segment now, so its words are not held back for the length of a pause. */
    private void flush() throws IOException {
        if (current.size() == 0) return;
        // Preserve even a short tail; the API requires 100 ms minimum PCM. Padding does not move offsets.
        if (current.size() < MIN_UPLOAD_BYTES) {
            byte[] tail = Arrays.copyOf(current.toByteArray(), MIN_UPLOAD_BYTES);
            current.reset(); current.write(tail, 0, tail.length);
        }
        enqueue();
    }
    /** @return the pause position in ms, or -1 if already paused or ended. */
    synchronized long pause() throws IOException {
        if (ended || paused) return -1;
        paused = true; flush(); return offsetMs();
    }
    /** @return the resume position in ms, or -1 if not paused. */
    synchronized long resume() {
        if (ended || !paused) return -1;
        paused = false; return offsetMs();
    }
    synchronized void finish() throws IOException {
        if (ended) return;
        ended = true;
        flush();
        notifyAll();
    }
    synchronized Segment poll() { return ready.poll(); }
    synchronized boolean complete() { return ended && ready.isEmpty(); }
    synchronized boolean paused() { return paused && !ended; }
    synchronized long size() { return bytes; }
    synchronized long offsetMs() { return bytes / BYTES_PER_MS; }
}
