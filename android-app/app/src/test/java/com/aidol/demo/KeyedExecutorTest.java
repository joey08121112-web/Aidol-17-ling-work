package com.aidol.demo;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

public class KeyedExecutorTest {
    private KeyedExecutor executor;

    @After public void close() { if (executor != null) executor.shutdownNow(); }

    @Test public void sameResourceRunsInSubmissionOrderWithoutOverlap() throws Exception {
        executor = new KeyedExecutor(3);
        CountDownLatch firstStarted = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(3);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        executor.execute("account:task:a", () -> {
            order.add(1); firstStarted.countDown(); await(release); order.add(2); done.countDown();
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        executor.execute("account:task:a", () -> { order.add(3); done.countDown(); });
        executor.execute("account:task:a", () -> { order.add(4); done.countDown(); });
        assertEquals(Collections.singletonList(1), order);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(1, 2, 3, 4), order);
    }

    @Test public void anotherResourceCompletesBeforeSlowResourceIsReleased() throws Exception {
        executor = new KeyedExecutor(2);
        CountDownLatch firstStarted = new CountDownLatch(1), release = new CountDownLatch(1), otherDone = new CountDownLatch(1);
        AtomicBoolean firstFinished = new AtomicBoolean();
        executor.execute("account:task:a", () -> { firstStarted.countDown(); await(release); firstFinished.set(true); });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        executor.execute("account:task:b", otherDone::countDown);
        assertTrue("The second task must not wait for the first task's response", otherDone.await(5, TimeUnit.SECONDS));
        assertFalse(firstFinished.get());
        release.countDown();
    }

    @Test public void failedTaskStillAdvancesItsResourceQueue() throws Exception {
        executor = new KeyedExecutor(2);
        CountDownLatch firstStarted = new CountDownLatch(1), release = new CountDownLatch(1), nextDone = new CountDownLatch(1);
        executor.execute("same", () -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {});
            firstStarted.countDown(); await(release); throw new IllegalStateException("Expected test failure");
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        executor.execute("same", nextDone::countDown);
        release.countDown();
        assertTrue(nextDone.await(5, TimeUnit.SECONDS));
    }

    @Test public void shutdownInterruptsRunningAndDiscardsBothKindsOfQueuedWork() throws Exception {
        executor = new KeyedExecutor(1);
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicInteger queuedRuns = new AtomicInteger();
        executor.execute("running", () -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.execute("running", queuedRuns::incrementAndGet);
        executor.execute("other", queuedRuns::incrementAndGet);
        executor.shutdownNow();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(0, queuedRuns.get());
        try { executor.execute("new", () -> {}); fail("Shutdown must reject new work"); }
        catch (RejectedExecutionException expected) {}
    }

    @Test public void interleavedResourcesRetainTheirOwnFifoOrder() throws Exception {
        executor = new KeyedExecutor(4);
        int resources = 7, jobs = 80;
        AtomicInteger[] counts = new AtomicInteger[resources];
        AtomicBoolean outOfOrder = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(resources * jobs);
        for (int key = 0; key < resources; key++) counts[key] = new AtomicInteger();
        for (int index = 0; index < jobs; index++) for (int key = 0; key < resources; key++) {
            int expected = index; AtomicInteger counter = counts[key];
            executor.execute("account:task:" + key, () -> {
                if (counter.getAndIncrement() != expected) outOfOrder.set(true);
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(outOfOrder.get());
        for (AtomicInteger count : counts) assertEquals(jobs, count.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
