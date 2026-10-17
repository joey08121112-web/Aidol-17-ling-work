package com.aidol.demo.hardware;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class FusionCaptureEventsTest {
    @Test public void sdkIdleBeforeRejectedStartPreservesTheSpecificFailure() {
        VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
        VendorCaptureSourceTest.Result result = new VendorCaptureSourceTest.Result();
        ArrayDeque<Runnable> deferred = new ArrayDeque<>(); AtomicInteger releases = new AtomicInteger();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            FusionCaptureEvents events = new FusionCaptureEvents(device.listener, deferred::add,
                    failure -> device.listener.error(failure.summary()), releases::incrementAndGet);
            events.state("starting", -1); events.state("idle", -1);
            assertNull(result.error);
            events.error(9999, "融合录音命令失败:6");
            while (!deferred.isEmpty()) deferred.remove().run();
            assertTrue(result.error.contains("固件状态 6"));
            assertTrue(result.error.contains("本地录音"));
            assertTrue(events.failure().rejectedStart());
            assertEquals("fusion_9999_status_6", events.failure().diagnostics());
            assertEquals(0, releases.get());
        }
    }
    @Test public void failuresAfterRecordingOrMicrophoneDataAlwaysRequireRecovery() {
        for (boolean pcmFirst : new boolean[]{true, false}) {
            VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
            try (VendorCaptureSource source = new VendorCaptureSource(device)) {
                source.start(new VendorCaptureSourceTest.Result());
                FusionCaptureEvents events = new FusionCaptureEvents(device.listener, action -> {}, failure -> {}, () -> {});
                if (pcmFirst) events.pcm(2, new byte[]{1, 2}); else events.state("recording", 2);
                events.state("idle", 2); events.error(9999, "融合录音命令失败:6");
                assertFalse(events.failure().rejectedStart());
            }
        }
    }
    @Test public void normalStopStillDeliversIdleThenReleasesOwnership() throws Exception {
        VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
        VendorCaptureSourceTest.Result result = new VendorCaptureSourceTest.Result();
        ArrayDeque<Runnable> deferred = new ArrayDeque<>(); AtomicInteger releases = new AtomicInteger();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            FusionCaptureEvents events = new FusionCaptureEvents(device.listener, deferred::add,
                    failure -> fail("Unexpected SDK error"), releases::incrementAndGet);
            events.state("recording", 2); events.pcm(2, new byte[]{1, 2});
            source.stop(result); events.state("idle", 2);
            deferred.remove().run(); result.await();
            assertNull(result.error); assertEquals(0, releases.get());
            deferred.remove().run(); assertEquals(1, releases.get());
        }
    }
    @Test public void unknownMessagesNeverEnterTheUiOrAllowSkippingRecovery() {
        for (String message : new String[]{null, "device=AA:BB:CC:DD:EE:FF private content status=6",
                "融合录音命令失败:-1", "融合录音命令失败:256", "融合录音命令失败:0", "融合录音命令失败:6 extra"}) {
            FusionCaptureEvents.Failure failure = new FusionCaptureEvents.Failure(9999, message, false, false);
            assertFalse(failure.rejectedStart()); assertNull(failure.status);
            assertFalse(failure.summary().contains("AA:BB")); assertFalse(failure.summary().contains("private content"));
        }
    }
    @Test public void rejectedStopWhileStartingStillRequiresRecovery() {
        VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(new VendorCaptureSourceTest.Result());
            FusionCaptureEvents events = new FusionCaptureEvents(device.listener, action -> {}, failure -> {}, () -> {});
            events.state("starting", -1); events.state("stopping", -1); events.state("idle", -1);
            events.error(9999, "融合录音命令失败:6");
            assertFalse(events.failure().rejectedStart());
        }
    }
    @Test public void unexpectedIdleWithoutSdkErrorStillRequestsRecoveryBeforeRelease() {
        VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
        VendorCaptureSourceTest.Result result = new VendorCaptureSourceTest.Result();
        ArrayDeque<Runnable> deferred = new ArrayDeque<>(); AtomicInteger releases = new AtomicInteger();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            FusionCaptureEvents events = new FusionCaptureEvents(device.listener, deferred::add,
                    failure -> device.listener.error(failure.summary()), releases::incrementAndGet);
            events.state("recording", 2); events.state("idle", 2);
            deferred.remove().run();
            assertTrue(result.error.contains("意外结束"));
            assertEquals(1, device.recoveries); assertEquals(0, releases.get());
            deferred.remove().run(); assertEquals(1, releases.get());
        }
    }
    @Test public void phoneCallSceneStillStopsCaptureImmediately() {
        VendorCaptureSourceTest.Device device = new VendorCaptureSourceTest.Device();
        VendorCaptureSourceTest.Result result = new VendorCaptureSourceTest.Result();
        try (VendorCaptureSource source = new VendorCaptureSource(device)) {
            source.start(result);
            FusionCaptureEvents events = new FusionCaptureEvents(device.listener, action -> {}, failure -> {}, () -> {});
            events.state("recording", 0);
            assertTrue(result.error.contains("通话期间")); assertEquals(1, device.recoveries);
        }
    }
}
