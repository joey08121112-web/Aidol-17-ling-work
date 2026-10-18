package com.aidol.demo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Version-guarded wake semantics shared by lease, playback and work-loop waits. */
public class EventSignalTest {
    @Test public void aSignalBeforeTheWaitIsNeverLost() throws Exception {
        PlaybackLease.EventSignal signal = new PlaybackLease.EventSignal();
        long observed = signal.version();
        signal.signal();
        assertTrue("A wake must not require a later signal", signal.await(observed, 0));
        assertTrue(signal.await(observed, 60));
    }

    @Test public void anUnsignalledWaitTimesOutWithoutMoving() throws Exception {
        PlaybackLease.EventSignal signal = new PlaybackLease.EventSignal();
        long observed = signal.version();
        long started = System.nanoTime();
        assertFalse(signal.await(observed, 60));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("Wait must respect its timeout, took " + elapsedMillis + "ms", elapsedMillis >= 60);
        assertTrue("Wait must not block far past its timeout, took " + elapsedMillis + "ms", elapsedMillis < 5000);
        assertEquals(observed, signal.version());
    }

    @Test public void aWaitingThreadWakesOnAnotherThreadsSignal() throws Exception {
        PlaybackLease.EventSignal signal = new PlaybackLease.EventSignal();
        long observed = signal.version();
        CountDownLatch waiting = new CountDownLatch(1);
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            waiting.countDown();
            return signal.await(observed, 5000);
        });
        new Thread(task, "event-signal-test").start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertThrows("The waiter must stay blocked until signalled", TimeoutException.class,
                () -> task.get(80, TimeUnit.MILLISECONDS));
            signal.signal();
            assertTrue(task.get(2, TimeUnit.SECONDS));
        } finally { task.cancel(true); }
    }

    @Test public void theVersionMovesMonotonicallyWithEachSignal() {
        PlaybackLease.EventSignal signal = new PlaybackLease.EventSignal();
        long first = signal.version();
        signal.signal(); signal.signal();
        assertEquals(first + 2, signal.version());
    }

    @Test public void aStaleObservationReturnsImmediatelyAfterAConcurrentSignal() throws Exception {
        PlaybackLease.EventSignal signal = new PlaybackLease.EventSignal();
        long observed = signal.version();
        CountDownLatch signalled = new CountDownLatch(1);
        new Thread(() -> { signal.signal(); signalled.countDown(); }, "event-signal-race-test").start();
        assertTrue(signalled.await(2, TimeUnit.SECONDS));
        // observed was read before the state the caller is about to check: no lost wake.
        assertTrue(signal.await(observed, 0));
    }
}
