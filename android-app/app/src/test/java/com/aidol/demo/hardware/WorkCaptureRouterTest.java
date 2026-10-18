package com.aidol.demo.hardware;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkCaptureRouterTest {
    private static final class Output implements WorkCaptureRouter.Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Boolean> calls = new ArrayList<>();
        String status;
        public void pcm(byte[] data) { bytes.write(data, 0, data.length); }
        public void status(String value) { status = value; }
        public void call(boolean active) { calls.add(active); }
    }
    static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) { bytes[2*i] = (byte) samples[i]; bytes[2*i+1] = (byte) (samples[i] >> 8); }
        return bytes;
    }
    @Test public void musicIsNeverTranscribedAndUnknownSceneDoesNotGuessInput() {
        Output out = new Output(); WorkCaptureRouter router = new WorkCaptureRouter(out);
        router.pcm(2, pcm(111), 0); assertEquals(0, out.bytes.size());
        router.scene(1, 1);
        router.pcm(1, pcm(30000), 2); router.pcm(255, pcm(30000), 2); router.pcm(2, pcm(456), 2);
        router.scene(2, 3); router.pcm(1, pcm(30000), 3); router.pcm(2, pcm(789), 3);
        assertArrayEquals(pcm(456, 789), out.bytes.toByteArray()); assertTrue(out.calls.isEmpty());
    }
    @Test public void ringingStopsAmbientUntilCallIsConfirmedByBothSystemAndHeadset() {
        Output out = new Output(); WorkCaptureRouter r = new WorkCaptureRouter(out);
        r.scene(1, 0); r.phone(WorkCaptureRouter.RINGING, 1);
        r.pcm(2, pcm(9000), 1); r.scene(0, 2); r.pcm(1, pcm(9000), 2);
        assertTrue(out.calls.isEmpty()); assertEquals(0, out.bytes.size());
        r.phone(WorkCaptureRouter.ACTIVE, 3);
        r.pcm(1, pcm(1000, 2000), 4); r.pcm(2, pcm(3000, 4000), 4);
        assertArrayEquals(pcm(2000, 3000), out.bytes.toByteArray());
        assertEquals(Arrays.asList(true), out.calls); assertTrue(out.status.contains("已收到双方音频"));
        r.phone(WorkCaptureRouter.NORMAL, 5); r.pcm(2, pcm(9000), 5); // Stale call scene cannot resume ambient.
        r.scene(1, 6); r.pcm(1, pcm(9000), 6); r.pcm(2, pcm(1234), 6);
        assertArrayEquals(pcm(2000, 3000, 1234), out.bytes.toByteArray());
        assertEquals(Arrays.asList(true, false), out.calls);
    }
    @Test public void rejectedCallAndCallOnPhoneSpeakerCannotLeakMediaOrClaimTwoSidedCapture() {
        Output out = new Output(); WorkCaptureRouter r = new WorkCaptureRouter(out);
        r.scene(2, 0); r.dialog(true, 1); r.pcm(2, pcm(222), 2);
        r.phone(WorkCaptureRouter.ACTIVE, 3); r.pcm(1, pcm(222), 4);
        assertEquals(0, out.bytes.size()); assertTrue(out.calls.isEmpty()); assertTrue(out.status.contains("通过耳机接听"));
        r.phone(WorkCaptureRouter.NORMAL, 5); r.dialog(false, 6); r.pcm(2, pcm(123), 7);
        assertArrayEquals(pcm(123), out.bytes.toByteArray());
    }
    @Test public void manualPauseSurvivesCallTransitionsAndDoesNotReplayBufferedSpeech() {
        Output out = new Output(); WorkCaptureRouter r = new WorkCaptureRouter(out);
        r.scene(0, 0); r.phone(WorkCaptureRouter.ACTIVE, 1);
        r.pcm(1, pcm(9000), 2); r.pause(true); // Discard unmatched audio before pausing.
        r.pcm(2, pcm(9000), 3); r.tick(500);
        r.phone(WorkCaptureRouter.NORMAL, 501); r.scene(1, 502); r.pcm(2, pcm(9000), 503);
        assertEquals(0, out.bytes.size());
        r.pause(false); r.pcm(2, pcm(123), 504); assertArrayEquals(pcm(123), out.bytes.toByteArray());
    }
    @Test public void finishingCallDrainsOneSidedTailExactlyOnceWithoutClaimingBothSides() {
        Output out = new Output(); WorkCaptureRouter r = new WorkCaptureRouter(out);
        r.scene(0, 0); r.phone(WorkCaptureRouter.ACTIVE, 1); r.pcm(1, pcm(8000), 2);
        assertFalse(out.status.contains("已收到双方")); r.finish(3); r.finish(4);
        assertArrayEquals(pcm(4000), out.bytes.toByteArray()); assertEquals(Arrays.asList(true, false), out.calls);
    }
    @Test public void mixerHandlesFragmentationLatencyMissingChannelAndNoDoubledTimeline() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CallPcmMixer mixer = new CallPcmMixer(bytes -> out.write(bytes, 0, bytes.length));
        byte[] mic = pcm(1000, -1000, 30000, -30000);
        mixer.accept(2, Arrays.copyOfRange(mic, 0, 3), 0);
        mixer.accept(2, Arrays.copyOfRange(mic, 3, mic.length), 1);
        mixer.accept(1, pcm(3000, -3000, 30000, -30000), 100);
        assertArrayEquals(pcm(2000, -2000, 30000, -30000), out.toByteArray());
        mixer.accept(2, pcm(8000), 200); mixer.drain(449, false); assertEquals(8, out.size());
        mixer.drain(450, false); assertEquals(10, out.size());
        mixer.accept(1, pcm(8000), 500); assertEquals(10, out.size()); // Late partner is not a second timeline.
        mixer.reset(); mixer.accept(1, pcm(4000), 600); mixer.accept(2, pcm(4000), 601);
        assertEquals(12, out.size());
    }
    @Test(expected = IllegalStateException.class) public void partialCallSampleCannotBeReportedAsComplete() {
        CallPcmMixer mixer = new CallPcmMixer(bytes -> {});
        mixer.accept(1, new byte[]{1}, 0); mixer.drain(1, true);
    }
}
