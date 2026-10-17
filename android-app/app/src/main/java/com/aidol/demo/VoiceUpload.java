package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/** One authenticated live ASR turn. Never reconnects/replays uncertain audio. */
final class VoiceUpload extends WebSocketListener {
    private final CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
    private final Consumer<String> partial;
    private final Consumer<String> reply;
    private final CountDownLatch replyReady = new CountDownLatch(1);
    final LiveReplyAudio replyAudio = new LiveReplyAudio();
    private boolean receiveReply;
    private boolean qwenRealtime;
    private Runnable speechEnd = () -> {};
    private boolean endpointReported;
    void onSpeechEnd(Runnable callback) { speechEnd = callback; }
    private volatile boolean hasReply, replyEnded, streamDone;
    private volatile WebSocket socket;
    private volatile IOException failure;
    private volatile JSONObject result;
    private boolean ended, closed;

    VoiceUpload(Consumer<String> partial) { this(partial, text -> {}); }
    VoiceUpload(Consumer<String> partial, Consumer<String> reply) { this.partial = partial; this.reply = reply; }
    void enableReply(boolean enabled) { receiveReply = enabled; }
    void useQwenRealtime(boolean enabled) { qwenRealtime = enabled; }
    synchronized void connect(ApiClient api, String requestId, String interaction) {
        if (closed) return;
        socket = api.voiceSocket("/v1/voice/stream?request_id=" + requestId + "&interaction_source=" + interaction
            + (receiveReply ? "&reply_audio=1" : "") + (qwenRealtime ? "&engine=qwen" : ""), this);
    }
    void awaitReady() throws Exception {
        if (!ready.await(15, TimeUnit.SECONDS)) throw new IOException("实时语音连接超时，请重新开始");
        check();
    }
    synchronized void audio(byte[] pcm) throws IOException {
        check();
        if (ended || socket == null) throw new IOException("实时语音会话已结束");
        for (int offset = 0; offset < pcm.length; offset += 32000) {
            int length = Math.min(32000, pcm.length - offset);
            if (socket.queueSize() + length > 128000 || !socket.send(ByteString.of(pcm, offset, length)))
                throw new IOException("上传网络过慢，已停止本轮，请检查网络后重试");
        }
    }
    synchronized void finish() throws IOException {
        check(); ended = true;
        if (!socket.send("{\"type\":\"end\"}")) throw new IOException("语音结束确认发送失败");
    }
    JSONObject awaitResult() throws Exception {
        if (!done.await(100, TimeUnit.SECONDS)) throw new IOException("本轮等待超时，请先查询历史记录");
        check();
        if (result == null) throw new IOException("实时语音连接已中断，请查询本轮状态");
        return result;
    }
    boolean awaitReply() throws Exception {
        if (!replyReady.await(100, TimeUnit.SECONDS)) throw new IOException("等待回复超时");
        check(); return hasReply;
    }
    synchronized void interruptReply() {
        replyAudio.discard();
        if (receiveReply && ended && !closed && !streamDone && socket != null)
            socket.send("{\"type\":\"interrupt\"}");
    }
    public void onMessage(WebSocket ws, ByteString bytes) {
        try {
            if (!receiveReply || !hasReply || replyEnded) throw new IOException("意外的回复音频");
            replyAudio.offer(bytes.toByteArray());
        } catch (Exception error) { fail(new IOException("回复音频中断，请查看文字结果", error)); ws.cancel(); }
    }
    public void onMessage(WebSocket ws, String text) {
        try {
            JSONObject value = new JSONObject(text);
            switch (value.optString("type")) {
                case "ready": ready.countDown(); break;
                case "transcript.partial": partial.accept(value.optString("text")); break;
                case "thinking": break;
                case "input.speech_end":
                    if (!qwenRealtime) throw new IOException("意外的停句事件");
                    if (!endpointReported) { endpointReported = true; speechEnd.run(); }
                    break;
                case "reply.start":
                    if (!receiveReply || hasReply || value.optInt("sample_rate") != 24000) throw new IOException("音频格式无效");
                    hasReply = true; reply.accept(value.optString("text")); replyReady.countDown(); break;
                case "reply.text":
                    if (!qwenRealtime || !hasReply || replyEnded) throw new IOException("意外的回复文字");
                    reply.accept(value.getString("text")); break;
                case "reply.done":
                    if (!hasReply || replyEnded) throw new IOException("语音结束帧无效");
                    replyEnded = true; replyAudio.finish(); break;
                case "reply.error": replyEnded = true; replyAudio.fail(new IOException(value.optString("detail", "语音播放中断"))); break;
                case "reply.interrupted": replyEnded = true; replyAudio.discard(); break;
                case "result": result = value.getJSONObject("result"); if (!receiveReply) { done.countDown(); replyReady.countDown(); } break;
                case "stream.done":
                    if (!receiveReply || result == null || hasReply && !replyEnded) throw new IOException("回复未完整结束");
                    streamDone = true; done.countDown(); replyReady.countDown(); break;
                case "error": fail(new ApiClient.ApiException(value.optInt("status", 502), value.optString("detail", "实时语音失败"))); break;
                default: fail(new IOException("实时语音返回格式无效"));
            }
        } catch (Exception error) { fail(new IOException("实时语音返回格式无效")); }
    }
    public void onFailure(WebSocket ws, Throwable error, Response response) {
        fail(response != null && response.code() == 403
            ? new ApiClient.ApiException(401, "实时语音登录失效，请重新登录")
            : new IOException("实时语音连接中断，请先查询本轮状态"));
    }
    public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); }
    public void onClosed(WebSocket ws, int code, String reason) {
        if (result == null || receiveReply && !streamDone) fail(new IOException("实时语音已断开，请查询本轮状态"));
    }
    private synchronized void fail(IOException error) {
        if (failure == null && (result == null || receiveReply && !streamDone)) failure = error;
        replyAudio.fail(error);
        ready.countDown(); done.countDown(); replyReady.countDown();
    }
    private void check() throws IOException { if (failure != null) throw failure; if (closed) throw new IOException("本轮已取消"); }
    synchronized void cancel() {
        closed = true;
        replyAudio.discard();
        if (socket != null) socket.cancel();
        ready.countDown(); done.countDown(); replyReady.countDown();
    }
}
