package com.aidol.demo;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** Fixed, TLS-verified server endpoint; retries of billable requests are explicit. */
public final class ApiClient {
    public final AuthVault vault;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    public ApiClient(Context context) { vault = new AuthVault(context); }
    public JSONObject json(String method, String path, JSONObject body) throws Exception {
        byte[] response = bytes(method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
    public Call call(String method, String path, byte[] body, String contentType) {
        RequestBody payload = body == null && (method.equals("GET") || method.equals("HEAD")) ? null
            : RequestBody.create(body == null ? new byte[0] : body, MediaType.get(contentType));
        Request.Builder builder = new Request.Builder().url(BuildConfig.SERVER_URL + path).method(method, payload);
        String token = vault.token();
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return client.newCall(builder.build());
    }
    public byte[] bytes(String method, String path, byte[] body, String contentType) throws Exception {
        return execute(call(method, path, body, contentType));
    }
    public byte[] execute(Call call) throws Exception {
        try (Response response = call.execute()) {
            if (response.body() == null) throw new IOException("服务器返回为空，请重试");
            byte[] bytes = response.body().bytes();
            if (!response.isSuccessful()) {
                String detail = "服务暂不可用（" + response.code() + "）";
                try { detail = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("detail", detail); } catch (Exception ignored) {}
                throw new ApiException(response.code(), detail);
            }
            return bytes;
        }
    }
}
