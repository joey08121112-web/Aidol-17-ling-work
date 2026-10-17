package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureTurnTest {
    @Test public void asynchronousStopPreservesLastSentenceAndProducesOneSnapshot() throws Exception {
        CaptureTurn turn = new CaptureTurn(60);
        assertTrue(turn.recording(1000));
        turn.append(new byte[] {1, 2}, 1100);
        assertTrue(turn.requestStop(1200));
        assertFalse(turn.requestStop(1201));
        assertEquals(CaptureTurn.Phase.STOP_REQUESTED, turn.phase());
        assertTrue(turn.append(new byte[] {3, 4, 5, 6}, 1300));
        assertTrue(turn.drained(1400));
        assertEquals(CaptureTurn.Phase.DRAINING, turn.phase());
        assertFalse(turn.append(new byte[] {7, 8}, 1401));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, turn.finish());
        assertFalse(turn.drained(1402));
        assertThrows(IOException.class, turn::finish);
    }

    @Test public void requestsStopEarlyEnoughToIncludeTailInSixtySecondBudget() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(1000);
        assertFalse(turn.shouldStop(57999));
        assertTrue(turn.shouldStop(58000));
        turn.requestStop(58000);
        assertEquals(3000, turn.remainingStopMillis(58000));
        turn.append(new byte[] {1, 2}, 60999);
        assertTrue(turn.drained(61000));
        assertArrayEquals(new byte[] {1, 2}, turn.finish());
    }

    @Test public void tailExceedingByteLimitFailsWholeTurnInsteadOfTruncating() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0);
        turn.append(new byte[CaptureTurn.MAX_BYTES - 2], 56000);
        assertTrue(turn.shouldStop(56000)); turn.requestStop(56000);
        turn.append(new byte[2], 57000);
        assertThrows(IOException.class, () -> turn.append(new byte[2], 57001));
        assertEquals(CaptureTurn.Phase.FAILED, turn.phase()); assertEquals(0, turn.size());
        assertFalse(turn.drained(58000)); assertThrows(IOException.class, turn::finish);
    }

    @Test public void shortenedUserLimitIncludesStopBudgetToo() throws Exception {
        CaptureTurn turn = new CaptureTurn(5); turn.recording(0);
        assertTrue(turn.shouldStop(2000)); turn.requestStop(2000);
        assertThrows(IOException.class, () -> turn.drained(5001));
        assertEquals(CaptureTurn.Phase.FAILED, turn.phase());
    }

    @Test public void delayedStopCannotExtendTotalSixtySecondCap() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0); turn.requestStop(59000);
        assertEquals(1000, turn.remainingStopMillis(59000));
        assertThrows(IOException.class, () -> turn.append(new byte[2], 60001));
        assertEquals(0, turn.size());
    }

    @Test public void stopConfirmationTimeoutFailsEvenBeforeTotalLimit() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0);
        turn.append(new byte[3200], 100); turn.requestStop(200);
        assertThrows(IOException.class, () -> turn.drained(3201));
        assertEquals(0, turn.size()); assertThrows(IOException.class, turn::finish);
    }

    @Test public void timeoutThenLateIdleNeverBecomesSuccessfulRecording() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0);
        turn.append(new byte[3200], 100); turn.requestStop(200); turn.fail("stop timeout");
        assertFalse(turn.drained(400)); assertFalse(turn.append(new byte[3200], 401));
        assertThrows(IOException.class, turn::finish);
    }

    @Test public void cancellationRejectsDelayedFramesAndCompletionFromAnotherThread() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0); turn.append(new byte[3200], 100);
        turn.requestStop(200);
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread late = new Thread(() -> {
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
                assertFalse(turn.append(new byte[3200], 300)); assertFalse(turn.drained(400));
            } catch (Throwable e) { error.set(e); } finally { finished.countDown(); }
        });
        late.start(); turn.cancel(); release.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        assertEquals(CaptureTurn.Phase.CANCELLED, turn.phase()); assertEquals(0, turn.size());
        assertFalse(turn.recording(500)); assertThrows(IOException.class, turn::finish);
    }

    @Test public void malformedPcmDoesNotLeavePartialAudioEligibleForUpload() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0); turn.append(new byte[3200], 100);
        assertThrows(IOException.class, () -> turn.append(new byte[] {1}, 101));
        assertEquals(0, turn.size()); assertThrows(IOException.class, turn::finish);
    }

    @Test public void modeChangeCanAtomicallyDiscardOnlyLiveRecording() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0); turn.append(new byte[3200], 100);
        assertTrue(turn.cancelRecording());
        assertFalse(turn.requestStop(200)); assertFalse(turn.drained(300));
        assertEquals(CaptureTurn.Phase.CANCELLED, turn.phase()); assertEquals(0, turn.size());
        assertThrows(IOException.class, turn::finish);
    }

    @Test public void modeChangeCannotCancelAfterVadOrTimeoutAlreadyStartedStopping() throws Exception {
        CaptureTurn turn = new CaptureTurn(60); turn.recording(0); turn.append(new byte[3200], 100);
        assertTrue(turn.requestStop(200));
        assertFalse(turn.cancelRecording());
        assertTrue(turn.drained(300)); assertFalse(turn.cancelRecording());
        assertEquals(3200, turn.finish().length); assertFalse(turn.cancelRecording());
        assertEquals(CaptureTurn.Phase.ENDED, turn.phase());
    }

    @Test public void racingStopAndModeChangeHaveExactlyOneWinner() throws Exception {
        for (int i = 0; i < 50; i++) {
            CaptureTurn turn = new CaptureTurn(60); turn.recording(0);
            CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
            AtomicReference<Boolean> stopped = new AtomicReference<>(), cancelled = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread stop = new Thread(() -> {
                try { ready.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); stopped.set(turn.requestStop(100)); }
                catch (Throwable failure) { error.set(failure); }
            });
            Thread cancel = new Thread(() -> {
                try { ready.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); cancelled.set(turn.cancelRecording()); }
                catch (Throwable failure) { error.set(failure); }
            });
            stop.start(); cancel.start(); assertTrue(ready.await(2, TimeUnit.SECONDS)); release.countDown();
            stop.join(2000); cancel.join(2000);
            if (error.get() != null) throw new AssertionError(error.get());
            assertNotNull(stopped.get()); assertNotNull(cancelled.get());
            assertNotEquals(stopped.get(), cancelled.get());
            assertEquals(stopped.get() ? CaptureTurn.Phase.STOP_REQUESTED : CaptureTurn.Phase.CANCELLED, turn.phase());
        }
    }
}
