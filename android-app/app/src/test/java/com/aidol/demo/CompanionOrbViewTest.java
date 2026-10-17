package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionOrbViewTest {
    @Test public void fullFrameRateWhileTheLevelRecentlyMoved() {
        assertEquals(CompanionOrbView.ACTIVE_FRAME_MILLIS, CompanionOrbView.frameDelayMillis(0));
        assertEquals(CompanionOrbView.ACTIVE_FRAME_MILLIS,
            CompanionOrbView.frameDelayMillis(CompanionOrbView.LEVEL_HOLD_MILLIS - 1));
    }

    @Test public void idleSilenceDropsToACoarseFrameRate() {
        assertEquals(CompanionOrbView.IDLE_FRAME_MILLIS,
            CompanionOrbView.frameDelayMillis(CompanionOrbView.LEVEL_HOLD_MILLIS));
        assertEquals(CompanionOrbView.IDLE_FRAME_MILLIS, CompanionOrbView.frameDelayMillis(Long.MAX_VALUE / 2));
    }

    @Test public void idleRateStillSparesEnoughFramesToNoticeANewLevel() {
        assertTrue("Idle must still redraw often enough to sample the meter level",
            CompanionOrbView.IDLE_FRAME_MILLIS < 1000);
        assertTrue("Idle must be a real cut from the active rate",
            CompanionOrbView.IDLE_FRAME_MILLIS > CompanionOrbView.ACTIVE_FRAME_MILLIS * 3);
        assertTrue("Hold must outlive the 300 ms meter cadence",
            CompanionOrbView.LEVEL_HOLD_MILLIS >= 1500);
    }
}
