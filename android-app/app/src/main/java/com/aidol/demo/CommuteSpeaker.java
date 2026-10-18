package com.aidol.demo;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.JSONObject;
import okhttp3.Call;

/** TTS output without opening a voice conversation or acquiring a microphone. */
final class CommuteSpeaker implements AutoCloseable {
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AudioManager audio;
    private volatile boolean closed, playing;
    private volatile long generation;
    private Call call;
    private MediaPlayer player;
    private PlaybackLease lease;
    private File file;
    private AudioFocusRequest focus;
    private Consumer<Boolean> callback;
    CommuteSpeaker(Context context) { this.context = context.getApplicationContext(); audio = context.getSystemService(AudioManager.class); }
    boolean busy() { return playing; }
    void speak(ApiClient api, String text, BooleanSupplier valid, Consumer<Boolean> done) {
        if (closed || playing || !valid.getAsBoolean() || audio.getMode() != AudioManager.MODE_NORMAL) { done.accept(false); return; }
        playing = true; long version = ++generation; String token = api.vault.token(); callback = done;
        BooleanSupplier cancelled = () -> closed || version != generation || !token.equals(api.vault.token());
        ui.postDelayed(() -> { if (version == generation && playing) finish(false); }, 90000);
        worker.execute(() -> {
            PlaybackLease acquired = null; File temporary = null;
            try {
                acquired = PlaybackLease.acquire(cancelled, 4000);
                Call request = api.call("POST", "/v1/speech", new JSONObject().put("text", text).toString().getBytes(StandardCharsets.UTF_8), "application/json");
                synchronized (this) { if (cancelled.getAsBoolean()) throw new InterruptedException(); call = request; }
                request.timeout().timeout(25, TimeUnit.SECONDS);
                byte[] wav = api.execute(request);
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                temporary = File.createTempFile("commute-voice-", ".wav", context.getCacheDir());
                Files.write(temporary.toPath(), wav);
                final File ready = temporary; final PlaybackLease owned = acquired;
                ui.post(() -> {
                    if (cancelled.getAsBoolean() || !valid.getAsBoolean() || audio.getMode() != AudioManager.MODE_NORMAL) { ready.delete(); owned.close(); if (version == generation) finish(false); return; }
                    file = ready; lease = owned;
                    try {
                        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener(change -> { if (change < 0) finish(false); }, ui).build();
                        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { finish(false); return; }
                        player = new MediaPlayer(); player.setAudioAttributes(attributes); player.setDataSource(ready.getAbsolutePath());
                        player.setOnPreparedListener(p -> { if (valid.getAsBoolean() && !cancelled.getAsBoolean()) p.start(); else finish(false); });
                        player.setOnCompletionListener(p -> finish(true)); player.setOnErrorListener((p, what, extra) -> { finish(false); return true; });
                        player.prepareAsync();
                    } catch (Exception failure) { finish(false); }
                });
            } catch (Exception failure) {
                if (acquired != null) acquired.close(); if (temporary != null) temporary.delete();
                ui.post(() -> { if (version == generation) finish(false); });
            }
        });
    }
    void stop() { finish(false); }
    private void finish(boolean success) {
        ++generation; playing = false;
        synchronized (this) { if (call != null) call.cancel(); call = null; }
        if (player != null) { player.release(); player = null; }
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        if (lease != null) { lease.close(); lease = null; }
        if (file != null) { file.delete(); file = null; }
        Consumer<Boolean> done = callback; callback = null; if (done != null) done.accept(success);
    }
    @Override public void close() { closed = true; stop(); ui.removeCallbacksAndMessages(null); worker.shutdownNow(); }
}
