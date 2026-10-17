package com.aidol.demo;

import java.time.Instant;
import org.json.JSONObject;

/** An observed phone position, never a destination or an assumed current location. */
final class LocationSnapshot {
    static final long CACHE_MS = 86_400_000L, FRESH_MS = 120_000L;
    final double longitude, latitude, accuracy;
    final long observedAt;
    LocationSnapshot(double longitude, double latitude, double accuracy, long observedAt) {
        this.longitude = longitude; this.latitude = latitude; this.accuracy = accuracy; this.observedAt = observedAt;
    }
    boolean usable(long now, long maximumAge) {
        return Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
            && Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
            && Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= 200
            && observedAt > 0 && observedAt <= now && now - observedAt <= maximumAge;
    }
    JSONObject json(boolean cached) {
        try { return new JSONObject().put("longitude", longitude).put("latitude", latitude).put("accuracy", accuracy)
            .put("source", "gps").put("observed_at", Instant.ofEpochMilli(observedAt).toString()).put("cached", cached); }
        catch (Exception invalid) { return null; }
    }
    static LocationSnapshot parse(String raw) {
        try {
            JSONObject v = new JSONObject(raw);
            if (!"gps".equals(v.optString("source"))) return null;
            return new LocationSnapshot(v.getDouble("longitude"), v.getDouble("latitude"), v.getDouble("accuracy"),
                Instant.parse(v.getString("observed_at")).toEpochMilli());
        } catch (Exception invalid) { return null; }
    }
    static LocationSnapshot newest(LocationSnapshot a, LocationSnapshot b, long now) {
        if (a != null && !a.usable(now, CACHE_MS)) a = null;
        if (b != null && !b.usable(now, CACHE_MS)) b = null;
        return b != null && (a == null || b.observedAt > a.observedAt
            || b.observedAt == a.observedAt && b.accuracy < a.accuracy) ? b : a;
    }
}
