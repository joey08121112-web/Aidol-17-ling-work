package com.aidol.demo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioFocusRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioRouting;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Plays a short reply using the same routing policy as the capture source. */
public final class ReplyPlayer {
    interface PlaybackListener {
        default void started() {}
        default void level(double rms) {}
        default void finished() {}
    }
    private static final PlaybackListener NO_PLAYBACK_LISTENER = new PlaybackListener() {};

    /** Maps the real playback head to written speech, excluding the silent route primer. */
    static final class PlaybackProgress {
        private static final class Part {
            final long end;
            final double rms;
            Part(long end, double rms) { this.end = end; this.rms = rms; }
        }
        private final PlaybackListener listener;
        private final long primerFrames;
        private final ArrayDeque<Part> parts = new ArrayDeque<>();
        private long writtenFrames;
        private boolean started, closed;
        PlaybackProgress(long primerFrames, PlaybackListener listener) {
            this.primerFrames = primerFrames; this.listener = listener;
        }
        synchronized void written(byte[] pcm, int offset, int count) {
            if (closed || count <= 0) return;
            double sum = 0;
            for (int i = offset; i + 1 < offset + count; i += 2) {
                int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                sum += (double) sample * sample;
            }
            writtenFrames += count / 2;
            parts.addLast(new Part(writtenFrames, Math.sqrt(sum / (count / 2)) / 32768.0));
        }
        synchronized void advanced(long playbackHead) {
            long heard = playbackHead - primerFrames;
            if (closed || heard <= 0 || writtenFrames == 0) return;
            if (!started) { started = true; listener.started(); }
            while (!parts.isEmpty() && parts.peekFirst().end < heard) parts.removeFirst();
            listener.level(parts.isEmpty() ? 0 : parts.peekFirst().rms);
        }
        synchronized void finish() {
            if (closed) return;
            closed = true; parts.clear();
            if (started) { listener.level(0); listener.finished(); }
        }
        synchronized void cancel() { closed = true; parts.clear(); }
    }
    private final AudioManager manager;
    private final AudioMode mode;
    private final Predicate<AudioDeviceInfo> outputIdentity;
    private final int sampleRate;
    private final long maxStreamBytes;
    private volatile boolean cancelled;
    private volatile String interruption;
    private volatile AudioTrack activeTrack;
    private AudioTrack preparedTrack;
    /** Wakes the route/device/marker waits on real events, including cancellation. */
    private final PlaybackLease.EventSignal waitEvents = new PlaybackLease.EventSignal();
    private static final int PRIMER_BYTES = 2400;

    /** Allocate only: standby never plays audio, takes focus or opens a microphone. */
    void prepare() throws IOException {
        synchronized (this) { if (cancelled || preparedTrack != null) return; }
        AudioTrack allocated = createTrack();
        synchronized (this) {
            if (cancelled || preparedTrack != null) allocated.release();
            else preparedTrack = allocated;
        }
    }
    private AudioTrack createTrack() throws IOException {
        int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("设备不支持 " + sampleRate / 1000 + "kHz 语音播放");
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(minimum * 2, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release(); throw new IOException("音频播放器初始化失败");
        }
        return track;
    }
    private AudioTrack takeTrack() throws IOException {
        AudioTrack track;
        synchronized (this) { track = preparedTrack; preparedTrack = null; }
        return track == null ? createTrack() : track;
    }

    public ReplyPlayer(Context context) { this(context, AudioMode.RISE_SDK); }
    public ReplyPlayer(Context context, AudioMode mode) {
        this(context, mode, device -> false);
    }
    public ReplyPlayer(Context context, AudioMode mode, Predicate<AudioDeviceInfo> outputIdentity) {
        this(context, mode, outputIdentity, 24000, 6 * 1024 * 1024);
    }
    /** Same route policy for other PCM16 mono sources, such as a saved 16 kHz meeting recording. */
    ReplyPlayer(Context context, AudioMode mode, Predicate<AudioDeviceInfo> outputIdentity, int sampleRate, long maxStreamBytes) {
        this.sampleRate = sampleRate; this.maxStreamBytes = maxStreamBytes;
        this.mode = AudioMode.requirePhysical(mode);
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.outputIdentity = outputIdentity == null ? device -> false : outputIdentity;
    }

    private boolean acceptable(AudioDeviceInfo device) {
        if (device == null || !mode.acceptsOutputType(device.getType())) return false;
        try { return !mode.requiresTargetIdentity() || outputIdentity.test(device); }
        catch (RuntimeException deniedOrDisconnected) { return false; }
    }

    public void play(byte[] pcm, Consumer<String> log) throws Exception {
        play(pcm, log, description -> log.accept("实际播放：" + description));
    }

    public void play(byte[] pcm, Consumer<String> log, Consumer<String> output) throws Exception {
        play(pcm, log, output, NO_PLAYBACK_LISTENER);
    }
    void play(byte[] pcm, Consumer<String> log, Consumer<String> output, PlaybackListener playback) throws Exception {
        if (pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > 6 * 1024 * 1024)
            throw new IOException("回复音频为空或格式不正确");
        playStream(new ByteArrayInputStream(pcm), log, output, playback);
    }

    public void playStream(InputStream stream, Consumer<String> log, Consumer<String> output) throws Exception {
        playStream(stream, log, output, NO_PLAYBACK_LISTENER);
    }
    void playStream(InputStream stream, Consumer<String> log, Consumer<String> output, PlaybackListener playback) throws Exception {
        PlaybackProgress progress = new PlaybackProgress(PRIMER_BYTES / 2, playback);
        AudioDeviceInfo target = null;
        if (mode.requiresVerifiedHeadsetOutput()) {
            long routeDeadline = SystemClock.elapsedRealtime() + 4000;
            // The framework delivers the current device list on registration, so no initial poll.
            AudioDeviceCallback devices = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { waitEvents.signal(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { waitEvents.signal(); }
            };
            manager.registerAudioDeviceCallback(devices, new Handler(Looper.getMainLooper()));
            try {
                long seen = waitEvents.version();
                while (!cancelled && target == null && SystemClock.elapsedRealtime() < routeDeadline) {
                    for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        if (acceptable(device) && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || (Build.VERSION.SDK_INT >= 31 && device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET))) {
                            target = device; break;
                        }
                    }
                    if (target == null) {
                        long remaining = routeDeadline - SystemClock.elapsedRealtime();
                        if (remaining > 0) waitEvents.await(seen, remaining);
                    }
                    seen = waitEvents.version();
                }
            } finally { manager.unregisterAudioDeviceCallback(devices); }
            if (cancelled) { return; }
            if (target == null) throw new IOException(mode.requiresTargetIdentity()
                ? "未核验到所连接 Rise 的媒体输出，请检查目标耳机的媒体音频和连接状态"
                : "没有蓝牙媒体输出，请打开耳机的“媒体音频”开关");
        }
        if (cancelled) { return; }
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                if (change < 0) {
                    interruption = "语音播放被其他音频或来电打断，请结束通话后重试";
                    cancel();
                }
            }).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("无法取得音频焦点，请关闭正在通话或播放的应用后重试");
        AudioTrack track = null;
        // Registered before play: while unarmed it only wakes the route wait below; once armed
        // (after the route is verified) it polices route loss for the rest of the segment.
        final AtomicBoolean routeArmed = new AtomicBoolean();
        AudioRouting.OnRoutingChangedListener routeListener = router -> {
            waitEvents.signal();
            if (!routeArmed.get()) return;
            if (!acceptable(router.getRoutedDevice())) {
                interruption = "实际播放已离开目标耳机，已停止，请重新连接后重试";
                AudioTrack active = activeTrack;
                if (active != null) { try { active.setVolume(0); } catch (RuntimeException ignored) {} }
                cancel();
            }
        };
        AudioTrack.OnPlaybackPositionUpdateListener marker = null;
        boolean routeObserved = false;
        try {
            interrupted();
            if (cancelled) { return; }
            track = takeTrack();
            activeTrack = track;
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("音频播放器初始化失败");
            // The 70 ms call chime is shorter than the default streaming start threshold.
            // Otherwise play() can remain in startup until our completion timeout fires.
            if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(1);
            if (target != null && !track.setPreferredDevice(target))
                log.accept("系统未接受首选设备，正在核验实际蓝牙输出…");
            track.addOnRoutingChangedListener(routeListener, new Handler(Looper.getMainLooper()));
            routeObserved = true;
            marker = new AudioTrack.OnPlaybackPositionUpdateListener() {
                private void observe(AudioTrack changed) {
                    try {
                        if (!cancelled) progress.advanced(Integer.toUnsignedLong(changed.getPlaybackHeadPosition()));
                    } catch (RuntimeException released) { /* A queued notification can outlive release(). */ }
                    waitEvents.signal();
                }
                @Override public void onMarkerReached(AudioTrack changed) { observe(changed); }
                @Override public void onPeriodicNotification(AudioTrack changed) { observe(changed); }
            };
            track.setPlaybackPositionUpdateListener(marker, new Handler(Looper.getMainLooper()));
            // Observe output even while the network input blocks waiting for another PCM chunk.
            track.setPositionNotificationPeriod(Math.max(1, sampleRate / 20));
            // Prime with silence: never play private speech before proving a Bluetooth route.
            track.setVolume(0);
            byte[] silence = new byte[PRIMER_BYTES];
            if (track.write(silence, 0, silence.length) != silence.length) throw new IOException("音频缓冲初始化失败");
            track.play();
            long readyDeadline = SystemClock.elapsedRealtime() + (mode.requiresVerifiedHeadsetOutput() ? 3000 : 500);
            long seen = waitEvents.version();
            while (!cancelled && !acceptable(track.getRoutedDevice()) && SystemClock.elapsedRealtime() < readyDeadline) {
                long remaining = readyDeadline - SystemClock.elapsedRealtime();
                if (remaining > 0) waitEvents.await(seen, remaining);
                seen = waitEvents.version();
            }
            interrupted();
            if (cancelled) { return; }
            AudioDeviceInfo actual = track.getRoutedDevice();
            if (!acceptable(actual)) throw new IOException("播放未路由至目标耳机，请检查媒体音频开关");
            output.accept(actual.getProductName() + " / type=" + actual.getType());
            // Keep the silent primer in the same playing track: pausing would invalidate
            // getRoutedDevice on some phones and force another unverified route transition.
            routeArmed.set(true);
            if (!acceptable(track.getRoutedDevice())) {
                throw new IOException("播放路由已改变，请重新连接目标耳机");
            }
            track.setVolume(1);
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                log.accept("媒体音量为 0，请按音量加键");
            }
            byte[] pcm = new byte[4096];
            long total = 0;
            while (!cancelled) {
                int length = stream.read(pcm);
                if (length < 0) break;
                total += length;
                if ((length & 1) != 0 || total > maxStreamBytes) throw new IOException("语音格式无效或超过上限");
                long writeDeadline = SystemClock.elapsedRealtime() + 5000;
                // No framework event exists for track buffer space; the 20 ms nap matches the
                // drain quantum of the ~9600-byte buffer and is not a busy wait.
                for (int offset = 0; !cancelled && offset < length;) {
                    if (!acceptable(track.getRoutedDevice())) throw new IOException("播放路由离开蓝牙耳机，已停止");
                    int count = track.write(pcm, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (count < 0) throw new IOException("回复音频写入失败：" + count);
                    if (count == 0) {
                        if (SystemClock.elapsedRealtime() >= writeDeadline) throw new IOException("回复音频写入超时");
                        Thread.sleep(20);
                    } else {
                        progress.written(pcm, offset, count);
                        if (!cancelled) progress.advanced(Integer.toUnsignedLong(track.getPlaybackHeadPosition()));
                        offset += count; writeDeadline = SystemClock.elapsedRealtime() + 5000;
                    }
                }
            }
            if (!cancelled && total == 0) throw new IOException("语音为空");
            // Older Android releases cannot lower the start threshold. Flush a full
            // silent buffer so short clips (and an underrun at EOF) can start/drain.
            byte[] tail = new byte[track.getBufferSizeInFrames() * 2];
            long tailDeadline = SystemClock.elapsedRealtime() + 5000;
            for (int offset = 0; !cancelled && offset < tail.length;) {
                if (!acceptable(track.getRoutedDevice())) throw new IOException("播放路由离开蓝牙耳机，已停止");
                int count = track.write(tail, offset, tail.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                if (count < 0) throw new IOException("回复音频写入失败：" + count);
                if (count == 0) {
                    if (SystemClock.elapsedRealtime() >= tailDeadline) throw new IOException("回复音频写入超时");
                    Thread.sleep(20);
                } else { offset += count; tailDeadline = SystemClock.elapsedRealtime() + 5000; }
            }
            long deadline = SystemClock.elapsedRealtime() + total * 500 / sampleRate + 5000;
            long expectedFrames = (total + silence.length) / 2L;
            // The playback-head wait wakes on the reach-the-end marker instead of polling.
            track.setNotificationMarkerPosition((int) Math.min(Integer.MAX_VALUE, expectedFrames));
            long markerSeen = waitEvents.version();
            while (!cancelled && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < expectedFrames) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("语音播放超时，请检查音量和媒体输出");
                if (!acceptable(track.getRoutedDevice())) throw new IOException("播放路由离开蓝牙耳机，已停止");
                // A coarse cap survives devices whose marker callbacks are unreliable.
                waitEvents.await(markerSeen, 500);
                markerSeen = waitEvents.version();
            }
            interrupted();
            if (!cancelled) {
                progress.advanced(Integer.toUnsignedLong(track.getPlaybackHeadPosition()));
                progress.finish();
            }
        } finally {
            progress.cancel();
            activeTrack = null;
            if (track != null) {
                if (marker != null) track.setPlaybackPositionUpdateListener(null);
                if (routeObserved) track.removeOnRoutingChangedListener(routeListener);
                try { track.stop(); } catch (Exception ignored) {} track.release();
            }
            manager.abandonAudioFocusRequest(focus);
        }
    }

    private void interrupted() throws IOException { if (interruption != null) throw new IOException(interruption); }

    /** Frames of the stream heard so far, excluding the silent route primer; 0 before playback. */
    long playedFrames() {
        AudioTrack track = activeTrack;
        if (track == null) return 0;
        try { return Math.max(0, Integer.toUnsignedLong(track.getPlaybackHeadPosition()) - PRIMER_BYTES / 2); }
        catch (RuntimeException released) { return 0; }
    }

    public void cancel() {
        AudioTrack prepared;
        synchronized (this) {
            cancelled = true; prepared = preparedTrack; preparedTrack = null;
        }
        waitEvents.signal();
        if (prepared != null) prepared.release();
        AudioTrack track = activeTrack;
        if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
    }
}
