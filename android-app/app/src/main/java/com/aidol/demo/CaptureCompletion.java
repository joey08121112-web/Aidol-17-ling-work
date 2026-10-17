package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;

/** Normal stop acknowledges only after the AudioRecord worker has released its route. */
final class CaptureCompletion {
    private final List<CaptureSource.StopCallback> waiting = new ArrayList<>();
    private boolean ended;
    private String failure;
    void await(CaptureSource.StopCallback callback) {
        String error;
        synchronized (this) {
            if (!ended) { waiting.add(callback); return; }
            error = failure;
        }
        deliver(callback, error);
    }
    synchronized void failure(String message) { if (failure == null) failure = message; }
    void end() {
        List<CaptureSource.StopCallback> callbacks;
        String error;
        synchronized (this) {
            if (ended) return;
            ended = true; error = failure;
            callbacks = new ArrayList<>(waiting); waiting.clear();
        }
        for (CaptureSource.StopCallback callback : callbacks) deliver(callback, error);
    }
    private static void deliver(CaptureSource.StopCallback callback, String error) {
        if (error == null) callback.ended(); else callback.error(error);
    }
}
