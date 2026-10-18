package com.aidol.demo;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultStateTest {
    @Test public void emptyStoredSessionSettlesRepeatedReadsAfterInitialization() throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                if ("getString".equals(method.getName()) && "encrypted".equals(args[0])) return "";
                throw new AssertionError("Empty session must not decrypt or write storage: " + method.getName());
            });
        ExecutorService loader = Executors.newSingleThreadExecutor();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            AuthVault vault = new AuthVault(preferences, loader);
            // The barrier runs after the constructor's real background initialization.
            loader.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals("", reader.submit(() -> {
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < 3; i++) values.append(vault.token()).append(vault.username());
                return values.toString();
            }).get(1, TimeUnit.SECONDS));
        } finally { reader.shutdownNow(); loader.shutdownNow(); }
    }

    @Test public void readsBlockUntilTheInitialLoadCompletes() throws Exception {
        AuthVault.VaultState state = new AuthVault.VaultState();
        long loading = state.loadStarts();
        CountDownLatch reading = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> { reading.countDown(); return state.token(); });
        new Thread(task, "vault-read-test").start();
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertThrows("A read must wait for the background load", TimeoutException.class,
                () -> task.get(80, TimeUnit.MILLISECONDS));
            state.loadCompletes(loading, "session-token", "listener");
            assertEquals("session-token", task.get(2, TimeUnit.SECONDS));
            assertEquals("listener", state.username());
        } finally { task.cancel(true); }
    }

    @Test public void loadCompletingAfterASaveDiscardsItsStaleValues() {
        AuthVault.VaultState state = new AuthVault.VaultState();
        long loading = state.loadStarts();
        state.save("new-token", "new-user");
        state.loadCompletes(loading, "old-token", "old-user");
        assertEquals("new-token", state.token());
        assertEquals("new-user", state.username());
    }

    @Test public void loadCompletingAfterAClearStaysEmpty() {
        AuthVault.VaultState state = new AuthVault.VaultState();
        long loading = state.loadStarts();
        state.clear();
        state.loadCompletes(loading, "old-token", "old-user");
        assertEquals("", state.token());
        assertEquals("", state.username());
    }

    @Test public void saveSettlesReadsWithoutAnyLoad() {
        AuthVault.VaultState state = new AuthVault.VaultState();
        state.save("direct-token", "direct-user");
        assertEquals("direct-token", state.token());
        assertEquals("direct-user", state.username());
    }

    @Test public void readsGiveUpAfterTheBoundedLoadWait() throws Exception {
        AuthVault.VaultState state = new AuthVault.VaultState(60);
        long started = System.nanoTime();
        assertEquals("", state.token());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("A read must not block past the bound, took " + elapsedMillis + "ms", elapsedMillis >= 60);
        assertTrue("A read must not block far past the bound, took " + elapsedMillis + "ms", elapsedMillis < 5000);
    }
}
