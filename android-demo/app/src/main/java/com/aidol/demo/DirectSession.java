package com.aidol.demo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One button-driven turn: headset -> direct ASR -> Ark -> direct TTS -> headset. */
public final class DirectSession {
    public record Config(String appId, String accessKey, String asrApiKey, String arkKey, String model) {}
    public interface Listener {
        void log(String message);
        void transcript(String text);
        void reply(String text);
        void recording();
        void processing();
        void meter(long bytes, double rms);
        void done();
    }
    private final Config config;
    private final Listener listener;
    private final BluetoothCapture capture;
    private final ReplyPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(64);
    private volatile boolean inputEnded, accepting;
    private volatile SpeechSocket asr, tts;
    private volatile Call chat;
    private Thread worker, sender;
    private volatile long bytes;
    private long lastMeter;
    private volatile String captureFailure;
    private final Runnable recordingLimit = () -> {
        if (!cancelled.get() && !inputEnded && !stopping.get()) { log("已到 60 秒，自动结束录音。"); stopRecording(); }
    };

    public DirectSession(Context context, Config config, Listener listener) {
        this.config = config; this.listener = listener;
        capture = new BluetoothCapture(context); player = new ReplyPlayer(context);
        client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build();
    }

    public void start() {
        worker = new Thread(this::run, "aidol-direct-turn"); worker.start();
        ui.postDelayed(recordingLimit, 60000);
    }
    private void run() {
        try {
            String text = recognize();
            if (cancelled.get()) return;
            if (text.trim().isEmpty()) throw new IOException("未识别到有效语音，请检查耳机麦克风和输入音量");
            ui.post(() -> listener.transcript(text));
            log("ASR 完成，手机直接请求豆包文本模型（关闭思考）…");
            String answer = chat(text);
            if (cancelled.get()) return;
            ui.post(() -> listener.reply(answer));
            log("手机直接请求语音合成…");
            byte[] reply = synthesize(answer);
            if (cancelled.get()) return;
            log("合成完成，播放到蓝牙耳机…");
            player.play(reply, this::log);
            if (!cancelled.get()) log("本轮完成：耳机 → 识别 → 豆包 → 耳机。可再次开始。");
        } catch (Exception e) {
            if (!cancelled.get()) log(captureFailure != null ? captureFailure
                : e instanceof IOException ? e.getMessage() : "调用失败：" + e.getClass().getSimpleName());
        } finally {
            accepting = false; inputEnded = true;
            capture.stop();
            if (sender != null) sender.interrupt();
            if (asr != null) asr.close();
            if (tts != null) tts.close();
            if (chat != null) chat.cancel();
            player.cancel();
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
            ui.removeCallbacks(recordingLimit);
            // Preserve the final message: delivery is FIFO on this Handler.
            ui.post(listener::done);
        }
    }
    private Request.Builder speechRequest(String url, String resource, boolean recognition) {
        Request.Builder request = new Request.Builder().url(url).header("X-Api-Resource-Id", resource)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString());
        if (recognition && !config.asrApiKey.trim().isEmpty()) request.header("X-Api-Key", config.asrApiKey);
        else request.header("X-Api-App-Key", config.appId).header("X-Api-Access-Key", config.accessKey);
        return request;
    }
    private String recognize() throws Exception {
        log("直连豆包流式 ASR…");
        asr = new SpeechSocket(client, speechRequest("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", "volc.seedasr.sauc.duration", true).build());
        checkCancelled();
        asr.awaitOpen();
        checkCancelled();
        JSONObject body = new JSONObject().put("user", new JSONObject().put("uid", "aidol-android"))
            .put("audio", new JSONObject().put("format", "pcm").put("codec", "raw").put("rate", 16000).put("bits", 16).put("channel", 1).put("language", "zh-CN"))
            .put("request", new JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true)
                .put("show_utterances", true).put("result_type", "full").put("enable_nonstream", true).put("end_window_size", 800));
        asr.send(SpeechProtocol.asr(body.toString().getBytes(StandardCharsets.UTF_8), true, false));
        accepting = true;
        capture.start(new CaptureSource.Listener() {
            @Override public void status(String message) {
                log(message);
                if (message.startsWith("实际麦克风")) ui.post(listener::recording);
            }
            @Override public void error(String message) { failCapture("耳机采集失败：" + message); }
            @Override public void pcm(byte[] pcm) {
                if (!accepting || cancelled.get()) return;
                try {
                    AudioFrames.split(pcm, frame -> { if (accepting && !audio.offer(frame)) failCapture("音频发送积压，请检查手机网络"); });
                    bytes += pcm.length;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastMeter >= 500) {
                        lastMeter = now; long count = bytes; double rms = AudioFrames.rms(pcm);
                        ui.post(() -> listener.meter(count, rms));
                    }
                } catch (Exception e) { failCapture("耳机返回不完整 PCM 音频"); }
            }
        });
        sender = new Thread(() -> {
            try {
                while (!cancelled.get() && (!inputEnded || !audio.isEmpty())) {
                    byte[] frame = audio.poll(100, TimeUnit.MILLISECONDS);
                    if (frame != null) asr.send(SpeechProtocol.asr(frame, false, false));
                }
                if (!cancelled.get()) asr.send(SpeechProtocol.asr(new byte[0], false, true));
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception e) { failCapture("ASR 上行中断，请检查手机网络"); }
        }, "aidol-asr-upload");
        sender.start();
        String finalText = "";
        while (!cancelled.get()) {
            SpeechProtocol.Packet packet = SpeechProtocol.parse(asr.receive(inputEnded ? 25 : 75), false);
            JSONObject root = new JSONObject(new String(packet.payload(), StandardCharsets.UTF_8));
            int code = root.optInt("code", 0);
            if (code != 0 && code != 1000 && code != 20000000) throw new IOException("ASR 返回错误码 " + code);
            JSONObject result = root.optJSONObject("result");
            if (result != null && !result.optString("text").trim().isEmpty()) {
                finalText = result.optString("text");
                String update = finalText; ui.post(() -> listener.transcript(update));
            }
            if (packet.last()) break;
        }
        checkCancelled();
        accepting = false; inputEnded = true; capture.stop();
        if (sender != null) sender.join(1000);
        asr.close();
        if (captureFailure != null) throw new IOException(captureFailure);
        return finalText;
    }
    private String chat(String text) throws Exception {
        JSONObject body = new JSONObject().put("model", config.model).put("thinking", new JSONObject().put("type", "disabled"))
            .put("max_tokens", 180).put("stream", false)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "你是小聆，用户正在测试蓝牙耳机语音对话。请用自然中文简短回答，不超过60个汉字，不使用Markdown。"))
                .put(new JSONObject().put("role", "user").put("content", text)));
        Request request = new Request.Builder().url("https://ark.cn-beijing.volces.com/api/v3/chat/completions")
            .header("Authorization", "Bearer " + config.arkKey)
            .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        chat = client.newCall(request);
        checkCancelled();
        try (Response response = chat.execute()) {
            if (!response.isSuccessful()) throw new IOException("豆包文本模型 HTTP " + response.code() + "，检查 Ark Key、模型权限/名称");
            if (response.body() == null) throw new IOException("文本模型返回空结果");
            String result = new JSONObject(response.body().string()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim();
            if (result.isEmpty()) throw new IOException("文本模型没有返回回答");
            return result.substring(0, Math.min(result.length(), 500));
        }
    }
    private JSONObject ttsBody(int event, String session, String text) throws Exception {
        JSONObject params = new JSONObject().put("speaker", "zh_female_jitangmeimei_mars_bigtts")
            .put("audio_params", new JSONObject().put("format", "pcm").put("sample_rate", 24000));
        if (event == 200) params.put("text", text).put("text_type", "plain");
        return new JSONObject().put("event", event).put("namespace", "BidirectionalTTS").put("reqid", UUID.randomUUID().toString())
            .put("user", new JSONObject().put("uid", "aidol-android")).put("req_params", params);
    }
    private byte[] synthesize(String text) throws Exception {
        tts = new SpeechSocket(client, speechRequest("wss://openspeech.bytedance.com/api/v3/tts/bidirection", "seed-tts-1.0", false).build());
        checkCancelled();
        tts.awaitOpen(); checkCancelled();
        String session = UUID.randomUUID().toString();
        tts.send(SpeechProtocol.tts(1, null, "{}"));
        if (SpeechProtocol.parse(tts.receive(15), true).event() != 50) throw new IOException("TTS 连接未确认");
        tts.send(SpeechProtocol.tts(100, session, ttsBody(100, session, text).toString()));
        SpeechProtocol.Packet opened = SpeechProtocol.parse(tts.receive(15), true);
        if (opened.event() != 150 || !session.equals(opened.session())) throw new IOException("TTS 会话未确认");
        tts.send(SpeechProtocol.tts(200, session, ttsBody(200, session, text).toString()));
        tts.send(SpeechProtocol.tts(102, session, "{}"));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + 45000;
        while (!cancelled.get()) {
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("TTS 合成超时");
            SpeechProtocol.Packet packet = SpeechProtocol.parse(tts.receive(20), true);
            if (!packet.session().isEmpty() && !session.equals(packet.session())) throw new IOException("TTS 会话不匹配");
            if (packet.kind() == 11 || packet.event() == 352) {
                if (pcm.size() + packet.payload().length > 6 * 1024 * 1024) throw new IOException("TTS 回复过长");
                pcm.write(packet.payload());
            }
            if (packet.event() == 152) { tts.send(SpeechProtocol.tts(2, null, "{}")); tts.close(); return pcm.toByteArray(); }
            if (packet.event() == 52 || packet.event() == 151) throw new IOException("TTS 提前终止");
        }
        checkCancelled(); return new byte[0];
    }
    public void stopRecording() {
        if (!stopping.compareAndSet(false, true) || cancelled.get()) return;
        accepting = false;
        ui.post(listener::processing);
        new Thread(() -> { capture.stop(); inputEnded = true; }, "aidol-end-input").start();
        log("录音结束，等待最终识别…");
    }
    public void cancel() {
        cancelled.set(true); accepting = false; inputEnded = true;
        if (asr != null) asr.close();
        if (tts != null) tts.close();
        if (chat != null) chat.cancel();
        player.cancel();
        if (worker != null) worker.interrupt();
    }
    private void failCapture(String message) { captureFailure = message; accepting = false; inputEnded = true; if (asr != null) asr.close(); }
    private void checkCancelled() throws InterruptedException { if (cancelled.get()) throw new InterruptedException(); }
    private void log(String message) { ui.post(() -> listener.log(message)); }
}
