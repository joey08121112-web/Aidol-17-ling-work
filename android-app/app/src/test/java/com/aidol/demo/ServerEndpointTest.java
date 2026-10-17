package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerEndpointTest {
    @Test public void localHttpIsDebugOnly() {
        for (String url : new String[]{ServerEndpoint.USB_LOCAL}) {
            assertEquals(url, ServerEndpoint.validate(url + "/", true));
            assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.validate(url, false));
        }
        for (String url : new String[]{ServerEndpoint.EMULATOR_LOCAL, "http://example.com", "http://10.0.2.2:80", "http://127.0.0.1:8000",
                "http://localhost:8108", "http://127.0.0.2:8108", "http://[::1]:8108", "http://192.168.1.2:8108"})
            assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.validate(url, true));
    }
    @Test public void rejectCredentialsAndNonOriginUrls() {
        for (String url : new String[]{"https://user:secret@example.com", "https://example.com/api", "https://example.com?token=private", "https://example.com/#private"})
            assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.validate(url, true));
    }
    @Test public void normalizedOriginsKeepIndependentSessions() {
        String shared = ServerEndpoint.validate("https://123.207.219.174:7776/", false);
        assertEquals("https://123.207.219.174:7776", shared);
        assertNotEquals(ServerEndpoint.sessionNamespace(shared), ServerEndpoint.sessionNamespace(ServerEndpoint.LOCAL));
        assertNotEquals(ServerEndpoint.sessionNamespace(ServerEndpoint.USB_LOCAL), ServerEndpoint.sessionNamespace(ServerEndpoint.EMULATOR_LOCAL));
        assertEquals(ServerEndpoint.sessionNamespace(shared), ServerEndpoint.sessionNamespace(ServerEndpoint.validate(shared, true)));
    }
}
