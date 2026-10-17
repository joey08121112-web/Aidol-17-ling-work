package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveCoverageTest {
    @Test public void segmentsWaitForLateFinalSentencesUnlessCaptureEnded() {
        assertFalse(LiveCoverage.settled(10_000, 14_999, false));
        assertTrue(LiveCoverage.settled(10_000, 15_000, false));
        assertTrue(LiveCoverage.settled(10_000, 10_001, true));
    }
    @Test public void onlyAnUnbrokenHealthySocketFromBeforeTheSegmentCoversIt() {
        assertTrue(LiveCoverage.covered(5_000, 4_000, true));
        assertTrue(LiveCoverage.covered(5_000, 5_000, true));
        assertFalse(LiveCoverage.covered(5_000, 5_001, true));       // Reconnected mid-segment: server recognises it.
        assertFalse(LiveCoverage.covered(5_000, 4_000, false));      // Failed now, or end not confirmed.
        assertFalse(LiveCoverage.covered(5_000, Long.MAX_VALUE, true));
    }
}
