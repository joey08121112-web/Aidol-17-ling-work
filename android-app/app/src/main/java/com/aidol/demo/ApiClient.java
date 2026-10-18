package com.aidol.demo;

import android.content.Context;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

/** Immutable endpoint per client; retries of billable requests are explicit. */
public final class ApiClient {
    public final AuthVault vault;
    public final String serverUrl;
    private volatile Consumer<String> diagnostics = value -> {};
    private final HttpTransport transport;
    /** Read-only recovery for stale USB/proxy connections must never replay a mutation. */
    static final class HttpTransport {
        private final OkHttpClient mutations;
        // newBuilder retains the same connection pool, dispatcher, timeouts and redirect policy.
        private final OkHttpClient safeReads;
        private final OkHttpClient liveSockets;
        // Media bodies are read at playback speed, so only the per-read timeout applies.
        private final OkHttpClient mediaReads;
        HttpTransport() { this(false); }
        HttpTransport(boolean debugUsbOrigin) {
            // Reuse is safe everywhere: reads recover a stale socket on a fresh connection and
            // mutations never replay an uncertain write. The debug USB origin only narrows the
            // stale-socket window (adb reverse reloads) with a short pool keep-alive.
            OkHttpClient.Builder base = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(100, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false);
            if (debugUsbOrigin) base.connectionPool(new ConnectionPool(5, 10, TimeUnit.SECONDS));
            mutations = base.build();
            safeReads = mutations.newBuilder().retryOnConnectionFailure(true).build();
            liveSockets = mutations.newBuilder().readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build();
            mediaReads = safeReads.newBuilder().callTimeout(0, TimeUnit.SECONDS).build();
        }
        Call call(Request request) {
            boolean readOnly = request.method().equals("GET") || request.method().equals("HEAD");
            return (readOnly ? safeReads : mutations).newCall(request);
        }
        Call media(Request request) {
            return mediaReads.newCall(request);
        }
        WebSocket socket(Request request, WebSocketListener listener) {
            return liveSockets.newWebSocket(request, listener);
        }
        void closeForTest() {
            mutations.connectionPool().evictAll();
            mutations.dispatcher().executorService().shutdown();
        }
    }
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    public ApiClient(Context context) { this(context, new DevelopmentSettings(context).serverUrl()); }
    public ApiClient(Context context, String serverUrl) {
        this.serverUrl = ServerEndpoint.validate(serverUrl, BuildConfig.DEBUG);
        transport = new HttpTransport(isDebugUsbOrigin(this.serverUrl, BuildConfig.DEBUG));
        vault = new AuthVault(context, ServerEndpoint.sessionNamespace(this.serverUrl));
    }
    static boolean isDebugUsbOrigin(String validatedOrigin, boolean debug) {
        return debug && ServerEndpoint.USB_LOCAL.equals(validatedOrigin);
    }
    public void setDiagnostics(Consumer<String> listener) { if (BuildConfig.DEBUG) diagnostics = listener; }
    public JSONObject json(String method, String path, JSONObject body) throws Exception {
        byte[] response = bytes(method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
    /** Serialize only settings writes; old servers get a fresh read/merge without replaying uncertain writes. */
    public synchronized JSONObject patchSettings(JSONObject changes) throws Exception {
        return SettingsUpdates.apply(changes, vault::token, (method, path, body, token) -> {
            byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
            byte[] response = execute(callWithToken(method, path, payload, "application/json; charset=utf-8", null, token));
            return new JSONObject(new String(response, StandardCharsets.UTF_8));
        }, error -> error instanceof ApiException ? ((ApiException) error).status : 0);
    }
    public Call call(String method, String path, byte[] body, String contentType) {
        return call(method, path, body, contentType, null);
    }
    public Call call(String method, String path, byte[] body, String contentType, String requestId) {
        return callWithToken(method, path, body, contentType, requestId, vault.token());
    }
    private Call callWithToken(String method, String path, byte[] body, String contentType, String requestId, String token) {
        RequestBody payload = body == null && (method.equals("GET") || method.equals("HEAD")) ? null
            : RequestBody.create(body == null ? new byte[0] : body, MediaType.get(contentType));
        Request.Builder builder = new Request.Builder().url(serverUrl + path).method(method, payload);
        if (requestId != null && requestId.matches("[a-zA-Z0-9-]{16,64}")) builder.header("X-Aidol-Request-ID", requestId);
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return transport.call(builder.build());
    }
    /** An authenticated GET whose body may stream for minutes (meeting audio). */
    public Call mediaCall(String path) {
        Request.Builder builder = new Request.Builder().url(serverUrl + path).get();
        String token = vault.token();
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return transport.media(builder.build());
    }
    /** Like execute, but hands the open body to the caller, who must close the response. */
    public Response open(Call call) throws IOException {
        String operation = operation(call);
        diagnostics.accept(operation + " · 请求中");
        Response response;
        try { response = call.execute(); }
        catch (IOException e) {
            diagnostics.accept(operation + " · " + (call.isCanceled() ? "已取消" : "网络失败：" + e.getClass().getSimpleName()));
            throw transportFailure(call, e);
        }
        diagnostics.accept(operation + " · HTTP " + response.code());
        if (response.isSuccessful() && response.body() != null) return response;
        try (Response failed = response) {
            String detail = "服务暂不可用（" + failed.code() + "）";
            try { detail = new JSONObject(failed.body().string()).optString("detail", detail); } catch (Exception ignored) {}
            throw new ApiException(failed.code(), detail);
        }
    }
    public byte[] bytes(String method, String path, byte[] body, String contentType) throws Exception {
        return execute(call(method, path, body, contentType));
    }
    WebSocket voiceSocket(String path, WebSocketListener listener) {
        return transport.socket(new Request.Builder().url(serverUrl + path)
            .header("Authorization", "Bearer " + vault.token()).build(), listener);
    }
    public byte[] execute(Call call) throws Exception {
        String operation = operation(call);
        diagnostics.accept(operation + " · 请求中");
        try (Response response = call.execute()) {
            diagnostics.accept(operation + " · HTTP " + response.code());
            if (response.body() == null) throw new IOException("服务器返回为空，请重试");
            byte[] bytes = response.body().bytes();
            if (!response.isSuccessful()) {
                String detail = "服务暂不可用（" + response.code() + "）";
                try { detail = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("detail", detail); } catch (Exception ignored) {}
                throw new ApiException(response.code(), detail);
            }
            return bytes;
        } catch (IOException e) {
            if (!(e instanceof ApiException)) diagnostics.accept(operation + " · " + (call.isCanceled() ? "已取消" : "网络失败：" + e.getClass().getSimpleName()));
            throw transportFailure(call, e);
        }
    }
    private static String operation(Call call) {
        // Only method/path/status: never log credentials, query strings or bodies.
        String correlation = call.request().header("X-Aidol-Request-ID");
        return call.request().method() + " " + call.request().url().encodedPath()
            + (correlation == null ? "" : " · request_id=" + correlation);
    }
    static IOException transportFailure(Call call, IOException cause) {
        if (cause instanceof ApiException) return cause;
        if (call.isCanceled()) return new IOException("已取消本次请求", cause);
        String message = cause instanceof InterruptedIOException ? "连接超时，请检查网络和服务状态"
            : cause instanceof ConnectException ? "无法连接服务，请检查网络和服务是否已启动"
            : "连接已中断，请检查网络和服务状态";
        boolean readOnly = call.request().method().equals("GET") || call.request().method().equals("HEAD");
        message += readOnly ? "后重新加载" : "。服务器可能已收到请求，请先确认页面结果或历史记录后再操作";
        return new IOException(message, cause);
    }
}
