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
    private TimeFormat() {}
}
