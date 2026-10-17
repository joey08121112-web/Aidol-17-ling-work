package com.aidol.demo;

/** Retain 320ms before speech onset so connecting ASR never clips the first word. */
final class VoicePreRoll {
    private final byte[] ring = new byte[10240];
    private int cursor, size;
    void append(byte[] pcm) {
        for (byte value : pcm) { ring[cursor] = value; cursor = (cursor + 1) % ring.length; size = Math.min(size + 1, ring.length); }
    }
    byte[] snapshot() {
        byte[] pcm = new byte[size];
        int start = (cursor - size + ring.length) % ring.length;
        for (int i = 0; i < size; i++) pcm[i] = ring[(start + i) % ring.length];
        return pcm;
    }
}
