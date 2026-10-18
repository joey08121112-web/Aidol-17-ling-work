package com.aidol.demo;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModeChangeRetryTest {
    @Test public void explicitBusyRejectionKeepsOriginalIntentWithinThreeRetries() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", "life", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        assertEquals(1000, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1500, backend, 7, true));
        assertEquals(1500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 2500, backend, 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 4000, backend, 7, true));
        assertEquals("work", retry.mode);
    }

    @Test public void unknownResultsAndOtherConflictsNeverAuthorizeAnotherMutation() {
        Object backend = new Object();
        for (int status : new int[] {0, 200, 401, 429, 500, 502, 503}) {
            ModeChangeRetry retry = new ModeChangeRetry("commute", "life", backend, 7, 1000);
            assertEquals(-1, retry.delayAfterFailure(status, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        }
        ModeChangeRetry retry = new ModeChangeRetry("commute", "life", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, "凭据已变更", 1000, backend, 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, null, 1000, backend, 7, true));
    }

    @Test public void backendSessionAndForegroundChangesPreventDeferredUpdates() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", "life", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, new Object(), 7, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 8, true));
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, false));
        assertFalse(retry.matches(new Object(), 7)); assertFalse(retry.matches(backend, 8));
    }

    @Test public void elapsedDeadlineStopsRetriesEvenIfAttemptBudgetRemains() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", "life", backend, 7, 1000);
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 4501, backend, 7, true));
    }

    @Test public void leavingForegroundPermanentlyDisarmsRemainingRetries() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", "life", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        retry.stopRetrying();
        assertEquals(-1, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1500, backend, 7, true));
        assertFalse(retry.canAttempt(1500, backend, 7, true));
        assertTrue(retry.matches(backend, 7)); // The one in-flight response may still complete normally.
    }

    @Test public void delayedHandlerCannotSubmitAfterTheRetryWindow() {
        Object backend = new Object();
        ModeChangeRetry retry = new ModeChangeRetry("work", "life", backend, 7, 1000);
        assertEquals(500, retry.delayAfterFailure(409, ModeChangeRetry.BUSY_DETAIL, 1000, backend, 7, true));
        assertFalse(retry.canAttempt(5001, backend, 7, true));
    }

    @Test public void optimisticTargetAndRollbackRewriteSettingsAndAgentMode() throws Exception {
        JSONObject state = new JSONObject().put("settings", new JSONObject().put("mode", "life").put("auto_mode", false).put("timezone", "Asia/Shanghai"))
            .put("agent", new JSONObject().put("mode", "life").put("reason", "用户手动指定"));
        ModeChangeRetry change = new ModeChangeRetry("work", "life", new Object(), 7, 1000);
        ModeChangeRetry.show(state, change.mode);
        assertEquals("work", state.getJSONObject("settings").getString("mode"));
        assertEquals("work", state.getJSONObject("agent").getString("mode"));
        assertEquals("Asia/Shanghai", state.getJSONObject("settings").getString("timezone"));
        ModeChangeRetry.show(state, change.previous);
        assertEquals("life", state.getJSONObject("settings").getString("mode"));
        assertEquals("life", state.getJSONObject("agent").getString("mode"));
        assertEquals("用户手动指定", state.getJSONObject("agent").getString("reason"));
    }

    @Test public void showCreatesMissingSnapshotSections() throws Exception {
        JSONObject state = new JSONObject();
        ModeChangeRetry.show(state, "commute");
        assertEquals("commute", state.getJSONObject("settings").getString("mode"));
        assertFalse(state.getJSONObject("settings").getBoolean("auto_mode"));
        assertEquals("commute", state.getJSONObject("agent").getString("mode"));
    }
}
