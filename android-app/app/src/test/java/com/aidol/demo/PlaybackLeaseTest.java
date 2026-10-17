package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackLeaseTest {
    @Test public void playbackWaitsForCaptureCleanupAndBlocksAReplacementRecording() throws Exception {
        Object capture = new Object(), replacement = new Object();
        assertTrue(SystemCaptureLease.acquire(capture));
        CountDownLatch attempted = new CountDownLatch(1), playing = new CountDownLatch(1), finish = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            attempted.countDown();
            try (PlaybackLease ignored = PlaybackLease.acquire(() -> false, 2000)) {
                playing.countDown(); assertTrue(finish.await(2, TimeUnit.SECONDS));
            }
            return null;
        });
        new Thread(task, "playback-lease-test").start();
        try {
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertFalse("An asynchronous stop has not released capture yet", playing.await(80, TimeUnit.MILLISECONDS));
            SystemCaptureLease.release(capture);
            assertTrue(playing.await(2, TimeUnit.SECONDS));
            assertFalse(SystemCaptureLease.acquire(replacement));
            finish.countDown(); task.get(2, TimeUnit.SECONDS);
            assertTrue(SystemCaptureLease.acquire(replacement));
        } finally {
            finish.countDown(); task.cancel(true);
            SystemCaptureLease.release(capture); SystemCaptureLease.release(replacement);
        }
    }

    @Test public void boundedWaitDoesNotTakeOrReleaseAnotherCapturesLease() {
        Object capture = new Object(), replacement = new Object();
        assertTrue(SystemCaptureLease.acquire(capture));
        try {
            assertThrows(IOException.class, () -> PlaybackLease.acquire(() -> false, 10));
            assertFalse(SystemCaptureLease.acquire(replacement));
        } finally { SystemCaptureLease.release(capture); SystemCaptureLease.release(replacement); }
    }

    @Test public void cancellationWhileWaitingNeverStartsPlayback() throws Exception {
        Object capture = new Object();
        assertTrue(SystemCaptureLease.acquire(capture));
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch attempted = new CountDownLatch(1);
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            attempted.countDown();
            try (PlaybackLease ignored = PlaybackLease.acquire(cancelled::get, 2000)) { return false; }
            catch (InterruptedException expected) { return true; }
        });
        new Thread(task, "playback-lease-cancel-test").start();
        try {
            assertTrue(attempted.await(2, TimeUnit.SECONDS)); cancelled.set(true);
            assertTrue(task.get(2, TimeUnit.SECONDS));
        } finally { task.cancel(true); SystemCaptureLease.release(capture); }
    }

    @Test public void cancellationImmediatelyAfterAcquiringReleasesTheUnusedLease() {
        AtomicInteger checks = new AtomicInteger();
        Object next = new Object();
        assertThrows(InterruptedException.class, () -> PlaybackLease.acquire(() -> checks.incrementAndGet() >= 2, 100));
        assertTrue(SystemCaptureLease.acquire(next)); SystemCaptureLease.release(next);
    }

    @Test public void repeatedOldPlaybackCleanupCannotReleaseANewerOwner() throws Exception {
        PlaybackLease previous = PlaybackLease.acquire(() -> false, 100);
        Object next = new Object(), outsider = new Object();
        previous.close(); assertTrue(SystemCaptureLease.acquire(next));
        try { previous.close(); assertFalse(SystemCaptureLease.acquire(outsider)); }
        finally { SystemCaptureLease.release(next); SystemCaptureLease.release(outsider); }
    }
}
