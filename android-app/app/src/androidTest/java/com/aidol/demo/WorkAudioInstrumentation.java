package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.WindowManager;
import com.aidol.demo.hardware.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.*;

/** Explicit USB hardware review: generated media + real SDK mic, in-memory fake backend, no ASR/model calls. */
public final class WorkAudioInstrumentation extends Instrumentation {
    private MainActivity activity;
    private HostedSession session;
    private VoiceSessionService service;
    private AudioTrack music;
    private AudioManager manager;
    private AudioFocusRequest focus;
    private MockWebServer server;
    private final AtomicBoolean playing = new AtomicBoolean();
    private Thread player;
    private final AtomicLong bytes = new AtomicLong(), uploaded = new AtomicLong();
    private final AtomicInteger focusLosses = new AtomicInteger();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch recording = new CountDownLatch(1), done = new CountDownLatch(1);

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            SystemClock.sleep(2500);
            if (!getTargetContext().getSystemService(PowerManager.class).isInteractive()
                    || getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new AssertionError("Unlock phone for audio review");
            if (field(activity, "session") != null) throw new AssertionError("An existing session is active");
            if ((Boolean) invoke(activity, "lockScreenEnabled")) throw new AssertionError("Existing foreground session must finish before test");
            HeadsetController headset = (HeadsetController) field(activity, "headset");
            for (int i = 0; i < 100 && !headset.snapshot().ready(); i++) SystemClock.sleep(200);
            if (!headset.snapshot().ready()) throw new AssertionError("Rise headset is not ready");
            manager = getTargetContext().getSystemService(AudioManager.class);
            if (manager.getMode() != AudioManager.MODE_NORMAL || headset.snapshot().inCall) throw new AssertionError("A real call is active");

            server = new MockWebServer();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    try {
                        if (path.contains("/live?")) return new MockResponse().setResponseCode(503).setBody("{\"detail\":\"offline review\"}");
                        if (path.startsWith("/v1/voice?")) {
                            uploaded.addAndGet(request.getBodySize()); request.getBody().clear();
                            return json(new JSONObject().put("meeting_id", session.sessionId()).put("text", ""));
                        }
                        if (path.endsWith("/events")) return json(new JSONObject(request.getBody().readUtf8()));
                        if (path.endsWith("/finish")) return json(new JSONObject().put("id", session.sessionId()).put("status", "saved"));
                        if (path.equals("/v1/work-meetings")) return json(new JSONObject().put("id", session.sessionId()).put("status", "recording"));
                    } catch (Exception error) { failure.set(error.toString()); }
                    return new MockResponse().setResponseCode(404);
                }
                private MockResponse json(JSONObject value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString()); }
            });
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            // Test package storage is isolated from the signed-in account; only this client's URL is changed.
            ApiClient fixtureApi = new ApiClient(getContext(), ServerEndpoint.USB_LOCAL);
            set(fixtureApi, "serverUrl", "http://127.0.0.1:" + server.getPort());
            session = new HostedSession(getTargetContext(), fixtureApi, AudioMode.RISE_SDK,
                new VendorCaptureSource(headset, true, true), headset::matchesOutput, new HostedSession.Listener() {
                    public void status(String text) {}
                    public void failure(String text) { failure.set(text); }
                    public void result(JSONObject value) {}
                    public void recording() { recording.countDown(); }
                    public void meter(long count, double rms) { bytes.set(count); }
                    public void done() { done.countDown(); }
                }).workRecording(true);
            // Exercise the real service and Activity background policy, without writing a user meeting.
            runOnMainSync(() -> uncheckedInvoke(activity, "enableLockScreenSession"));
            for (int i = 0; i < 50 && field(activity, "voiceService") == null; i++) SystemClock.sleep(100);
            service = (VoiceSessionService) field(activity, "voiceService");
            if (service == null || !service.enabled()) throw new AssertionError("Recording foreground service unavailable");
            runOnMainSync(() -> {
                uncheckedSet(activity, "session", session); uncheckedSet(activity, "sessionUsesCapture", true);
                service.own(session);
            });
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0) focusLosses.incrementAndGet(); }).build();
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new AssertionError("Media focus unavailable");
            music = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(48000).setTransferMode(AudioTrack.MODE_STREAM).build();
            music.setVolume(.08f); music.play(); playing.set(true);
            player = new Thread(() -> {
                short[] tone = new short[9600]; long frame = 0;
                while (playing.get()) {
                    for (int i = 0; i < tone.length; i += 2, frame++) {
                        short value = (short) (Math.sin(frame * 2 * Math.PI * 440 / 48000) * 2500);
                        tone[i] = tone[i + 1] = value;
                    }
                    music.write(tone, 0, tone.length, AudioTrack.WRITE_BLOCKING);
                }
            }, "review-public-tone"); player.start();
            SystemClock.sleep(700); session.start();
            if (!recording.await(15, TimeUnit.SECONDS)) throw new AssertionError("SDK did not start: " + failure.get());
            int beforeHead = music.getPlaybackHeadPosition();
            SystemClock.sleep(3500);
            AudioDeviceInfo route = music.getRoutedDevice();
            if (route == null || route.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || !headset.matchesOutput(route)) throw new AssertionError("Music not routed to the connected Rise A2DP output");
            if (focusLosses.get() != 0 || field(session, "captureFocus") != null) throw new AssertionError("Work capture stole media focus");
            if (bytes.get() < 32000 || music.getPlaybackHeadPosition() - beforeHead < 48000) throw new AssertionError("Concurrent microphone/playback did not advance");
            if (!session.pauseWork()) throw new AssertionError("Cannot pause work recording");
            long pausedOffset = session.workOffsetMs(); SystemClock.sleep(500);
            if (session.workOffsetMs() != pausedOffset) throw new AssertionError("Paused audio was retained");
            if (!session.resumeWork()) throw new AssertionError("Cannot resume work recording");
            long foregroundBytes = bytes.get();
            runOnMainSync(() -> activity.moveTaskToBack(true)); SystemClock.sleep(2000);
            if (field(activity, "session") != session || !service.enabled() || session.workSaving() || bytes.get() <= foregroundBytes) throw new AssertionError("Moving away stopped work capture");
            runOnMainSync(() -> service.onStartCommand(new Intent().setAction("com.aidol.demo.STOP_HEADSET_SESSION"), 0, 1));
            if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("Notification stop did not drain/save");
            if (failure.get() != null) throw new AssertionError(failure.get());
            if (uploaded.get() < 32000 || focusLosses.get() != 0) throw new AssertionError("Missing saved audio or media focus loss");
            result.putString("stream", "PASS: real Rise A2DP playback + SDK microphone concurrently; work owns no playback focus; pause drops audio; Activity background retains capture; notification stop drains/saves to in-memory fixture. mic_bytes=" + bytes.get() + ", uploaded_bytes=" + uploaded.get() + ". No provider calls, persisted audio, real telephone call, or user meeting changes.");
            outcome = Activity.RESULT_OK;
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); }
        finally {
            if (session != null) session.cancel(); playing.set(false);
            if (music != null) { music.pause(); music.flush(); }
            if (player != null) try { player.join(2000); } catch (InterruptedException ignored) {}
            if (music != null) music.release();
            if (manager != null && focus != null) manager.abandonAudioFocusRequest(focus);
            if (activity != null) runOnMainSync(() -> {
                uncheckedSet(activity, "session", null); uncheckedSet(activity, "sessionUsesCapture", false);
                if (service != null) { service.release(session); service.onStopped(null); }
                uncheckedInvoke(activity, "stopLockScreenSession");
            });
            if (server != null) try { server.shutdown(); } catch (Exception ignored) {}
        }
        finish(outcome, result);
    }
    private static Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    private static Object invoke(Object object, String name) throws Exception { Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
    private static void uncheckedInvoke(Object object, String name) { try { invoke(object, name); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void uncheckedSet(Object object, String name, Object value) { try { set(object, name, value); } catch (Exception e) { throw new RuntimeException(e); } }
}
