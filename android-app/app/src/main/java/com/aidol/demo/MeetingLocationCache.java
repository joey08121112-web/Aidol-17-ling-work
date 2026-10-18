package com.aidol.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.SystemClock;

/** One private account-session snapshot. Network availability never controls its persistence. */
final class MeetingLocationCache {
    private static final String PREFS = "meeting_location_cache";
    private static String owner(ApiClient api, String token) {
        return ServerEndpoint.sessionNamespace(api.serverUrl + "\n" + token);
    }
    static synchronized LocationSnapshot read(Context context, ApiClient api, String token) {
        if (token.isEmpty() || !token.equals(api.vault.token())) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!owner(api, token).equals(prefs.getString("owner", ""))) return null;
        return LocationSnapshot.newest(null, LocationSnapshot.parse(prefs.getString("fix", "")), System.currentTimeMillis());
    }
    static void remember(Context context, ApiClient api, String token, Location location) {
        remember(context, api, token, from(location));
    }
    static synchronized void remember(Context context, ApiClient api, String token, LocationSnapshot snapshot) {
        if (token.isEmpty() || !token.equals(api.vault.token()) || snapshot == null
                || !snapshot.usable(System.currentTimeMillis(), LocationSnapshot.CACHE_MS)) return;
        LocationSnapshot latest = LocationSnapshot.newest(read(context, api, token), snapshot, System.currentTimeMillis());
        if (latest == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("owner", owner(api, token))
            .putString("fix", latest.json(true).toString()).apply();
    }
    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
    static LocationSnapshot from(Location value) {
        if (value == null || !value.hasAccuracy()) return null;
        long age = SystemClock.elapsedRealtimeNanos() - value.getElapsedRealtimeNanos();
        if (age < 0 || age / 1_000_000L > LocationSnapshot.CACHE_MS) return null;
        return new LocationSnapshot(value.getLongitude(), value.getLatitude(), value.getAccuracy(),
            System.currentTimeMillis() - age / 1_000_000L);
    }
}
