package com.aidol.demo;

import java.util.Arrays;
import java.util.function.Consumer;

public final class AudioFrames {
    private AudioFrames() {}
    public static byte[] encode(short[] input, int count) {
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            pcm[2 * i] = (byte) input[i];
            pcm[2 * i + 1] = (byte) (input[i] >> 8);
        }
        return pcm;
    }
    public static double rms(byte[] pcm) {
        if (pcm.length < 2) return 0;
        double total = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            total += (double) value * value;
        }
        return Math.sqrt(total / (pcm.length / 2)) / 32768.0;
    }
    public static void split(byte[] pcm, Consumer<byte[]> sink) {
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 数据长度为奇数");
        // 100 ms frames; SDK callbacks can contain more than one frame.
        for (int offset = 0; offset < pcm.length; offset += 3200) {
            sink.accept(Arrays.copyOfRange(pcm, offset, Math.min(offset + 3200, pcm.length)));
        }
    }
}
