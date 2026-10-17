package com.aidol.demo.hardware;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlEventGateTest {
    @Test public void onlyFirmwareAcknowledgedCurrentListenerCanDeliver() {
        ControlEventGate gate = new ControlEventGate();
        long first = gate.begin("test-device");
        assertNull(gate.accept(first, "test-device", 0x90, 100));
        assertTrue(gate.confirm(first));
        assertEquals(HeadsetController.ControlKind.DOUBLE_TAP, gate.accept(first, "test-device", 0x90, 101).kind);
        gate.invalidate();
        assertFalse(gate.confirm(first));
        assertNull(gate.accept(first, "test-device", 0x90, 102));
    }
    @Test public void cancelledRegistrationAndOldDeviceCannotReviveOnReconnect() {
        ControlEventGate gate = new ControlEventGate();
        long old = gate.begin("first-device"); gate.invalidate();
        long current = gate.begin("second-device");
        assertFalse(gate.confirm(old)); assertTrue(gate.confirm(current));
        assertNull(gate.accept(old, "first-device", 0x91, 1));
        assertNull(gate.accept(current, "first-device", 0x91, 2));
        assertEquals(HeadsetController.ControlKind.TRIPLE_TAP, gate.accept(current, "SECOND-DEVICE", 0x91, 3).kind);
    }
    @Test public void verifiedCodesAreNotEnumOrdinalsOrOrdinaryButtonProtocol() {
        assertEquals(HeadsetController.ControlKind.TAP, ControlEventGate.kind(149));
        assertEquals(HeadsetController.ControlKind.DOUBLE_TAP, ControlEventGate.kind(144));
        assertEquals(HeadsetController.ControlKind.TRIPLE_TAP, ControlEventGate.kind(145));
        assertEquals(HeadsetController.ControlKind.LONG_PRESS, ControlEventGate.kind(146));
        assertEquals(HeadsetController.ControlKind.PRESS_DOUBLE, ControlEventGate.kind(129));
        assertEquals(HeadsetController.ControlKind.PRESS_TRIPLE, ControlEventGate.kind(130));
        assertEquals(HeadsetController.ControlKind.PRESS_LONG, ControlEventGate.kind(131));
        assertNull(ControlEventGate.kind(7)); assertNull(ControlEventGate.kind(0x5d07));
    }
    @Test public void ignoredHeadImuUnknownEventsNeverBecomeControlActions() {
        ControlEventGate gate = new ControlEventGate(); long token = gate.begin("device"); gate.confirm(token);
        assertNull(gate.accept(token, "device", 0xc0, 1));
        assertNull(gate.accept(token, "device", 0xc1, 2));
        assertNull(gate.accept(token, "device", 0xb0, 3));
        assertNull(gate.accept(token, "device", -1, 4));
        assertNull(gate.accept(token, null, 0x90, 5));
        HeadsetController.ControlEvent event = gate.accept(token, "device", 0x90, 6);
        assertEquals(1, event.sequence); assertEquals(6, event.receivedAtMillis);
    }
    @Test public void sequenceDoesNotRestartWhenTheSameDeviceReconnects() {
        ControlEventGate gate = new ControlEventGate(); long first = gate.begin("device"); gate.confirm(first);
        assertEquals(1, gate.accept(first, "device", 0x90, 10).sequence);
        gate.invalidate(); long second = gate.begin("device"); gate.confirm(second);
        assertEquals(2, gate.accept(second, "device", 0x90, 20).sequence);
        assertNull(gate.accept(first, "device", 0x90, 21));
    }
}
