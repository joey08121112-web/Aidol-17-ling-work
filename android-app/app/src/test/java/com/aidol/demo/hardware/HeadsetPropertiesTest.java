package com.aidol.demo.hardware;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeadsetPropertiesTest {
    @Test public void unknownBatteryIsNeverShownAsZero() {
        assertNull(HeadsetController.reportedBattery(255));
        assertNull(HeadsetController.reportedBattery(-1));
        assertNull(HeadsetController.reportedBattery(101));
        assertEquals(Integer.valueOf(0), HeadsetController.reportedBattery(0));
        assertEquals(Integer.valueOf(100), HeadsetController.reportedBattery(100));
    }
    @Test public void closedOrUnreportedPositionRemainsUnknown() {
        assertNull(HeadsetController.reportedWear(0));
        assertNull(HeadsetController.reportedWear(-1));
        assertNull(HeadsetController.reportedWear(3));
        assertEquals(Boolean.TRUE, HeadsetController.reportedWear(1));
        assertEquals(Boolean.FALSE, HeadsetController.reportedWear(2));
        assertEquals(Boolean.FALSE, HeadsetController.reportedWear(4));
    }
}
