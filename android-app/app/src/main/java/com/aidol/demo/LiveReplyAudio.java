package com.aidol.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;

/** Bounded producer/consumer PCM. EOF requires an explicit successful trailer. */
final class LiveReplyAudio extends InputStream {
    private static final int CAPACITY = 384000;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private int buffered, offset, total;
    private boolean ended, discarded;
    private IOException failure;

    synchronized void offer(byte[] frame) throws IOException, InterruptedException {
        if (discarded) return;
        if (ended || frame.length == 0 || frame.length > 24000 || (frame.length & 1) != 0
                || total + frame.length > 6 * 1024 * 1024) throw new IOException("回复音频格式无效");
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!discarded && failure == null && buffered + frame.length > CAPACITY) {
            if (System.nanoTime() >= deadline) throw new IOException("回复播放缓冲超时");
            wait(100);
        }
        if (discarded) return;
        if (failure != null) throw failure;
        frames.add(frame); buffered += frame.length; total += frame.length; notifyAll();
    }
    synchronized void finish() { ended = true; notifyAll(); }
    synchronized void fail(IOException error) { failure = error; frames.clear(); buffered = offset = 0; notifyAll(); }
    synchronized void discard() { discarded = true; frames.clear(); buffered = offset = 0; notifyAll(); }
    @Override public synchronized int read(byte[] target, int start, int length) throws IOException {
        if (length == 0) return 0;
        long deadline = System.nanoTime() + 65_000_000_000L;
        while (frames.isEmpty() && !ended && !discarded && failure == null) {
            if (System.nanoTime() >= deadline) throw new IOException("回复音频等待超时");
            try { wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("播放已中断", e); }
        }
        if (discarded) return -1;
        if (failure != null) throw failure;
        if (frames.isEmpty()) return -1;
        byte[] frame = frames.peek();
        int count = Math.min(length, frame.length - offset);
        System.arraycopy(frame, offset, target, start, count); offset += count; buffered -= count;
        if (offset == frame.length) { frames.remove(); offset = 0; }
        notifyAll(); return count;
    }
    @Override public int read() throws IOException {
        byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public void close() { discard(); }
}
