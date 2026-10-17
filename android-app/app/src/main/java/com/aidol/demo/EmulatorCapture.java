package com.aidol.demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

/** Records the emulator's virtual microphone; host Bluetooth routing stays with the host OS. */
public final class EmulatorCapture implements CaptureSource {
    private final Context context;
    private final AudioManager manager;
    private volatile boolean running;
    private volatile boolean cancelled;
    private boolean started;
    private final CaptureCompletion completion = new CaptureCompletion();
    private volatile AudioRecord recorder;
    private Thread worker;

    public EmulatorCapture(Context context) {
        this.context = context.getApplicationContext();
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override public synchronized void start(Listener listener) {
        if (started || cancelled) { listener.error("录音源已使用，请创建新会话"); return; }
        started = true;
        if (!SystemCaptureLease.acquire(this)) { listener.error("上一次录音正在释放，请稍后重新开始"); return; }
        running = true;
        worker = new Thread(() -> capture(listener), "aidol-emulator-input");
        worker.start();
    }

    private void capture(Listener listener) {
        AudioRecord local = null;
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0 && running) {
                    completion.failure("录音被其他音频或来电打断，请结束通话后重新开始。");
                    listener.error("录音被其他音频或来电打断，请结束通话后重新开始。");
                    running = false;
                    AudioRecord active = recorder;
                    if (active != null) { try { active.stop(); } catch (Exception ignored) {} }
                }
            }).build();
        boolean focused = false;
        try {
            if (!running) return;
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("麦克风权限已被撤销，请重新授权。");
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focused) throw new IllegalStateException("无法取得录音音频焦点，请关闭其他通话或录音后重试。");
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("模拟器不支持 16kHz 单声道录音");
            // MIC uses the system's default virtual input and does not negotiate SCO.
            local = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, 6400)).build();
            recorder = local;
            if (!running) return;
            if (local.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("模拟器录音初始化失败");
            local.startRecording();
            listener.status("模拟器使用系统默认输入；请开启模拟器的主机麦克风，并在电脑系统中选择耳机输入和输出。");
            short[] samples = new short[1600];
            boolean confirmed = false;
            int lastDeviceId = Integer.MIN_VALUE;
            while (running) {
                int count = local.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (cancelled) break;
                if (!running && count <= 0) break;
                if (count < 0) throw new IllegalStateException("模拟器 AudioRecord 读取失败：" + count);
                if (count == 0) continue;
                AudioDeviceInfo actual = local.getRoutedDevice();
                int deviceId = actual == null ? -1 : actual.getId();
                if (!confirmed || deviceId != lastDeviceId) {
                    lastDeviceId = deviceId;
                    listener.input((actual == null ? "系统默认虚拟输入（未报告设备）"
                        : actual.getProductName() + " / type=" + actual.getType())
                        + " / 16kHz mono PCM16；电脑耳机麦克风需人工确认");
                }
                if (!confirmed) { confirmed = true; listener.recording(); }
                listener.pcm(AudioFrames.encode(samples, count));
            }
        } catch (Exception e) {
            if (!cancelled) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                completion.failure(message); listener.error(message);
            }
        } finally {
            running = false;
            recorder = null;
            if (local != null) { try { local.stop(); } catch (Exception ignored) {} local.release(); }
            try { if (focused) manager.abandonAudioFocusRequest(focus); }
            finally { SystemCaptureLease.release(this); completion.end(); }
        }
    }

    @Override public void stop(StopCallback callback) {
        completion.await(callback);
        endCapture();
    }

    @Override public void cancel() {
        cancelled = true;
        completion.failure("录音已取消");
        endCapture();
    }

    private void endCapture() {
        running = false;
        AudioRecord active = recorder;
        if (cancelled && active != null) { try { active.stop(); } catch (Exception ignored) {} }
        Thread thread = worker;
        if (thread == null) completion.end();
        else if (cancelled && thread != Thread.currentThread()) thread.interrupt();
    }
}
