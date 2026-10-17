package com.aidol.demo;

/** Backend choice is independent of the selected microphone source. */
public enum BackendTarget {
    USB_LOCAL("USB 本地后端"),
    EMULATOR_LOCAL("已停用的模拟器后端"), // Retained only to migrate previously saved enum names.
    DEPLOYED("远端服务器");

    private final String label;
    BackendTarget(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isLocal() { return this != DEPLOYED; }
    public String serverUrl(String deployedUrl) {
        switch (this) {
            case USB_LOCAL: return ServerEndpoint.USB_LOCAL;
            case EMULATOR_LOCAL: throw new IllegalStateException("模拟器后端已停用，请使用真机 USB 本地服务");
            case DEPLOYED: return deployedUrl;
            default: throw new IllegalStateException("Unknown backend target");
        }
    }

    static BackendTarget resolve(boolean debug, boolean emulator, String saved, Boolean legacyLocal) {
        if (saved != null) {
            try {
                BackendTarget target = valueOf(saved);
                return target == EMULATOR_LOCAL ? USB_LOCAL : target;
            } catch (IllegalArgumentException ignored) { /* Use the old preference. */ }
        }
        // Local is now USB-only. AuthVault is selected by the resulting exact origin;
        // this migrates a preference, never the old 10.0.2.2 credential namespace.
        if (legacyLocal != null) return legacyLocal ? USB_LOCAL : DEPLOYED;
        return USB_LOCAL;
    }
}
