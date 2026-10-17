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
        assertEquals(WorkAudioBuffer.SEGMENT_BYTES, buffer.poll().pcm.length);
        assertFalse(buffer.complete());
        assertNull(buffer.poll());
        buffer.finish();
        WorkAudioBuffer.Segment tail = buffer.poll();
        assertArrayEquals(Arrays.copyOfRange(original, WorkAudioBuffer.SEGMENT_BYTES, original.length), tail.pcm);
        assertEquals(20000, tail.offsetMs);
        assertTrue(buffer.complete());
    }
    @Test public void slowNetworkDoesNotDropSamplesOrStopMicrophoneAtBoundary() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        for (int i = 0; i < 4; i++) {
            byte[] part = new byte[WorkAudioBuffer.SEGMENT_BYTES]; Arrays.fill(part, (byte) i); buffer.append(part);
        }
        buffer.finish();
        for (int i = 0; i < 4; i++) {
            byte[] part = buffer.poll().pcm; assertEquals(WorkAudioBuffer.SEGMENT_BYTES, part.length);
            for (byte sample : part) assertEquals((byte) i, sample);
        }
        assertTrue(buffer.complete());
    }
    @Test public void shortFinalTailIsPreservedAndPaddedForApiMinimum() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer(); buffer.append(new byte[]{1, 2}); buffer.finish(); buffer.finish();
        byte[] tail = buffer.poll().pcm; assertEquals(3200, tail.length); assertEquals(1, tail[0]); assertEquals(2, tail[1]);
        assertTrue(buffer.complete());
    }
    @Test(expected = IOException.class) public void backpressureFailsExplicitlyInsteadOfSilentlyDroppingWork() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        for (int i = 0; i < 7; i++) buffer.append(new byte[WorkAudioBuffer.SEGMENT_BYTES]);
    }
    @Test public void pauseFlushesPartialSegmentDropsPausedAudioAndKeepsOffsetsOnRecordedTime() throws Exception {
        WorkAudioBuffer buffer = new WorkAudioBuffer();
        buffer.append(new byte[64000]);                         // 2000 ms recorded
        assertEquals(2000, buffer.pause());
        assertEquals(-1, buffer.pause());
        assertTrue(buffer.paused());
        WorkAudioBuffer.Segment first = buffer.poll();
        assertEquals(0, first.offsetMs); assertEquals(64000, first.pcm.length);
        buffer.append(new byte[32000]);                         // Discarded while paused.
        assertEquals(2000, buffer.offsetMs());
        assertNull(buffer.poll());
        assertEquals(2000, buffer.resume());
        assertEquals(-1, buffer.resume());
        buffer.append(new byte[2]);                              // Tiny tail is padded, offset is not.
        assertEquals(2000, buffer.pause());
        WorkAudioBuffer.Segment tail = buffer.poll();
        assertEquals(2000, tail.offsetMs); assertEquals(3200, tail.pcm.length);
        buffer.resume();
        buffer.append(new byte[WorkAudioBuffer.SEGMENT_BYTES]);
        assertEquals(2000, buffer.poll().offsetMs);              // Real bytes, not padded bytes, set offsets.
        buffer.finish();
        assertFalse(buffer.paused());
        assertEquals(-1, buffer.pause());
    }
}
