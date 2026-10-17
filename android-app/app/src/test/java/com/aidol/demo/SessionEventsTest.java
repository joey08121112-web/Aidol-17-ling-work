package com.aidol.demo;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionEventsTest {
    @Test public void queuedResultErrorAndDoneCannotClearReplacementSessionAfterCancel() {
        SessionEvents events = new SessionEvents();
        long previous = events.generation();
        AtomicInteger delivered = new AtomicInteger();
        Runnable result = () -> events.deliver(previous, delivered::incrementAndGet);
        Runnable error = () -> events.deliver(previous, delivered::incrementAndGet);
        Runnable done = () -> events.deliver(previous, delivered::incrementAndGet);
        events.invalidate();
        result.run(); error.run(); done.run();
        assertEquals(0, delivered.get());
        events.deliver(events.generation(), delivered::incrementAndGet);
        assertEquals(1, delivered.get());
    }

    @Test public void completionWaitsForWorkerCleanupAndIsIdempotent() {
        CaptureCompletion completion = new CaptureCompletion();
        AtomicInteger ended = new AtomicInteger(), failed = new AtomicInteger();
        CaptureSource.StopCallback callback = new CaptureSource.StopCallback() {
            public void ended() { ended.incrementAndGet(); }
            public void error(String message) { failed.incrementAndGet(); }
        };
        completion.await(callback); assertEquals(0, ended.get());
        completion.end(); completion.end();
        assertEquals(1, ended.get()); assertEquals(0, failed.get());
    }

    @Test public void sourceFailureNeverAcknowledgesSuccessfulStop() {
        CaptureCompletion completion = new CaptureCompletion();
        AtomicInteger ended = new AtomicInteger(), failed = new AtomicInteger();
        completion.await(new CaptureSource.StopCallback() {
            public void ended() { ended.incrementAndGet(); }
            public void error(String message) { assertEquals("route lost", message); failed.incrementAndGet(); }
        });
        completion.failure("route lost"); completion.end();
        assertEquals(0, ended.get()); assertEquals(1, failed.get());
    }

    @Test public void oldCaptureMustReleaseGlobalRouteBeforeNewCaptureStarts() {
        Object previous = new Object(), next = new Object();
        assertTrue(SystemCaptureLease.acquire(previous));
        try { assertFalse(SystemCaptureLease.acquire(next)); }
        finally { SystemCaptureLease.release(previous); }
        assertTrue(SystemCaptureLease.acquire(next));
        try {
            SystemCaptureLease.release(previous); // A delayed old finally cannot release the replacement.
            assertFalse(SystemCaptureLease.acquire(previous));
        } finally { SystemCaptureLease.release(next); }
    }
}
