package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceCadenceTest {
    @Test public void returningToForegroundRefreshesImmediatelyThenEveryThreeMinutes() {
        MaintenanceCadence clock = new MaintenanceCadence();
        assertFalse(clock.claim(0));
        clock.enter(1000);
        assertTrue(clock.claim(1000)); clock.complete();
        assertFalse(clock.claim(180999));
        assertTrue(clock.claim(181000)); clock.complete();
        clock.leave(); assertFalse(clock.claim(900000));
        clock.enter(900001); assertTrue(clock.claim(900001));
    }

    @Test public void pageReturnDuringAnUpdateDoesNotQueueDuplicateAcquisition() {
        MaintenanceCadence clock = new MaintenanceCadence();
        clock.enter(0); assertTrue(clock.claim(0));
        assertFalse(clock.enter(5000));
        assertFalse(clock.claim(5000));
        clock.complete(); assertFalse(clock.claim(5000));
        assertTrue(clock.claim(180000));
    }

    @Test public void pageReturnAfterCompletionRequestsOneNewFix() {
        MaintenanceCadence clock = new MaintenanceCadence();
        clock.enter(0); assertTrue(clock.claim(0)); clock.complete();
        assertTrue(clock.enter(15000)); assertTrue(clock.claim(15000));
        clock.leave(); assertFalse(clock.claim(500000));
        clock.enter(500001); assertTrue(clock.claim(500001));
    }
}
