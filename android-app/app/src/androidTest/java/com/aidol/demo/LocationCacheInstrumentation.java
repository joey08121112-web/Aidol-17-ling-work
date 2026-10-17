package com.aidol.demo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** Two separate instrument invocations prove private cache persistence without touching user data. */
public final class LocationCacheInstrumentation extends Instrumentation {
    private String phase;
    private Context fixture;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); phase = arguments.getString("phase", "verify"); start(); }
    @Override public void onStart() {
        fixture = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("location_review_" + name, mode);
            }
        };
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        ApiClient api = new ApiClient(fixture, ServerEndpoint.USB_LOCAL);
        try {
            if ("seed".equals(phase)) {
                MeetingLocationCache.clear(fixture);
                api.vault.save("offline-cache-session", "fixture");
                long observed = System.currentTimeMillis() - 1_200_000;
                MeetingLocationCache.remember(fixture, api, api.vault.token(), new LocationSnapshot(116.4, 39.9, 30, observed));
                fixture.getSharedPreferences("cache_review", 0).edit().putLong("observed", observed).commit();
                // commit waits for the previous async preference write to reach storage.
                fixture.getSharedPreferences("meeting_location_cache", 0).edit().commit();
                check(MeetingLocationCache.read(fixture, api, api.vault.token()) != null, "seed failed");
            } else {
                String token = api.vault.token();
                long observed = fixture.getSharedPreferences("cache_review", 0).getLong("observed", 0);
                LocationSnapshot cached = MeetingLocationCache.read(fixture, api, token);
                check(cached != null && observed > 0 && cached.observedAt == observed, "process restart lost cache");
                JSONObject capture = MeetingStartLocation.capture(new MeetingStartLocation.Source() {
                    public boolean foreground() { return false; }
                    public LocationSnapshot cached() { return MeetingLocationCache.read(fixture, api, token); }
                    public LocationSnapshot acquire() { throw new AssertionError("background requested active location"); }
                });
                check(capture.getBoolean("cached") && LocationSnapshot.parse(capture.toString()).observedAt == observed,
                    "background changed timestamp");
                check(MeetingPlace.label(new JSONObject().put("location", capture.put("address", "测试地址"))).contains("缓存定位"), "cache label missing");
                MeetingLocationCache.remember(fixture, api, token, new LocationSnapshot(121.5, 31.2, 30, observed - 1000));
                check(MeetingLocationCache.read(fixture, api, token).observedAt == observed, "old fix replaced cache");
                ApiClient other = new ApiClient(fixture, "https://example.com");
                try {
                    other.vault.save(token, "fixture");
                    check(MeetingLocationCache.read(fixture, other, token) == null, "backend isolation failed");
                } finally { other.vault.clear(); }
                api.vault.save("offline-rotated-session", "fixture");
                check(MeetingLocationCache.read(fixture, api, api.vault.token()) == null, "account session isolation failed");
                MeetingLocationCache.remember(fixture, api, token, cached);
                check(MeetingLocationCache.read(fixture, api, api.vault.token()) == null, "old callback restored cache");
                Location location = new Location("gps"); location.setLongitude(116.4); location.setLatitude(39.9); location.setAccuracy(30);
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 2_000_000_000L);
                LocationSnapshot converted = MeetingLocationCache.from(location);
                check(converted != null && converted.usable(System.currentTimeMillis(), 5000), "monotonic age conversion failed");
                MeetingLocationCache.remember(fixture, api, api.vault.token(), location);
                check(MeetingLocationCache.read(fixture, api, api.vault.token()) != null, "offline phone fix not persisted");
                location.removeAccuracy(); check(MeetingLocationCache.from(location) == null, "missing accuracy accepted");
                MeetingLocationCache.clear(fixture);
                check(MeetingLocationCache.read(fixture, api, api.vault.token()) == null, "clear retained position");
                api.vault.clear(); fixture.getSharedPreferences("cache_review", 0).edit().clear().commit();
            }
            result.putString("result", "PASS " + phase); outcome = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("failure", failure.toString());
            MeetingLocationCache.clear(fixture); api.vault.clear();
        }
        finish(outcome, result);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
