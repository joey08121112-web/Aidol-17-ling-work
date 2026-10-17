package com.aidol.demo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoicePreRollTest {
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
}
