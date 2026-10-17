package com.aidol.demo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One live-caption socket for a work meeting: recorded PCM up, interim and final sentences down.
 * The server saves final sentences; this class only reports them. Any failure is final for this
 * socket: the caller reconnects at the current offset and the server recognises the missed segment.
 */
final class WorkLiveCaptions extends WebSocketListener {
    interface Listener {
        void partial(String text);
        void finals(JSONArray sentences);
        void failed(String message);
    }
    private static final long MAX_QUEUED_BYTES = 192_000; // 6 s of audio waiting on a slow network.
    private final Listener listener;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile boolean ready, failed, finished;
    private volatile long readyAt = Long.MAX_VALUE;

    private WorkLiveCaptions(Listener listener) { this.listener = listener; }

    static WorkLiveCaptions connect(ApiClient api, String meetingId, long offsetMs, Listener listener) {
        WorkLiveCaptions live = new WorkLiveCaptions(listener);
        live.socket = api.voiceSocket("/v1/work-meetings/" + meetingId + "/live?offset_ms=" + offsetMs, live);
        return live;
    }
    /** {@link LiveCoverage#now()} when the server accepted audio; MAX_VALUE until then or after a failure. */
    long healthySince() { return ready && !failed ? readyAt : Long.MAX_VALUE; }
    boolean healthy() { return ready && !failed; }

    /** @return false when this socket can no longer carry audio (the caller reconnects). */
    synchronized boolean audio(byte[] pcm) {
        if (failed || finished) return false;
        if (!ready) return true; // Audio before ready is not captioned; its segment falls back to the server.
        for (int offset = 0; offset < pcm.length; offset += 32000) {
            int length = Math.min(32000, pcm.length - offset);
            if (socket.queueSize() + length > MAX_QUEUED_BYTES || !socket.send(ByteString.of(pcm, offset, length))) {
                fail("网络过慢，实时字幕暂停，这段录音会改用整段识别"); return false;
            }
        }
        return true;
    }
    synchronized void pause() { if (healthy() && !finished) socket.send("{\"type\":\"pause\"}"); }
    synchronized void resume(long offsetMs) {
        if (healthy() && !finished) socket.send("{\"type\":\"resume\",\"offset_ms\":" + offsetMs + "}");
    }
    /** Ask the server to save the last sentences; true only when it confirmed within the timeout. */
    boolean end(long timeoutMs) throws InterruptedException {
        synchronized (this) {
            if (!healthy() || finished) { close(); return false; }
            finished = true;
            if (!socket.send("{\"type\":\"end\"}")) { close(); return false; }
        }
        boolean confirmed = done.await(timeoutMs, TimeUnit.MILLISECONDS) && !failed;
        close();
        return confirmed;
    }
    void close() { finished = true; WebSocket current = socket; if (current != null) current.cancel(); done.countDown(); }

    @Override public void onMessage(WebSocket ws, String text) {
        try {
            JSONObject value = new JSONObject(text);
            switch (value.optString("type")) {
                case "ready": readyAt = LiveCoverage.now(); ready = true; break;
                case "partial": listener.partial(value.optString("text")); break;
                case "final": listener.finals(value.optJSONArray("sentences") == null ? new JSONArray() : value.getJSONArray("sentences")); break;
                case "paused": break;
                case "done": done.countDown(); break;
                case "error": fail(value.optString("message", "实时字幕中断")); break;
                default: fail("实时字幕返回格式无效");
            }
        } catch (Exception error) { fail("实时字幕返回格式无效"); }
    }
    @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
        if (!finished || done.getCount() > 0) fail("实时字幕连接中断，这段录音会改用整段识别");
    }
    @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(1000, null); }
    @Override public void onClosed(WebSocket ws, int code, String reason) {
        if (done.getCount() > 0) fail(code == 4409 ? "会议已结束或不在工作模式，实时字幕未开启" : "实时字幕已断开，这段录音会改用整段识别");
    }
    private void fail(String message) {
        boolean first;
        synchronized (this) { first = !failed; failed = true; }
        done.countDown();
        if (first && !finished) listener.failed(message);
    }
}
