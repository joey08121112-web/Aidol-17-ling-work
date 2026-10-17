package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceInteractionTest {
    @Test public void continuousCallLetsTheServerDistinguishAmbientSpeechFromQuestions() {
        assertEquals("continuous_call", VoiceInteraction.source(VoiceInteraction.CALL));
        assertEquals("direct_question", VoiceInteraction.source(VoiceInteraction.QUESTION));
        assertEquals("ambient_capture", VoiceInteraction.source(VoiceInteraction.NOTES));
        assertEquals("ambient_capture", VoiceInteraction.source(VoiceInteraction.SCHEDULED));
    }
    @Test public void recordAndScheduledChoicesCannotLeakIntoOtherBusinessModes() {
        for (String mode : new String[]{"life", "work", "commute"}) {
            assertTrue(VoiceInteraction.allowed(VoiceInteraction.QUESTION, mode));
            assertTrue(VoiceInteraction.allowed(VoiceInteraction.CALL, mode));
            assertEquals("work".equals(mode), VoiceInteraction.allowed(VoiceInteraction.NOTES, mode));
            assertEquals("life".equals(mode), VoiceInteraction.allowed(VoiceInteraction.SCHEDULED, mode));
        }
    }
    @Test public void callAndSingleQuestionAreDirectedAndAlwaysAutoEnd() {
        for (int mode : new int[]{VoiceInteraction.QUESTION, VoiceInteraction.CALL}) {
            assertTrue(VoiceInteraction.directed(mode));
            assertTrue(VoiceInteraction.endpoint(mode, false));
        }
        assertFalse(VoiceInteraction.repeats(VoiceInteraction.QUESTION));
        assertTrue(VoiceInteraction.repeats(VoiceInteraction.CALL));
    }
    @Test public void notesStayAmbientAndNeverStopAtShortPauses() {
        assertFalse(VoiceInteraction.directed(VoiceInteraction.NOTES));
        assertFalse(VoiceInteraction.endpoint(VoiceInteraction.NOTES, true));
        assertTrue(VoiceInteraction.repeats(VoiceInteraction.NOTES));
        assertFalse(VoiceInteraction.directed(VoiceInteraction.SCHEDULED));
    }
    @Test public void callRearmsPromptlyWhileScheduledListeningKeepsItsInterval() {
        assertEquals(180, VoiceInteraction.nextDelay(VoiceInteraction.CALL, 300));
        assertEquals(300000, VoiceInteraction.nextDelay(VoiceInteraction.SCHEDULED, 300));
    }
}
