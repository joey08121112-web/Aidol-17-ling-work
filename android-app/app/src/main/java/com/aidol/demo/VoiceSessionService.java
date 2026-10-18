package com.aidol.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Explicit, non-restoring lock-screen lease. It never starts the microphone itself. */
public final class VoiceSessionService extends Service {
    enum StopCause { USER, SYSTEM }
    private static final String CHANNEL = "headset_session";
    private static final String STOP = "com.aidol.demo.STOP_HEADSET_SESSION";
    private static final int NOTIFICATION = 31;
    static final long WAKE_LEASE_MILLIS = 600_000L, WAKE_RENEW_AFTER_MILLIS = 300_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Binder binder = new LocalBinder();
    private final Runnable expiry = () -> { if (this.session == null) stopSession("耳机待命已满一小时，请回到 App 重新开启"); };
    private long wakeRenewedAt;
    /** Silence is still part of a live call; its bounded lease is renewed on schedule, not polled. */
    private final Runnable wakeRenew = new Runnable() {
        @Override public void run() {
            if (!enabled || session == null || wakeLock == null) return;
            wakeLock.acquire(WAKE_LEASE_MILLIS); wakeRenewedAt = android.os.SystemClock.elapsedRealtime();
            handler.postDelayed(this, WAKE_RENEW_AFTER_MILLIS);
        }
    };
    /** Legacy fallback below API 31, which has no mode-change event. */
    private final Runnable callCheck = new Runnable() {
        @Override public void run() {
            if (!enabled) return;
            HostedSession current = session;
            if (current != null && current.recordsWorkCalls()) {
                // A work recording captures the call; it survives call modes and mirrors its status here.
                updateNotification(current.workSaving() ? "正在保存工作记录…" : current.workAudioStatus());
            } else if (stopForCallMode()) return;
            // Below API 31 this loop also watches for calls without mode-change events;
            // above it, it only keeps running while a work recording is active.
            if (Build.VERSION.SDK_INT < 31 || current != null && current.recordsWorkCalls())
                handler.postDelayed(this, 1000);
        }
    };
    private HostedSession session;
    private java.util.function.BiConsumer<String, StopCause> stopped;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioManager.OnModeChangedListener modeListener;
    private boolean enabled;
    private Notification.Builder notification;
    private String notificationText = "";

    public final class LocalBinder extends Binder {
        VoiceSessionService service() { return VoiceSessionService.this; }
    }

    /** Call states that must end a headset session; owning the route exempts communication mode. */
    static boolean shouldStopForCall(int mode, boolean ownsCommunication) {
        return mode == AudioManager.MODE_IN_CALL
            || mode == AudioManager.MODE_IN_COMMUNICATION && !ownsCommunication
            || mode == AudioManager.MODE_RINGTONE;
    }

    /** The lease is re-acquired halfway through so it never expires mid-call. */
    static boolean wakeRenewDue(long nowElapsed, long renewedAtElapsed) {
        return nowElapsed - renewedAtElapsed >= WAKE_RENEW_AFTER_MILLIS;
    }

    /** @return true when the current audio mode stopped the session for a call. */
    private boolean stopForCallMode() {
        if (audioManager == null) return false;
        // A work recording is capturing the call on purpose; the session must survive it.
        if (session != null && session.recordsWorkCalls()) return false;
        int mode = audioManager.getMode();
        boolean ownsCommunication = session != null && session.usesSystemCommunicationRoute();
        if (shouldStopForCall(mode, ownsCommunication)) {
            stopSession("检测到来电或通话，耳机会话已停止；结束后请手动开启"); return true;
        }
        return false;
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "耳机会话", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VoiceSessionService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aidol 耳机会话已开启")
            .setContentText("AI 通话可锁屏继续；点击停止即可挂断")
            .setContentIntent(content).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "停止会话", stop).build()).setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, notification.build());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aidol:VoiceTurn");
        wakeLock.setReferenceCounted(false);
        enabled = true;
        handler.postDelayed(expiry, 60 * 60 * 1000L);
        if (Build.VERSION.SDK_INT >= 31) {
            // One mode-change event replaces the per-second main-thread audio-manager query.
            modeListener = mode -> { if (enabled) stopForCallMode(); };
            audioManager.addOnModeChangedListener(getMainExecutor(), modeListener);
            // Catch an already-active call without waiting for the first change event.
            stopForCallMode();
        } else handler.post(callCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            if (intent != null && session != null && session.recordsWorkCalls()) {
                session.stopRecording(); updateNotification("正在结束并保存工作记录…");
            } else if (intent != null) {
                stopSession("已从通知停止耳机会话；已提交内容请查看历史", StopCause.USER);
            } else stopSession("耳机会话已停止，请回到 App 重新开启");
        }
        return START_NOT_STICKY;
    }
    private void updateNotification(String text) {
        if (!enabled || text.equals(notificationText)) return;
        notificationText = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION,
            notification.setContentTitle("Aidol 工作记录进行中").setContentText(text).build());
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    boolean enabled() { return enabled; }
    void onStopped(java.util.function.BiConsumer<String, StopCause> callback) { stopped = callback; }

    void own(HostedSession active) {
        if (!enabled) throw new IllegalStateException("耳机会话已停止，请回到 App 重新开启");
        if (session != null && session != active) throw new IllegalStateException("上一轮尚未结束");
        session = active;
        // A continuing call renews its lease; silence is still part of that call.
        // Only idle standby expires, never a call because it reached one hour.
        handler.removeCallbacks(expiry);
        // Active persistent calls do not expire as idle standby.
        // Bound the wake lock even if a vendor callback or HTTP request never returns.
        wakeLock.acquire(WAKE_LEASE_MILLIS); wakeRenewedAt = android.os.SystemClock.elapsedRealtime();
        handler.removeCallbacks(wakeRenew);
        handler.postDelayed(wakeRenew, WAKE_RENEW_AFTER_MILLIS);
        // A new turn may start a work recording; its lock-screen status feed restarts with the turn.
        handler.removeCallbacks(callCheck);
        handler.postDelayed(callCheck, 1000);
    }
    void release(HostedSession completed) {
        release(completed, false);
    }
    void release(HostedSession completed, boolean continuingCall) {
        if (session != completed) return;
        session = null;
        handler.removeCallbacks(wakeRenew);
        if (enabled && completed != null && completed.recordsWorkCalls()) {
            notificationText = "";
            getSystemService(NotificationManager.class).notify(NOTIFICATION,
                notification.setContentTitle("Aidol 耳机会话已开启").setContentText("AI 通话可锁屏继续；点击停止即可挂断").build());
        }
        if (!continuingCall && wakeLock.isHeld()) wakeLock.release();
        if (!continuingCall && enabled) handler.postDelayed(expiry, 60 * 60 * 1000L);
    }
    void stopSession(String reason) {
        stopSession(reason, StopCause.SYSTEM);
    }
    private void stopSession(String reason, StopCause cause) {
        if (!enabled) return;
        enabled = false;
        handler.removeCallbacks(expiry);
        handler.removeCallbacks(callCheck);
        handler.removeCallbacks(wakeRenew);
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null && audioManager != null) {
            audioManager.removeOnModeChangedListener(modeListener);
            modeListener = null;
        }
        HostedSession active = session; session = null;
        if (active != null) active.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        java.util.function.BiConsumer<String, StopCause> callback = stopped; stopped = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (callback != null) callback.accept(reason, cause);
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSession("App 已关闭，耳机会话已停止"); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() { stopSession("耳机会话已停止，请回到 App 重新开启"); super.onDestroy(); }
}
