package com.aidol.demo;

import com.aidol.demo.hardware.HeadsetController.ControlKind;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aidol.demo.HeadsetInteractionPolicy.Action.*;
import static com.aidol.demo.HeadsetInteractionPolicy.Gesture.*;

public class HeadsetStemControlsTest {
    private HeadsetInteractionPolicy.Context state(boolean busy) {
        return new HeadsetInteractionPolicy.Context(true, true, true, true, false,
            busy, true, true, false, "work", 8, false, false, true);
    }
    @Test public void stemHoldDoesNotEndMeetingAndDiscardsPreliminaryPinch() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, state(false));
        assertTrue(policy.hasPending());
        assertTrue(policy.event(HeadsetInteractionPolicy.gesture(ControlKind.PRESS_LONG),
            2, 1200, 1200, state(false)).actions.isEmpty());
        assertFalse(policy.hasPending());
        assertTrue(policy.flush(2000, state(false)).actions.isEmpty());
        assertEquals(Arrays.asList(HeadsetInteractionPolicy.Action.CANCEL), policy.event(
            HeadsetInteractionPolicy.gesture(ControlKind.LONG_PRESS), 3, 2100, 2100, state(false)).actions);
    }
    @Test public void doublePinchRetainsMeetingBookmarkAndIdleModeCycle() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(HeadsetInteractionPolicy.gesture(ControlKind.PRESS_DOUBLE), 1, 1000, 1000, state(false));
        assertEquals(Arrays.asList(MARK), policy.flush(1400, state(false)).actions);
        HeadsetInteractionPolicy.Context idle = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, false, false, false, "life");
        policy.event(HeadsetInteractionPolicy.gesture(ControlKind.PRESS_DOUBLE), 2, 2000, 2000, idle);
        HeadsetInteractionPolicy.Decision result = policy.flush(2400, idle);
        assertEquals(Arrays.asList(CHANGE_MODE), result.actions);
        assertEquals("work", result.mode);
    }
    @Test public void slidesDuringRecordingOrSyncOnlyAdjustVolumeAndPreserveBookmark() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, state(false));
        assertEquals(Arrays.asList(HeadsetInteractionPolicy.Action.VOLUME_UP), policy.event(
            HeadsetInteractionPolicy.gesture(ControlKind.SLIDE_FORWARD), 2, 1100, 1100, state(true)).actions);
        assertEquals(Arrays.asList(HeadsetInteractionPolicy.Action.VOLUME_DOWN), policy.event(
            HeadsetInteractionPolicy.gesture(ControlKind.SLIDE_BACKWARD), 3, 1300, 1300, state(false)).actions);
        assertEquals(Arrays.asList(MARK), policy.flush(1400, state(false)).actions);
    }
    @Test public void slidesRejectStaleDuplicateAndRapidReportsButAllowRepeatedSwipes() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        HeadsetInteractionPolicy.Gesture up = HeadsetInteractionPolicy.Gesture.VOLUME_UP;
        assertTrue(policy.event(up, 1, 1000, 2600, state(false)).actions.isEmpty());
        assertEquals(1, policy.event(up, 2, 3000, 3000, state(false)).actions.size());
        assertTrue(policy.event(up, 2, 3000, 3200, state(false)).actions.isEmpty());
        assertTrue(policy.event(up, 3, 3050, 3050, state(false)).actions.isEmpty());
        assertEquals(1, policy.event(up, 4, 3200, 3200, state(false)).actions.size());
        assertTrue(policy.event(up, 5, 3400, 3300, state(false)).actions.isEmpty());
        assertFalse(policy.hasPending());
    }
    @Test public void slidesRespectAuthorizationDisconnectWearAndPhoneCalls() {
        for (int blocked = 0; blocked < 6; blocked++) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            HeadsetInteractionPolicy.Context context = new HeadsetInteractionPolicy.Context(
                blocked != 0, blocked != 1, blocked != 2, blocked != 3, blocked == 4,
                false, true, true, blocked == 5, "work");
            assertTrue(policy.event(HeadsetInteractionPolicy.Gesture.VOLUME_UP, 1, 1000, 1000, context).actions.isEmpty());
            assertTrue(policy.event(HeadsetInteractionPolicy.Gesture.VOLUME_DOWN, 2, 1200, 1200, context).actions.isEmpty());
            assertFalse(policy.hasPending());
        }
    }
}
