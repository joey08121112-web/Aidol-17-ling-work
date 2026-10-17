package com.aidol.demo;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Short, offline nonverbal UI feedback. Shares the verified headset route, never the conversation. */
final class QuickFeedback implements AutoCloseable {
    private final Context context;
    private final Predicate<AudioDeviceInfo> outputIdentity;
    private final BooleanSupplier allowed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FeedbackThrottle throttle = new FeedbackThrottle();
    private Runnable pending;
    private ReplyPlayer active;
    private boolean closed;

    QuickFeedback(Context context, Predicate<AudioDeviceInfo> outputIdentity, BooleanSupplier allowed) {
        this.context = context.getApplicationContext();
        this.outputIdentity = outputIdentity;
        this.allowed = allowed;
    }

    // Called on the main thread. A burst keeps only its latest hint; earcons never queue.
    void say(LocalCue cue) {
        if (pending != null) ui.removeCallbacks(pending);
        pending = null;
        if (closed || active != null || !allowed.getAsBoolean()) return;
        pending = () -> {
            pending = null;
            if (closed || active != null || !allowed.getAsBoolean()
                || !throttle.accept(cue.name(), SystemClock.elapsedRealtime())) return;
            ReplyPlayer player = new ReplyPlayer(context, AudioMode.RISE_SDK, outputIdentity);
            active = player;
            worker.execute(() -> {
                try { player.play(cue.pcm(context), ignored -> {}); }
                catch (Exception unavailable) { /* The full hint remains visible in the page. */ }
                finally { ui.post(() -> { if (active == player) active = null; }); }
            });
        };
        ui.postDelayed(pending, 180);
    }

    void stop() {
        if (pending != null) ui.removeCallbacks(pending);
        pending = null;
        // Keep the slot occupied until the worker releases its audio focus and route.
        if (active != null) active.cancel();
    }

    @Override public void close() {
        closed = true;
        stop();
        worker.shutdownNow();
    }
}
