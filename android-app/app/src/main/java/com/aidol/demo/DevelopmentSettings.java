package com.aidol.demo;

import android.content.Context;
import android.content.SharedPreferences;

/** One SDK source; backend accounts remain isolated by their exact origin. */
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
        // Migrate retired microphone choices, preserving explicitly selected servers.
        prefs.edit().putString("audio_mode", audioMode().name())
            .putString("backend_target", backendTarget().name()).apply();
    }
    public AudioMode audioMode() {
        return AudioMode.RISE_SDK;
    }
    public BackendTarget backendTarget() {
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
        prefs.edit().putString("audio_mode", AudioMode.RISE_SDK.name())
            .putString("backend_target", backendTarget == BackendTarget.EMULATOR_LOCAL ? BackendTarget.USB_LOCAL.name() : backendTarget.name()).apply();
    }
    /** Migrate the retired boolean API to physical-phone inputs and USB; never copy credentials. */
    public void save(boolean emulatorAudio, boolean localBackend) {
        save(AudioMode.RISE_SDK, localBackend);
    }
}
