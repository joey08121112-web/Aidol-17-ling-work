package com.aidol.demo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import okhttp3.Call;
import org.json.JSONObject;

/** One foreground HTTP turn. Capture ownership and routing are injected independently. */
public final class HostedSession {
    public interface Listener {
        void status(String text);
        default void failure(String text) { status(text); }
        default void input(String description) {}
        default void output(String description) {}
        default void diagnostic(String text) {}
        void result(JSONObject result);
        void recording();
        void meter(long bytes, double rms);
        void done();
    }
    private final ApiClient api;
    private final Listener listener;
    private final AudioMode mode;
    private final CaptureSource capture;
    private final Context context;
    private final Predicate<AudioDeviceInfo> outputIdentity;
    private volatile ReplyPlayer player;
    private final AudioManager audioManager;
    private final Handler ui;
    private final SessionEvents events = new SessionEvents();
    private final AtomicBoolean cancelled = new AtomicBoolean(), launched = new AtomicBoolean();
    private final CapturePromptSequence prompts = new CapturePromptSequence(cancelled::get);
    private final AtomicBoolean firstPcm = new AtomicBoolean();
    private final CountDownLatch end = new CountDownLatch(1);
    private final String sessionId = UUID.randomUUID().toString();
    private final String requestId = UUID.randomUUID().toString();
    private final long createdAt;
    private final Object vadLock = new Object();
    private final Object captureStartLock = new Object();
    private volatile CaptureTurn turn = new CaptureTurn(60);
    private volatile Call pending;
    private volatile VoiceUpload upload;
    private boolean liveUpload, liveSpeech;
    private volatile String failure;
    private String promptNotice = "";
    private volatile boolean captureActive;
    private volatile AudioFocusRequest captureFocus;
    private Thread worker;
    private long lastMeter;
    private int recordSeconds = 60;
    private String interactionSource = "direct_question";
    private boolean autoEnd;
    private SpeechEndpoint endpoint = new SpeechEndpoint(.025);
    private double voiceThreshold = .025;
    private final Runnable limit = this::stopRecording;
    private final Runnable startupTimeout = () -> failCapture("麦克风启动超时，请恢复或重连设备后重试");
    private final Runnable stopTimeout = () -> failCapture("录音停止未确认，已丢弃本轮，请恢复或重连耳机");

    public HostedSession(Context context, ApiClient api, Listener listener) {
        this(context, api, AudioMode.BLUETOOTH, listener);
    }
    public HostedSession(Context context, ApiClient api, AudioMode mode, Listener listener) {
        this(context, api, mode, systemCapture(context, mode), device -> false, listener);
    }
    public HostedSession(Context context, ApiClient api, AudioMode mode, CaptureSource capture,
                         Predicate<AudioDeviceInfo> outputIdentity, Listener listener) {
        this.mode = AudioMode.requirePhysical(mode);
        if (capture == null) throw new IllegalArgumentException("必须明确提供录音来源");
        this.api = api; this.listener = listener; this.capture = capture;
        this.context = context.getApplicationContext(); this.outputIdentity = outputIdentity;
        ui = new Handler(Looper.getMainLooper()); createdAt = SystemClock.elapsedRealtime();
        audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
    private static CaptureSource systemCapture(Context context, AudioMode mode) {
        AudioMode.requirePhysical(mode);
        switch (mode) {
            case BLUETOOTH: return new BluetoothCapture(context);
            case EMULATOR: throw new IllegalArgumentException("模拟器来源已停用，请使用安卓真机");
            case RISE_SDK: throw new IllegalArgumentException("Rise SDK 需要注入已连接的 VendorCaptureSource");
            default: throw new IllegalArgumentException("未知录音来源");
        }
    }
    public HostedSession configure(int seconds, boolean vad, double threshold) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        recordSeconds = Math.max(5, Math.min(60, seconds)); turn = new CaptureTurn(recordSeconds);
        autoEnd = vad; voiceThreshold = Math.max(.005, Math.min(.2, threshold));
        endpoint = new SpeechEndpoint(voiceThreshold); return this;
    }
    public HostedSession streaming(boolean voice, boolean speech) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        liveUpload = voice; liveSpeech = speech; return this;
    }
    public String sessionId() { return sessionId; }
    boolean usesSystemCommunicationRoute() { return mode == AudioMode.BLUETOOTH && captureActive; }
    public HostedSession interactionSource(boolean directQuestion) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        interactionSource = directQuestion ? "direct_question" : "ambient_capture";
        return this;
    }
    public String requestId() { return requestId; }
    public String phase() { return turn.phase().name(); }
    public String diagnostics() {
        return "session=" + sessionId + " request_id=" + requestId + " phase=" + phase()
            + " bytes=" + turn.size() + " elapsed_ms=" + (SystemClock.elapsedRealtime() - createdAt);
    }
    public void start() { launch(null, null, null, "aidol-voice"); }
    public void startText(String text) { launch(text, null, null, "aidol-chat"); }
    public void speak(String text) { launch(null, text, null, "aidol-play"); }
    /** Announce an already-confirmed settings change; never calls the backend or starts capture. */
    public void announceMode(String businessMode) { launch(null, null, LocalCue.forMode(businessMode), "aidol-mode-cue"); }
    private void launch(String text, String playback, LocalCue modeCue, String name) {
        if (!launched.compareAndSet(false, true)) throw new IllegalStateException("每个会话只能启动一次");
        worker = new Thread(() -> run(text, playback, modeCue), name); worker.start();
    }
    private void run(String text, String playback, LocalCue modeCue) {
        try {
            check(); diagnostic("开始");
            if (modeCue != null) {
                try {
                    playCue(modeCue); check();
                    status("操作提示已播放；等待你手动开始");
                } catch (Exception cueFailure) {
                    check(); fail(modeCue.label + "已切换；提示音未播放：" + audioFailure(cueFailure));
                }
                return;
            }
            if (playback != null) { play(playback); return; }
            JSONObject result;
            if (text == null) {
                if (liveUpload) {
                    upload = new VoiceUpload(partial -> status("正在听：" + partial));
                    upload.connect(api, requestId, interactionSource);
                    if (cancelled.get()) upload.cancel();
                    upload.awaitReady(); check();
                }
                prompts.start(() -> {
                    playCue(LocalCue.START);
                    // Allow the short media tail to clear before opening either microphone.
                    Thread.sleep(200);
                }, this::startCapture);
                if (!end.await(recordSeconds + 15L, TimeUnit.SECONDS))
                    failCapture("录音等待超时，已丢弃本轮，请恢复或重连设备");
                clearCaptureTimers(); check();
                if (failure != null) throw new IOException(failure);
                byte[] pcm = prompts.finish(() -> {
                    byte[] snapshot = turn.finish();
                    captureActive = false; releaseCaptureFocus(); diagnostic("结束并排空");
                    if (snapshot.length < 3200) throw new IOException("录音太短，请重新录音");
                    // Flush ASR as soon as the hardware drain is confirmed;
                    // the short END cue can play while the backend finalizes.
                    if (upload != null) upload.finish();
                    return snapshot;
                }, () -> playCue(LocalCue.END), cueFailure -> {
                    promptNotice = "结束提示音未播放";
                    status("录音已结束，提示音未播放；正在提交录音");
                    diagnostic("结束提示音失败 · " + audioFailure(cueFailure));
                });
                if (pcm.length < 3200) throw new IOException("录音太短，请重新录音");
                status("正在识别并整理回复…"); diagnostic("上传 bytes=" + pcm.length);
                if (upload != null) {
                    result = upload.awaitResult();
                } else result = new JSONObject(new String(request("/v1/voice?request_id=" + requestId + "&interaction_source=" + interactionSource,
                    pcm, "audio/pcm"), StandardCharsets.UTF_8));
            } else {
                status("正在思考…");
                result = new JSONObject(new String(request("/v1/chat", new JSONObject().put("text", text)
                    .put("interaction_source", interactionSource)
                    .put("request_id", requestId).toString().getBytes(StandardCharsets.UTF_8), "application/json"), StandardCharsets.UTF_8));
            }
            check(); diagnostic("业务响应"); post(() -> listener.result(result));
            String reply = result.optString("response");
            if (result.optBoolean("speak", true) && !reply.isEmpty()) {
                try { play(reply); }
                catch (Exception e) {
                    check(); fail("文字已保存。" + (e instanceof IOException ? e.getMessage() : "播放失败，请检查音频输出"));
                    return;
                }
            }
            String notice = result.optString("notice").isEmpty() ? "本轮完成 · 随时继续" : result.optString("notice");
            status(notice + (promptNotice.isEmpty() ? "" : "；" + promptNotice));
        } catch (Exception e) {
            if (!cancelled.get()) {
                if (e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 401) api.vault.clear();
                fail(e instanceof java.net.SocketTimeoutException ? "网络超时，请查看历史记录确认结果后再试"
                    : e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 409
                    ? "本轮结果尚未确认，请先查看历史记录；不会自动重新提交录音"
                    : e instanceof IOException ? e.getMessage() : "连接失败，请检查设备和网络后重试");
            }
        } finally {
            captureActive = false; clearCaptureTimers();
            capture.close(); releaseCaptureFocus(); cancelPlayer();
            if (upload != null) upload.cancel();
            if (pending != null) pending.cancel();
            if (turn.phase() != CaptureTurn.Phase.ENDED && turn.phase() != CaptureTurn.Phase.FAILED) turn.cancel();
            // Cancellation invalidates queued done too; UI clears its active generation on cancel.
            post(listener::done);
        }
    }
    private void startCapture() throws Exception {
        synchronized (captureStartLock) { check(); captureActive = true; }
        requestCaptureFocus(); check();
        switch (mode) {
            case RISE_SDK: status("正在启动 Rise 耳机麦克风…"); break;
            case BLUETOOTH: status("正在连接系统蓝牙麦克风…"); break;
            case EMULATOR: throw new IllegalStateException("模拟器来源已停用，请使用安卓真机");
        }
        ui.postDelayed(startupTimeout, 12000);
        // Mark/check readiness under one session gate, but never call a source while holding it:
        // SDK callbacks may synchronously report failure under the source's own lifecycle gate.
        synchronized (captureStartLock) {
            check();
            if (!captureActive || failure != null || turn.phase() != CaptureTurn.Phase.NEW)
                throw new IOException(failure == null ? "录音准备已中止，请重新开始" : failure);
        }
        // Terminal cleanup closes the source even before start; the SDK also checks its generation
        // at the queued command boundary. A failure between this gate and start stays final.
        capture.start(new CaptureSource.Listener() {
            public void status(String value) { if (captureActive) HostedSession.this.status(value); }
            public void input(String description) { if (captureActive) post(() -> listener.input(description)); }
            public void recording() {
                if (cancelled.get() || !captureActive || !turn.recording(SystemClock.elapsedRealtime())) return;
                ui.removeCallbacks(startupTimeout);
                diagnostic("录音就绪"); post(listener::recording);
                ui.postDelayed(limit, turn.recordingBudgetMillis());
            }
            public void error(String value) { failCapture(value); }
            public void pcm(byte[] bytes) { acceptPcm(bytes); }
        });
    }
    private void acceptPcm(byte[] bytes) {
        if (cancelled.get() || !captureActive) return;
        long now = SystemClock.elapsedRealtime();
        try {
            if (!turn.append(bytes, now)) return;
            if (upload != null) upload.audio(bytes);
            if (firstPcm.compareAndSet(false, true)) diagnostic("首个 PCM");
            double level = AudioFrames.rms(bytes);
            boolean silenceEnd = false;
            synchronized (vadLock) {
                if (autoEnd) {
                    silenceEnd = endpoint.accept(bytes);
                }
                if (now - lastMeter > 300) {
                    lastMeter = now; long size = turn.size(); post(() -> listener.meter(size, level));
                }
            }
            if (silenceEnd || turn.shouldStop(now)) stopRecording();
        } catch (IOException e) { failCapture(e.getMessage()); }
    }
    public void stopRecording() {
        if (cancelled.get() || !captureActive) return;
        long now = SystemClock.elapsedRealtime();
        if (!turn.requestStop(now)) return;
        ui.removeCallbacks(limit); status("正在结束录音…"); diagnostic("请求停止");
        ui.postDelayed(stopTimeout, turn.remainingStopMillis(now));
        try {
            capture.stop(new CaptureSource.StopCallback() {
                public void ended() {
                    try {
                        if (cancelled.get() || !turn.drained(SystemClock.elapsedRealtime())) return;
                        diagnostic("停止确认并排空"); ui.removeCallbacks(stopTimeout); end.countDown();
                    } catch (IOException e) { failCapture(e.getMessage()); }
                }
                public void error(String message) { failCapture(message); }
            });
        } catch (RuntimeException e) { failCapture("停止录音失败，请恢复或重连耳机"); }
    }
    private void failCapture(String message) {
        synchronized (captureStartLock) {
            synchronized (this) {
                if (cancelled.get() || !captureActive || turn.phase() == CaptureTurn.Phase.DRAINING
                    || turn.phase() == CaptureTurn.Phase.ENDED) return;
                if (failure == null) failure = message;
                turn.fail(failure); captureActive = false;
            }
        }
        clearCaptureTimers();
        if (upload != null) upload.cancel();
        capture.close(); releaseCaptureFocus(); end.countDown(); diagnostic("录音失败");
    }
    private void requestCaptureFocus() throws IOException {
        if (mode != AudioMode.RISE_SDK) return;
        if (audioManager.getMode() == AudioManager.MODE_IN_CALL || audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
            throw new IOException("通话或其他语音会话正在进行，请结束后重试");
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0) failCapture("录音被其他音频或来电打断，请结束后重新开始");
            }).build();
        captureFocus = focus;
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("无法取得录音音频焦点，请结束通话或其他录音后重试");
    }
    private synchronized void releaseCaptureFocus() {
        if (captureFocus != null) {
            audioManager.abandonAudioFocusRequest(captureFocus); captureFocus = null;
        }
    }
    private byte[] request(String path, byte[] body, String type) throws Exception {
        check(); Call call = api.call("POST", path, body, type, requestId); pending = call;
        if (cancelled.get()) { call.cancel(); check(); }
        return api.execute(call); // No automatic retry or regenerated request ID.
    }
    private void play(String text) throws Exception {
        String stage = "语音合成请求";
        try {
            if (liveSpeech) { playStreaming(text); return; }
            status("正在准备语音…"); diagnostic("TTS 请求");
            byte[] wav = request("/v1/speech", new JSONObject().put("text", text.substring(0, Math.min(text.length(), 1500)))
                .toString().getBytes(StandardCharsets.UTF_8), "application/json");
            stage = "音频输出"; diagnostic("TTS 响应"); check();
            status("正在核验耳机播放…");
            playAudio(WavAudio.pcm(wav));
            check(); diagnostic("播放完成（听音待人工确认）");
        } catch (Exception e) {
            check();
            if (e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 401) api.vault.clear();
            throw new IOException("播放失败：" + stage + " · " + (e.getMessage() == null ? "请检查音频输出后重试" : e.getMessage()), e);
        }
    }
    private void playStreaming(String text) throws Exception {
        status("正在准备语音…");
        Call call = api.call("POST", "/v1/speech/stream", new JSONObject().put("text", text.substring(0, Math.min(text.length(), 1500)))
            .toString().getBytes(StandardCharsets.UTF_8), "application/json", requestId);
        pending = call;
        if (cancelled.get()) { call.cancel(); check(); }
        try (okhttp3.Response response = call.execute()) {
            if (!response.isSuccessful()) throw new ApiClient.ApiException(response.code(), "语音服务暂不可用，文字回复已保留");
            if (response.body() == null || !"24000".equals(response.header("X-Aidol-Audio-Rate")))
                throw new IOException("语音服务返回格式无效");
            ReplyPlayer segment = new ReplyPlayer(context, mode, outputIdentity);
            player = segment;
            try (PlaybackLease ignored = PlaybackLease.acquire(cancelled::get, 4000);
                 PcmFrameInputStream stream = new PcmFrameInputStream(response.body().byteStream())) {
                check();
                segment.playStream(stream, this::status, description -> post(() -> listener.output(description)));
                check();
            } finally {
                segment.cancel();
                if (player == segment) player = null;
            }
        }
    }
    private void playCue(LocalCue cue) throws Exception {
        check(); status("正在播放提示：" + cue.label); diagnostic("本地操作提示 " + cue.name());
        playAudio(cue.pcm(context)); check();
        diagnostic("操作提示播放完成（听音待人工确认）");
    }
    private void playAudio(byte[] pcm) throws Exception {
        // Focus/route loss cancels a ReplyPlayer permanently. Give each segment its own player,
        // so a failed END cue cannot silently suppress the subsequent business reply.
        ReplyPlayer segment = new ReplyPlayer(context, mode, outputIdentity);
        player = segment;
        try (PlaybackLease ignored = PlaybackLease.acquire(cancelled::get, 4000)) {
            check();
            segment.play(pcm, this::status, description -> {
                post(() -> listener.output(description)); diagnostic("实际输出已核验");
            });
            check();
        } finally {
            segment.cancel();
            if (player == segment) player = null;
        }
    }
    private static String audioFailure(Exception failure) {
        return failure instanceof IOException && failure.getMessage() != null ? failure.getMessage() : "请检查耳机音频输出";
    }
    private void cancelPlayer() {
        ReplyPlayer active = player;
        if (active != null) active.cancel();
    }
    private void post(Runnable event) {
        if (cancelled.get()) return;
        long generation = events.generation();
        ui.post(() -> events.deliver(generation, () -> { if (!cancelled.get()) event.run(); }));
    }
    private void diagnostic(String stage) { if (BuildConfig.DEBUG) { String value = stage + " · " + diagnostics(); post(() -> listener.diagnostic(value)); } }
    private void status(String text) { post(() -> listener.status(text)); }
    private void fail(String text) { post(() -> listener.failure(text)); }
    private void check() throws InterruptedException { if (cancelled.get()) throw new InterruptedException(); }
    private void clearCaptureTimers() { ui.removeCallbacks(limit); ui.removeCallbacks(startupTimeout); ui.removeCallbacks(stopTimeout); }
    /** Mode changes may discard live capture, but must not cancel a turn already stopping or submitted. */
    public boolean cancelIfRecording() {
        if (cancelled.get() || !turn.cancelRecording()) return false;
        cancel(); return true;
    }
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        events.invalidate(); captureActive = false; turn.cancel(); clearCaptureTimers(); end.countDown();
        if (upload != null) upload.cancel();
        if (pending != null) pending.cancel(); cancelPlayer();
        capture.close();
        releaseCaptureFocus();
        if (worker != null) worker.interrupt();
    }
}
