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
    private volatile WebSocket socket;
    private volatile IOException failure;
    private volatile JSONObject result;
    private boolean ended, closed;

    VoiceUpload(Consumer<String> partial) { this.partial = partial; }
    synchronized void connect(ApiClient api, String requestId, String interaction) {
        if (closed) return;
        socket = api.voiceSocket("/v1/voice/stream?request_id=" + requestId + "&interaction_source=" + interaction, this);
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
    public void onMessage(WebSocket ws, String text) {
        try {
            JSONObject value = new JSONObject(text);
            switch (value.optString("type")) {
                case "ready": ready.countDown(); break;
                case "transcript.partial": partial.accept(value.optString("text")); break;
                case "result": result = value.getJSONObject("result"); done.countDown(); break;
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
        if (result == null) fail(new IOException("实时语音已断开，请查询本轮状态"));
    }
    private synchronized void fail(IOException error) {
        if (failure == null && result == null) failure = error;
        ready.countDown(); done.countDown();
    }
    private void check() throws IOException { if (failure != null) throw failure; if (closed) throw new IOException("本轮已取消"); }
    synchronized void cancel() {
        closed = true;
        if (socket != null) socket.cancel();
        ready.countDown(); done.countDown();
    }
}
