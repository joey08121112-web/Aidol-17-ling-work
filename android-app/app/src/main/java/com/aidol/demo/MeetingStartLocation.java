package com.aidol.demo;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Background starts read cache only; visible starts may request a bounded fresh fix. */
final class MeetingStartLocation {
    interface Source {
        boolean foreground();
        LocationSnapshot cached();
        LocationSnapshot acquire();
    }
    private MeetingStartLocation() {}
    static JSONObject capture(Source source) {
        LocationSnapshot cached = LocationSnapshot.newest(null, source.cached(), System.currentTimeMillis());
        if (source.foreground() && (cached == null || !cached.usable(System.currentTimeMillis(), LocationSnapshot.FRESH_MS))) {
            LocationSnapshot fresh = source.acquire();
            if (fresh != null && fresh.usable(System.currentTimeMillis(), LocationSnapshot.FRESH_MS)) return fresh.json(false);
            cached = LocationSnapshot.newest(cached, source.cached(), System.currentTimeMillis());
        }
        return cached == null ? null : cached.json(true);
    }
    static JSONObject capture(Context context, ApiClient api, BooleanSupplier foreground) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        final String token = api.vault.token();
        if (token.isEmpty()) return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        Source source = new Source() {
            public boolean foreground() {
                PowerManager power = context.getSystemService(PowerManager.class);
                KeyguardManager lock = context.getSystemService(KeyguardManager.class);
                return foreground.getAsBoolean() && power != null && power.isInteractive() && lock != null && !lock.isKeyguardLocked();
            }
            public LocationSnapshot cached() {
                LocationSnapshot best = MeetingLocationCache.read(context, api, token);
                if (manager != null) try {
                    for (String provider : manager.getProviders(true)) {
                        if (!allowed(provider)) continue;
                        try { best = LocationSnapshot.newest(best, MeetingLocationCache.from(manager.getLastKnownLocation(provider)), System.currentTimeMillis()); }
                        catch (RuntimeException unavailable) { /* Other providers/cache can still work. */ }
                    }
                } catch (RuntimeException unavailable) { /* App cache remains available. */ }
                MeetingLocationCache.remember(context, api, token, best);
                return best;
            }
            public LocationSnapshot acquire() {
                if (manager == null || !foreground()) return null;
                AtomicReference<LocationSnapshot> fix = new AtomicReference<>();
                CountDownLatch ready = new CountDownLatch(1);
                LocationListener listener = location -> {
                    LocationSnapshot value = MeetingLocationCache.from(location);
                    if (foreground() && value != null && value.usable(System.currentTimeMillis(), LocationSnapshot.FRESH_MS)) {
                        fix.set(value); ready.countDown();
                    }
                };
                try {
                    int subscribed = 0;
                    for (String provider : manager.getProviders(true)) {
                        if (!foreground() || !allowed(provider)) continue;
                        try { manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper()); subscribed++; }
                        catch (RuntimeException unavailable) { /* One provider must not block another. */ }
                    }
                    long until = SystemClock.elapsedRealtime() + 3000;
                    while (subscribed > 0 && foreground() && token.equals(api.vault.token()) && SystemClock.elapsedRealtime() < until)
                        if (ready.await(100, TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (RuntimeException unavailable) { /* Fall back to the existing cache. */ }
                finally { try { manager.removeUpdates(listener); } catch (RuntimeException ignored) {} }
                MeetingLocationCache.remember(context, api, token, fix.get());
                return fix.get();
            }
        };
        JSONObject value = capture(source);
        return token.equals(api.vault.token()) ? value : null;
    }
    private static boolean allowed(String provider) { return "network".equals(provider) || "fused".equals(provider) || "gps".equals(provider); }
}
