package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionVisualPolicyTest {
    @Test public void workingStagesAlwaysStayAwakeAndQuiet() {
        for (CompanionPhase phase : CompanionPhase.values()) {
            if (phase == CompanionPhase.RESTING) continue;
            assertEquals(phase.name(), CompanionVisualPolicy.Asset.LIFE, CompanionVisualPolicy.asset("work", phase));
            assertFalse(phase.name(), CompanionVisualPolicy.playsVideo("work", phase, true));
        }
    }

    @Test public void onlyExplicitRestUsesTheClosingClipAndItNeverLoops() {
        for (String mode : new String[]{"life", "commute", "work"}) {
            assertEquals(CompanionVisualPolicy.Asset.REST, CompanionVisualPolicy.asset(mode, CompanionPhase.RESTING));
            assertTrue(CompanionVisualPolicy.playsVideo(mode, CompanionPhase.RESTING, true));
            for (CompanionPhase phase : CompanionPhase.values()) {
                if (phase == CompanionPhase.RESTING) continue;
                assertNotEquals(CompanionVisualPolicy.Asset.REST, CompanionVisualPolicy.asset(mode, phase));
            }
        }
        assertFalse(CompanionVisualPolicy.loops(CompanionPhase.RESTING));
        assertEquals(CompanionVisualPolicy.Asset.COMMUTE, CompanionVisualPolicy.asset("commute", CompanionPhase.LISTENING));
    }

    @Test public void rebuildAndResumeUseElapsedClosingTimeInsteadOfRestarting() {
        long started = 10_000;
        assertEquals(0, CompanionVisualPolicy.restPosition(started, started));
        assertEquals(2400, CompanionVisualPolicy.restPosition(started, started + 2400));
        assertEquals(CompanionVisualPolicy.REST_DURATION_MILLIS,
            CompanionVisualPolicy.restPosition(started, started + 90_000));
        assertEquals(0, CompanionVisualPolicy.restPosition(started, started - 1));
    }

    @Test public void reducedMotionIsStaticInEveryStageAndMode() {
        for (String mode : new String[]{"life", "commute", "work"}) {
            for (CompanionPhase phase : CompanionPhase.values())
                assertFalse(mode + ":" + phase, CompanionVisualPolicy.playsVideo(mode, phase, false));
        }
    }

    @Test public void endingPausedAndErrorNeverLookLikeActiveVideoOrListenToMeter() {
        for (CompanionPhase phase : new CompanionPhase[]{CompanionPhase.STOPPING, CompanionPhase.PAUSED, CompanionPhase.ERROR}) {
            assertFalse(CompanionVisualPolicy.playsVideo("life", phase, true));
            assertFalse(CompanionVisualPolicy.playsVideo("commute", phase, true));
            assertFalse(CompanionVisualPolicy.inputMeter(phase));
            assertFalse(CompanionVisualPolicy.outputMeter(phase));
        }
        assertEquals("正在结束", CompanionVisualPolicy.label("work", CompanionPhase.STOPPING));
    }

    @Test public void stageChoosesTheActualAudioDirectionAndClampsMalformedLevels() {
        assertTrue(CompanionVisualPolicy.inputMeter(CompanionPhase.LISTENING));
        assertTrue(CompanionVisualPolicy.inputMeter(CompanionPhase.RECORDING));
        assertFalse(CompanionVisualPolicy.inputMeter(CompanionPhase.SPEAKING));
        assertTrue(CompanionVisualPolicy.outputMeter(CompanionPhase.SPEAKING));
        assertFalse(CompanionVisualPolicy.outputMeter(CompanionPhase.LISTENING));
        assertEquals(0, CompanionVisualPolicy.level(Float.NaN), 0);
        assertEquals(0, CompanionVisualPolicy.level(Float.POSITIVE_INFINITY), 0);
        assertEquals(0, CompanionVisualPolicy.level(-.2f), 0);
        assertEquals(1, CompanionVisualPolicy.level(2), 0);
        assertEquals(.35f, CompanionVisualPolicy.level(.35f), 0);
    }
}
