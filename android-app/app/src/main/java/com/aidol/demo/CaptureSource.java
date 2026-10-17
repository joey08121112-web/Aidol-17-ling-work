package com.aidol.demo;

/** Audio always enters the backend as 16 kHz, mono, PCM16 little-endian. */
public interface CaptureSource {
    interface Listener {
        void status(String message);
        default void input(String description) { status("实际麦克风：" + description); }
        /** Called once the source has started and its mode-specific route is ready. */
        default void recording() {}
        void pcm(byte[] bytes);
        void error(String message);
    }
    void start(Listener listener);
    interface StopCallback {
        /** Called after capture has ended and every valid queued PCM frame was delivered. */
        void ended();
        /** An unconfirmed/failed stop must never be uploaded as a completed recording. */
        void error(String message);
    }
    /** May deliver valid tail PCM before ended(); repeated stops must not repeat capture. */
    void stop(StopCallback callback);
    /** Immediately discards this turn. Cleanup may complete asynchronously. */
    void cancel();
    default void close() { cancel(); }
}
