package com.aidol.demo;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Local mode feedback owns only audio output, never a conversation or microphone. Main-thread API. */
final class ModeCuePlayer implements AutoCloseable {
    private final Context context;
    private final Predicate<AudioDeviceInfo> outputIdentity;
    private final Consumer<String> output, failure;
    private final Runnable finished;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ReplyPlayer active;
    private AtomicBoolean cancelled;
    private boolean closed;

    ModeCuePlayer(Context context, Predicate<AudioDeviceInfo> outputIdentity,
                  Consumer<String> output, Consumer<String> failure, Runnable finished) {
        this.context = context.getApplicationContext();
        this.outputIdentity = outputIdentity;
        this.output = output; this.failure = failure; this.finished = finished;
    }

    void play(String mode) {
        LocalCue cue = LocalCue.forMode(mode);
        stop();
        if (closed) return;
        ReplyPlayer player = new ReplyPlayer(context, AudioMode.RISE_SDK, outputIdentity);
        AtomicBoolean stopped = new AtomicBoolean();
        active = player; cancelled = stopped;
        worker.execute(() -> {
            try (PlaybackLease ignored = PlaybackLease.acquire(stopped::get, 4000)) {
                player.play(cue.pcm(context), ignoredLog -> {}, description -> ui.post(() -> {
                    if (active == player && !stopped.get()) output.accept(description);
                }));
            } catch (Exception unavailable) {
                ui.post(() -> { if (active == player && !stopped.get()) failure.accept(unavailable.getMessage()); });
            } finally {
                ui.post(() -> {
                    if (active != player) return;
                    active = null; cancelled = null;
                    if (!closed) finished.run();
                });
            }
        });
    }

    boolean active() { return active != null; }

    void stop() {
        if (cancelled != null) cancelled.set(true);
        if (active != null) active.cancel();
        active = null; cancelled = null;
    }

    @Override public void close() { closed = true; stop(); worker.shutdownNow(); }
}
