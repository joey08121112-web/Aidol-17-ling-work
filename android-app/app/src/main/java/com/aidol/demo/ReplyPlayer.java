package com.aidol.demo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.util.function.Consumer;

/** Plays one short reply after capture has released the SCO recording route. */
public final class ReplyPlayer {
    private final AudioManager manager;
    private volatile boolean cancelled;
    public ReplyPlayer(Context context) { manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); }
    private static boolean headset(AudioDeviceInfo device) {
        return device != null && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || BluetoothCapture.isBluetoothInput(device)
            || (Build.VERSION.SDK_INT >= 31 && device.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }
    public void play(byte[] pcm, Consumer<String> log) throws Exception {
        if (pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > 6 * 1024 * 1024) throw new IOException("回复音频为空或格式不正确");
        AudioDeviceInfo target = null;
        long routeDeadline = SystemClock.elapsedRealtime() + 4000;
        while (!cancelled && target == null && SystemClock.elapsedRealtime() < routeDeadline) {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)) { target = device; break; }
            }
            if (target == null) Thread.sleep(100);
        }
        if (cancelled) return;
        if (target == null) throw new IOException("没有蓝牙媒体输出，请打开耳机的“媒体音频”开关");
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) cancelled = true;
            }).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("无法取得音频焦点，请关闭正在通话或播放的应用后重试");
        AudioTrack track = null;
        try {
        int minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("设备不支持 24kHz 语音播放");
        // MODE_STATIC starts in STATE_NO_STATIC_DATA until write(): treating that
        // state as an error was the cause of the original no-sound regression.
        // A small streaming buffer also supports prompt cancellation of long replies.
        track = new AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(minimum * 2, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("音频播放器初始化失败");
            if (!track.setPreferredDevice(target)) log.accept("系统未接受首选设备，正在核验实际蓝牙输出…");
            // Prime with silence: never play private speech before proving the route.
            track.setVolume(0);
            byte[] silence = new byte[2400];
            if (track.write(silence, 0, silence.length) != silence.length) throw new IOException("音频缓冲初始化失败");
            track.play();
            long readyDeadline = SystemClock.elapsedRealtime() + 3000;
            while (!cancelled && !headset(track.getRoutedDevice()) && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(40);
            if (cancelled) return;
            if (!headset(track.getRoutedDevice())) throw new IOException("播放未路由至蓝牙耳机，请检查媒体音频开关");
            log.accept("实际播放：" + track.getRoutedDevice().getProductName());
            track.pause(); track.flush(); track.setVolume(1); track.play();
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) log.accept("媒体音量为 0，请按手机音量加键");
            for (int offset = 0; !cancelled && offset < pcm.length;) {
                if (!headset(track.getRoutedDevice())) throw new IOException("播放路由离开蓝牙耳机，已停止");
                int count = track.write(pcm, offset, Math.min(4096, pcm.length - offset), AudioTrack.WRITE_BLOCKING);
                if (count <= 0) throw new IOException("回复音频写入失败：" + count);
                offset += count;
            }
            long deadline = SystemClock.elapsedRealtime() + pcm.length / 48 + 5000;
            while (!cancelled && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < pcm.length / 2L) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("蓝牙播放超时，请检查音量和媒体输出");
                AudioDeviceInfo actual = track.getRoutedDevice();
                if (!headset(actual)) throw new IOException("播放路由离开蓝牙耳机，已停止");
                Thread.sleep(40);
            }
        } finally {
            if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            manager.abandonAudioFocusRequest(focus);
        }
    }
    public void cancel() { cancelled = true; }
}
