package com.aidol.demo;

/** Audio always enters the backend as 16 kHz, mono, PCM16 little-endian. */
public interface CaptureSource {
    interface Listener {
        void status(String message);
        void pcm(byte[] bytes);
        void error(String message);
    }
    void start(Listener listener);
    /** Stops capture synchronously before the WebSocket stop marker is sent. */
    void stop();
}
