package com.aidol.demo;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkAudioBufferTest {
    @Test public void durationBoundaryQueuesButNeverEndsCaptureAndKeepsTail() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        byte[] original = new byte[WorkAudioBuffer.SEGMENT_BYTES + 8000];
        Arrays.fill(original, (byte) 7);
        buffer.append(original);
        assertEquals(WorkAudioBuffer.SEGMENT_BYTES, buffer.poll().length);
        assertFalse(buffer.complete());
        assertNull(buffer.poll());
        buffer.finish();
        assertArrayEquals(Arrays.copyOfRange(original, WorkAudioBuffer.SEGMENT_BYTES, original.length), buffer.poll());
        assertTrue(buffer.complete());
    }
    @Test public void slowNetworkDoesNotDropSamplesOrStopMicrophoneAtBoundary() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        for (int i = 0; i < 4; i++) {
            byte[] part = new byte[WorkAudioBuffer.SEGMENT_BYTES]; Arrays.fill(part, (byte) i); buffer.append(part);
        }
        buffer.finish();
        for (int i = 0; i < 4; i++) {
            byte[] part = buffer.poll(); assertEquals(WorkAudioBuffer.SEGMENT_BYTES, part.length);
            for (byte sample : part) assertEquals((byte) i, sample);
        }
        assertTrue(buffer.complete());
    }
    @Test public void shortFinalTailIsPreservedAndPaddedForApiMinimum() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer(); buffer.append(new byte[]{1, 2}); buffer.finish(); buffer.finish();
        byte[] tail = buffer.poll(); assertEquals(3200, tail.length); assertEquals(1, tail[0]); assertEquals(2, tail[1]);
        assertTrue(buffer.complete());
    }
    @Test(expected = IOException.class) public void backpressureFailsExplicitlyInsteadOfSilentlyDroppingWork() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        for (int i = 0; i < 7; i++) buffer.append(new byte[WorkAudioBuffer.SEGMENT_BYTES]);
    }
}
