package com.aidol.demo;

import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimeFormatTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    @Test public void utcConvertsToShanghaiWithYearAndSeconds() {
        assertEquals("2026-10-17 16:03:05", TimeFormat.format("2026-10-17T08:03:05Z", SHANGHAI));
    }
    @Test public void millisecondsAreDisplayedToWholeSeconds() {
        assertEquals("2026-10-17 16:03:05", TimeFormat.format("2026-10-17T08:03:05.123+00:00", SHANGHAI));
    }
    @Test public void offsetConversionCanCrossTheYearBoundary() {
        assertEquals("2027-01-01 11:03:05", TimeFormat.format("2026-12-31T23:03:05-04:00", SHANGHAI));
    }
    @Test public void invalidTimestampIsUnchanged() {
        assertEquals("invalid timestamp", TimeFormat.format("invalid timestamp", SHANGHAI));
        assertEquals("", TimeFormat.format("", SHANGHAI));
        assertNull(TimeFormat.format(null, SHANGHAI));
    }
}
