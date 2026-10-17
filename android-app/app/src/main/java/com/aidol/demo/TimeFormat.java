package com.aidol.demo;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Display source timestamps in the device's local time zone. */
final class TimeFormat {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    static String format(String raw) { return format(raw, ZoneId.systemDefault()); }
    static String format(String raw, ZoneId zone) {
        if (raw == null) return null;
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(zone).format(DISPLAY); }
        catch (DateTimeParseException e) { return raw; }
    }
    /** Position on a recording timeline: m:ss below an hour, h:mm:ss after. */
    static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000, h = seconds / 3600, m = seconds / 60 % 60, sec = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    /** Local clock time "14:03:12" of an ISO timestamp; "" when it cannot be parsed. */
    static String clockOf(String raw) { return clockOf(raw, ZoneId.systemDefault()); }
    static String clockOf(String raw, ZoneId zone) {
        if (raw == null || raw.isEmpty()) return "";
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(zone).format(CLOCK); }
        catch (DateTimeParseException e) { return ""; }
    }
    /** Local clock time of an epoch-millisecond instant. */
    static String clockAt(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(CLOCK);
    }
    /** "2026-10-17 星期六" of an ISO timestamp; "" when it cannot be parsed. */
    static String dayOf(String raw) { return dayOf(raw, ZoneId.systemDefault()); }
    static String dayOf(String raw, ZoneId zone) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            java.time.ZonedDateTime time = OffsetDateTime.parse(raw).atZoneSameInstant(zone);
            return time.toLocalDate() + " " + WEEKDAYS[time.getDayOfWeek().getValue() - 1];
        } catch (DateTimeParseException e) { return ""; }
    }
    /** Reference-style position "00:00:41" (always hours). */
    static String hms(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private static final String[] SHORT_WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    /** "2026年10月17日（周六）" of an ISO timestamp; "" when it cannot be parsed. */
    static String chineseDay(String raw, ZoneId zone) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            java.time.ZonedDateTime t = OffsetDateTime.parse(raw).atZoneSameInstant(zone);
            return t.getYear() + "年" + t.getMonthValue() + "月" + t.getDayOfMonth() + "日（" + SHORT_WEEKDAYS[t.getDayOfWeek().getValue() - 1] + "）";
        } catch (DateTimeParseException e) { return ""; }
    }
    /** "09:59" of an ISO timestamp; "" when it cannot be parsed. */
    static String hourMinute(String raw, ZoneId zone) {
        String clock = clockOf(raw, zone);
        return clock.length() >= 5 ? clock.substring(0, 5) : "";
    }
    /** "GMT+08" for the zone's current offset. */
    static String gmt(ZoneId zone) {
        int seconds = zone.getRules().getOffset(java.time.Instant.now()).getTotalSeconds();
        return String.format(Locale.ROOT, "GMT%s%02d", seconds < 0 ? "-" : "+", Math.abs(seconds) / 3600);
    }
    private TimeFormat() {}
}
