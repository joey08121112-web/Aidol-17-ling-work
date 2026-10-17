package com.aidol.demo;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import okhttp3.Call;
import okhttp3.Response;

/**
 * Plays one saved meeting recording from a timeline position on the verified headset route.
 * Holds the exclusive playback lease, so it never overlaps capture; pausing releases everything
 * and resumes from the heard position. All methods run on the main thread.
 */
final class MeetingPlayback implements AutoCloseable {
    enum State { IDLE, LOADING, PLAYING, PAUSED }
    interface Listener { void changed(); }

    private static final class Run {
        final String meetingId; final long startMs, endMs;
        final ReplyPlayer player;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile MeetingAudioStream stream;
        Run(String meetingId, long startMs, long endMs, ReplyPlayer player) {
            this.meetingId = meetingId; this.startMs = startMs; this.endMs = endMs; this.player = player;
        }
        void cancel() {
            cancelled.set(true); player.cancel();
            for (Call call : calls) call.cancel();
        }
    }

    private final Context context;
    private final Predicate<AudioDeviceInfo> outputIdentity;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService prefetch = Executors.newCachedThreadPool();
    private State state = State.IDLE;
    private String meetingId = "", error = "", notice = "";
    private long endMs, pausedMs;
    private Run run;

    MeetingPlayback(Context context, Predicate<AudioDeviceInfo> outputIdentity, Listener listener) {
        this.context = context.getApplicationContext(); this.outputIdentity = outputIdentity; this.listener = listener;
    }

    State state() { return state; }
    String meetingId() { return meetingId; }
    /** Why the last playback ended early; empty after a normal end or a stop. */
    String error() { return error; }
    /** Route hints from the player, such as a muted media volume. */
    String notice() { return notice; }
    boolean active() { return state == State.LOADING || state == State.PLAYING; }

    long positionMs() {
        if (state == State.PAUSED) return pausedMs;
        Run current = run;
        if (current == null) return pausedMs;
        MeetingAudioStream stream = current.stream;
        return stream == null ? current.startMs
            : MeetingAudioStream.positionMs(current.startMs, current.player.playedFrames(), stream.delivered());
    }

    /** endMs is the end of saved audio on the timeline (0 when unknown). */
    void play(ApiClient api, String meetingId, long startMs, long endMs) {
        stopRun();
        this.meetingId = meetingId; this.endMs = endMs; pausedMs = startMs; error = ""; notice = "";
        Run started = new Run(meetingId, startMs, endMs, new ReplyPlayer(context, AudioMode.RISE_SDK, outputIdentity,
            MeetingAudioStream.SAMPLE_RATE, Long.MAX_VALUE));
        run = started; state = State.LOADING;
        new Thread(() -> work(api, started), "aidol-meeting-audio").start();
        listener.changed();
    }

    void pause() {
        if (!active()) return;
        pausedMs = positionMs();
        stopRun(); state = State.PAUSED; listener.changed();
    }

    void resume(ApiClient api) {
        if (state == State.PAUSED) play(api, meetingId, pausedMs, endMs);
    }

    void stop() {
        if (state == State.IDLE && run == null) return;
        stopRun(); state = State.IDLE; error = ""; listener.changed();
    }

    @Override public void close() {
        stopRun(); state = State.IDLE;
        prefetch.shutdownNow();
    }

    private void stopRun() {
        Run current = run; run = null;
        if (current != null) current.cancel();
    }

    private void work(ApiClient api, Run current) {
        String failure = "";
        try (PlaybackLease ignored = PlaybackLease.acquire(current.cancelled::get, 1500);
             MeetingAudioStream stream = new MeetingAudioStream(windows(api, current), current.startMs, current.endMs,
                 MeetingAudioStream.WINDOW_MS, prefetch)) {
            current.stream = stream;
            current.player.playStream(stream, hint -> ui.post(() -> {
                if (run == current) { notice = hint; listener.changed(); }
            }), output -> ui.post(() -> {
                if (run == current && state == State.LOADING) { state = State.PLAYING; listener.changed(); }
            }));
        } catch (InterruptedException stopped) {
            // Stopped before the lease was taken.
        } catch (Exception failed) {
            if (!current.cancelled.get()) failure = failed.getMessage() == null ? "会议录音播放失败，请重试" : failed.getMessage();
        } finally {
            current.cancel();
        }
        String ended = failure;
        ui.post(() -> {
            if (run != current) return;
            run = null; state = State.IDLE; error = ended; listener.changed();
        });
    }

    private static MeetingAudioStream.Windows windows(ApiClient api, Run current) {
        return (startMs, durationMs) -> {
            if (current.cancelled.get()) throw new IOException("已停止播放");
            // The token travels only in the Authorization header, never in this URL.
            Call call = api.mediaCall("/v1/work-meetings/" + Uri.encode(current.meetingId)
                + "/audio?start_ms=" + startMs + "&duration_ms=" + durationMs);
            current.calls.add(call);
            if (current.cancelled.get()) call.cancel();
            Response response;
            try { response = api.open(call); }
            catch (ApiClient.ApiException missing) {
                if (missing.status == 404) return null;
                throw missing;
            }
            return new FilterInputStream(response.body().byteStream()) {
                @Override public void close() { response.close(); }
            };
        };
    }
}
