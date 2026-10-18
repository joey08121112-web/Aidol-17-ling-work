package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkVisualPhaseTest {
    @Test public void backgroundUploadsNeverOverrideRecordingOrPause() {
        assertNull(WorkVisualPhase.afterRecording("recording", "pending", "pending"));
    }
    @Test public void savedRecordingDoesNotClaimMinutesAreReady() {
        assertEquals(CompanionPhase.SUMMARIZING, WorkVisualPhase.afterRecording("saved", "pending", "waiting"));
        assertEquals(CompanionPhase.SUMMARIZING, WorkVisualPhase.afterRecording("saved", "ready", "pending"));
        assertEquals(CompanionPhase.COMPLETE, WorkVisualPhase.afterRecording("saved", "ready", "ready"));
    }
    @Test public void partialAndFailedResultsNeverLookLikeSuccess() {
        assertEquals(CompanionPhase.ERROR, WorkVisualPhase.afterRecording("partial", "ready", "ready"));
        assertEquals(CompanionPhase.ERROR, WorkVisualPhase.afterRecording("empty", "", "unavailable"));
        assertEquals(CompanionPhase.ERROR, WorkVisualPhase.afterRecording("saved", "pending", "failed"));
        assertEquals(CompanionPhase.ERROR, WorkVisualPhase.afterRecording("saved", "ready", "fallback"));
        assertNull(WorkVisualPhase.afterRecording("", "", ""));
    }
}
