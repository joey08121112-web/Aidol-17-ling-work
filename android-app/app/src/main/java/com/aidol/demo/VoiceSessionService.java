package com.aidol.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Explicit, non-restoring lock-screen lease. It never starts the microphone itself. */
public final class VoiceSessionService extends Service {
    private static final String CHANNEL = "headset_session";
    private static final String STOP = "com.aidol.demo.STOP_HEADSET_SESSION";
    private static final int NOTIFICATION = 31;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Binder binder = new LocalBinder();
    private final Runnable expiry = () -> { if (this.session == null) stopSession("耳机待命已满一小时，请回到 App 重新开启"); };
    private long wakeRenewedAt;
    private final Runnable callCheck = new Runnable() {
        @Override public void run() {
            if (!enabled) return;
            if (session != null && android.os.SystemClock.elapsedRealtime() - wakeRenewedAt >= 300_000L) {
                wakeLock.acquire(600_000L); wakeRenewedAt = android.os.SystemClock.elapsedRealtime();
            }
            int mode = getSystemService(android.media.AudioManager.class).getMode();
            boolean ownsCommunication = session != null && session.usesSystemCommunicationRoute();
            if (mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION && !ownsCommunication
                    || mode == android.media.AudioManager.MODE_RINGTONE) {
                stopSession("检测到来电或通话，耳机会话已停止；结束后请手动开启"); return;
            }
            handler.postDelayed(this, 1000);
        }
    };
    private HostedSession session;
    private java.util.function.Consumer<String> stopped;
    private PowerManager.WakeLock wakeLock;
    private boolean enabled;

    public final class LocalBinder extends Binder {
        VoiceSessionService service() { return VoiceSessionService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "耳机会话", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VoiceSessionService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aidol 耳机会话已开启")
            .setContentText("AI 通话可锁屏继续；点击停止即可挂断")
            .setContentIntent(content).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "停止会话", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, notification);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aidol:VoiceTurn");
        wakeLock.setReferenceCounted(false);
        enabled = true;
        handler.postDelayed(expiry, 60 * 60 * 1000L);
        handler.post(callCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) stopSession("已从通知停止耳机会话；已提交内容请查看历史");
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    boolean enabled() { return enabled; }
    void onStopped(java.util.function.Consumer<String> callback) { stopped = callback; }

    void own(HostedSession active) {
        if (!enabled) throw new IllegalStateException("耳机会话已停止，请回到 App 重新开启");
        if (session != null && session != active) throw new IllegalStateException("上一轮尚未结束");
        session = active;
        // A continuing call renews its lease; silence is still part of that call.
        // Only idle standby expires, never a call because it reached one hour.
        handler.removeCallbacks(expiry);
        // Active persistent calls do not expire as idle standby.
        // Bound the wake lock even if a vendor callback or HTTP request never returns.
        wakeLock.acquire(600_000L); wakeRenewedAt = android.os.SystemClock.elapsedRealtime();
    }
    void release(HostedSession completed) {
        release(completed, false);
    }
    void release(HostedSession completed, boolean continuingCall) {
        if (session != completed) return;
        session = null;
        if (!continuingCall && wakeLock.isHeld()) wakeLock.release();
        if (!continuingCall && enabled) handler.postDelayed(expiry, 60 * 60 * 1000L);
    }
    void stopSession(String reason) {
        if (!enabled) return;
        enabled = false;
        handler.removeCallbacks(expiry);
        handler.removeCallbacks(callCheck);
        HostedSession active = session; session = null;
        if (active != null) active.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        java.util.function.Consumer<String> callback = stopped; stopped = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (callback != null) callback.accept(reason);
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSession("App 已关闭，耳机会话已停止"); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() { stopSession("耳机会话已停止，请回到 App 重新开启"); super.onDestroy(); }
}
