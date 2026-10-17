package com.aidol.demo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoicePreRollTest {
    private static byte[] ramp(int length, int seed) {
        byte[] samples = new byte[length];
        for (int i = 0; i < length; i++) samples[i] = (byte) (seed + i * 31);
        return samples;
    }

    @Test public void retainsFirstSyllablesAndOnlyBoundedRecentSilence() {
        VoicePreRoll buffer = new VoicePreRoll();
        byte[] initial = new byte[1000]; Arrays.fill(initial, (byte) 1); buffer.append(initial);
        assertArrayEquals(initial, buffer.snapshot());
        byte[] samples = new byte[20000];
        for (int i = 0; i < samples.length; i++) samples[i] = (byte) i;
        buffer.append(samples);
        assertArrayEquals(Arrays.copyOfRange(samples, samples.length - 10240, samples.length), buffer.snapshot());
        assertEquals(10240, buffer.snapshot().length);
    }

    @Test public void aWrapAroundAppendKeepsByteOrder() {
        VoicePreRoll buffer = new VoicePreRoll();
        byte[] head = ramp(10000, 1);
        buffer.append(head);
        byte[] tail = ramp(5000, 99);
        buffer.append(tail);
        byte[] expected = new byte[10240];
        System.arraycopy(head, 10000 - 5240, expected, 0, 5240);
        System.arraycopy(tail, 0, expected, 5240, 5000);
        assertArrayEquals(expected, buffer.snapshot());
    }

    @Test public void anAppendEndingExactlyAtTheRingEdgeStaysConsistent() {
        VoicePreRoll buffer = new VoicePreRoll();
        buffer.append(ramp(10240, 7));
        assertArrayEquals(ramp(10240, 7), buffer.snapshot());
        buffer.append(ramp(10240, 42));
        assertArrayEquals(ramp(10240, 42), buffer.snapshot());
    }

    @Test public void anOversizedChunkKeepsOnlyItsNewestBytes() {
        VoicePreRoll buffer = new VoicePreRoll();
        buffer.append(ramp(3000, 5));
        byte[] oversized = ramp(30000, 100);
        buffer.append(oversized);
        assertArrayEquals(Arrays.copyOfRange(oversized, 30000 - 10240, 30000), buffer.snapshot());
        assertEquals(10240, buffer.snapshot().length);
    }

    @Test public void anEmptyAppendChangesNothing() {
        VoicePreRoll buffer = new VoicePreRoll();
        buffer.append(ramp(640, 3));
        buffer.append(new byte[0]);
        assertArrayEquals(ramp(640, 3), buffer.snapshot());
    }

    @Test public void repeatedSmallAppendsMatchOneLargeAppend() {
        VoicePreRoll streamed = new VoicePreRoll(), whole = new VoicePreRoll();
        byte[] chunk = ramp(997, 11);
        byte[] all = new byte[997 * 23];
        for (int i = 0; i < 23; i++) {
            streamed.append(chunk);
            System.arraycopy(chunk, 0, all, i * 997, 997);
        }
        whole.append(all);
        assertArrayEquals(whole.snapshot(), streamed.snapshot());
        assertEquals(10240, streamed.snapshot().length);
        assertArrayEquals(Arrays.copyOfRange(all, all.length - 10240, all.length), streamed.snapshot());
    }
}
