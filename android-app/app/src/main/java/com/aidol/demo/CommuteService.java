package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.location.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import okhttp3.Call;
import org.json.*;

/** Explicit location foreground service; no microphone or automatic restart. */
public final class CommuteService extends Service {
    static final String BOARD = "com.aidol.demo.BOARD_COMMUTE";
    static final String STOP = "com.aidol.demo.STOP_COMMUTE";
    private static final String CHANNEL = "commute_location";
    private static final int NOTIFICATION = 43;
    private static volatile CommuteService instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private ApiClient api;
    private String token, owner, encryptedSession, backendOrigin;
    private DevelopmentSettings backendSettings;
    private android.content.SharedPreferences credentials;
    private JSONObject journey = new JSONObject();
    private ForegroundLocationMonitor locations;
    private LocationSnapshot fix;
    private long movingAt;
    private PowerManager.WakeLock wake;
    private CommuteSpeaker speaker;
    private TransitProgress progress;
    private JSONObject frozenRoute;
    private LocationListener stationListener;
    private android.content.SharedPreferences reminderState;
    private String reminderStatus = "等待公共交通路线";
    private Call routeCall;
    static String reminderStatus(ApiClient api) { return running(api) ? instance.reminderStatus : "开始公共交通通勤后启用"; }
    static String boarded(ApiClient api) {
        if (!running(api) || instance.progress == null) return "站点路线尚未就绪，请稍后重试";
        instance.reminderStatus = instance.progress.board(); instance.saveProgress(); return instance.reminderStatus;
    }
    static boolean remindersEnabled(Context context, ApiClient api) {
        return context.getSharedPreferences("commute_reminders", MODE_PRIVATE).getBoolean(owner(api) + ":enabled", true);
    }
    static void setReminders(Context context, ApiClient api, boolean enabled) {
        context.getSharedPreferences("commute_reminders", MODE_PRIVATE).edit().putBoolean(owner(api) + ":enabled", enabled).apply();
        if (running(api) && !enabled && instance.speaker != null) instance.speaker.stop();
    }
    static boolean showReminder(Context context, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("commute_stations", "通勤站点提醒", NotificationManager.IMPORTANCE_HIGH));
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("commute_stations").getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        PendingIntent open = PendingIntent.getActivity(context, 46, new Intent(context, MainActivity.class).putExtra("open_commute", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(46, new Notification.Builder(context, "commute_stations").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aidol 站点提醒")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build());
        return true;
    }

    private boolean ending, inFlight;
    private long due, wakeAt;
    private String status = "正在获取当前位置", notificationText = "";

    static String owner(ApiClient api) { return ServerEndpoint.sessionNamespace(api.serverUrl + "\n" + api.vault.token()); }
    static boolean running(ApiClient api) { CommuteService service = instance; return service != null && !service.ending && service.owner.equals(owner(api)); }
    static JSONObject current(ApiClient api) {
        CommuteService service = instance;
        if (!running(api)) return null;
        try { return new JSONObject(service.journey.toString()); } catch (Exception e) { return null; }
    }
    static String status(ApiClient api) { return running(api) ? instance.status : ""; }
    static void start(Context context, JSONObject journey) {
        context.startForegroundService(new Intent(context, CommuteService.class).putExtra("journey", journey.toString()));
    }
    static void stop(Context context) { CommuteService service = instance; if (service != null) service.end(); else context.stopService(new Intent(context, CommuteService.class)); }
    static void refresh(ApiClient api) { if (running(api)) { instance.due = 0; instance.locations.enter(instance.api, "page_return"); instance.refreshRoute(); } }
    static String pendingStop(Context context, ApiClient api) { return context.getSharedPreferences("commute_stop_queue", MODE_PRIVATE).getString(owner(api), ""); }
    static void queueStop(Context context, ApiClient api, String id) { context.getSharedPreferences("commute_stop_queue", MODE_PRIVATE).edit().putString(owner(api), id).apply(); }
    static void clearPendingStop(Context context, ApiClient api, String expectedId) {
        if (expectedId.equals(pendingStop(context, api))) clearPendingStop(context, api);
    }
    static void clearPendingStop(Context context, ApiClient api) { context.getSharedPreferences("commute_stop_queue", MODE_PRIVATE).edit().remove(owner(api)).apply(); }

    @Override public void onCreate() {
        super.onCreate(); api = new ApiClient(this); token = api.vault.token(); owner = owner(api);
        backendSettings = new DevelopmentSettings(this); backendOrigin = backendSettings.serverUrl();
        credentials = getSharedPreferences(ServerEndpoint.sessionNamespace(api.serverUrl), MODE_PRIVATE);
        encryptedSession = credentials.getString("encrypted", "");
        speaker = new CommuteSpeaker(this);
        reminderState = getSharedPreferences("commute_station_progress", MODE_PRIVATE);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aidol:Commute"); wake.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "通勤定位与路线", NotificationManager.IMPORTANCE_LOW));
        locations = new ForegroundLocationMonitor(this, new ForegroundLocationMonitor.Listener() {
            public void status(String message) { if (!inFlight && !ending && "gps".equals(journey.optString("origin_mode"))) { status = message; notifyState(); } }
            public void updated() {}
            public void fixed(LocationSnapshot value) { fix = value; refreshRoute(); }
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (ending) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && BOARD.equals(intent.getAction())) { if (instance == this) boarded(api); return START_NOT_STICKY; }
        if (intent == null || STOP.equals(intent.getAction())) { end(); return START_NOT_STICKY; }
        try {
            JSONObject value = new JSONObject(intent.getStringExtra("journey"));
            if (!value.optBoolean("active") || token.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
            if (instance == this && !journey.optString("id").equals(value.optString("id"))) { end(); return START_NOT_STICKY; }
            journey = value;
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION, notification());
            instance = this; wake.acquire(600_000); wakeAt = SystemClock.elapsedRealtime();
            setupStations();
            fix = MeetingLocationCache.read(this, api, token);
            locations.enter(api, "app_open");
            ui.removeCallbacks(tick); ui.post(tick);
        } catch (Exception unavailable) { status = "请回到 App 检查定位权限后继续通勤"; end(); }
        return START_NOT_STICKY;
    }
    private final Runnable tick = new Runnable() { public void run() {
        if (ending) return;
        if (!backendOrigin.equals(backendSettings.serverUrl()) || !encryptedSession.equals(credentials.getString("encrypted", ""))) { end(); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - wakeAt >= 300_000) { wake.acquire(600_000); wakeAt = now; }
        refreshRoute(); checkStations(); ui.postDelayed(this, 1000);
    }};
    private void refreshRoute() {
        if (ending || inFlight || !journey.optBoolean("active") || SystemClock.elapsedRealtime() < due) return;
        boolean gps = "gps".equals(journey.optString("origin_mode"));
        if (gps && (fix == null || !fix.usable(System.currentTimeMillis(), 120_000))) return;
        final String id = journey.optString("id");
        try {
            JSONObject body = new JSONObject().put("request_id", UUID.randomUUID().toString());
            if (gps) body.put("location", fix.json(false));
            routeCall = api.call("POST", "/v1/commute/" + id + "/refresh", body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            routeCall.timeout().timeout(40, TimeUnit.SECONDS);
            final Call call = routeCall;
            inFlight = true; due = SystemClock.elapsedRealtime() + 180_000; status = "正在更新路线"; notifyState();
            network.execute(() -> {
                JSONObject result = null; String error = ""; boolean missing = false;
                try { result = new JSONObject(new String(api.execute(call), StandardCharsets.UTF_8)); }
                catch (Exception e) { error = "路线更新失败，稍后自动重试"; missing = e instanceof ApiClient.ApiException && (((ApiClient.ApiException)e).status == 401 || ((ApiClient.ApiException)e).status == 404); }
                final JSONObject saved = result; final String failure = error; final boolean stopped = missing;
                ui.post(() -> {
                    if (ending || !id.equals(journey.optString("id")) || !token.equals(api.vault.token())) return;
                    inFlight = false; routeCall = null;
                    if (stopped || saved != null && !saved.optBoolean("active")) { end(); return; }
                    if (saved != null) {
                        journey = saved; setupStations();
                        status = saved.optString("error").isEmpty() ? "路线已更新 · " + TimeFormat.clockOf(saved.optString("updated_at")) : saved.optString("error");
                        if (!saved.optString("error").isEmpty()) due = SystemClock.elapsedRealtime() + 30_000;
                    } else { status = failure; due = SystemClock.elapsedRealtime() + 30_000; }
                    notifyState();
                });
            });
        } catch (Exception failure) { inFlight = false; due = SystemClock.elapsedRealtime() + 30_000; status = "路线暂未更新"; notifyState(); }
    }
    private void setupStations() {
        if (!"transit".equals(journey.optString("transport"))) return;
        if (progress == null) {
            try {
                JSONObject saved = new JSONObject(reminderState.getString(owner, "{}"));
                boolean same = journey.optString("id").equals(saved.optString("id"));
                frozenRoute = same ? saved.optJSONObject("route") : null;
                if (frozenRoute == null) frozenRoute = journey.optJSONObject("route");
                if (frozenRoute != null && frozenRoute.optBoolean("available")) {
                    progress = new TransitProgress(frozenRoute, same ? saved.optJSONObject("progress") : null);
                    reminderStatus = progress.status(); saveProgress();
                }
            } catch (Exception ignored) { reminderStatus = "站点提醒暂不可用"; }
        }
        if (stationListener == null) {
            stationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    long age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000;
                    if (!location.hasAccuracy() || age < 0 || age > 45000) return;
                    LocationSnapshot candidate = new LocationSnapshot(location.getLongitude(), location.getLatitude(), location.getAccuracy(), System.currentTimeMillis() - age);
                    if (candidate.usable(System.currentTimeMillis(), 45000)) {
                        fix = candidate;
                        movingAt = location.hasSpeed() && location.getSpeed() >= 2.5f ? candidate.observedAt : 0;
                    }
                }
            };
            LocationManager manager = getSystemService(LocationManager.class);
            if (manager != null) for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try { if (manager.isProviderEnabled(provider)) manager.requestLocationUpdates(provider, 10000, 10, stationListener, Looper.getMainLooper()); }
                catch (SecurityException | IllegalArgumentException ignored) { /* Existing location status exposes revoked permissions. */ }
            }
        }
    }
    private void saveProgress() {
        if (progress == null) return;
        try { reminderState.edit().putString(owner, new JSONObject().put("id", journey.optString("id")).put("route", frozenRoute).put("progress", progress.snapshot()).toString()).apply(); }
        catch (Exception ignored) { }
    }
    private void checkStations() {
        if (progress == null || !remindersEnabled(this, api)) return;
        String before = progress.snapshot().toString();
        long now = System.currentTimeMillis();
        progress.autoBoard(now, fix, movingAt > 0 && now - movingAt < 45000);
        String message = progress.update(now, fix);
        reminderStatus = progress.status();
        if (!before.equals(progress.snapshot().toString())) saveProgress();
        if (message == null) return;
        boolean shown = showReminder(this, message);
        if (!speaker.busy()) speaker.speak(api, message, () -> !ending && remindersEnabled(this, api), success -> {
            if (!success) reminderStatus = shown ? "已发通知，语音未播放（音频忙或网络不可用）" : "通知未开启，语音也未播放，请检查权限和网络";
        });
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).putExtra("open_commute", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 43, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 44, new Intent(this, CommuteService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JSONObject route = journey.optJSONObject("route");
        String detail = status;
        if (route != null && route.optBoolean("available")) detail = ("fixed".equals(journey.optString("origin_mode")) ? "固定起点估算 " : "预计还需 ")
            + Math.max(1, Math.round(route.optDouble("eta_seconds") / 60)) + " 分钟 · " + status;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aidol 通勤进行中")
            .setContentText(detail).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null, "结束通勤", stop).build());
        if ("transit".equals(journey.optString("transport"))) {
            PendingIntent board = PendingIntent.getService(this, 45, new Intent(this, CommuteService.class).setAction(BOARD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "我已上车", board).build());
        }
        return builder.build();
    }
    private void notifyState() {
        if (ending || instance != this || status.equals(notificationText)) return;
        notificationText = status; getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    private void end() {
        if (ending) return; ending = true;
        locations.stop(); if (speaker != null) speaker.stop(); removeStationListener(); ui.removeCallbacks(tick); if (routeCall != null) routeCall.cancel();
        String id = journey.optString("id");
        if (!id.isEmpty() && !token.isEmpty()) {
            getSharedPreferences("commute_stop_queue", MODE_PRIVATE).edit().putString(owner, id).apply();
            network.execute(() -> {
                try { api.json("DELETE", "/v1/commute/" + id, null); clearPendingStop(this, api, id); }
                catch (Exception ignored) { /* Retried on the next visible, authenticated entry. */ }
            });
        }
        getSystemService(NotificationManager.class).cancel(46);
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void removeStationListener() {
        if (stationListener != null) { LocationManager manager = getSystemService(LocationManager.class); if (manager != null) manager.removeUpdates(stationListener); stationListener = null; }
    }
    @Override public void onDestroy() {
        ending = true; removeStationListener(); if (speaker != null) speaker.close(); if (instance == this) instance = null;
        ui.removeCallbacksAndMessages(null); if (locations != null) locations.close(); if (routeCall != null) routeCall.cancel();
        if (wake != null && wake.isHeld()) wake.release(); network.shutdown(); super.onDestroy();
    }
    public final class LocalBinder extends Binder { CommuteService service() { return CommuteService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
}
