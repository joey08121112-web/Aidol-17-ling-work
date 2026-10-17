package com.aidol.demo.hardware;

import com.aidol.demo.CaptureSource;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class VendorCaptureSourceTest {
    static final class Device implements FusionDevice {
        Listener listener;
        int stops, cancels, recoveries;
        @Override public void startFusion(Listener listener) { this.listener = listener; }
        @Override public void stopFusion(Listener owner) { if (owner == listener) stops++; }
        @Override public void cancelFusion(Listener owner) { if (owner == listener) cancels++; }
        @Override public void requireReconnect(Listener owner) { if (owner == listener) recoveries++; }
    }
    static class Result implements CaptureSource.Listener, CaptureSource.StopCallback {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicInteger endings = new AtomicInteger();
        volatile String error;
        volatile boolean recording;
        @Override public void status(String ignored) {}
        @Override public void recording() { recording = true; }
        @Override public synchronized void pcm(byte[] bytes) {
            assertTrue("recording is announced before PCM", recording);
            assertEquals(0, bytes.length % 2);
            audio.write(bytes, 0, bytes.length);
        }
        @Override public void error(String message) { error = message; complete.countDown(); }
        @Override public void ended() { endings.incrementAndGet(); complete.countDown(); }
        void await() throws Exception { assertTrue(complete.await(2, TimeUnit.SECONDS)); }
    }

    @Test public void keepsTailUntilIdleAndFiltersOtherChannelsAcrossOddBoundaries() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            device.listener.pcm(1, new byte[]{99, 99});
            device.listener.pcm(2, new byte[]{1});
            source.stop(result);
            device.listener.pcm(255, new byte[]{99});
            assertFalse("end marker alone cannot confirm SDK idle", result.complete.await(20, TimeUnit.MILLISECONDS));
            device.listener.pcm(2, new byte[]{2, 3});
            device.listener.pcm(2, new byte[]{4, 5, 6});
            device.listener.state("idle", 2);
            result.await();
            assertNull(result.error); assertEquals(1, result.endings.get());
            assertArrayEquals(new byte[]{1,2,3,4,5,6}, result.audio.toByteArray());
        }
    }
    @Test public void duplicateStopAndIdleCannotCompleteTwice() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result); device.listener.state("recording", 2);
            device.listener.pcm(2, new byte[]{1,2});
            source.stop(result); source.stop(result);
            device.listener.state("idle", 2); device.listener.state("idle", 2);
            result.await(); assertEquals(1, result.endings.get()); assertEquals(1, device.stops);
        }
    }
    @Test public void concurrentPcmWaitsUntilRecordingAnnouncementCompletes() throws Exception {
        Device device = new Device();
        CountDownLatch inputEntered = new CountDownLatch(1), releaseInput = new CountDownLatch(1);
        CountDownLatch pcmAttempted = new CountDownLatch(1), pcmDelivered = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicInteger recordingAnnouncements = new AtomicInteger();
        Result result = new Result() {
            @Override public void input(String ignored) {
                inputEntered.countDown();
                try { assertTrue(releaseInput.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }
            @Override public void recording() { super.recording(); recordingAnnouncements.incrementAndGet(); }
            @Override public void pcm(byte[] bytes) { super.pcm(bytes); pcmDelivered.countDown(); }
        };
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            Thread state = new Thread(() -> {
                try { device.listener.state("recording", 2); }
                catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); }
            });
            Thread pcm = new Thread(() -> {
                pcmAttempted.countDown();
                try { device.listener.pcm(2, new byte[]{1, 2}); }
                catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); }
            });
            try {
                state.start(); assertTrue(inputEntered.await(1, TimeUnit.SECONDS));
                pcm.start(); assertTrue(pcmAttempted.await(1, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (pcm.isAlive() && pcm.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals("PCM waits while recording readiness is being announced", Thread.State.BLOCKED, pcm.getState());
                assertFalse(result.recording); assertEquals(1L, pcmDelivered.getCount());
            } finally {
                releaseInput.countDown(); state.join(2000); pcm.join(2000);
            }
            assertFalse(state.isAlive()); assertFalse(pcm.isAlive()); assertNull(callbackFailure.get());
            source.stop(result); device.listener.state("idle", 2); result.await();
            assertNull(result.error); assertEquals(1, recordingAnnouncements.get());
            assertEquals(0L, pcmDelivered.getCount()); assertArrayEquals(new byte[]{1, 2}, result.audio.toByteArray());
        } finally { releaseInput.countDown(); }
    }
    @Test public void cancelDuringInputNotificationNeverAnnouncesRecordingOrQueuesPcm() {
        for (boolean stateFirst : new boolean[]{true, false}) {
            Device device = new Device(); AtomicInteger recordingAnnouncements = new AtomicInteger();
            final VendorCaptureSource[] current = new VendorCaptureSource[1];
            Result result = new Result() {
                @Override public void input(String ignored) { current[0].cancel(); }
                @Override public void recording() { super.recording(); recordingAnnouncements.incrementAndGet(); }
            };
            try (VendorCaptureSource source = new VendorCaptureSource(device)) {
                current[0] = source; source.start(result);
                if (stateFirst) device.listener.state("recording", 2);
                else device.listener.pcm(2, new byte[]{1, 2});
                device.listener.pcm(2, new byte[]{3, 4}); device.listener.state("idle", 2);
                assertEquals(0, recordingAnnouncements.get()); assertFalse(result.recording);
                assertEquals(0, result.audio.size()); assertNull(result.error);
                assertEquals(0, result.endings.get()); assertEquals(1, device.cancels);
            }
        }
    }
    @Test public void halfSampleAtIdleFailsInsteadOfTruncating() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result); device.listener.pcm(2, new byte[]{1});
            source.stop(result); device.listener.state("idle", 2);
            result.await(); assertNotNull(result.error); assertEquals(0, result.endings.get());
        }
    }
    @Test public void missingIdleTimesOutAndRequiresReconnect() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device, 30, 1024)) {
            source.start(result); source.stop(result); result.await();
            assertNotNull(result.error); assertEquals(0, result.endings.get());
            // Failure notification is immediately followed by recovery on the deadline thread.
            for (int i=0; i<100 && device.recoveries == 0; i++) Thread.sleep(1);
            assertEquals(1, device.recoveries);
        }
    }
    @Test public void totalDurationIncludesWaitingForStopConfirmation() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device, 1000, 1024, 40)) {
            source.start(result); device.listener.state("recording", 2);
            device.listener.pcm(2, new byte[]{1,2}); source.stop(result);
            result.await(); assertTrue(result.error.contains("60 秒")); assertEquals(0, result.endings.get());
            device.listener.state("idle", 2); assertEquals(0, result.endings.get());
        }
    }
    @Test public void queueByteLimitFailsWithoutSilentDrop() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device, 200, 4)) {
            source.start(result); device.listener.pcm(2, new byte[6]); result.await();
            assertTrue(result.error.contains("队列")); assertEquals(1, device.recoveries);
            assertEquals(0, result.audio.size()); assertEquals(0, result.endings.get());
        }
    }
    @Test public void slowConsumerDoesNotBlockSdkAndBacklogFailsExplicitly() throws Exception {
        Device device = new Device(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Result result = new Result() {
            @Override public void pcm(byte[] bytes) {
                entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new AssertionError(ex); }
                super.pcm(bytes);
            }
        };
        try (VendorCaptureSource source = new VendorCaptureSource(device, 2000, 4)) {
            source.start(result); device.listener.pcm(2, new byte[]{1,2});
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            device.listener.pcm(2, new byte[]{3,4}); device.listener.pcm(2, new byte[]{5,6});
            device.listener.pcm(2, new byte[]{7,8}); result.await();
            assertTrue(result.error.contains("队列")); assertEquals(0, result.endings.get());
        } finally { release.countDown(); }
    }
    @Test public void completeSegmentLimitIncludesLateTail() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device, 2000, VendorCaptureSource.MAX_BYTES)) {
            source.start(result); device.listener.pcm(2, new byte[VendorCaptureSource.MAX_BYTES]);
            source.stop(result); device.listener.pcm(2, new byte[]{1,2}); result.await();
            assertTrue(result.error.contains("上限")); assertEquals(0, result.endings.get());
        }
    }
    @Test public void cancelAndLateCallbacksNeverEnterNextGeneration() throws Exception {
        Device device = new Device(); Result first = new Result(); Result second = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(first); FusionDevice.Listener old = device.listener;
            source.stop(first); source.cancel(); source.cancel();
            source.start(second);
            old.pcm(2, new byte[]{99,99}); old.state("idle",2); old.error("stale");
            device.listener.pcm(2, new byte[]{3,4}); source.stop(second); device.listener.state("idle",2);
            second.await(); assertNull(second.error); assertArrayEquals(new byte[]{3,4}, second.audio.toByteArray());
            assertEquals(0, first.endings.get()); assertNull(first.error); assertEquals(1, device.cancels);
        }
    }
    @Test public void sdkBufferReuseDoesNotModifyQueuedPcm() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result); byte[] frame = {1,2}; device.listener.pcm(2, frame); frame[0] = 99;
            source.stop(result); device.listener.state("idle",2); result.await();
            assertArrayEquals(new byte[]{1,2}, result.audio.toByteArray());
        }
    }
    @Test public void callSceneFailsAndNeverCompletesAnAiRecording() throws Exception {
        Device device = new Device(); Result result = new Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result); device.listener.state("recording", 0); result.await();
            assertNotNull(result.error); assertEquals(0, result.endings.get()); assertEquals(1, device.recoveries);
        }
    }
}
