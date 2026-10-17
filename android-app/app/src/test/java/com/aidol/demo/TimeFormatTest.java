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
    @Test public void recordingClockShowsMinutesThenHours() {
        assertEquals("0:00", TimeFormat.clock(-5));
        assertEquals("1:05", TimeFormat.clock(65_999));
        assertEquals("59:59", TimeFormat.clock(3_599_000));
        assertEquals("1:00:01", TimeFormat.clock(3_601_000));
    }
    @Test public void clockAndDayOfIsoTimestampsInLocalZone() {
        assertEquals("14:03:05", TimeFormat.clockOf("2026-10-17T06:03:05+00:00", SHANGHAI));
        assertEquals("2026-10-17 星期六", TimeFormat.dayOf("2026-10-17T06:03:05+00:00", SHANGHAI));
        assertEquals("2026-10-19 星期一", TimeFormat.dayOf("2026-10-18T17:00:00+00:00", SHANGHAI));
        assertEquals("", TimeFormat.clockOf("", SHANGHAI));
        assertEquals("", TimeFormat.dayOf("bad", SHANGHAI));
    }
    @Test public void referenceStyleDatesAndPositions() {
        assertEquals("00:00:41", TimeFormat.hms(41_999));
        assertEquals("01:02:05", TimeFormat.hms(3_725_000));
        assertEquals("2026年9月4日（周五）", TimeFormat.chineseDay("2026-09-04T01:59:00+00:00", SHANGHAI));
        assertEquals("09:59", TimeFormat.hourMinute("2026-09-04T01:59:30+00:00", SHANGHAI));
        assertEquals("GMT+08", TimeFormat.gmt(SHANGHAI));
    }
}
