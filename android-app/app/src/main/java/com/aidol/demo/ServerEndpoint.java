package com.aidol.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import okhttp3.HttpUrl;

/** Endpoint policy and credential namespace shared by all API requests. */
public final class ServerEndpoint {
    public static final String USB_LOCAL = "http://127.0.0.1:8108";
    /** Historical origin for migration/isolation tests; validate always rejects it. */
    public static final String EMULATOR_LOCAL = "http://10.0.2.2:8108";
    public static final String LOCAL = USB_LOCAL;
    private ServerEndpoint() {}

    public static String validate(String value, boolean debug) {
        HttpUrl url = HttpUrl.get(value);
        if (!url.username().isEmpty() || !url.password().isEmpty() || url.query() != null
                || url.fragment() != null || !url.encodedPath().equals("/"))
            throw new IllegalArgumentException("服务器地址必须是独立的 HTTP(S) 源地址");
        String origin = url.toString().replaceAll("/$", "");
        if (!url.isHttps() && !USB_LOCAL.equals(origin))
            throw new IllegalArgumentException("只有 USB 本地后端 127.0.0.1:8108 允许 HTTP");
        return origin;
    }

    public static String sessionNamespace(String origin) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("session_");
            for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return name.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
