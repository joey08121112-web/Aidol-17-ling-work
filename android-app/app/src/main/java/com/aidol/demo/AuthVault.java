package com.aidol.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Stores only an encrypted session credential, never the account password. */
public final class AuthVault {
    /** First reads wait at most this long for the background load before treating the vault as empty. */
    static final long LOAD_WAIT_MILLIS = 2500;
    private final SharedPreferences prefs;
    private final VaultState state = new VaultState();
    private final ExecutorService background;
    private static final String ALIAS = "aidol.session.v1";

    /**
     * In-memory session decoupled from slow storage: crypto and disk writes run elsewhere, and a
     * load that completes after a newer save/clear is discarded instead of overwriting it.
     */
    static final class VaultState {
        private String token = "", username = "";
        private long generation;
        private final CountDownLatch settled = new CountDownLatch(1);
        private final long loadWaitMillis;

        VaultState() { this(LOAD_WAIT_MILLIS); }
        VaultState(long loadWaitMillis) { this.loadWaitMillis = loadWaitMillis; }

        synchronized long loadStarts() { return generation; }
        /** Applies the loaded credential only when no save/clear happened since the load began. */
        synchronized void loadCompletes(long observed, String token, String username) {
            if (generation == observed) { this.token = token; this.username = username; }
            settled.countDown();
        }
        synchronized void save(String token, String username) {
            generation++; this.token = token; this.username = username;
            settled.countDown();
        }
        synchronized void clear() {
            generation++; token = ""; username = "";
            settled.countDown();
        }
        synchronized long generation() { return generation; }
        String token() { awaitLoad(); return currentToken(); }
        String username() { awaitLoad(); return currentUsername(); }
        private void awaitLoad() {
            try { settled.await(loadWaitMillis, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        private synchronized String currentToken() { return token; }
        private synchronized String currentUsername() { return username; }
    }

    /** Local presence only; never decrypts credentials or claims the server session is valid. */
    public static boolean hasSavedSession(Context context, String namespace) {
        String saved = context.getSharedPreferences(namespace, Context.MODE_PRIVATE).getString("encrypted", "");
        return saved != null && !saved.isEmpty();
    }
    public AuthVault(Context context) {
        this(context, "session");
    }
    public AuthVault(Context context, String namespace) {
        prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
        background = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, "aidol-auth-vault");
            worker.setDaemon(true);
            return worker;
        });
        final long loading = state.loadStarts();
        background.execute(() -> {
            try {
                String saved = prefs.getString("encrypted", "");
                if (saved.isEmpty()) return;
                String[] parts = saved.split(":", 2);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                JSONObject data = new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
                state.loadCompletes(loading, data.getString("token"), data.getString("username"));
            } catch (Exception ignored) {
                // A locked/unavailable Keystore can recover later. Reading the vault must not
                // destroy its encrypted credential; explicit logout still clears it below.
                state.loadCompletes(loading, "", "");
            }
        });
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    public String token() { return state.token(); }
    public String username() { return state.username(); }
    public synchronized void save(String token, String username) throws Exception {
        state.save(token, username);
        final long written = state.generation();
        // The in-memory session is already current; Keystore encryption and the disk write
        // must never block the caller. A newer save/clear supersedes this write in order.
        background.execute(() -> {
            if (state.generation() != written) return;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
                byte[] encrypted = cipher.doFinal(new JSONObject().put("token", token).put("username", username).toString().getBytes(StandardCharsets.UTF_8));
                prefs.edit().putString("encrypted", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
            } catch (Exception ignored) {
                // This run keeps the session in memory; explicit logout still clears storage.
            }
        });
    }
    public synchronized void clear() { state.clear(); prefs.edit().clear().apply(); }
}
