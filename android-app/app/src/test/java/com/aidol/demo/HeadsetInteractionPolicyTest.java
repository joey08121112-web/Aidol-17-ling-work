package com.aidol.demo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aidol.demo.HeadsetInteractionPolicy.Action.*;
import static com.aidol.demo.HeadsetInteractionPolicy.Gesture.TOGGLE;
import static com.aidol.demo.HeadsetInteractionPolicy.Gesture.NEXT_MODE;
import static com.aidol.demo.HeadsetInteractionPolicy.Gesture.IGNORE;

public class HeadsetInteractionPolicyTest {
    private static HeadsetInteractionPolicy.Context context(boolean foreground, boolean controls, boolean login, boolean ready,
            boolean call, boolean busy, boolean active, boolean recording, boolean notWorn, String mode) {
        return new HeadsetInteractionPolicy.Context(foreground, controls, login, ready, call, busy, active, recording, notWorn, mode);
    }
    private static HeadsetInteractionPolicy.Context idle() {
        return context(true, true, true, true, false, false, false, false, false, "life");
    }
    private static HeadsetInteractionPolicy.Context recording() {
        return context(true, true, true, true, false, false, true, true, false, "life");
    }
    private static HeadsetInteractionPolicy.Device device(boolean ready, boolean call, Boolean left, Boolean right, Integer battery) {
        return new HeadsetInteractionPolicy.Device(ready, call, left, right, battery, null);
    }

    @Test public void singleTapStartsCallAndDuplicateCallbacksCannotImmediatelySubmitIt() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        assertTrue(policy.event(TOGGLE, 1, 1000, 1000, idle()).actions.isEmpty());
        assertTrue(policy.event(TOGGLE, 1, 1000, 1001, idle()).actions.isEmpty());
        assertTrue(policy.flush(1399, idle()).actions.isEmpty());
        assertEquals(Arrays.asList(START), policy.flush(1400, idle()).actions);
        assertTrue(policy.event(TOGGLE, 2, 1500, 1500, recording()).actions.isEmpty());
        assertTrue(policy.flush(1900, recording()).actions.isEmpty());
        policy.event(TOGGLE, 3, 2100, 2100, recording());
        assertEquals(Arrays.asList(STOP_AND_SEND), policy.flush(2500, recording()).actions);
        assertTrue(policy.flush(2600, recording()).actions.isEmpty());
    }

    @Test public void doubleTapSupersedesPendingStopAndDefersModeWithoutDiscardingRecording() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, recording());
        assertTrue(policy.event(NEXT_MODE, 2, 1200, 1200, recording()).actions.isEmpty());
        assertTrue(policy.flush(1400, recording()).actions.isEmpty());
        HeadsetInteractionPolicy.Decision decision = policy.flush(1600, recording());
        assertEquals(Arrays.asList(DEFER_MODE), decision.actions);
        assertEquals("work", decision.mode);
        assertFalse(policy.hasPending()); assertTrue(policy.flush(1600, recording()).actions.isEmpty());
    }

    @Test public void modeCycleNeverStartsMicrophone() {
        String[] current = {"life", "work", "commute"}, next = {"work", "commute", "life"};
        for (int i = 0; i < current.length; i++) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            HeadsetInteractionPolicy.Context state = context(true, true, true, true, false, false, false, false, false, current[i]);
            assertTrue(policy.event(NEXT_MODE, 1, 1000, 1000, state).actions.isEmpty());
            HeadsetInteractionPolicy.Decision decision = policy.flush(1400, state);
            assertEquals(Arrays.asList(CHANGE_MODE), decision.actions); assertEquals(next[i], decision.mode);
            assertTrue(policy.flush(1500, state).actions.isEmpty());
        }
    }

    @Test public void doubleTapDiscardsPendingStartInsteadOfOpeningMicrophone() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, idle());
        assertTrue(policy.event(NEXT_MODE, 2, 1200, 1200, idle()).actions.isEmpty());
        assertTrue(policy.flush(1400, idle()).actions.isEmpty());
        assertEquals(Arrays.asList(CHANGE_MODE), policy.flush(1600, idle()).actions);
        assertTrue(policy.flush(2000, idle()).actions.isEmpty());
    }

    @Test public void tripleTapDiscardsPreliminarySingleAndDoubleWithoutAnyProductAction() {
        for (HeadsetInteractionPolicy.Context context : new HeadsetInteractionPolicy.Context[] {idle(), recording()}) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            policy.event(TOGGLE, 1, 1000, 1000, context);
            policy.event(NEXT_MODE, 2, 1200, 1200, context);
            assertTrue(policy.event(HeadsetInteractionPolicy.Gesture.DISCARD_COMBINATION, 3, 1300, 1300, context).actions.isEmpty());
            assertFalse(policy.hasPending());
            assertTrue(policy.flush(2000, context).actions.isEmpty());
        }
    }

    @Test public void deferredDoubleKeepsOriginalModeIntentAndRejectsChangedGenerationOrBusyState() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, idle());
        HeadsetInteractionPolicy.Context changedMode = context(true, true, true, true, false, false, false, false, false, "commute");
        assertEquals("work", policy.flush(1400, changedMode).mode);

        policy = new HeadsetInteractionPolicy(); policy.event(NEXT_MODE, 1, 1000, 1000, idle());
        HeadsetInteractionPolicy.Context replacement = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, false, false, false, "life", 1);
        assertTrue(policy.flush(1400, replacement).actions.isEmpty());

        policy = new HeadsetInteractionPolicy(); policy.event(NEXT_MODE, 1, 1000, 1000, idle());
        HeadsetInteractionPolicy.Context busy = context(true, true, true, true, false, true, false, false, false, "life");
        assertTrue(policy.flush(1400, busy).actions.isEmpty());
        assertTrue(policy.flush(1600, idle()).actions.isEmpty());
    }

    @Test public void doubleTapDuringSubmittedBusinessDefersWithoutCancellingIt() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        HeadsetInteractionPolicy.Context processing = context(true, true, true, true, false, false, true, false, false, "life");
        HeadsetInteractionPolicy.Decision decision = policy.event(NEXT_MODE, 1, 1000, 1000, processing);
        assertTrue(decision.actions.isEmpty()); assertNull(decision.mode);
        assertTrue(policy.hasPending());
        assertEquals(Arrays.asList(DEFER_MODE), policy.flush(1400, processing).actions);
    }

    private static HeadsetInteractionPolicy.Context modeCue(String mode, long generation) {
        return new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, true, false, false, mode, generation, true);
    }

    @Test public void repeatedDoubleTapsCanReplaceEachModeAnnouncementWithoutRecording() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        String[] modes = {"life", "work", "commute", "life"};
        for (int i = 0; i < modes.length - 1; i++) {
            HeadsetInteractionPolicy.Context cue = modeCue(modes[i], i * 2);
            long now = 1000 + i * 1000;
            assertEquals("", policy.event(NEXT_MODE, i + 1, now, now, cue).message);
            HeadsetInteractionPolicy.Decision decision = policy.flush(now + 400, cue);
            assertEquals(Arrays.asList(CHANGE_MODE), decision.actions);
            assertEquals(modes[i + 1], decision.mode);
        }
    }

    @Test public void naturalCueCompletionPreservesSettlingModeGestureOnly() {
        HeadsetInteractionPolicy.Context finished = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, false, false, false, "work", 8);
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, modeCue("work", 7));
        policy.modeCueFinished(7);
        HeadsetInteractionPolicy.Decision decision = policy.flush(1400, finished);
        assertEquals(Arrays.asList(CHANGE_MODE), decision.actions);
        assertEquals("commute", decision.mode);

        policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, modeCue("work", 7));
        policy.modeCueFinished(7);
        assertTrue(policy.flush(1400, finished).actions.isEmpty());
    }

    @Test public void cueGestureCannotAffectReplacementSessionOrSurviveCancellation() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, modeCue("work", 7));
        policy.modeCueFinished(6);
        assertTrue(policy.flush(1400, modeCue("life", 8)).actions.isEmpty());

        policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, modeCue("work", 7));
        policy.clearPending();
        policy.modeCueFinished(7);
        assertTrue(policy.flush(1400, modeCue("life", 8)).actions.isEmpty());
    }

    @Test public void longPressCancelsImmediatelyAndDropsDelayedToggle() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, recording());
        assertEquals(Arrays.asList(CANCEL), policy.event(HeadsetInteractionPolicy.Gesture.CANCEL, 2, 1100, 1100, recording()).actions);
        assertTrue(policy.flush(2000, idle()).actions.isEmpty());
    }

    @Test public void backgroundCallPermissionAndBusyStatesCannotOpenMicrophone() {
        HeadsetInteractionPolicy.Context[] blocked = {
            context(false, true, true, true, false, false, false, false, false, "life"),
            context(true, false, true, true, false, false, false, false, false, "life"),
            context(true, true, false, true, false, false, false, false, false, "life"),
            context(true, true, true, false, false, false, false, false, false, "life"),
            context(true, true, true, true, true, false, false, false, false, "life"),
            context(true, true, true, true, false, true, false, false, false, "life"),
            context(true, true, true, true, false, false, false, false, true, "life")
        };
        for (HeadsetInteractionPolicy.Context state : blocked) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            assertTrue(policy.event(TOGGLE, 1, 1000, 1000, state).actions.isEmpty());
            assertTrue(policy.flush(1400, state).actions.isEmpty());
            assertTrue(policy.flush(1500, idle()).actions.isEmpty());
        }
    }

    @Test public void delayedGestureRechecksForegroundAndCallStateAtExecution() {
        for (HeadsetInteractionPolicy.Context state : new HeadsetInteractionPolicy.Context[] {
                context(false, true, true, true, false, false, false, false, false, "life"),
                context(true, true, true, true, true, false, false, false, false, "life")}) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            policy.event(TOGGLE, 1, 1000, 1000, idle());
            assertTrue(policy.flush(1400, state).actions.isEmpty());
            assertTrue(policy.flush(2000, idle()).actions.isEmpty());
        }
    }

    @Test public void processedTurnCannotBeSubmittedTwiceAndUnmappedOrStaleGesturesDoNothing() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        HeadsetInteractionPolicy.Context processing = context(true, true, true, true, false, false, true, false, false, "life");
        assertTrue(policy.event(TOGGLE, 1, 1000, 1000, processing).actions.isEmpty());
        assertEquals(Arrays.asList(STOP_AND_SEND), policy.flush(1400, processing).actions);
        assertTrue(policy.event(IGNORE, 2, 2000, 2000, idle()).actions.isEmpty());
        assertTrue(policy.event(TOGGLE, 3, 2000, 4000, idle()).actions.isEmpty());
        assertTrue(policy.flush(4500, idle()).actions.isEmpty());
    }

    @Test public void singleTapDuringProcessingCannotBecomeStartWhenResponseFinishes() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        HeadsetInteractionPolicy.Context processing = context(true, true, true, true, false, false, true, false, false, "life");
        policy.event(TOGGLE, 1, 1000, 1000, processing);
        assertTrue(policy.hasPending());
        assertTrue(policy.flush(1400, idle()).actions.isEmpty());
    }

    private static HeadsetInteractionPolicy.Context replying() {
        return new HeadsetInteractionPolicy.Context(true, true, true, true, false, false,
            true, false, false, "life", 0, false, true);
    }

    @Test public void singleTapEndsListeningThinkingAndSpeaking() {
        HeadsetInteractionPolicy.Context processing = context(true, true, true, true, false, false, true, false, false, "life");
        for (HeadsetInteractionPolicy.Context state : new HeadsetInteractionPolicy.Context[]{recording(), processing, replying()}) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            policy.event(TOGGLE, 1, 1000, 1000, state);
            assertEquals(Arrays.asList(STOP_AND_SEND), policy.flush(1400, state).actions);
            assertTrue(policy.flush(1500, idle()).actions.isEmpty());
        }
    }

    @Test public void singleTapCanReplaceModeAnnouncementWithExplicitCallStart() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, modeCue("work", 7));
        assertEquals(Arrays.asList(START), policy.flush(1400, modeCue("work", 7)).actions);
    }

    @Test public void stopAndModeIntentsSurviveNaturalTurnFinish() {
        HeadsetInteractionPolicy.Context finished = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, true, false, false, "life", 1);
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, recording());
        policy.turnFinished(0);
        HeadsetInteractionPolicy.Decision decision = policy.flush(1400, finished);
        assertEquals(Arrays.asList(DEFER_MODE), decision.actions);
        assertEquals("work", decision.mode);
        policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, recording());
        policy.turnFinished(0);
        assertEquals(Arrays.asList(STOP_AND_SEND), policy.flush(1400, finished).actions);
    }

    @Test public void longPressDuringSettingsRequestOrBetweenTurnsAlwaysHangsUp() {
        for (boolean busy : new boolean[]{true, false}) {
            HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
            HeadsetInteractionPolicy.Context state = context(true, true, true, true, false, busy, true, false, false, "work");
            assertEquals(Arrays.asList(CANCEL), policy.event(HeadsetInteractionPolicy.Gesture.CANCEL, 1, 1000, 1000, state).actions);
            assertFalse(policy.hasPending());
        }
    }

    @Test public void laterDoubleUsesAlreadyQueuedModeAsCycleBase() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, recording());
        assertEquals("work", policy.flush(1400, recording()).mode);
        HeadsetInteractionPolicy.Context queuedWork = context(true, true, true, true, false, false, true, true, false, "work");
        policy.event(NEXT_MODE, 2, 2000, 2000, queuedWork);
        assertEquals("commute", policy.flush(2400, queuedWork).mode);
        assertTrue(policy.event(HeadsetInteractionPolicy.Gesture.CANCEL, 3, 2500, 2500, queuedWork).actions.contains(CANCEL));
        assertFalse(policy.hasPending());
    }

    @Test public void pendingStopCannotTurnIntoStartWhenRecordingFinishesNaturally() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, recording());
        assertTrue(policy.flush(1400, idle()).actions.isEmpty());
        assertTrue(policy.flush(1500, idle()).actions.isEmpty());
    }

    @Test public void delayedGestureCannotAffectReplacementSessionEvenInSameRecordingState() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, recording());
        HeadsetInteractionPolicy.Context replacement = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, true, true, false, "life", 1);
        assertTrue(policy.flush(1400, replacement).actions.isEmpty());
    }

    @Test public void physicalDeviceLossDoesNotCancelTextOnlyBusinessRequest() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.device(device(true, false, true, true, 80), false);
        assertNull(policy.device(device(false, false, null, null, null), false).stopReason);
        assertNull(policy.device(device(true, false, false, false, 80), false).stopReason);
    }

    @Test public void disconnectCancelsPendingStartAndReconnectOnlyRestoresReadiness() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.device(device(true, false, true, true, 80), false);
        policy.event(TOGGLE, 1, 1000, 1000, idle());
        assertNotNull(policy.device(device(false, false, null, null, null), true).stopReason);
        assertFalse(policy.hasPending());
        assertNull(policy.device(device(true, false, true, true, 80), false).stopReason);
        assertTrue(policy.flush(1500, idle()).actions.isEmpty());
    }

    @Test public void incomingCallPausesAndCallEndDoesNotResume() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.device(device(true, false, true, true, 80), true);
        assertNotNull(policy.device(device(true, true, true, true, 80), true).stopReason);
        assertNull(policy.device(device(true, false, true, true, 80), false).stopReason);
        assertTrue(policy.flush(1500, idle()).actions.isEmpty());
    }

    @Test public void onlyBothExplicitlyRemovedAfterKnownWearPauseAndWearingAgainDoesNotStart() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        assertNull(policy.device(device(true, false, null, null, null), true).stopReason);
        assertNull(policy.device(device(true, false, false, null, null), true).stopReason);
        assertNull(policy.device(device(true, false, true, true, null), true).stopReason);
        assertNull(policy.device(device(true, false, false, true, null), true).stopReason);
        assertNotNull(policy.device(device(true, false, false, false, null), true).stopReason);
        HeadsetInteractionPolicy.DeviceUpdate worn = policy.device(device(true, false, true, true, null), false);
        assertNull(worn.stopReason); assertEquals(1, worn.notices.size());
        assertTrue(policy.flush(1500, idle()).actions.isEmpty());
    }

    @Test public void unknownWearDoesNotBecomeAFalseRemoval() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.device(device(true, false, true, true, 80), true);
        assertNull(policy.device(device(true, false, null, false, 80), true).stopReason);
        assertNull(policy.device(device(true, false, false, null, 80), true).stopReason);
    }

    @Test public void knownRemovalStopsAnActiveTurnEvenAfterUnknownWearState() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.device(device(true, false, true, true, 80), true);
        assertNull(policy.device(device(true, false, null, null, 80), true).stopReason);
        assertNotNull(policy.device(device(true, false, false, false, 80), true).stopReason);
    }

    @Test public void doubleTapThatSettlesAfterVadStopStillPreservesTheSubmittedTurn() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, recording());
        HeadsetInteractionPolicy.Context processing = context(true, true, true, true, false, false, true, false, false, "life");
        assertEquals(Arrays.asList(DEFER_MODE), policy.flush(1400, processing).actions);
    }

    @Test public void batteryBandsAlertOnceAndRequireRecoveryAboveTwentyFiveBeforeRearming() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        assertEquals(1, policy.device(device(true, false, null, null, 20), false).notices.size());
        for (Integer value : new Integer[] {19, 20, null, 255})
            assertTrue(policy.device(device(true, false, null, null, value), false).notices.isEmpty());
        assertEquals(1, policy.device(device(true, false, null, null, 10), false).notices.size());
        for (Integer value : new Integer[] {9, 11, 20, 25})
            assertTrue(policy.device(device(true, false, null, null, value), false).notices.isEmpty());
        assertTrue(policy.device(device(true, false, null, null, 26), false).notices.isEmpty());
        assertEquals(1, policy.device(device(true, false, null, null, 20), false).notices.size());
    }

    private static HeadsetInteractionPolicy.Context workMeeting(boolean capturing, long generation) {
        return new HeadsetInteractionPolicy.Context(true, true, true, true, false, false, true, true, false, "work",
            generation, false, false, capturing);
    }

    @Test public void doubleTapWhileWorkMeetingRecordsMarksInsteadOfSwitchingMode() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(TOGGLE, 1, 1000, 1000, workMeeting(true, 3));      // Would stop if left alone.
        assertTrue(policy.event(NEXT_MODE, 2, 1200, 1200, workMeeting(true, 3)).actions.isEmpty());
        assertTrue(policy.flush(1599, workMeeting(true, 3)).actions.isEmpty());
        HeadsetInteractionPolicy.Decision decision = policy.flush(1600, workMeeting(true, 3));
        assertEquals(Arrays.asList(MARK), decision.actions);
        assertNull(decision.mode);
        assertFalse(policy.hasPending());
        // A second mark is allowed once the debounce window has passed.
        policy.event(NEXT_MODE, 3, 2500, 2500, workMeeting(true, 3));
        assertEquals(Arrays.asList(MARK), policy.flush(2900, workMeeting(true, 3)).actions);
    }

    @Test public void pendingMarkIsDroppedOnceSavingBeginsOrByTripleTapAndIdleWorkStillSwitchesMode() {
        HeadsetInteractionPolicy policy = new HeadsetInteractionPolicy();
        policy.event(NEXT_MODE, 1, 1000, 1000, workMeeting(true, 3));
        assertTrue(policy.flush(1400, workMeeting(false, 3)).actions.isEmpty());   // Saving: neither mark nor switch.
        policy.event(NEXT_MODE, 2, 2000, 2000, workMeeting(true, 3));
        policy.event(HeadsetInteractionPolicy.Gesture.DISCARD_COMBINATION, 3, 2100, 2100, workMeeting(true, 3));
        assertTrue(policy.flush(2400, workMeeting(true, 3)).actions.isEmpty());
        HeadsetInteractionPolicy.Context idleWork = new HeadsetInteractionPolicy.Context(
            true, true, true, true, false, false, false, false, false, "work", 3, false, false, false);
        policy.event(NEXT_MODE, 4, 3000, 3000, idleWork);
        HeadsetInteractionPolicy.Decision decision = policy.flush(3400, idleWork);
        assertEquals(Arrays.asList(CHANGE_MODE), decision.actions);
        assertEquals("commute", decision.mode);
    }
    @Test public void workCallCaptureKeepsCallButStillStopsForDisconnectAndRemoval() {
        HeadsetInteractionPolicy p = new HeadsetInteractionPolicy();
        assertNull(p.device(device(true, true, true, false, 80), true, true).stopReason);
        assertNotNull(p.device(device(true, true, false, false, 80), true, true).stopReason);
        assertNotNull(p.device(device(false, true, true, true, 80), true, true).stopReason);
        assertNotNull(p.device(device(true, true, true, true, 80), true, false).stopReason);
    }
}
