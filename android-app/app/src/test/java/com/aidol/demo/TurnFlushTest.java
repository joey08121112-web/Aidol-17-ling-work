package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** The overlapped ASR end flush: runs beside the end cue, joins with the serial failure semantics. */
public class TurnFlushTest {
    @Test public void theFlushRunsWhileTheCallerProceedsAndJoinsAfterwards() throws Exception {
        CountDownLatch flushStarted = new CountDownLatch(1), releaseFlush = new CountDownLatch(1);
        AtomicBoolean overlapped = new AtomicBoolean();
        HostedSession.TurnFlush flush = new HostedSession.TurnFlush("flush-overlap-test", () -> {
            flushStarted.countDown();
            assertTrue(releaseFlush.await(5, TimeUnit.SECONDS));
            return;
        }).start();
        try {
            assertTrue(flushStarted.await(2, TimeUnit.SECONDS));
            // This stands in for the end cue that used to be serialized behind the flush.
            overlapped.set(true);
            final HostedSession.TurnFlush joined = flush;
            FutureTask<Boolean> awaitTask = new FutureTask<>(() -> { joined.await(); return true; });
            new Thread(awaitTask, "flush-join-test").start();
            assertThrows("await must block until the flush finishes", TimeoutException.class,
                () -> awaitTask.get(80, TimeUnit.MILLISECONDS));
            releaseFlush.countDown();
            assertTrue(awaitTask.get(2, TimeUnit.SECONDS));
        } finally {
            releaseFlush.countDown();
            flush.cancel();
        }
    }

    @Test public void aFlushFailureSurfacesAtTheJoinExactlyOnce() throws Exception {
        HostedSession.TurnFlush flush = new HostedSession.TurnFlush("flush-failure-test", () -> {
            throw new IOException("实时语音连接超时，请重新开始");
        }).start();
        try {
            IOException failure = assertThrows(IOException.class, flush::await);
            assertEquals("实时语音连接超时，请重新开始", failure.getMessage());
            // A repeated join after completion stays settled instead of throwing twice.
            assertThrows(IOException.class, flush::await);
        } finally { flush.cancel(); }
    }

    @Test public void anAbandonedFlushIsInterruptedInsteadOfLeakingItsWait() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        Thread[] worker = new Thread[1];
        HostedSession.TurnFlush flush = new HostedSession.TurnFlush("flush-stuck-test", () -> {
            worker[0] = Thread.currentThread();
            stuck.countDown();
            Thread.sleep(10_000);
        }).start();
        assertTrue(stuck.await(2, TimeUnit.SECONDS));
        flush.cancel();
        assertNotNull(worker[0]);
        worker[0].join(2000);
        assertFalse("cancel must interrupt the stuck flush thread", worker[0].isAlive());
    }
}
