package com.aidol.demo.hardware;

/** Vendor-free boundary. Implementations must preserve callback order within a recording. */
public interface FusionDevice {
    interface Listener {
        /** Execute the real start command synchronously, atomically with this source's cancellation. */
        boolean startIfCurrent(Runnable startCommand);
        void state(String state, int scene);
        void pcm(int channel, byte[] bytes);
        void error(String message);
    }
    void startFusion(Listener listener);
    void stopFusion(Listener owner);
    void cancelFusion(Listener owner);
    void requireReconnect(Listener owner);
}
