package com.aidol.demo;

import android.media.AudioManager;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure call-watch algebra; the constants are compile-time inlined values. */
public class VoiceSessionServiceTest {
    @Test public void anOngoingCallAlwaysStopsTheHeadsetSession() {
        assertTrue(VoiceSessionService.shouldStopForCall(AudioManager.MODE_IN_CALL, false));
        assertTrue(VoiceSessionService.shouldStopForCall(AudioManager.MODE_IN_CALL, true));
    }

    @Test public void communicationModeOnlyStopsSessionsThatDoNotOwnTheRoute() {
        assertFalse(VoiceSessionService.shouldStopForCall(AudioManager.MODE_IN_COMMUNICATION, true));
        assertTrue(VoiceSessionService.shouldStopForCall(AudioManager.MODE_IN_COMMUNICATION, false));
    }

    @Test public void ringingStopsTheSessionEvenWhenTheRouteIsOwned() {
        assertTrue(VoiceSessionService.shouldStopForCall(AudioManager.MODE_RINGTONE, true));
        assertTrue(VoiceSessionService.shouldStopForCall(AudioManager.MODE_RINGTONE, false));
    }

    @Test public void normalModeNeverStopsTheSession() {
        assertFalse(VoiceSessionService.shouldStopForCall(AudioManager.MODE_NORMAL, false));
        assertFalse(VoiceSessionService.shouldStopForCall(AudioManager.MODE_NORMAL, true));
    }

    @Test public void theWakeLeaseRenewsAtHalfItsBoundedLife() {
        assertFalse(VoiceSessionService.wakeRenewDue(299_999L, 0));
        assertTrue(VoiceSessionService.wakeRenewDue(300_000L, 0));
        assertTrue(VoiceSessionService.wakeRenewDue(400_000L, 100_000L));
        assertTrue("Half the lease keeps a call continuously held",
            VoiceSessionService.WAKE_RENEW_AFTER_MILLIS * 2 <= VoiceSessionService.WAKE_LEASE_MILLIS);
    }
}
