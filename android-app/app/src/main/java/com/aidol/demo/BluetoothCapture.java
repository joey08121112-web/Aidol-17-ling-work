package com.aidol.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standard Android headset audio. Never silently sends the handset microphone. */
public final class BluetoothCapture implements CaptureSource {
    private final Context context;
    private final AudioManager manager;
    private volatile boolean running;
    private volatile boolean cancelled;
    private boolean started;
    private final CaptureCompletion completion = new CaptureCompletion();
    private volatile AudioRecord recorder;
    private Thread worker;

    public BluetoothCapture(Context context) {
        this.context = context.getApplicationContext();
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public static boolean isBluetoothInput(AudioDeviceInfo device) {
        return device != null && AudioMode.BLUETOOTH.acceptsInputType(device.getType());
    }

    @Override public synchronized void start(Listener listener) {
        if (started || cancelled) { listener.error("录音源已使用，请创建新会话"); return; }
        started = true;
        if (!SystemCaptureLease.acquire(this)) { listener.error("上一次录音正在释放，请稍后重新开始"); return; }
        running = true;
        worker = new Thread(() -> capture(listener), "aidol-bluetooth-input");
        worker.start();
    }

    @SuppressWarnings("deprecation")
    private void capture(Listener listener) {
        int previousMode = manager.getMode();
        AudioDeviceInfo previousDevice = null;
        BroadcastReceiver receiver = null;
        boolean changedRoute = false;
        boolean focused = false;
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
        try {
            if (!running) return;
            if (Build.VERSION.SDK_INT >= 31) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    throw new IllegalStateException("附近设备权限已被撤销，请重新授权。");
                }
                previousDevice = manager.getCommunicationDevice();
            }
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focused) throw new IllegalStateException("无法取得录音音频焦点，请结束通话或关闭其他录音后重试。");
            if (!running) return;
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            changedRoute = true;
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo headset = null;
                for (AudioDeviceInfo candidate : manager.getAvailableCommunicationDevices()) {
                    if (isBluetoothInput(candidate)) { headset = candidate; break; }
                }
                if (headset == null) throw new IllegalStateException("未找到蓝牙通话设备。请在系统蓝牙中开启耳机的“通话音频”，并暂时断开其他耳机。");
                listener.status("请求蓝牙通话路由：" + headset.getProductName());
                if (!manager.setCommunicationDevice(headset)) throw new IllegalStateException("系统拒绝蓝牙通话路由，请关闭其他正在录音或通话的 App。");
                long deadline = SystemClock.elapsedRealtime() + 8000;
                while (running && !isBluetoothInput(manager.getCommunicationDevice()) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                if (!running) return;
                if (!isBluetoothInput(manager.getCommunicationDevice())) throw new IllegalStateException("蓝牙通话路由等待超时");
            } else {
                if (!manager.isBluetoothScoAvailableOffCall()) throw new IllegalStateException("这台手机不支持通话外蓝牙麦克风");
                CountDownLatch connected = new CountDownLatch(1);
                receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        if (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown();
                    }
                };
                context.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
                manager.startBluetoothSco();
                listener.status("等待蓝牙 SCO 通话音频…");
                if (!connected.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("蓝牙 SCO 未连接，请检查“通话音频”开关");
                manager.setBluetoothScoOn(true);
            }
            if (!running) return;
            AudioDeviceInfo input = null;
            for (AudioDeviceInfo candidate : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (isBluetoothInput(candidate)) { input = candidate; break; }
            }
            if (input == null) throw new IllegalStateException("系统没有暴露蓝牙麦克风；不会改用手机麦克风。");
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("系统不支持 16kHz 单声道录音");
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("麦克风权限已被撤销，请重新授权。");
            }
            local = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, 6400)).build();
            recorder = local;
            if (local.getState() != AudioRecord.STATE_INITIALIZED || !local.setPreferredDevice(input)) {
                throw new IllegalStateException("蓝牙录音初始化失败");
            }
            if (!running) return;
            local.startRecording();
            short[] samples = new short[1600];
            boolean confirmed = false;
            long routeDeadline = SystemClock.elapsedRealtime() + 3000;
            while (running) {
                int count = local.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (cancelled) break;
                if (!running && count <= 0) break;
                if (count < 0) throw new IllegalStateException("AudioRecord 读取失败：" + count);
                AudioDeviceInfo actual = local.getRoutedDevice();
                if (!isBluetoothInput(actual)) {
                    if (confirmed || SystemClock.elapsedRealtime() >= routeDeadline) {
                        throw new IllegalStateException("实际输入不是蓝牙耳机，已停止，避免误录手机麦克风。");
                    }
                    continue; // Discard all samples before the hardware route is proven.
                }
                if (!confirmed) {
                    confirmed = true;
                    listener.input(actual.getProductName() + " / type=" + actual.getType() + " / 16kHz mono PCM16");
                    listener.recording();
                }
                if (count > 0) listener.pcm(AudioFrames.encode(samples, count));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!cancelled) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                completion.failure(message); listener.error(message);
            }
        } finally {
            running = false;
            recorder = null;
            if (local != null) { try { local.stop(); } catch (Exception ignored) {} local.release(); }
            if (receiver != null) { try { context.unregisterReceiver(receiver); } catch (Exception ignored) {} }
            if (changedRoute) {
                try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        if (previousDevice != null) manager.setCommunicationDevice(previousDevice);
                        else manager.clearCommunicationDevice();
                    } else { manager.setBluetoothScoOn(false); manager.stopBluetoothSco(); }
                } catch (RuntimeException ignored) { /* Permission/device may disappear during capture. */ }
                try { manager.setMode(previousMode); } catch (RuntimeException ignored) {}
            }
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
        // Normal stop lets the in-flight read finish against the still-proven route.
        // Only cancellation interrupts AudioRecord; the session bounds a hung read.
        if (cancelled && active != null) { try { active.stop(); } catch (Exception ignored) {} }
        Thread thread = worker;
        if (thread == null) completion.end();
        else if (cancelled && thread != Thread.currentThread()) thread.interrupt();
    }
}
