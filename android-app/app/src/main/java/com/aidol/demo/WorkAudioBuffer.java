package com.aidol.demo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** One uninterrupted capture, bounded segments queued independently of network latency. */
final class WorkAudioBuffer {
    static final int SEGMENT_BYTES = 20 * 32000;
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();
    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private boolean ended;
    private long bytes;
    synchronized void append(byte[] pcm) throws IOException {
        if (ended) return;
        if ((pcm.length & 1) != 0) throw new IOException("录音格式错误，记录未完整保存");
        for (int pos = 0; pos < pcm.length;) {
            int count = Math.min(pcm.length - pos, SEGMENT_BYTES - current.size());
            current.write(pcm, pos, count); pos += count; bytes += count;
            if (current.size() == SEGMENT_BYTES) enqueue();
        }
    }
    private void enqueue() throws IOException {
        if (ready.size() >= 6) throw new IOException("转写积压，记录未完整保存；已提交部分可查看历史，请检查网络");
        ready.add(current.toByteArray()); current.reset(); notifyAll();
    }
    synchronized void finish() throws IOException {
        if (ended) return;
        ended = true;
        if (current.size() > 0) {
            // Preserve even a short drained tail; the API requires 100 ms minimum PCM.
            if (current.size() < 3200) {
                byte[] tail = Arrays.copyOf(current.toByteArray(), 3200);
                current.reset(); current.write(tail, 0, tail.length);
            }
            enqueue();
        }
        notifyAll();
    }
    synchronized byte[] poll() { return ready.poll(); }
    synchronized boolean complete() { return ended && ready.isEmpty(); }
    synchronized long size() { return bytes; }
}
