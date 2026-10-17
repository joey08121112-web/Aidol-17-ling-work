package com.aidol.demo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechEndpointTest {
    @Test public void callEndsAfterSixHundredMillisecondsWithoutCuttingShortPauses() {
        SpeechEndpoint detector = new SpeechEndpoint(.025, 600);
        assertFalse(detector.accept(sound(300, (short) 3000)));
        assertFalse(detector.accept(sound(400, (short) 0)));
        assertFalse(detector.accept(sound(300, (short) 3000)));
        assertFalse(detector.accept(sound(580, (short) 0)));
        assertTrue(detector.accept(sound(20, (short) 0)));
        assertTrue(detector.heardSpeech()); assertFalse(detector.idle());
    }
    @Test public void idleSilenceTimesOutWithoutPretendingItWasSpeech() {
        SpeechEndpoint detector = new SpeechEndpoint(.025, 600);
        detector.accept(sound(44980, (short) 0)); assertFalse(detector.idle());
        detector.accept(sound(20, (short) 0)); assertTrue(detector.idle());
        assertFalse(detector.heardSpeech());
    }
    private byte[] sound(int milliseconds, short amplitude) {
        short[] samples = new short[milliseconds * 16]; Arrays.fill(samples, amplitude);
        return AudioFrames.encode(samples, samples.length);
    }
    @Test public void silenceAndIsolatedClicksNeverEndTurn() {
        SpeechEndpoint detector = new SpeechEndpoint(.025);
        assertFalse(detector.accept(sound(5000, (short) 0)));
        for (int i=0; i<30; i++) {
            assertFalse(detector.accept(sound(100, (short) 3000)));
            assertFalse(detector.accept(sound(200, (short) 0)));
        }
    }
    @Test public void endpointUsesAudioTimeRegardlessOfCallbackSize() {
        for (int frame : new int[]{2, 64, 640, 3200, 6400}) {
            SpeechEndpoint detector = new SpeechEndpoint(.025);
            byte[] speech = sound(300, (short) 3000);
            for (int i=0; i<speech.length; i+=frame) assertFalse(detector.accept(Arrays.copyOfRange(speech,i,Math.min(i+frame,speech.length))));
            assertFalse(detector.accept(sound(780, (short) 0)));
            assertTrue(detector.accept(sound(20, (short) 0)));
        }
    }
    @Test public void quietSyllableTailResetsSilence() {
        SpeechEndpoint detector = new SpeechEndpoint(.025);
        detector.accept(sound(300, (short) 3000));
        assertFalse(detector.accept(sound(700, (short) 0)));
        assertFalse(detector.accept(sound(100, (short) 600)));
        assertFalse(detector.accept(sound(780, (short) 0)));
        assertTrue(detector.accept(sound(20, (short) 0)));
    }
}
