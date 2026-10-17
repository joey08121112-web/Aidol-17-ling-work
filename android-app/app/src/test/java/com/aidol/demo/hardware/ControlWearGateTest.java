package com.aidol.demo.hardware;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlWearGateTest {
    @Test public void onlyActualPositionAttributeCanMakeEitherEarEligible() {
        ControlWearGate gate = new ControlWearGate(); gate.reset(1);
        assertFalse(gate.worn());
        assertFalse(gate.observe(1, false, true, true)); assertFalse(gate.worn());
        assertTrue(gate.observe(1, true, false, true)); assertTrue(gate.worn());
        gate.observe(1, true, true, null); assertTrue(gate.worn());
    }
    @Test public void UnknownAndUnwornReportsDoNotAuthorizeRegistration() {
        ControlWearGate gate = new ControlWearGate(); gate.reset(2);
        gate.observe(2, true, null, null); assertFalse(gate.worn()); assertFalse(gate.bothOut());
        gate.observe(2, true, false, null); assertFalse(gate.worn()); assertFalse(gate.bothOut());
        gate.observe(2, true, false, false); assertFalse(gate.worn()); assertTrue(gate.bothOut());
    }
    @Test public void reconnectRequiresFreshPositionFromTheNewConnection() {
        ControlWearGate gate = new ControlWearGate(); gate.reset(1);
        gate.observe(1, true, true, false); assertTrue(gate.worn());
        gate.reset(2); assertFalse(gate.worn());
        assertFalse(gate.observe(1, true, true, true)); assertFalse(gate.worn());
        assertTrue(gate.observe(2, true, false, true)); assertTrue(gate.worn());
    }
    @Test public void propertyQueryIsClaimedOncePerConnectionEvenWhileWaiting() {
        ControlWearGate gate = new ControlWearGate(); gate.reset(3);
        assertTrue(gate.claimQuery(3)); assertFalse(gate.claimQuery(3));
        gate.observe(3, true, false, false); assertFalse(gate.claimQuery(3));
        gate.observe(3, true, null, null); assertFalse(gate.claimQuery(3));
        gate.reset(4); assertFalse(gate.claimQuery(3)); assertTrue(gate.claimQuery(4));
        assertFalse(gate.claimQuery(4));
    }
}
