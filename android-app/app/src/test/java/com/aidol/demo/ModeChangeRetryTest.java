package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeChangeRetryTest {
    @Test public void explicitBusyRejectionKeepsOriginalIntentWithinThreeRetries() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        assertEquals(1000, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1500, backend, 7, true));
        assertEquals(1500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 2500, backend, 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 4000, backend, 7, true));
        assertEquals("work", retry.mode);
    }

    @Test public void unknownResultsAndOtherConflictsNeverAuthorizeAnotherMutation() {
        Object backend = new Object();
        for (int status : new int[] {0, 200, 401, 429, 500, 502, 503}) {
            ModeChangeRetry retry = new ModeChangeRetry("commute", backend, 7, 1000);
            assertEquals(-1, retry.delayAfterFailure(status, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        }
        ModeChangeRetry retry = new ModeChangeRetry("commute", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, "凭据已变更", 1000, backend, 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, null, 1000, backend, 7, true));
    }

    @Test public void backendSessionAndForegroundChangesPreventDeferredUpdates() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, new Object(), 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 8, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, false));
        assertFalse(retry.matches(new Object(), 7)); assertFalse(retry.matches(backend, 8));
    }

    @Test public void elapsedDeadlineStopsRetriesEvenIfAttemptBudgetRemains() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 4501, backend, 7, true));
    }

    @Test public void leavingForegroundPermanentlyDisarmsRemainingRetries() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        retry.stopRetrying();
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1500, backend, 7, true));
        assertFalse(retry.canAttempt(1500, backend, 7, true));
        assertTrue(retry.matches(backend, 7)); // The one in-flight response may still complete normally.
    }

    @Test public void delayedHandlerCannotSubmitAfterTheRetryWindow() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        assertFalse(retry.canAttempt(5001, backend, 7, true));
    }
}
