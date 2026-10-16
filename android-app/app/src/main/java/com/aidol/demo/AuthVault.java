package com.aidol.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Stores only an encrypted session credential, never the account password. */
public final class AuthVault {
    private final SharedPreferences prefs;
    private String token = "", username = "";
    private static final String ALIAS = "aidol.session.v1";
    public AuthVault(Context context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE);
        try {
            String saved = prefs.getString("encrypted", "");
            if (saved.isEmpty()) return;
            String[] parts = saved.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject data = new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            token = data.getString("token"); username = data.getString("username");
        } catch (Exception ignored) { clear(); }
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
    public synchronized String token() { return token; }
    public synchronized String username() { return username; }
    public synchronized void save(String token, String username) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(new JSONObject().put("token", token).put("username", username).toString().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("encrypted", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存登录状态，请检查手机存储空间");
        this.token = token; this.username = username;
    }
    public synchronized void clear() { token = ""; username = ""; prefs.edit().clear().apply(); }
}
