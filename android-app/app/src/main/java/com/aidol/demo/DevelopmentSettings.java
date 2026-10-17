package com.aidol.demo;

import android.content.Context;
import android.content.SharedPreferences;

/** Debug preferences affect hardware transport and endpoint, never business rules. */
public final class DevelopmentSettings {
    private final SharedPreferences prefs;
    private final boolean debug;
    public DevelopmentSettings(Context context) {
        this(context.getSharedPreferences("development", Context.MODE_PRIVATE), false, BuildConfig.DEBUG);
    }
    /** The old emulator flag is ignored: current product setup only supports physical phones. */
    DevelopmentSettings(SharedPreferences prefs, boolean legacyEmulator, boolean debug) {
        this.prefs = prefs;
        this.debug = debug;
        if (debug) {
            // Persist the resolved choices once. Existing booleans remain readable
            // for upgrades, but all new writes use explicit independent enum values.
            prefs.edit().putString("audio_mode", audioMode().name())
                .putString("backend_target", backendTarget().name()).apply();
        }
    }
    public AudioMode audioMode() {
        if (!debug) return AudioMode.BLUETOOTH;
        String saved = prefs.getString("audio_mode", null);
        if (saved != null) {
            try { return physicalAudio(AudioMode.valueOf(saved)); } catch (IllegalArgumentException ignored) { /* Migrate legacy choice. */ }
        }
        return AudioMode.BLUETOOTH;
    }
    public BackendTarget backendTarget() {
        if (!debug) return BackendTarget.DEPLOYED;
        return BackendTarget.resolve(true, false, prefs.getString("backend_target", null),
            prefs.contains("local_backend") ? prefs.getBoolean("local_backend", false) : null);
    }
    public boolean localBackend() { return backendTarget().isLocal(); }
    /** Local computer always means the physical phone's USB reverse connection. */
    public BackendTarget localBackendTarget() { return BackendTarget.USB_LOCAL; }
    public String serverUrl() {
        return ServerEndpoint.validate(backendTarget().serverUrl(BuildConfig.SERVER_URL), debug);
    }
    /** The two-choice UI selects the local computer over USB or the deployed HTTPS server. */
    public void save(AudioMode audioMode, boolean localBackend) {
        save(audioMode, localBackend ? localBackendTarget() : BackendTarget.DEPLOYED);
    }
    public void save(AudioMode audioMode, BackendTarget backendTarget) {
        if (debug) prefs.edit().putString("audio_mode", physicalAudio(audioMode).name())
            .putString("backend_target", backendTarget == BackendTarget.EMULATOR_LOCAL ? BackendTarget.USB_LOCAL.name() : backendTarget.name()).apply();
    }
    private static AudioMode physicalAudio(AudioMode mode) { return mode == AudioMode.EMULATOR ? AudioMode.BLUETOOTH : mode; }
    /** Migrate the retired boolean API to physical-phone inputs and USB; never copy credentials. */
    public void save(boolean emulatorAudio, boolean localBackend) {
        save(AudioMode.BLUETOOTH, localBackend);
    }
}
