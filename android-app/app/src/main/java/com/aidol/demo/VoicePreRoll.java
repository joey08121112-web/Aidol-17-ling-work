package com.aidol.demo;

/** Retain 320ms before speech onset so connecting ASR never clips the first word. */
final class VoicePreRoll {
    private final byte[] ring = new byte[10240];
    private int cursor, size;
    void append(byte[] pcm) {
        // When the chunk overflows the ring, only its newest ring.length bytes are kept.
        int fill = Math.min(pcm.length, ring.length);
        int source = pcm.length - fill;
        int first = Math.min(ring.length - cursor, fill);
        System.arraycopy(pcm, source, ring, cursor, first);
        System.arraycopy(pcm, source + first, ring, 0, fill - first);
        cursor = (cursor + fill) % ring.length;
        size = Math.min(size + fill, ring.length);
    }
    byte[] snapshot() {
        byte[] pcm = new byte[size];
        int start = (cursor - size + ring.length) % ring.length;
        int first = Math.min(ring.length - start, size);
        System.arraycopy(ring, start, pcm, 0, first);
        System.arraycopy(ring, 0, pcm, first, size - first);
        return pcm;
    }
}
