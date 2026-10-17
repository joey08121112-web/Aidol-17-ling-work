package com.aidol.demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Bounded foreground start snapshot. Never reuse a commute destination or an old GPS fix. */
final class MeetingStartLocation {
    private MeetingStartLocation() {}

    static JSONObject capture(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return null;
        AtomicReference<Location> fix = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        LocationListener listener = location -> { if (fresh(location)) { fix.set(location); ready.countDown(); } };
        try {
            for (String provider : manager.getProviders(true)) {
                Location cached = manager.getLastKnownLocation(provider);
                if (fresh(cached) && (fix.get() == null || cached.getElapsedRealtimeNanos() > fix.get().getElapsedRealtimeNanos())) fix.set(cached);
            }
            if (fix.get() == null) {
                for (String provider : manager.getProviders(true)) manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
                ready.await(3, TimeUnit.SECONDS);
            }
            Location location = fix.get();
            if (location == null) return null;
            return new JSONObject().put("longitude", location.getLongitude()).put("latitude", location.getLatitude())
                .put("accuracy", location.getAccuracy()).put("source", "gps");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception error) { return null; }
        finally { try { manager.removeUpdates(listener); } catch (Exception ignored) {} }
    }

    private static boolean fresh(Location location) {
        if (location == null) return false;
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= TimeUnit.MINUTES.toNanos(2);
    }
}
