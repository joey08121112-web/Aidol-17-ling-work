package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class FeedbackThrottleTest {
    @Test public void repeatedTapsDoNotExtendSuppressionForever() {
        FeedbackThrottle gate = new FeedbackThrottle();
        assertTrue(gate.accept("copied", 0));
        for (long time = 100; time < 4000; time += 100) assertFalse(gate.accept("copied", time));
        assertTrue(gate.accept("copied", 4000));
    }

    @Test public void DifferentActionsStillHaveAMinimumQuietInterval() {
        FeedbackThrottle gate = new FeedbackThrottle();
        assertTrue(gate.accept("copied", 10));
        assertFalse(gate.accept("exported", 500));
        assertTrue(gate.accept("exported", 1010));
    }
}
