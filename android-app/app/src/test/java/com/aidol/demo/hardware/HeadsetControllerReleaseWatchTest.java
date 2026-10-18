package com.aidol.demo.hardware;

import com.aidol.demo.hardware.HeadsetController.ReleaseWatch;
import com.aidol.demo.hardware.HeadsetController.ReleaseWatch.Decision;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeadsetControllerReleaseWatchTest {
    private static Decision decide(boolean superseded, boolean released, long now, long deadline) {
        return ReleaseWatch.decide(superseded, released, now, deadline);
    }

    @Test public void aSupersededOperationAbandonsBeforeAnyOtherOutcome() {
        assertEquals(Decision.STALE, decide(true, true, 0, 0));
        assertEquals(Decision.STALE, decide(true, false, Long.MAX_VALUE, 0));
    }

    @Test public void aConfirmedReleaseWinsOverTheDeadline() {
        assertEquals(Decision.RELEASED, decide(false, true, Long.MAX_VALUE, 0));
        assertEquals(Decision.RELEASED, decide(false, true, 1000, 1000));
    }

    @Test public void theDeadlineIsInclusiveAndKeepsPollingBeforeIt() {
        assertEquals(Decision.RETRY, decide(false, false, 999, 1000));
        assertEquals(Decision.TIMEOUT, decide(false, false, 1000, 1000));
        assertEquals(Decision.TIMEOUT, decide(false, false, 1001, 1000));
    }

    @Test public void thePollIntervalStaysCoarse() {
        assertEquals(50, ReleaseWatch.POLL_MILLIS);
    }
}
