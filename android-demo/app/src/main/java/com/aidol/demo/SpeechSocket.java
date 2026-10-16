package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Blocking facade used only on worker threads; bounded receive and send queues. */
public final class SpeechSocket extends WebSocketListener implements AutoCloseable {
    private final ArrayBlockingQueue<byte[]> messages = new ArrayBlockingQueue<>(128);
    private final CountDownLatch opened = new CountDownLatch(1);
    private volatile IOException error;
    private volatile boolean closed;
    private final WebSocket socket;
    public SpeechSocket(OkHttpClient client, Request request) { socket = client.newWebSocket(request, this); }
    public void awaitOpen() throws Exception {
        if (!opened.await(15, TimeUnit.SECONDS)) throw new IOException("语音连接超时");
        if (error != null) throw error;
        if (closed) throw new IOException("语音连接已取消");
    }
    public void send(byte[] bytes) throws IOException {
        if (error != null) throw error;
        if (closed || socket.queueSize() > 256000 || !socket.send(ByteString.of(bytes))) throw new IOException("语音网络发送失败或积压");
    }
    public byte[] receive(int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (error != null) throw error;
            byte[] frame = messages.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null) return frame;
            if (closed) throw new IOException("语音连接提前关闭");
        }
        throw new IOException("语音响应超时");
    }
    @Override public void onOpen(WebSocket ws, Response response) { opened.countDown(); }
    @Override public void onMessage(WebSocket ws, ByteString data) {
        if (data.size() > 4 * 1024 * 1024 || !messages.offer(data.toByteArray())) {
            error = new IOException("语音响应过大或接收积压"); ws.cancel();
        }
    }
    @Override public void onMessage(WebSocket ws, String text) { error = new IOException("服务返回非预期文本帧"); ws.cancel(); }
    @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
        error = new IOException(response == null ? "语音连接失败：" + t.getClass().getSimpleName()
            : "语音服务 HTTP " + response.code() + "，请检查 AppID、Access Key 和资源权限");
        opened.countDown();
    }
    @Override public void onClosing(WebSocket ws, int code, String reason) { closed = true; ws.close(code, ""); }
    @Override public void onClosed(WebSocket ws, int code, String reason) { closed = true; }
    @Override public void close() { closed = true; socket.cancel(); opened.countDown(); }
}
