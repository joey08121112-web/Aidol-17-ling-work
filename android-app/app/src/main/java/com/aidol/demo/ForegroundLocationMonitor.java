package com.aidol.demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import org.json.JSONObject;

/** Foreground-only location work. Never uses the voice worker, audio focus or mode APIs. */
final class ForegroundLocationMonitor implements AutoCloseable {
    interface Listener { void status(String message); void updated(); default void fixed(LocationSnapshot value) {} }
    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MaintenanceCadence cadence = new MaintenanceCadence();
    private final Runnable tick = this::tick;
    private final Runnable timeout = () -> finish("定位：暂未取得准确位置");
    private volatile boolean active, closed;
    private volatile long generation;
    private ApiClient api;
    private LocationListener fix;
    private Call upload;
    private String trigger = "app_open";

    ForegroundLocationMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }
    void enter(ApiClient client, String reason) {
        if (closed) return;
        if (api != client) stop();
        api = client;
        if (api == null || api.vault.token().isEmpty()) { listener.status("定位：登录后自动更新"); return; }
        active = true;
        if (cadence.enter(SystemClock.elapsedRealtime())) trigger = reason;
        ui.removeCallbacks(tick); ui.post(tick);
    }
    private void tick() {
        if (!active || closed) return;
        if (api == null || api.vault.token().isEmpty()) { stop(); listener.status("定位：登录后自动更新"); return; }
        if (cadence.claim(SystemClock.elapsedRealtime())) acquire();
        ui.postDelayed(tick, 1000);
    }
    private void acquire() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finish("定位：需要位置权限"); return;
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) { finish("定位：系统定位不可用"); return; }
        final ApiClient client = api;
        final String token = client.vault.token(), origin = trigger;
        trigger = "interval";
        final long current = generation;
        listener.status("定位：正在后台更新");
        fix = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (fix != this || !current(client, token, current) || !usable(location)) return;
                clearFix();
                MeetingLocationCache.remember(context, client, token, location);
                listener.fixed(MeetingLocationCache.from(location));
                listener.status("定位：正在记录");
                try {
                    long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
                    JSONObject body = new JSONObject().put("record_id", UUID.randomUUID().toString())
                        .put("observed_at", Instant.now().minusNanos(age).toString()).put("trigger", origin)
                        .put("longitude", location.getLongitude()).put("latitude", location.getLatitude())
                        .put("accuracy", location.getAccuracy()).put("source", "gps");
                    upload = client.call("POST", "/v1/location-records", body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
                    upload.timeout().timeout(10, TimeUnit.SECONDS);
                    final Call call = upload;
                    network.execute(() -> {
                        boolean ok = false;
                        try {
                            if (current(client, token, current)) { client.execute(call); ok = true; }
                        } catch (Exception ignored) {}
                        final boolean recorded = ok;
                        ui.post(() -> {
                            if (!current(client, token, current)) return;
                            upload = null;
                            if (recorded) {
                                finish("定位：" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " 已记录");
                                listener.updated();
                            } else finish("定位：已获取，记录暂未同步");
                        });
                    });
                } catch (Exception ignored) { finish("定位：记录暂未同步"); }
            }
        };
        try {
            List<String> enabled = manager.getProviders(true), providers = new ArrayList<>();
            boolean precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            for (String name : new String[]{"network", "fused", "gps"})
                if (enabled.contains(name) && (precise || !"gps".equals(name))) providers.add(name);
            int subscribed = 0;
            Location cached = null;
            for (String provider : providers) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (usable(candidate) && (cached == null || candidate.getElapsedRealtimeNanos() > cached.getElapsedRealtimeNanos())) cached = candidate;
                } catch (RuntimeException ignored) {}
                try { manager.requestLocationUpdates(provider, 0, 0, fix, Looper.getMainLooper()); subscribed++; }
                catch (RuntimeException ignored) {}
            }
            if (cached != null) fix.onLocationChanged(cached);
            else if (subscribed > 0) ui.postDelayed(timeout, 25000);
            else finish("定位：请检查系统定位开关");
        } catch (RuntimeException ignored) { finish("定位：请检查系统定位与权限"); }
    }
    private boolean current(ApiClient client, String token, long current) {
        return active && !closed && generation == current && api == client && token.equals(client.vault.token());
    }
    private static boolean usable(Location value) {
        if (value == null || !value.hasAccuracy()) return false;
        long age = SystemClock.elapsedRealtimeNanos() - value.getElapsedRealtimeNanos();
        return age >= 0 && age <= 60_000_000_000L && Float.isFinite(value.getAccuracy())
            && value.getAccuracy() >= 0 && value.getAccuracy() <= 200;
    }
    private void clearFix() {
        ui.removeCallbacks(timeout);
        if (fix != null) {
            try { context.getSystemService(LocationManager.class).removeUpdates(fix); } catch (RuntimeException ignored) {}
            fix = null;
        }
    }
    private void finish(String message) { clearFix(); cadence.complete(); listener.status(message); }
    void stop() {
        active = false; generation++; cadence.leave(); ui.removeCallbacks(tick); clearFix();
        if (upload != null) { upload.cancel(); upload = null; }
    }
    @Override public void close() { stop(); closed = true; network.shutdownNow(); }
}
