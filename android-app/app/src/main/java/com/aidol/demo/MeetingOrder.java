package com.aidol.demo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;

final class MeetingOrder {
    private static Instant time(JSONObject meeting) {
        try { return Instant.parse(meeting.optString("started_at")); } catch (Exception ignored) { return Instant.MIN; }
    }
    private static boolean pinned(JSONObject meeting) { return meeting.optBoolean("pinned") || meeting.optInt("pinned") == 1; }
    static JSONArray sorted(JSONArray meetings) {
        ArrayList<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < meetings.length(); i++) if (meetings.optJSONObject(i) != null) rows.add(meetings.optJSONObject(i));
        rows.sort((a, b) -> {
            int pin = Boolean.compare(pinned(b), pinned(a)); if (pin != 0) return pin;
            int at = time(b).compareTo(time(a)); return at != 0 ? at : b.optString("id").compareTo(a.optString("id"));
        });
        JSONArray result = new JSONArray(); for (JSONObject row : rows) result.put(row); return result;
    }
    static JSONObject latest(JSONArray meetings) {
        JSONObject newest = null;
        for (int i = 0; i < meetings.length(); i++) {
            JSONObject row = meetings.optJSONObject(i);
            if (row != null && (newest == null || time(row).isAfter(time(newest)))) newest = row;
        }
        return newest;
    }
}
