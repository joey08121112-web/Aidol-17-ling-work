package com.aidol.demo.hardware;

import java.util.function.Consumer;

/** Keeps the SDK's idle-before-error cleanup from hiding a rejected start command. */
final class FusionCaptureEvents {
    static final class Failure {
        final int code;
        final Integer status;
        final boolean started, stopping;
        Failure(int code, String message, boolean started, boolean stopping) {
            this.code = code; this.started = started; this.stopping = stopping;
            String prefix = "融合录音命令失败:";
            String value = message != null && message.startsWith(prefix) ? message.substring(prefix.length()) : "";
            Integer parsed = null;
            if (value.matches("[0-9]{1,3}")) { int number = Integer.parseInt(value); if (number > 0 && number <= 255) parsed = number; }
            status = parsed;
        }
        boolean rejectedStart() { return !started && !stopping && status != null; }
        String diagnostics() { return "fusion_" + code + (status == null ? "" : "_status_" + status); }
        String summary() {
            String detail = "（SDK 代码 " + code + (status == null ? "" : " · 固件状态 " + status) + "）";
            if (rejectedStart() && status == 6) return "耳机当前状态不允许启动录音" + detail + "；请检查耳机本地录音是否仍在进行，结束后再试";
            return (rejectedStart() ? "耳机拒绝启动录音" : "耳机录音失败") + detail;
        }
    }
    private final FusionDevice.Listener listener;
    private final Consumer<Runnable> defer;
    private final Consumer<Failure> failed;
    private final Runnable released;
    private volatile boolean started, stopping;
    private volatile Failure failure;

    FusionCaptureEvents(FusionDevice.Listener listener, Consumer<Runnable> defer, Consumer<Failure> failed, Runnable released) {
        this.listener = listener; this.defer = defer; this.failed = failed; this.released = released;
    }
    Failure failure() { return failure; }
    void state(String state, int scene) {
        if ("recording".equals(state)) started = true;
        if ("stopping".equals(state)) stopping = true;
        if ("idle".equals(state)) {
            // SDK 6.7 clears itself with idle, then calls onError in the same callback stack.
            defer.accept(() -> {
                if (failure != null) return;
                listener.state(state, scene);
                defer.accept(released); // Let unexpected-idle recovery run before releasing its owner.
            });
        } else listener.state(state, scene);
    }
    void pcm(int channel, byte[] bytes) {
        if (channel == 2 && bytes != null && bytes.length > 0) started = true;
        listener.pcm(channel, bytes);
    }
    void error(int code, String message) {
        Failure value = new Failure(code, message, started, stopping); failure = value;
        failed.accept(value);
    }
}
