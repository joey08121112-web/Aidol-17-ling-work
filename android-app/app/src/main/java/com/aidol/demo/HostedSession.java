package com.aidol.demo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import org.json.JSONObject;

/** Foreground single-turn lifecycle, bounded audio, verified Bluetooth routes. */
public final class HostedSession {
    public interface Listener {
        void status(String text);
        default void failure(String text) { status(text); }
        void result(JSONObject result);
        void recording();
        void meter(long bytes, double rms);
        void done();
    }
    private final ApiClient api;
    private final Listener listener;
    private final BluetoothCapture capture;
    private final ReplyPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(), stopped = new AtomicBoolean();
    private final CountDownLatch end = new CountDownLatch(1);
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private volatile Call pending;
    private volatile String failure;
    private Thread worker;
    private long lastMeter;
    private int recordSeconds = 60;
    private boolean autoEnd, heardSpeech;
    private double voiceThreshold = .025;
    private long voicedBytes, silenceBytes;
    private final Runnable limit = () -> { status("已到本次录音时限，正在生成回复…"); stopRecording(); };
    public HostedSession(Context context, ApiClient api, Listener listener) {
        this.api=api; this.listener=listener;
        capture=new BluetoothCapture(context); player=new ReplyPlayer(context);
    }
    public HostedSession configure(int seconds, boolean vad, double threshold) {
        recordSeconds=Math.max(5,Math.min(60,seconds)); autoEnd=vad;
        voiceThreshold=Math.max(.005,Math.min(.2,threshold)); return this;
    }
    public void start() { worker=new Thread(() -> run(null,null),"aidol-voice"); worker.start(); }
    public void startText(String text) { worker=new Thread(() -> run(text,null),"aidol-chat"); worker.start(); }
    public void speak(String text) { worker=new Thread(() -> run(null,text),"aidol-play"); worker.start(); }
    private void run(String text,String playback) {
        try {
            check();
            if(playback!=null) { play(playback); return; }
            JSONObject result;
            String id=UUID.randomUUID().toString();
            if(text==null) {
                status("正在连接蓝牙耳机…");
                capture.start(new CaptureSource.Listener() {
                    public void status(String value) {
                        HostedSession.this.status(value);
                        if(value.startsWith("实际麦克风")) { ui.post(() -> { if(!cancelled.get()&&!stopped.get()) listener.recording(); }); ui.postDelayed(limit,recordSeconds*1000L); }
                    }
                    public void error(String value) { failure=value; end.countDown(); }
                    public void pcm(byte[] bytes) {
                        synchronized(audio) {
                            if(cancelled.get()||stopped.get()) return;
                            int count=Math.min(bytes.length,1920000-audio.size());
                            audio.write(bytes,0,count);
                            double level=AudioFrames.rms(bytes);
                            if(autoEnd) {
                                if(level>=voiceThreshold) { voicedBytes+=count; silenceBytes=0; heardSpeech=voicedBytes>=6400; }
                                else if(heardSpeech) silenceBytes+=count;
                                if(heardSpeech && audio.size()>=22400 && silenceBytes>=38400) { stopped.set(true); end.countDown(); }
                            }
                            long now=SystemClock.elapsedRealtime();
                            if(now-lastMeter>300) {
                                lastMeter=now; long size=audio.size(); double rms=AudioFrames.rms(bytes);
                                ui.post(() -> { if(!cancelled.get()) listener.meter(size,rms); });
                            }
                            if(audio.size()>=recordSeconds*32000) { stopped.set(true); end.countDown(); }
                        }
                    }
                });
                if(!end.await(75,TimeUnit.SECONDS)) throw new java.io.IOException("录音等待超时，请重新连接耳机");
                capture.stop(); ui.removeCallbacks(limit); check();
                if(failure!=null) throw new java.io.IOException(failure);
                byte[] pcm;
                synchronized(audio) { pcm=audio.toByteArray(); audio.reset(); }
                if(pcm.length<3200) throw new java.io.IOException("录音太短，请重新录音");
                status("正在识别并整理回复…");
                result=new JSONObject(new String(request("/v1/voice?request_id="+id,pcm,"audio/pcm"),StandardCharsets.UTF_8));
            } else {
                status("正在思考…");
                result=new JSONObject(new String(request("/v1/chat",new JSONObject().put("text",text).put("request_id",id).toString().getBytes(StandardCharsets.UTF_8),"application/json"),StandardCharsets.UTF_8));
            }
            check(); ui.post(() -> { if(!cancelled.get()) listener.result(result); });
            String reply=result.optString("response");
            if(result.optBoolean("speak",true) && !reply.isEmpty()) {
                try { play(reply); } catch(Exception e) {
                    check(); fail("文字已保存。"+(e instanceof java.io.IOException?e.getMessage():"暂时无法播放，请检查耳机连接"));
                    return;
                }
            }
            status(result.optString("notice").isEmpty()?"本轮完成 · 随时继续":result.optString("notice"));
        } catch(Exception e) {
            if(!cancelled.get()) {
                if(e instanceof ApiClient.ApiException && ((ApiClient.ApiException)e).status==401) api.vault.clear();
                fail(e instanceof java.net.SocketTimeoutException?"网络超时，请查看历史记录确认结果后再试":e instanceof java.io.IOException?e.getMessage():"连接失败，请检查网络后重试");
            }
        } finally {
            stopped.set(true); capture.stop(); player.cancel();
            if(pending!=null) pending.cancel();
            synchronized(audio) { audio.reset(); }
            ui.removeCallbacks(limit); ui.post(listener::done);
        }
    }
    private byte[] request(String path,byte[] body,String type) throws Exception {
        check(); pending=api.call("POST",path,body,type); check(); return api.execute(pending);
    }
    private void play(String text) throws Exception {
        status("正在准备语音…");
        byte[] wav=request("/v1/speech",new JSONObject().put("text",text.substring(0,Math.min(text.length(),1500))).toString().getBytes(StandardCharsets.UTF_8),"application/json");
        check(); status("正在耳机中播放…"); player.play(WavAudio.pcm(wav),this::status); check();
    }
    private void status(String text) { ui.post(() -> { if(!cancelled.get()) listener.status(text); }); }
    private void fail(String text) { ui.post(() -> { if(!cancelled.get()) listener.failure(text); }); }
    private void check() throws InterruptedException { if(cancelled.get()) throw new InterruptedException(); }
    public void stopRecording() { stopped.set(true); end.countDown(); ui.removeCallbacks(limit); }
    public void cancel() { cancelled.set(true); stopped.set(true); end.countDown(); if(pending!=null) pending.cancel(); player.cancel(); if(worker!=null) worker.interrupt(); ui.removeCallbacks(limit); }
}
