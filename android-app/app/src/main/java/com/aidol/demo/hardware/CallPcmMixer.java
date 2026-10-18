package com.aidol.demo.hardware;

import java.util.function.Consumer;

/** Two continuous SDK PCM streams, aligned by sample position within one call scene. */
final class CallPcmMixer {
    private static final int CAPACITY = 32000; // Two seconds per channel; never an unbounded call buffer.
    private final short[][] samples = new short[2][CAPACITY];
    private final long[] written = new long[2];
    private final int[] pending = {-1, -1};
    private final Consumer<byte[]> output;
    private long read, waitingSince = -1;
    CallPcmMixer(Consumer<byte[]> output) { this.output = output; }

    void accept(int channel, byte[] bytes, long now) {
        int lane = channel - 1;
        if (lane < 0 || lane > 1) return;
        for (byte value : bytes) {
            if (pending[lane] < 0) { pending[lane] = value & 255; continue; }
            short sample = (short) (pending[lane] | value << 8); pending[lane] = -1;
            long position = written[lane]++;
            if (position < read) continue; // A late channel cannot replay time already emitted with silence.
            if (position - read >= CAPACITY) throw new IllegalStateException("通话音频积压，记录已停止");
            samples[lane][(int) (position % CAPACITY)] = sample;
        }
        drain(now, false);
    }

    void drain(long now, boolean finish) {
        if (finish && (pending[0] >= 0 || pending[1] >= 0))
            throw new IllegalStateException("通话音频以半个采样结束，记录未完整保存");
        long common = Math.min(written[0], written[1]), last = Math.max(written[0], written[1]);
        long until = Math.max(read, common);
        if (last > until) {
            if (waitingSince < 0) waitingSince = now;
            if (finish || now - waitingSince >= 250) until = last;
        }
        while (read < until) {
            int count = (int) Math.min(320, until - read);
            byte[] pcm = new byte[count * 2];
            for (int i = 0; i < count; i++, read++) {
                int slot = (int) (read % CAPACITY);
                int a = read < written[0] ? samples[0][slot] : 0;
                int b = read < written[1] ? samples[1][slot] : 0;
                // Headroom for simultaneous speech; one channel's silence does not double duration.
                int mixed = (a + b) / 2;
                pcm[i * 2] = (byte) mixed; pcm[i * 2 + 1] = (byte) (mixed >> 8);
            }
            output.accept(pcm);
        }
        if (read >= last || common >= last) waitingSince = -1;
    }
    void reset() {
        written[0] = written[1] = read = 0;
        pending[0] = pending[1] = -1; waitingSince = -1;
    }
}
