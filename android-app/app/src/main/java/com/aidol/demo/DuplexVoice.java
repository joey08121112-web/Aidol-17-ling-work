package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/** A call, not a capture turn: microphone frames remain live during every reply. */
final class DuplexVoice extends WebSocketListener {
    interface Listener {
        void speechStart();
        void thinking();
        void transcript(String text);
        void reply(String text);
        void playback(LiveReplyAudio audio);
        void result(JSONObject result);
        /** Stop the previous output without pretending that the user has started speaking. */
        default void playbackStopped() {}
        /** Playback drained or a recoverable reply ended; the microphone is still live. */
        default void listening() { speechStart(); }
    }
    private volatile Listener listener;
    private boolean preparation, activated;
    private String requestId;
    private final CountDownLatch ready = new CountDownLatch(1), ended = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile LiveReplyAudio audio;
    private volatile IOException failure;
    private volatile boolean closed;
    private volatile int outputTurn;
    private volatile int expectedTurn;
    private boolean awaitingReply;

    DuplexVoice(Listener listener) { this.listener = listener; }
    DuplexVoice() { this(new Listener() {
        public void speechStart() {}
        public void thinking() {}
        public void transcript(String text) {}
        public void reply(String text) {}
        public void playback(LiveReplyAudio audio) {}
        public void result(JSONObject result) {}
    }); }
    void connect(ApiClient api, String id) {
        connect(api, id, false);
    }
    void prepare(ApiClient api, String id) { connect(api, id, true); }
    private void connect(ApiClient api, String id, boolean prepare) {
        requestId = id; preparation = prepare;
        socket = api.voiceSocket("/v1/voice/stream?request_id=" + id
            + "&interaction_source=continuous_call&reply_audio=1&engine=qwen&duplex=1"
            + (prepare ? "&prepare=1" : ""), this);
    }
    String requestId() { return requestId; }
    synchronized boolean available() { return preparation && !activated && !closed && failure == null; }
    synchronized void activate(Listener target) throws IOException {
        check();
        if (!available()) throw new IOException("预连接已失效，请重新接通");
        activated = true; listener = target;
        if (socket == null || !socket.send("{\"type\":\"start\"}"))
            throw new IOException("预连接已断开，请重新接通");
    }
    void awaitReady() throws Exception {
        if (!ready.await(25, TimeUnit.SECONDS)) throw new IOException("实时通话连接超时");
        check();
    }
    void awaitEnd() throws Exception { ended.await(); check(); }
    synchronized void audio(byte[] pcm) throws IOException {
        check();
        for (int pos = 0; pos < pcm.length; pos += 32000) {
            int count = Math.min(32000, pcm.length - pos);
            if (socket == null || socket.queueSize() + count > 128000
                    || !socket.send(ByteString.of(pcm, pos, count)))
                throw new IOException("实时语音上传积压，请检查网络后重新接通");
        }
    }
    synchronized void text(String value) throws IOException {
        check();
        try {
            if (socket == null || !socket.send(new JSONObject().put("type", "text").put("text", value).toString()))
                throw new IOException("文字消息发送失败");
        } catch (org.json.JSONException error) { throw new IOException("文字消息格式无效", error); }
    }
    boolean current(LiveReplyAudio value) { return audio == value && !closed && failure == null; }
    boolean currentPresentation(LiveReplyAudio value) { return current(value) && outputTurn == expectedTurn; }
    synchronized void playbackDone(LiveReplyAudio value) {
        if (!current(value)) return;
        try { if (socket != null) socket.send(new JSONObject().put("type", "playback.done").put("turn", outputTurn).toString()); }
        catch (org.json.JSONException ignored) { /* Integer-only message. */ }
        audio = null;
        if (outputTurn == expectedTurn) {
            awaitingReply = false;
            listener.listening();
        }
    }
    private void clearPlayback() {
        LiveReplyAudio old = audio; audio = null;
        if (old != null) old.discard();
        listener.playbackStopped();
    }
    @Override public void onMessage(WebSocket ws, ByteString pcm) {
        LiveReplyAudio current = audio;
        if (closed || current == null) return;
        try { current.offer(pcm.toByteArray()); }
        catch (Exception error) { abort("实时回复音频中断，请重新接通"); }
    }
    @Override public synchronized void onMessage(WebSocket ws, String text) {
        if (closed || failure != null) return;
        try {
            JSONObject event = new JSONObject(text);
            switch (event.optString("type")) {
                case "prepared": break; // Standby never announces microphone readiness.
                case "ready":
                    if (!event.optBoolean("duplex")) throw new IOException("后端尚未启用全双工通话");
                    ready.countDown(); break;
                case "input.speech_start":
                    clearPlayback(); awaitingReply = false; listener.speechStart(); break;
                case "reply.interrupted":
                    clearPlayback(); awaitingReply = false; listener.listening(); break;
                // VAD can end on an empty utterance or the interruption keyword alone.
                // Only the accepted business turn proves that a response is being prepared.
                case "input.speech_end": break;
                case "thinking":
                    if (event.optInt("turn", expectedTurn) < expectedTurn) break;
                    expectedTurn = event.optInt("turn", expectedTurn); awaitingReply = true;
                    listener.thinking(); break;
                case "transcript.partial": listener.transcript(event.optString("text")); break;
                case "reply.start":
                    if (event.optInt("turn", expectedTurn) < expectedTurn) break;
                    if (event.optInt("sample_rate") != 24000) throw new IOException("回复音频格式无效");
                    clearPlayback();
                    outputTurn = event.optInt("turn", expectedTurn);
                    expectedTurn = outputTurn; awaitingReply = true;
                    LiveReplyAudio next = new LiveReplyAudio(); audio = next;
                    listener.playback(next); break;
                case "reply.text":
                    if (event.optInt("turn", expectedTurn) == expectedTurn) listener.reply(event.optString("text"));
                    break;
                case "reply.done":
                    if (audio != null && event.optInt("turn", outputTurn) == outputTurn) audio.finish();
                    break;
                case "reply.error":
                    clearPlayback(); awaitingReply = false; listener.listening();
                    listener.transcript(event.optString("detail", "本轮回复中断，可继续说话")); break;
                case "result":
                    listener.result(event.getJSONObject("result"));
                    // Silent business receipts have no audio stream or playback completion.
                    // A receipt from an older turn must never reset a newer response's phase.
                    if (audio == null && awaitingReply && event.optInt("turn", expectedTurn) == expectedTurn) {
                        awaitingReply = false; listener.listening();
                    }
                    break;
                case "call.done": ended.countDown(); break;
                case "error": abort(event.optString("detail", "实时通话失败")); break;
                default: throw new IOException("实时通话协议无效");
            }
        } catch (Exception error) { abort(error.getMessage() == null ? "实时通话协议无效" : error.getMessage()); }
    }
    @Override public void onFailure(WebSocket ws, Throwable error, Response response) { abort("实时通话连接中断，请重新接通"); }
    @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); }
    @Override public void onClosed(WebSocket ws, int code, String reason) {
        if (!closed && ended.getCount() > 0) abort("实时通话已断开，请重新接通");
    }
    synchronized void abort(String message) {
        if (failure == null) failure = new IOException(message);
        ready.countDown(); ended.countDown();
        clearPlayback();
        if (socket != null) socket.cancel();
    }
    synchronized void cancel() {
        if (closed) return;
        closed = true; clearPlayback(); ready.countDown(); ended.countDown();
        if (socket != null) { socket.send("{\"type\":\"end\"}"); socket.close(1000, null); }
    }
    private void check() throws IOException {
        if (failure != null) throw failure;
        if (closed) throw new IOException("通话已结束");
    }
}
