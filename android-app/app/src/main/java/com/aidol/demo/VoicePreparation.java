package com.aidol.demo;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.SystemClock;
import java.util.UUID;
import java.util.function.Predicate;

/** One bounded standby connection. Ownership transfers once, only on a user start. */
final class VoicePreparation implements AutoCloseable {
    private static final long LIFETIME_MS = 110000;
    private final ApiClient api;
    private final String token;
    private final long created = SystemClock.elapsedRealtime();
    private final Handler handler;
    private final Runnable expiry = this::close;
    private DuplexVoice call;
    private ReplyPlayer player;

    VoicePreparation(Context context, ApiClient api, Predicate<AudioDeviceInfo> identity, Handler handler) {
        this.api = api; this.token = api.vault.token(); this.handler = handler;
        call = new DuplexVoice();
        player = new ReplyPlayer(context, AudioMode.RISE_SDK, identity);
        call.prepare(api, UUID.randomUUID().toString());
        ReplyPlayer output = player;
        Thread worker = new Thread(() -> {
            try { output.prepare(); }
            catch (Exception unavailable) { /* Retry allocation when actual playback starts. */ }
        }, "aidol-audio-prepare");
        worker.setDaemon(true); worker.start();
        handler.postDelayed(expiry, LIFETIME_MS);
    }
    synchronized boolean matches(ApiClient current) {
        return current == api && token.equals(current.vault.token());
    }
    synchronized boolean usable(ApiClient current) {
        return matches(current) && call != null && call.available()
            && SystemClock.elapsedRealtime() - created < LIFETIME_MS;
    }
    synchronized boolean claim(ApiClient current, HostedSession session) {
        if (!usable(current)) { close(); return false; }
        handler.removeCallbacks(expiry);
        session.prepared(call, player);
        call = null; player = null;
        return true;
    }
    @Override public synchronized void close() {
        handler.removeCallbacks(expiry);
        if (call != null) { call.cancel(); call = null; }
        if (player != null) { player.cancel(); player = null; }
    }
}
