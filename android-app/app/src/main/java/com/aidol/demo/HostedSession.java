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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
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
        default void transcript(String text) {}
        default void reply(String text) {}
        default void idle() {}
        default void processing() {}
        default void submitted(String requestId) {}
        default void meeting(JSONObject meeting) {}
        /** Live captions: final sentences (saved by the server) and the current interim text. */
        default void captionFinals(org.json.JSONArray sentences) {}
        default void captionPartial(String text) {}
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
    private volatile String requestId = UUID.randomUUID().toString();
    private final long createdAt;
    private final Object vadLock = new Object();
    private final Object captureStartLock = new Object();
    private volatile CaptureTurn turn = new CaptureTurn(60);
    private volatile Call pending;
    private volatile VoiceUpload upload;
    private volatile boolean uploadStarted;
    private final VoicePreRoll preRoll = new VoicePreRoll();
    private boolean liveUpload, liveSpeech, liveReply, callTurn, openingCue = true;
    private final AtomicBoolean skipReply = new AtomicBoolean();
    private volatile String failure;
    private String promptNotice = "";
    private String replyFailure = "";
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
    private final Runnable stopTimeout = () -> failCapture("聆听停止未确认，已丢弃本轮，请恢复或重连耳机");

    /**
     * Overlaps the ASR end flush (socket ready handshake plus the end message) with the local
     * end cue: independent systems that were previously serialized. The chain joins before
     * proceeding and the flush failure surfaces exactly where the serial version threw it.
     */
    static final class TurnFlush {
        interface Step { void run() throws Exception; }
        private final AtomicReference<Exception> failure = new AtomicReference<>();
        private final Thread worker;
        TurnFlush(String name, Step step) {
            worker = new Thread(() -> {
                try { step.run(); }
                catch (Exception error) { failure.compareAndSet(null, error); }
            }, name);
            worker.setDaemon(true);
        }
        TurnFlush start() { worker.start(); return this; }
        void await() throws Exception {
            worker.join();
            Exception error = failure.get();
            if (error != null) throw error;
        }
        void cancel() { worker.interrupt(); }
    }

    public HostedSession(Context context, ApiClient api, Listener listener) {
        this(context, api, AudioMode.RISE_SDK, listener);
    }
    public HostedSession(Context context, ApiClient api, AudioMode mode, Listener listener) {
        this(context, api, mode, systemCapture(context, mode), device -> false, listener);
    }
    public HostedSession(Context context, ApiClient api, AudioMode mode, CaptureSource capture,
                         Predicate<AudioDeviceInfo> outputIdentity, Listener listener) {
        this.mode = AudioMode.requirePhysical(mode);
        if (capture == null) throw new IllegalArgumentException("必须明确提供聆听来源");
        this.api = api; this.listener = listener; this.capture = capture;
        this.context = context.getApplicationContext(); this.outputIdentity = outputIdentity;
        ui = new Handler(Looper.getMainLooper()); createdAt = SystemClock.elapsedRealtime();
        audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
    private static CaptureSource systemCapture(Context context, AudioMode mode) {
        AudioMode.requirePhysical(mode);
        switch (mode) {
            case EMULATOR: throw new IllegalArgumentException("模拟器来源已停用，请使用安卓真机");
            case RISE_SDK: throw new IllegalArgumentException("Rise SDK 需要注入已连接的 VendorCaptureSource");
            default: throw new IllegalArgumentException("未知聆听来源");
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
    private volatile DuplexVoice duplex;
    private volatile ReplyPlayer openingPlayer;
    void prepared(DuplexVoice call, ReplyPlayer output) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        duplex = call; openingPlayer = output; requestId = call.requestId();
    }
    public boolean isDuplexCall() { return duplex != null; }
    public void sendDuplexText(String value) {
        try { duplex.text(value); } catch (IOException error) { status(error.getMessage()); }
    }
    private BooleanSupplier locationForeground = () -> false;
    public HostedSession locationForeground(BooleanSupplier foreground) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        locationForeground = foreground; return this;
    }
    private boolean workRecording;
    private volatile String workAudioStatus = "正在准备工作录音";
    public boolean recordsWorkCalls() { return workRecording && mode == AudioMode.RISE_SDK; }
    public String workAudioStatus() { return workPaused() ? "已暂停 · 不记录、不上传" : workAudioStatus; }
    private final Runnable workPhoneCheck = new Runnable() {
        @Override public void run() {
            if (!recordsWorkCalls() || cancelled.get() || !captureActive) return;
            updateWorkPhoneState();
            ui.postDelayed(this, 250);
        }
    };
    private void updateWorkPhoneState() {
        int value = audioManager.getMode();
        capture.phoneAudioState(value == AudioManager.MODE_RINGTONE ? 1
            : value == AudioManager.MODE_IN_CALL || value == AudioManager.MODE_IN_COMMUNICATION ? 2 : 0);
    }
    private final AtomicBoolean workStopping = new AtomicBoolean();
    private WorkAudioBuffer workBuffer;
    private volatile boolean workMeetingStarted;
    private int workSegmentCount;
    private volatile int workMarks;
    // Pause/resume/mark receipts are posted in order, off the segment-upload worker.
    private final ExecutorService workEvents = Executors.newSingleThreadExecutor(r -> new Thread(r, "aidol-work-events"));
    // Streaming ASR is the main source of meeting text; segments then upload audio only.
    private volatile WorkLiveCaptions live;
    private volatile boolean liveWanted;
    private final Runnable liveReconnect = this::reconnectLive;
    /** Wakes the work loop for a closed segment, capture end or failure, instead of 10 Hz polling. */
    private final PlaybackLease.EventSignal workArrived = new PlaybackLease.EventSignal();
    private static final long WORK_IDLE_FALLBACK_MILLIS = 1000;
    public String workMeetingId() { return workRecording && workMeetingStarted ? sessionId : ""; }
    public boolean workSaving() { return workRecording && workStopping.get(); }
    public boolean workPaused() { WorkAudioBuffer buffer = workBuffer; return buffer != null && buffer.paused(); }
    public int workMarks() { return workMarks; }
    /** Recorded time on the meeting timeline, excluding pauses. */
    public long workOffsetMs() { WorkAudioBuffer buffer = workBuffer; return buffer == null ? 0 : buffer.offsetMs(); }
    /** Stop keeping audio; the headset stream stays connected but paused PCM is discarded. */
    public boolean pauseWork() {
        WorkAudioBuffer buffer = workBuffer;
        if (!activeWorkCapture() || buffer == null) return false;
        try {
            long offset = buffer.pause();
            if (offset < 0) return false;
            capture.paused(true);
            // Pausing closes the partial segment; the loop should see it without waiting.
            workArrived.signal();
            WorkLiveCaptions current = live; if (current != null) current.pause();
            postWorkEvent("pause", offset, "app");
            status("已暂停 · 不记录、不上传；点“继续”恢复");
            return true;
        } catch (IOException error) { workFailure(error.getMessage()); return false; }
    }
    public boolean resumeWork() {
        WorkAudioBuffer buffer = workBuffer;
        if (!activeWorkCapture() || buffer == null) return false;
        capture.paused(false);
        long offset = buffer.resume();
        if (offset < 0) return false;
        WorkLiveCaptions current = live; if (current != null) current.resume(offset);
        postWorkEvent("resume", offset, "app");
        status("已继续记录");
        return true;
    }
    /** @param origin "app" or "headset", stored with the mark. */
    public boolean markWork(String origin) {
        WorkAudioBuffer buffer = workBuffer;
        if (!activeWorkCapture() || buffer == null) return false;
        workMarks++;
        postWorkEvent("mark", buffer.offsetMs(), origin);
        return true;
    }
    /** True while double tap should mean "mark": capturing a work meeting, not yet saving. */
    public boolean workCapturing() { return activeWorkCapture(); }
    private boolean activeWorkCapture() {
        return workRecording && workMeetingStarted && captureActive && !workStopping.get() && !cancelled.get();
    }
    private void postWorkEvent(String kind, long offsetMs, String origin) {
        String eventId = UUID.randomUUID().toString();
        workEvents.execute(() -> {
            // Same event id on retry: the server stores each pause/resume/mark exactly once.
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    api.json("POST", "/v1/work-meetings/" + sessionId + "/events", new JSONObject()
                        .put("event_id", eventId).put("kind", kind).put("offset_ms", offsetMs).put("origin", origin));
                    return;
                } catch (ApiClient.ApiException error) {
                    if (error.status < 500) break;  // Conflict or ended meeting: retrying cannot help.
                } catch (Exception ignored) { /* Network: retry below. */ }
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException interrupted) { return; }
            }
            status("mark".equals(kind) ? "重点标记未能保存，请检查网络" : "暂停状态未能同步，录音时间轴可能不准确");
        });
    }
    public HostedSession workRecording(boolean enabled) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        workRecording = enabled; return this;
    }
    private boolean qwenRealtime;
    public HostedSession qwenRealtime(boolean enabled) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        qwenRealtime = enabled; return this;
    }
    public String sessionId() { return sessionId; }
    public boolean isReplyPlaying() { return turn.phase() == CaptureTurn.Phase.ENDED && player != null && !cancelled.get(); }
    boolean usesSystemCommunicationRoute() { return false; }
    public HostedSession callExperience(boolean direct, boolean opening, boolean streamedReply) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        callTurn = direct; openingCue = opening; liveReply = streamedReply && direct && liveUpload;
        if (direct) { autoEnd = !qwenRealtime; endpoint = new SpeechEndpoint(voiceThreshold, 600); }
        return this;
    }
    /** Interrupt speech, but allow an already-submitted turn to finish exactly once. */
    public boolean interruptReply() {
        if (cancelled.get() || turn.phase() != CaptureTurn.Phase.ENDED) return false;
        skipReply.set(true);
        VoiceUpload active = upload;
        if (active != null) active.interruptReply();
        cancelPlayer();
        status("已打断回复，正在接续聆听…");
        return true;
    }
    public HostedSession interactionSource(boolean directQuestion) {
        return interactionSource(directQuestion ? "direct_question" : "ambient_capture");
    }
    public HostedSession interactionSource(String source) {
        if (launched.get()) throw new IllegalStateException("会话已开始");
        if (!"direct_question".equals(source) && !"ambient_capture".equals(source) && !"continuous_call".equals(source))
            throw new IllegalArgumentException("未知会话来源");
        interactionSource = source;
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
            if (qwenRealtime && callTurn && text == null) { runDuplex(); return; }
            if (workRecording && text == null) { runWorkRecording(); return; }
            JSONObject result;
            if (text == null) {
                if (liveUpload) {
                    upload = new VoiceUpload(partial -> post(() -> listener.transcript(partial)),
                        answer -> post(() -> listener.reply(answer)));
                    upload.onSpeechEnd(() -> post(this::stopRecording));
                    upload.enableReply(liveReply);
                    upload.useQwenRealtime(qwenRealtime && liveReply);
                    if (!callTurn || qwenRealtime) { upload.connect(api, requestId, interactionSource); uploadStarted = true; }
                    if (cancelled.get()) upload.cancel();
                }
                prompts.start(() -> {
                    if (openingCue) playAudio(callTone());
                }, this::startCapture);
                if (!end.await(recordSeconds + 15L, TimeUnit.SECONDS))
                    failCapture("聆听等待超时，已丢弃本轮，请恢复或重连设备");
                clearCaptureTimers(); check();
                if (failure != null) throw new IOException(failure);
                final TurnFlush[] flush = new TurnFlush[1];
                byte[] pcm;
                try {
                    pcm = prompts.finish(() -> {
                        byte[] snapshot = turn.finish();
                        captureActive = false; releaseCaptureFocus(); diagnostic("结束并排空");
                        if (snapshot.length < 3200) throw new IOException("刚才没听清，请再说一次");
                        // The ASR end flush and the END cue wait on independent systems (socket
                        // handshake vs local audio); run them overlapped, not back to back.
                        if (upload != null && uploadStarted)
                            flush[0] = new TurnFlush("aidol-upload-flush", () -> {
                                upload.awaitReady(); check(); upload.finish();
                            }).start();
                        return snapshot;
                    }, () -> {}, cueFailure -> {
                        promptNotice = "结束提示音未播放";
                        status("聆听已结束，提示音未播放；正在提交聆听");
                        diagnostic("结束提示音失败 · " + audioFailure(cueFailure));
                    });
                    // Join the overlapped flush where the serial chain used to continue.
                    if (flush[0] != null) flush[0].await();
                } finally {
                    // An abandoned drain must not leave the flush thread waiting on its latches.
                    if (flush[0] != null) flush[0].cancel();
                }
                if (callTurn && !qwenRealtime && !endpoint.heardSpeech()) {
                    if (upload != null) upload.cancel();
                    status("我在，想说的时候直接说"); post(listener::idle); return;
                }
                if (pcm.length < 3200) throw new IOException("聆听太短，请重新聆听");
                status("正在想你的问题…"); diagnostic("上传 bytes=" + pcm.length);
                if (upload != null) {
                    if (liveReply && upload.awaitReply() && !skipReply.get()) {
                        status("正在回答 · 单击结束会话");
                        try { playReplyStream(upload.replyAudio); }
                        catch (Exception audioError) {
                            check(); upload.replyAudio.discard();
                            replyFailure = skipReply.get() ? "" : "语音播放中断，文字回复已保留；请检查耳机后重新接通";
                        }
                    }
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
            if (!replyFailure.isEmpty()) { fail(replyFailure); return; }
            String reply = result.optString("response");
            if (!liveReply && !skipReply.get() && result.optBoolean("speak", true) && !reply.isEmpty()) {
                post(() -> listener.reply(reply));
                try { play(reply); }
                catch (Exception e) {
                    check(); fail("文字已保存。" + (e instanceof IOException ? e.getMessage() : "播放失败，请检查音频输出"));
                    return;
                }
            }
            String notice = result.optString("notice").isEmpty() ? "我在，接着说" : result.optString("notice");
            status(notice + (promptNotice.isEmpty() ? "" : "；" + promptNotice));
        } catch (Exception e) {
            if (!cancelled.get()) {
                if (e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 401) api.vault.clear();
                fail(e instanceof java.net.SocketTimeoutException ? "网络超时，请查看历史记录确认结果后再试"
                    : e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 409
                    ? "本轮结果尚未确认，请先查看历史记录；不会自动重新提交聆听"
                    : e instanceof IOException ? e.getMessage() : "连接失败，请检查设备和网络后重试");
            }
        } finally {
            captureActive = false; clearCaptureTimers(); ui.removeCallbacks(workPhoneCheck);
            capture.close(); releaseCaptureFocus(); cancelPlayer();
            if (duplex != null) duplex.cancel();
            if (upload != null) upload.cancel();
            if (pending != null) pending.cancel();
            if (turn.phase() != CaptureTurn.Phase.ENDED && turn.phase() != CaptureTurn.Phase.FAILED) turn.cancel();
            // Cancellation invalidates queued done too; UI clears its active generation on cancel.
            post(listener::done);
        }
    }
    private void runDuplex() throws Exception {
        DuplexVoice.Listener events = new DuplexVoice.Listener() {
            public void speechStart() {
                cancelPlayer();
                post(() -> { listener.recording(); listener.status("正在听你说 · 播报时说“小万”可打断"); });
            }
            public void thinking() { post(listener::processing); }
            public void transcript(String text) { post(() -> listener.transcript(text)); }
            public void reply(String text) { post(() -> listener.reply(text)); }
            public void result(JSONObject value) { post(() -> listener.result(value)); }
            public void playback(LiveReplyAudio audio) {
                cancelPlayer();
                // Install on the socket callback thread so a later interruption cannot
                // race with an old worker installing its player after the cancellation.
                ReplyPlayer segment = new ReplyPlayer(context, mode, outputIdentity);
                player = segment;
                Thread playback = new Thread(() -> {
                    try (PlaybackLease ignored = PlaybackLease.acquireDuplex(
                            () -> cancelled.get() || !duplex.current(audio), 4000)) {
                        check();
                        segment.playStream(audio, HostedSession.this::status, description -> post(() -> listener.output(description)));
                        duplex.playbackDone(audio);
                    }
                    catch (Exception error) {
                        if (!cancelled.get() && duplex.current(audio))
                            duplex.abort("耳机实时播放失败：" + audioFailure(error));
                    } finally {
                        segment.cancel();
                        if (player == segment) player = null;
                    }
                }, "aidol-duplex-output");
                playback.setDaemon(true); playback.start();
            }
        };
        if (duplex == null) {
            duplex = new DuplexVoice(events);
            duplex.connect(api, requestId);
        } else { diagnostic("接管语音预连接"); duplex.activate(events); }
        status("正在连接语音服务…");
        duplex.awaitReady(); check();
        diagnostic("语音服务就绪");
        if (openingCue) playAudio(callTone());
        diagnostic("开始提示完成");
        check(); captureActive = true;
        ui.postDelayed(startupTimeout, 12000);
        diagnostic("请求耳机开麦");
        capture.start(new CaptureSource.Listener() {
            private long bytes;
            public void status(String value) { if (captureActive) HostedSession.this.status(value); }
            public void input(String value) { post(() -> listener.input(value)); }
            public void recording() {
                ui.removeCallbacks(startupTimeout);
                diagnostic("SDK采音就绪");
                if (!cancelled.get()) post(listener::recording);
            }
            public void pcm(byte[] pcm) {
                if (cancelled.get() || !captureActive) return;
                try {
                    if (firstPcm.compareAndSet(false, true)) diagnostic("首个 PCM");
                    duplex.audio(pcm); bytes += pcm.length;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastMeter > 300) {
                        lastMeter = now; long total = bytes; double rms = AudioFrames.rms(pcm);
                        post(() -> listener.meter(total, rms));
                    }
                } catch (IOException error) { duplex.abort(error.getMessage()); }
            }
            public void error(String value) { duplex.abort(value); }
        });
        duplex.awaitEnd();
    }
    private void workFailure(String message) {
        failure = message;
        captureActive = false;
        capture.close(); end.countDown(); workArrived.signal();
    }
    private void runWorkRecording() throws Exception {
        status("正在创建本次会议记录…");
        JSONObject startMetadata = new JSONObject().put("meeting_id", sessionId)
            .put("whole_audio", false)
            .put("started_at", java.time.Instant.now().toString());
        JSONObject location = MeetingStartLocation.capture(context, api, locationForeground);
        if (location != null) startMetadata.put("location", location);
        JSONObject started = new JSONObject(new String(request("/v1/work-meetings",
            startMetadata.toString().getBytes(StandardCharsets.UTF_8), "application/json"), StandardCharsets.UTF_8));
        workMeetingStarted = true;
        post(() -> listener.meeting(started));
        boolean complete = false;
        JSONObject finished = null;
        try {
            captureWorkSegments();
            complete = true;
        } finally {
            captureActive = false; ui.removeCallbacks(workPhoneCheck); capture.close(); releaseCaptureFocus();
            liveWanted = false; ui.removeCallbacks(liveReconnect);
            WorkLiveCaptions current = live; if (current != null) current.close();
            // Let queued marks/pauses land before finish; the server rejects them afterwards.
            workEvents.shutdown();
            try { workEvents.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try {
                // Closing a deterministic session is idempotent; this never replays PCM.
                finished = api.json("POST", "/v1/work-meetings/" + sessionId + "/finish",
                    new JSONObject().put("segment_count", workSegmentCount).put("complete", complete && !cancelled.get()));
                JSONObject receipt = finished;
                post(() -> listener.meeting(receipt));
            } catch (Exception error) {
                if (complete) throw new IOException("会议结束状态未确认；请在工作页查看已保存原文，不要重复上传录音。" + error.getMessage(), error);
            }
        }
        if (finished == null || !"saved".equals(finished.optString("status")))
            throw new IOException(finished == null ? "会议保存未确认，请查看工作页" : finished.optString("error", "工作记录未完整保存"));
        status("pending".equals(finished.optString("transcription_status"))
            ? "会议录音已保存，正在识别整场发言人；完成后自动总结"
            : "会议原文已保存，正在后台整理摘要；可在工作页查看进度");
    }
    private void captureWorkSegments() throws Exception {
        workBuffer = new WorkAudioBuffer();
        check();
        if (workStopping.get()) { workBuffer.finish(); return; }
        // Open live captions first (up to 3 s) so the first words are captioned, not recognised twice.
        liveWanted = true; startLive(0);
        for (long waitUntil = LiveCoverage.now() + 3000; LiveCoverage.now() < waitUntil; ) {
            WorkLiveCaptions current = live;
            if (current == null || current.healthy() || workStopping.get() || failure != null) break;
            check(); end.await(50, TimeUnit.MILLISECONDS);
        }
        // SDK microphone data is independent of Android media playback. Work capture must not own playback focus.
        captureActive = true; updateWorkPhoneState(); ui.post(workPhoneCheck);
        if (workStopping.get()) { captureActive = false; workBuffer.finish(); return; }
        ui.postDelayed(startupTimeout, 12000);
        capture.start(new CaptureSource.Listener() {
            public void status(String value) {
                workAudioStatus = value;
                if (captureActive) HostedSession.this.status(workAudioStatus());
            }
            public void callRecording(boolean active) {
                if (!cancelled.get() && workBuffer != null)
                    postWorkEvent(active ? "call_start" : "call_end", workBuffer.offsetMs(), "headset");
            }
            public void input(String value) { post(() -> listener.input(value)); }
            public void recording() {
                ui.removeCallbacks(startupTimeout);
                if (!cancelled.get() && captureActive) post(listener::recording);
            }
            public void pcm(byte[] pcm) {
                if (cancelled.get() || !captureActive) return;
                try {
                    // Only audio the buffer kept (not paused) is captioned, so both share one timeline.
                    if (workBuffer.append(pcm)) {
                        WorkLiveCaptions current = live;
                        if (current != null && !current.audio(pcm)) scheduleLiveReconnect();
                    }
                    // A pending closed segment is what the loop waits for; per-frame signals are
                    // no-ops while the loop is busy uploading (no waiter).
                    if (workBuffer.peek() != null) workArrived.signal();
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastMeter > 300) {
                        lastMeter = now; long size = workBuffer.size(); double rms = AudioFrames.rms(pcm);
                        post(() -> listener.meter(size, rms));
                    }
                } catch (IOException error) { workFailure(error.getMessage()); }
            }
            public void error(String message) { workFailure(message); }
        });
        boolean liveClosed = false, liveConfirmed = false;
        long seen = workArrived.version();
        while (true) {
            check();
            if (failure != null) throw new IOException(failure);
            WorkAudioBuffer.Segment segment = workBuffer.peek();
            boolean captureEnded = workBuffer.ended();
            if (captureEnded && !liveClosed) {
                // Ask the server to save the last live sentences before deciding the tail segments.
                liveClosed = true; liveWanted = false; ui.removeCallbacks(liveReconnect);
                WorkLiveCaptions current = live;
                long since = current == null ? Long.MAX_VALUE : current.healthySince();
                liveConfirmed = current != null && current.end(10_000);
                liveEndedHealthySince = liveConfirmed ? since : Long.MAX_VALUE;
            }
            if (segment != null && !LiveCoverage.settled(segment.endedAt, LiveCoverage.now(), captureEnded)) {
                // Wake exactly at the settle boundary, or early when capture ends or fails.
                workArrived.await(seen, Math.max(1, LiveCoverage.SETTLE_MS - (LiveCoverage.now() - segment.endedAt)));
                seen = workArrived.version();
                continue;
            }
            if (segment != null) {
                workBuffer.poll();
                WorkLiveCaptions current = live;
                boolean covered = captureEnded
                    ? LiveCoverage.covered(segment.startedAt, liveEndedHealthySince, liveConfirmed)
                    : current != null && LiveCoverage.covered(segment.startedAt, current.healthySince(), current.healthy());
                requestId = UUID.randomUUID().toString();
                String segmentId = requestId; post(() -> listener.submitted(segmentId));
                int segmentIndex = workSegmentCount++;
                try {
                    JSONObject result = new JSONObject(new String(request("/v1/voice?request_id=" + segmentId
                        + "&interaction_source=ambient_capture&meeting_id=" + sessionId + "&segment_index=" + segmentIndex
                        + "&offset_ms=" + segment.offsetMs + (covered ? "&recognize=0" : ""), segment.pcm, "audio/pcm"),
                        StandardCharsets.UTF_8));
                    check();
                    // Server-recognised fallback text joins the captions as final text.
                    String fallbackText = result.optString("text");
                    org.json.JSONArray fallback = new org.json.JSONArray();
                    if (!fallbackText.isEmpty()) fallback.put(new JSONObject().put("text", fallbackText).put("start_ms", segment.offsetMs)
                        .put("clock", TimeFormat.clockAt(System.currentTimeMillis() - Math.max(0, workBuffer.offsetMs() - segment.offsetMs))));
                    post(() -> {
                        if (fallback.length() > 0) listener.captionFinals(fallback);
                        listener.result(result);
                    });
                } catch (Exception error) {
                    throw new IOException("工作记录未完整保存；请查看历史确认已提交部分。" + error.getMessage(), error);
                }
            } else if (workBuffer.complete()) break;
            else {
                // Signals cover segments, capture end and failure; the cap only guards a missed wake.
                workArrived.await(seen, WORK_IDLE_FALLBACK_MILLIS);
                seen = workArrived.version();
            }
        }
    }
    private volatile long liveEndedHealthySince = Long.MAX_VALUE;
    private void startLive(long offsetMs) {
        if (!liveWanted || cancelled.get()) return;
        WorkLiveCaptions previous = live; if (previous != null) previous.close();
        live = WorkLiveCaptions.connect(api, sessionId, offsetMs, new WorkLiveCaptions.Listener() {
            public void partial(String text) { post(() -> listener.captionPartial(text)); }
            public void finals(org.json.JSONArray sentences) {
                // Clock time of each sentence's start: now minus the recorded audio since it began.
                WorkAudioBuffer buffer = workBuffer;
                long now = System.currentTimeMillis(), recorded = buffer == null ? 0 : buffer.offsetMs();
                for (int i = 0; i < sentences.length(); i++) {
                    JSONObject sentence = sentences.optJSONObject(i);
                    if (sentence == null) continue;
                    try { sentence.put("clock", TimeFormat.clockAt(now - Math.max(0, recorded - sentence.optLong("start_ms")))); }
                    catch (org.json.JSONException ignored) { /* Display only. */ }
                }
                post(() -> listener.captionFinals(sentences));
            }
            public void failed(String message) { status(message); scheduleLiveReconnect(); }
        });
    }
    private void scheduleLiveReconnect() {
        if (!liveWanted || cancelled.get()) return;
        ui.removeCallbacks(liveReconnect); ui.postDelayed(liveReconnect, 1500);
    }
    /** A new socket starts at the current recorded offset; the missed audio's segment falls back to the server. */
    private void reconnectLive() {
        WorkLiveCaptions current = live;
        if (!liveWanted || cancelled.get() || workStopping.get() || current != null && current.healthy()) return;
        WorkAudioBuffer buffer = workBuffer;
        startLive(buffer == null ? 0 : buffer.offsetMs());
        WorkLiveCaptions started = live;
        if (started != null && buffer != null && buffer.paused()) started.pause();
    }
    private void startCapture() throws Exception {
        synchronized (captureStartLock) { check(); captureActive = true; }
        requestCaptureFocus(); check();
        switch (mode) {
            case RISE_SDK: status("正在接通耳机…"); break;
            case EMULATOR: throw new IllegalStateException("模拟器来源已停用，请使用安卓真机");
        }
        ui.postDelayed(startupTimeout, 12000);
        // Mark/check readiness under one session gate, but never call a source while holding it:
        // SDK callbacks may synchronously report failure under the source's own lifecycle gate.
        synchronized (captureStartLock) {
            check();
            if (!captureActive || failure != null || turn.phase() != CaptureTurn.Phase.NEW)
                throw new IOException(failure == null ? "聆听准备已中止，请重新开始" : failure);
        }
        // Terminal cleanup closes the source even before start; the SDK also checks its generation
        // at the queued command boundary. A failure between this gate and start stays final.
        capture.start(new CaptureSource.Listener() {
            public void status(String value) { if (captureActive) HostedSession.this.status(value); }
            public void input(String description) { if (captureActive) post(() -> listener.input(description)); }
            public void recording() {
                if (cancelled.get() || !captureActive || !turn.recording(SystemClock.elapsedRealtime())) return;
                ui.removeCallbacks(startupTimeout);
                diagnostic("聆听就绪"); post(listener::recording);
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
            if (firstPcm.compareAndSet(false, true)) diagnostic("首个 PCM");
            double level = AudioFrames.rms(bytes);
            boolean silenceEnd = false;
            synchronized (vadLock) {
                if (autoEnd) {
                    silenceEnd = endpoint.accept(bytes) || callTurn && endpoint.idle();
                }
                if (upload != null) {
                    if (!uploadStarted && endpoint.heardSpeech()) {
                        upload.connect(api, requestId, interactionSource); uploadStarted = true;
                        byte[] prefix = preRoll.snapshot();
                        if (prefix.length > 0) upload.audio(prefix);
                        diagnostic("检测到人声，连接实时识别");
                    }
                    if (uploadStarted) upload.audio(bytes);
                    else preRoll.append(bytes);
                }
                if (now - lastMeter > 300) {
                    lastMeter = now; long size = turn.size(); post(() -> listener.meter(size, level));
                }
            }
            if (silenceEnd || turn.shouldStop(now)) stopRecording();
        } catch (IOException e) { failCapture(e.getMessage()); }
    }
    public void stopRecording() {
        if (workRecording) {
            if (cancelled.get() || !workStopping.compareAndSet(false, true)) return;
            if (!captureActive) return;
            post(listener::processing);
            ui.postDelayed(stopTimeout, 12000);
            try {
                capture.stop(new CaptureSource.StopCallback() {
                    public void ended() {
                        ui.removeCallbacks(stopTimeout); ui.removeCallbacks(startupTimeout);
                        captureActive = false;
                        try { workBuffer.finish(); } catch (IOException error) { failure = error.getMessage(); }
                        releaseCaptureFocus(); end.countDown(); workArrived.signal();
                    }
                    public void error(String message) { workFailure(message); }
                });
            } catch (RuntimeException error) { workFailure("停止录音失败，记录未完整保存"); }
            return;
        }
        if (cancelled.get() || !captureActive) return;
        long now = SystemClock.elapsedRealtime();
        if (!turn.requestStop(now)) return;
        ui.removeCallbacks(limit);
        if (!callTurn || endpoint.heardSpeech()) { post(listener::processing); status("正在思考…"); }
        diagnostic("请求停止");
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
        } catch (RuntimeException e) { failCapture("停止聆听失败，请恢复或重连耳机"); }
    }
    private void failCapture(String message) {
        if (duplex != null) { duplex.abort(message); captureActive = false; capture.close(); return; }
        if (workRecording) { workFailure(message); return; }
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
        capture.close(); releaseCaptureFocus(); end.countDown(); workArrived.signal(); diagnostic("聆听失败");
    }
    private void requestCaptureFocus() throws IOException {
        if (mode != AudioMode.RISE_SDK) return;
        if (audioManager.getMode() == AudioManager.MODE_IN_CALL || audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
            throw new IOException("通话或其他语音会话正在进行，请结束后重试");
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0) failCapture("聆听被其他音频或来电打断，请结束后重新开始");
            }).build();
        captureFocus = focus;
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("无法取得聆听音频焦点，请结束通话或其他聆听后重试");
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
        for (String chunk : SpeechText.chunks(text)) {
            check();
            if (skipReply.get()) return;
            playChunk(chunk);
        }
    }
    private void playChunk(String text) throws Exception {
        String stage = "语音合成请求";
        try {
            if (liveSpeech) { playStreaming(text); return; }
            status("正在回答…"); diagnostic("TTS 请求");
            byte[] wav = request("/v1/speech", new JSONObject().put("text", text)
                .toString().getBytes(StandardCharsets.UTF_8), "application/json");
            stage = "音频输出"; diagnostic("TTS 响应"); check();
            status("正在回答…");
            playAudio(WavAudio.pcm(wav));
            check(); diagnostic("播放完成（听音待人工确认）");
        } catch (Exception e) {
            check();
            if (e instanceof ApiClient.ApiException && ((ApiClient.ApiException) e).status == 401) api.vault.clear();
            throw new IOException("播放失败：" + stage + " · " + (e.getMessage() == null ? "请检查音频输出后重试" : e.getMessage()), e);
        }
    }
    private void playStreaming(String text) throws Exception {
        status("正在回答…");
        Call call = api.call("POST", "/v1/speech/stream", new JSONObject().put("text", text)
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
    private void playReplyStream(java.io.InputStream stream) throws Exception {
        ReplyPlayer segment = new ReplyPlayer(context, mode, outputIdentity);
        player = segment;
        try (PlaybackLease ignored = PlaybackLease.acquire(cancelled::get, 4000)) {
            check(); if (skipReply.get()) return;
            segment.playStream(stream, this::status, description -> post(() -> listener.output(description)));
            check();
        } finally { segment.cancel(); if (player == segment) player = null; }
    }
    /** 70 ms soft chime, generated locally and played only when a call opens. */
    private static byte[] callTone() {
        byte[] pcm = new byte[3360];
        for (int i = 0; i < pcm.length / 2; i++) {
            double envelope = Math.sin(Math.PI * i / (pcm.length / 2));
            short sample = (short) (Math.sin(2 * Math.PI * 660 * i / 24000) * envelope * 2600);
            pcm[i * 2] = (byte) sample; pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
    private void playCue(LocalCue cue) throws Exception {
        check(); status("正在播放提示：" + cue.label); diagnostic("本地操作提示 " + cue.name());
        playAudio(cue.pcm(context)); check();
        diagnostic("操作提示播放完成（听音待人工确认）");
    }
    private void playAudio(byte[] pcm) throws Exception {
        // Focus/route loss cancels a ReplyPlayer permanently. Give each segment its own player,
        // so a failed END cue cannot silently suppress the subsequent business reply.
        ReplyPlayer segment = openingPlayer;
        openingPlayer = null;
        if (segment == null) segment = new ReplyPlayer(context, mode, outputIdentity);
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
        ReplyPlayer prepared = openingPlayer; openingPlayer = null;
        if (prepared != null) prepared.cancel();
        ReplyPlayer active = player;
        if (active != null) active.cancel();
    }
    private void post(Runnable event) {
        if (cancelled.get()) return;
        long generation = events.generation();
        ui.post(() -> events.deliver(generation, () -> { if (!cancelled.get()) event.run(); }));
    }
    private void diagnostic(String stage) { if (BuildConfig.DEBUG) {
        String value = stage + " · " + diagnostics();
        // Fixed startup milestones only: never log spoken text, tokens or audio.
        if (stage.equals("开始") || stage.equals("接管语音预连接") || stage.equals("语音服务就绪")
                || stage.equals("开始提示完成") || stage.equals("请求耳机开麦")
                || stage.equals("SDK采音就绪") || stage.equals("首个 PCM")) android.util.Log.i("AidolStartup", value);
        post(() -> listener.diagnostic(value));
    } }
    private void status(String text) {
        String message = text.replace("录音", "聆听");
        post(() -> listener.status(message));
    }
    private void fail(String text) {
        String message = text.replace("录音", "语音");
        post(() -> listener.failure(message));
    }
    private void check() throws InterruptedException { if (cancelled.get()) throw new InterruptedException(); }
    private void clearCaptureTimers() { ui.removeCallbacks(limit); ui.removeCallbacks(startupTimeout); ui.removeCallbacks(stopTimeout); }
    /** Mode changes may discard live capture, but must not cancel a turn already stopping or submitted. */
    public boolean cancelIfRecording() {
        if (cancelled.get() || !turn.cancelRecording()) return false;
        cancel(); return true;
    }
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        if (duplex != null) duplex.cancel();
        events.invalidate(); captureActive = false; turn.cancel(); clearCaptureTimers(); end.countDown(); workArrived.signal();
        if (upload != null) upload.cancel();
        liveWanted = false; WorkLiveCaptions current = live; if (current != null) current.close();
        if (pending != null) pending.cancel(); cancelPlayer();
        capture.close();
        releaseCaptureFocus();
        if (worker != null) worker.interrupt();
    }
}
