package com.aidol.demo;

import android.content.Context;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** Immutable endpoint per client; retries of billable requests are explicit. */
public final class ApiClient {
    public final AuthVault vault;
    public final String serverUrl;
    private volatile Consumer<String> diagnostics = value -> {};
    private final HttpTransport transport;
    /** Read-only recovery for stale USB/proxy connections must never replay a mutation. */
    static final class HttpTransport {
        private final boolean freshConnections;
        private final OkHttpClient mutations = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        // newBuilder retains the same connection pool, dispatcher, timeouts and redirect policy.
        private final OkHttpClient safeReads = mutations.newBuilder().retryOnConnectionFailure(true).build();
        HttpTransport() { this(false); }
        HttpTransport(boolean freshConnections) { this.freshConnections = freshConnections; }
        Call call(Request request) {
            // adb reverse/local reloads can leave stale HTTP/1.1 sockets. Prevent reuse for this
            // debug-only origin instead of replaying an uncertain write after losing its response.
            if (freshConnections) request = request.newBuilder().header("Connection", "close").build();
            boolean readOnly = request.method().equals("GET") || request.method().equals("HEAD");
            return (readOnly ? safeReads : mutations).newCall(request);
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
    public ApiClient(Context context) { this(context, BuildConfig.SERVER_URL); }
    public ApiClient(Context context, String serverUrl) {
        this.serverUrl = ServerEndpoint.validate(serverUrl, BuildConfig.DEBUG);
        transport = new HttpTransport(useFreshConnections(this.serverUrl, BuildConfig.DEBUG));
        vault = new AuthVault(context, BuildConfig.DEBUG ? ServerEndpoint.sessionNamespace(this.serverUrl) : "session");
    }
    static boolean useFreshConnections(String validatedOrigin, boolean debug) {
        return debug && ServerEndpoint.USB_LOCAL.equals(validatedOrigin);
    }
    public void setDiagnostics(Consumer<String> listener) { if (BuildConfig.DEBUG) diagnostics = listener; }
    public JSONObject json(String method, String path, JSONObject body) throws Exception {
        byte[] response = bytes(method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
    public Call call(String method, String path, byte[] body, String contentType) {
        return call(method, path, body, contentType, null);
    }
    public Call call(String method, String path, byte[] body, String contentType, String requestId) {
        RequestBody payload = body == null && (method.equals("GET") || method.equals("HEAD")) ? null
            : RequestBody.create(body == null ? new byte[0] : body, MediaType.get(contentType));
        Request.Builder builder = new Request.Builder().url(serverUrl + path).method(method, payload);
        if (requestId != null && requestId.matches("[a-zA-Z0-9-]{16,64}")) builder.header("X-Aidol-Request-ID", requestId);
        String token = vault.token();
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return transport.call(builder.build());
    }
    public byte[] bytes(String method, String path, byte[] body, String contentType) throws Exception {
        return execute(call(method, path, body, contentType));
    }
    public byte[] execute(Call call) throws Exception {
        // Only method/path/status: never log credentials, query strings or bodies.
        String correlation = call.request().header("X-Aidol-Request-ID");
        String operation = call.request().method() + " " + call.request().url().encodedPath()
            + (correlation == null ? "" : " · request_id=" + correlation);
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
