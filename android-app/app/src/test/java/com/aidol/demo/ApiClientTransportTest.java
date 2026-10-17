package com.aidol.demo;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual socket tests: a method policy alone would not catch an accidental POST replay. */
public class ApiClientTransportTest {
    private static Request request(MockWebServer server, String method, String path) {
        RequestBody body = method.equals("GET") || method.equals("HEAD") ? null
            : RequestBody.create("synthetic-test-input", MediaType.get("application/octet-stream"));
        return new Request.Builder().url(server.url(path)).method(method, body).build();
    }

    private static void prime(ApiClient.HttpTransport transport, MockWebServer server) throws Exception {
        server.enqueue(new MockResponse().setBody("ready"));
        try (Response response = transport.call(request(server, "GET", "/prime")).execute()) {
            assertEquals("ready", response.body().string());
        }
        assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS).getSequenceNumber());
    }

    @Test public void freshConnectionsAreLimitedToTheExactDebugUsbOrigin() {
        assertTrue(ApiClient.useFreshConnections(ServerEndpoint.validate(ServerEndpoint.USB_LOCAL + "/", true), true));
        assertFalse(ApiClient.useFreshConnections(ServerEndpoint.USB_LOCAL, false));
        for (String origin : new String[] {"https://aidol.example", "https://127.0.0.1:8108",
                "http://127.0.0.1:8109", "http://localhost:8108", "http://127.0.0.1:8108.evil.example"}) {
            assertFalse(origin, ApiClient.useFreshConnections(origin, true));
        }
    }

    @Test public void usbReadVoiceSpeechAndSettingsAlwaysUseFreshSockets() throws Exception {
        ApiClient.HttpTransport transport = new ApiClient.HttpTransport(true);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String[][] cases = {{"GET", "/v1/state"}, {"POST", "/v1/voice"},
                {"POST", "/v1/speech"}, {"PUT", "/v1/settings"}, {"GET", "/v1/state"}};
            for (String[] item : cases) {
                server.enqueue(new MockResponse().setBody("complete"));
                try (Response response = transport.call(request(server, item[0], item[1])).execute()) {
                    assertEquals("complete", response.body().string());
                }
                RecordedRequest received = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(received); assertEquals(item[0], received.getMethod());
                assertEquals("close", received.getHeader("Connection"));
                assertEquals("USB requests must never reuse the previous socket", 0, received.getSequenceNumber());
            }
            assertEquals(cases.length, server.getRequestCount());
        } finally { transport.closeForTest(); }
    }

    @Test public void getAndHeadRecoverWhenReusedSocketClosesBeforeResponse() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            ApiClient.HttpTransport transport = new ApiClient.HttpTransport();
            try (MockWebServer server = new MockWebServer()) {
                server.start(); prime(transport, server);
                server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
                server.enqueue(new MockResponse().setResponseCode(200).setBody(method.equals("GET") ? "restored" : ""));
                try (Response response = transport.call(request(server, method, "/v1/state")).execute()) {
                    assertEquals(200, response.code());
                    assertEquals(method.equals("GET") ? "restored" : "", response.body().string());
                }
                RecordedRequest stale = server.takeRequest(2, TimeUnit.SECONDS);
                RecordedRequest recovered = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(stale); assertNotNull(recovered);
                assertEquals(method, stale.getMethod()); assertEquals(method, recovered.getMethod());
                assertEquals("Must exercise an already-used connection", 1, stale.getSequenceNumber());
                assertEquals("Recovery must open a fresh connection", 0, recovered.getSequenceNumber());
                assertEquals(3, server.getRequestCount());
            } finally { transport.closeForTest(); }
        }
    }

    @Test public void voiceSpeechAndOtherMutationsAreNeverReplayedAfterLostResponse() throws Exception {
        String[][] cases = {{"POST", "/v1/voice?request_id=synthetic-request-123"}, {"POST", "/v1/speech"},
            {"POST", "/v1/chat"}, {"PUT", "/v1/settings"}, {"DELETE", "/v1/data"}, {"PATCH", "/v1/tasks/test"}};
        for (boolean freshConnections : new boolean[] {false, true}) for (String[] item : cases) {
            ApiClient.HttpTransport transport = new ApiClient.HttpTransport(freshConnections);
            try (MockWebServer server = new MockWebServer()) {
                server.start(); prime(transport, server);
                server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
                server.enqueue(new MockResponse().setBody("must not be reached"));
                assertThrows(item[0] + " " + item[1], IOException.class,
                    () -> transport.call(request(server, item[0], item[1])).execute());
                RecordedRequest only = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(only); assertEquals(item[0], only.getMethod());
                assertEquals(freshConnections ? 0 : 1, only.getSequenceNumber());
                assertEquals("synthetic-test-input", only.getBody().readUtf8());
                assertNull("Mutation was replayed", server.takeRequest(150, TimeUnit.MILLISECONDS));
                assertEquals(2, server.getRequestCount());
            } finally { transport.closeForTest(); }
        }
    }

    @Test public void transportErrorsAreReadableAndDoNotHideApiDetailsOrSuggestWriteReplay() throws Exception {
        ApiClient.HttpTransport transport = new ApiClient.HttpTransport();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Call write = transport.call(request(server, "POST", "/v1/voice"));
            IOException lost = new IOException("unexpected end of stream on http://127.0.0.1:8108/private");
            IOException failure = ApiClient.transportFailure(write, lost);
            assertSame(lost, failure.getCause());
            assertTrue(failure.getMessage().startsWith("连接已中断"));
            assertTrue(failure.getMessage().contains("服务器可能已收到请求"));
            assertFalse(failure.getMessage().contains("127.0.0.1"));
            assertFalse(failure.getMessage().contains("重试"));
            assertTrue(ApiClient.transportFailure(write, new SocketTimeoutException()).getMessage().startsWith("连接超时"));
            assertTrue(ApiClient.transportFailure(write, new ConnectException()).getMessage().startsWith("无法连接服务"));
            Call read = transport.call(request(server, "GET", "/v1/state"));
            assertTrue(ApiClient.transportFailure(read, lost).getMessage().endsWith("后重新加载"));
            ApiClient.ApiException apiFailure = new ApiClient.ApiException(409, "上一轮正在处理");
            assertSame(apiFailure, ApiClient.transportFailure(write, apiFailure));
            write.cancel();
            assertEquals("已取消本次请求", ApiClient.transportFailure(write, lost).getMessage());
        } finally { transport.closeForTest(); }
    }

    @Test public void cancellingSafeReadDoesNotStartARecoveryRequest() throws Exception {
        ApiClient.HttpTransport transport = new ApiClient.HttpTransport();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setBody("must not be reached"));
            Call call = transport.call(request(server, "GET", "/v1/state"));
            FutureTask<Boolean> task = new FutureTask<>(() -> {
                try (Response ignored = call.execute()) { return false; }
                catch (IOException expected) { return call.isCanceled(); }
            });
            new Thread(task, "safe-read-cancel-test").start();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); call.cancel();
            assertTrue(task.get(2, TimeUnit.SECONDS));
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS)); assertEquals(1, server.getRequestCount());
        } finally { transport.closeForTest(); }
    }

    @Test public void safeReadDoesNotFollowRedirectOrRetryHttpFailure() throws Exception {
        for (int status : new int[] {302, 401, 500, 503}) {
            ApiClient.HttpTransport transport = new ApiClient.HttpTransport();
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location", server.url("/elsewhere")));
                server.enqueue(new MockResponse().setBody("must not be reached"));
                try (Response response = transport.call(request(server, "GET", "/v1/state")).execute()) {
                    assertEquals(status, response.code());
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS)); assertEquals(1, server.getRequestCount());
            } finally { transport.closeForTest(); }
        }
    }

    @Test public void meetingAudioStreamsWithoutAWholeCallDeadline() throws Exception {
        ApiClient.HttpTransport transport = new ApiClient.HttpTransport(true);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // A 10-minute window is read at playback speed, far beyond the 120 s call timeout.
            Call media = transport.media(request(server, "GET", "/v1/work-meetings/m/audio?start_ms=0&duration_ms=600000"));
            assertEquals(0, media.timeout().timeoutNanos());
            assertTrue(transport.call(request(server, "GET", "/v1/state")).timeout().timeoutNanos() > 0);
            server.enqueue(new MockResponse().setBody("wav"));
            try (Response response = media.execute()) { assertEquals("wav", response.body().string()); }
            RecordedRequest received = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("GET", received.getMethod()); assertEquals("close", received.getHeader("Connection"));
        } finally { transport.closeForTest(); }
    }
}
