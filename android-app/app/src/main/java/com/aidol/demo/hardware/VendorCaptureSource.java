package com.aidol.demo.hardware;

import com.aidol.demo.CaptureSource;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded, single-consumer PCM adapter. SDK callbacks only copy/enqueue microphone data. */
public final class VendorCaptureSource implements CaptureSource, AutoCloseable {
    public static final int MAX_BYTES = 1_920_000;
    private static final int MAX_QUEUED_BYTES = 256_000;
    private static final int MAX_FRAMES = 128;
    private final FusionDevice device;
    private final ScheduledExecutorService timer;
    private final long stopTimeoutMs;
    private final long durationLimitMs;
    private final int queueByteLimit;
    private static final class Frame {
        final byte[] bytes;
        final Boolean call;
        Frame(byte[] bytes, Boolean call) { this.bytes = bytes; this.call = call; }
    }
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final boolean workCalls;
    private WorkCaptureRouter workRouter;
    private int phoneState;
    private Listener listener;
    private StopCallback completion;
    private FusionDevice.Listener vendorListener;
    private ScheduledFuture<?> stopTimeout, durationTimeout, startupTimeout, workTick;
    private long generation;
    private boolean active, stopping, idle, closed, recording;
    private int queuedBytes, pendingByte = -1;
    private long receivedBytes;
    private Thread consumer;

    public VendorCaptureSource(HeadsetController controller) { this((FusionDevice) controller); }
    /** Explicit work recording keeps SDK capture open; both processing queues remain bounded. */
    public VendorCaptureSource(HeadsetController controller, boolean continuous) {
        this((FusionDevice) controller, 2500, MAX_QUEUED_BYTES, continuous ? 0 : 60_000);
    }
    public VendorCaptureSource(HeadsetController controller, boolean continuous, boolean workCalls) {
        this((FusionDevice) controller, 2500, MAX_QUEUED_BYTES, continuous ? 0 : 60_000, workCalls);
    }
    public VendorCaptureSource(FusionDevice device) { this(device, 2500, MAX_QUEUED_BYTES); }
    VendorCaptureSource(FusionDevice device, long stopTimeoutMs, int queueByteLimit) {
        this(device, stopTimeoutMs, queueByteLimit, 60_000);
    }
    VendorCaptureSource(FusionDevice device, long stopTimeoutMs, int queueByteLimit, long durationLimitMs) {
        this(device, stopTimeoutMs, queueByteLimit, durationLimitMs, false);
    }
    VendorCaptureSource(FusionDevice device, long stopTimeoutMs, int queueByteLimit, long durationLimitMs, boolean workCalls) {
        this.device = device;
        this.stopTimeoutMs = stopTimeoutMs;
        this.queueByteLimit = queueByteLimit;
        this.durationLimitMs = durationLimitMs;
        this.workCalls = workCalls;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rise-capture-deadline"); t.setDaemon(true); return t;
        });
    }

    @Override public void start(Listener target) {
        final long token;
        final FusionDevice.Listener callbacks;
        synchronized (this) {
            if (closed || active) { target.error("耳机录音尚未结束，请先停止或重新连接"); return; }
            token = ++generation;
            listener = target; completion = null; active = true; stopping = false; idle = false;
            recording = false; pendingByte = -1; queuedBytes = 0; receivedBytes = 0; queue.clear();
            if (workCalls) {
                workRouter = new WorkCaptureRouter(new WorkCaptureRouter.Output() {
                    public void pcm(byte[] bytes) { enqueue(token, bytes); }
                    public void status(String message) { if (current(token)) target.status(message); }
                    public void call(boolean active) {
                        if (queue.size() >= MAX_FRAMES) throw new IllegalStateException("通话切换积压，记录已停止");
                        queue.add(new Frame(null, active)); VendorCaptureSource.this.notifyAll();
                    }
                });
                workRouter.phone(phoneState, now());
                workTick = timer.scheduleWithFixedDelay(() -> {
                    synchronized (VendorCaptureSource.this) {
                        if (current(token) && !idle) try { workRouter.tick(now()); }
                        catch (RuntimeException error) { fail(token, error.getMessage(), true); }
                    }
                }, 50, 50, TimeUnit.MILLISECONDS);
            }
            vendorListener = new FusionDevice.Listener() {
                @Override public boolean startIfCurrent(Runnable startCommand) {
                    synchronized (VendorCaptureSource.this) {
                        if (!current(token)) return false;
                        startCommand.run();
                        return true;
                    }
                }
                @Override public void state(String state, int scene) {
                    try { onState(token, state, scene); }
                    catch (RuntimeException error) { fail(token, error.getMessage(), true); }
                }
                @Override public void pcm(int channel, byte[] bytes) { onPcm(token, channel, bytes); }
                @Override public void error(String message) { fail(token, message, true); }
                @Override public boolean recordsPhoneCalls() { return workCalls; }
                @Override public void phoneCall(boolean active) {
                    synchronized (VendorCaptureSource.this) {
                        if (current(token) && !idle && workRouter != null) try { workRouter.dialog(active, now()); }
                        catch (RuntimeException error) { fail(token, error.getMessage(), true); }
                    }
                }
            };
            callbacks = vendorListener;
            consumer = new Thread(() -> consume(token), "rise-pcm-" + token);
            consumer.setDaemon(true); consumer.start();
            startupTimeout = timer.schedule(() -> fail(token, "耳机麦克风启动超时，请重连后再试", true),
                    12_000, TimeUnit.MILLISECONDS);
        }
        target.status("正在开启耳机麦克风");
        synchronized (this) { if (!current(token)) return; }
        device.startFusion(callbacks);
    }

    private void onState(long token, String state, int scene) {
        boolean unexpected = false;
        synchronized (this) {
            if (!current(token)) return;
            // Only an explicitly started work recording may accept the phone-call scene.
            if (scene == 0 && !workCalls) { unexpected = true; }
            else if ("recording".equals(state) && !recording && !stopping) {
                announceRecording(token);
            } else if ("idle".equals(state)) {
                if (!stopping) unexpected = true;
                else {
                    if (workRouter != null) workRouter.finish(now());
                    idle = true; notifyAll();
                }
            }
            if (workRouter != null && !idle && !"idle".equals(state)) workRouter.scene(scene, now());
        }
        if (unexpected) { fail(token, scene == 0 ? "通话期间已停止耳机录音" : "耳机录音意外结束，请重新连接", true); return; }
    }

    /** Called with the producer lock held, so PCM cannot overtake the readiness notification. */
    private void announceRecording(long token) {
        recording = true;
        Listener target = listener;
        target.input("Rise SDK 麦克风 · 16 kHz 单声道");
        if (current(token)) {
            target.recording();
            if (!current(token)) return;
            if (startupTimeout != null) startupTimeout.cancel(false);
            // Share HostedSession's readiness origin: SDK startup latency is not recorded audio.
            // The independent deadline still includes the stop/drain budget.
            if (durationLimitMs > 0) durationTimeout = timer.schedule(() -> fail(token, "录音超过 60 秒，已丢弃本段，请重新开始", true),
                    durationLimitMs, TimeUnit.MILLISECONDS);
        }
    }

    private void onPcm(long token, int channel, byte[] bytes) {
        String failure = null;
        synchronized (this) {
            if (!current(token) || idle || bytes == null || bytes.length == 0) return;
            if (workRouter != null) {
                try { workRouter.pcm(channel, bytes, now()); }
                catch (RuntimeException error) { fail(token, error.getMessage(), true); }
                return;
            }
            if (channel != 2) return;
            if (durationLimitMs > 0 && bytes.length > MAX_BYTES - receivedBytes) failure = "耳机录音超过 60 秒音频上限，已丢弃本段";
            else if (queue.size() >= MAX_FRAMES || bytes.length > queueByteLimit - queuedBytes)
                failure = "耳机音频处理队列已满，已丢弃本段，请重试";
            else {
                // Some firmware delivers its first PCM before the recording state callback.
                if (!recording && !stopping) {
                    announceRecording(token);
                    if (!current(token)) return;
                }
                queue.add(new Frame(Arrays.copyOf(bytes, bytes.length), null));
                queuedBytes += bytes.length; receivedBytes += bytes.length; notifyAll();
            }
        }
        if (failure != null) fail(token, failure, true);
    }

    private void enqueue(long token, byte[] bytes) {
        if (!current(token)) return;
        if (queue.size() >= MAX_FRAMES || bytes.length > queueByteLimit - queuedBytes)
            throw new IllegalStateException("耳机音频处理队列已满，请检查网络后重试");
        if (!recording && !stopping) announceRecording(token);
        if (!current(token)) return;
        queue.add(new Frame(Arrays.copyOf(bytes, bytes.length), null));
        queuedBytes += bytes.length; receivedBytes += bytes.length; notifyAll();
    }
    private static long now() { return System.nanoTime() / 1_000_000L; }
    @Override public synchronized void phoneAudioState(int state) {
        phoneState = state;
        if (active && !idle && workRouter != null) try { workRouter.phone(state, now()); }
        catch (RuntimeException error) { fail(generation, error.getMessage(), true); }
    }
    @Override public synchronized void paused(boolean paused) {
        if (workRouter != null) {
            workRouter.pause(paused);
            queue.removeIf(frame -> frame.call == null); queuedBytes = 0; pendingByte = -1;
        }
    }

    private void consume(long token) {
        try {
            while (true) {
                byte[] bytes;
                StopCallback ended = null;
                boolean partial = false;
                synchronized (this) {
                    while (current(token) && queue.isEmpty() && !idle) wait();
                    if (!current(token)) return;
                    if (queue.isEmpty()) {
                        partial = pendingByte >= 0;
                        if (!partial) {
                            active = false; cancelTimers(); ended = completion; completion = null;
                        }
                        bytes = null;
                    } else {
                        Frame frame = queue.remove();
                        if (frame.call != null) {
                            // Scene boundaries cannot splice a half microphone sample into call PCM.
                            pendingByte = -1;
                            listener.callRecording(frame.call);
                            continue;
                        }
                        bytes = frame.bytes; queuedBytes -= bytes.length;
                    }
                }
                if (partial) { fail(token, "耳机音频以半个采样结束，已丢弃本段", true); return; }
                if (bytes == null) {
                    synchronized (this) { if (generation == token && !closed && ended != null) ended.ended(); }
                    return;
                }
                byte[] aligned;
                Listener target;
                synchronized (this) {
                    if (!current(token)) return;
                    int count = bytes.length + (pendingByte < 0 ? 0 : 1);
                    aligned = new byte[count & ~1];
                    int destination = 0, offset = 0;
                    if (pendingByte >= 0 && aligned.length > 0) {
                        aligned[destination++] = (byte) pendingByte; pendingByte = -1;
                    }
                    int copied = aligned.length - destination;
                    System.arraycopy(bytes, 0, aligned, destination, copied); offset += copied;
                    if (offset < bytes.length) pendingByte = bytes[offset] & 0xff;
                    target = listener;
                }
                // Do not hold the producer lock while a consumer runs. An in-flight callback stays
                // bound to this generation's listener; HostedSession also rejects cancelled turns.
                if (aligned.length > 0) target.pcm(aligned);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ignored) { fail(token, "耳机音频处理失败，已丢弃本段", true); }
    }

    @Override public void stop(StopCallback callback) {
        FusionDevice.Listener owner;
        synchronized (this) {
            if (!active) { callback.error("没有可结束的耳机录音"); return; }
            if (stopping) return; // The original completion owns this stop operation.
            stopping = true; completion = callback; owner = vendorListener;
            long token = generation;
            stopTimeout = timer.schedule(() -> fail(token, "耳机未确认结束，请重新连接后再录音", true),
                    stopTimeoutMs, TimeUnit.MILLISECONDS);
            listener.status("正在结束耳机录音");
        }
        device.stopFusion(owner);
    }

    private void fail(long token, String message, boolean reconnect) {
        FusionDevice.Listener owner;
        synchronized (this) {
            if (!current(token)) return;
            active = false; queue.clear(); queuedBytes = 0; pendingByte = -1; cancelTimers(); notifyAll();
            owner = vendorListener;
            StopCallback callback = completion; completion = null;
            if (callback != null) callback.error(message); else listener.error(message);
        }
        if (reconnect) device.requireReconnect(owner); else device.cancelFusion(owner);
    }

    @Override public void cancel() {
        FusionDevice.Listener owner;
        synchronized (this) {
            if (!active) return;
            active = false; generation++; completion = null; owner = vendorListener;
            queue.clear(); queuedBytes = 0; pendingByte = -1; cancelTimers(); notifyAll();
        }
        device.cancelFusion(owner);
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        cancel();
        timer.shutdownNow();
    }
    private boolean current(long token) { return active && generation == token && !closed; }
    private void cancelTimers() {
        if (startupTimeout != null) startupTimeout.cancel(false);
        if (stopTimeout != null) stopTimeout.cancel(false);
        if (durationTimeout != null) durationTimeout.cancel(false);
        if (workTick != null) workTick.cancel(false);
    }
    public synchronized String diagnostics() {
        return "generation=" + generation + " bytes=" + receivedBytes + " queue_bytes=" + queuedBytes
                + " queue_frames=" + queue.size() + " phase=" + (idle ? "DRAINING" : stopping ? "STOP_REQUESTED" : active ? "RECORDING" : "ENDED");
    }
}
