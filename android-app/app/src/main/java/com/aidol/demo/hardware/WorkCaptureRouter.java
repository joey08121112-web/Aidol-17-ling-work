package com.aidol.demo.hardware;

/** Work-only routing. Media audio never enters notes; phone audio requires a confirmed SDK call scene. */
final class WorkCaptureRouter {
    static final int NORMAL = 0, RINGING = 1, ACTIVE = 2;
    interface Output {
        void pcm(byte[] bytes);
        void status(String message);
        void call(boolean active);
    }
    private enum Route { WAITING, AMBIENT, CALL }
    private final Output output;
    private final CallPcmMixer mixer;
    private int scene = -1, phone = NORMAL;
    private boolean dialog, paused, microphoneSeen, speakerSeen;
    private Route route = Route.WAITING;
    private String lastStatus = "";
    WorkCaptureRouter(Output output) { this.output = output; mixer = new CallPcmMixer(output::pcm); }

    void scene(int value, long now) { scene = value; update(now); }
    void phone(int value, long now) { phone = value; update(now); }
    void dialog(boolean value, long now) { dialog = value; update(now); }
    void pause(boolean value) { paused = value; mixer.reset(); }
    private void update(long now) {
        Route next = phone == ACTIVE && scene == 0 ? Route.CALL
            : phone == NORMAL && !dialog && (scene == 1 || scene == 2) ? Route.AMBIENT : Route.WAITING;
        if (next != route) {
            if (route == Route.CALL) { if (!paused) mixer.drain(now, true); output.call(false); }
            mixer.reset(); microphoneSeen = speakerSeen = false;
            route = next;
            if (next == Route.CALL) output.call(true);
        }
        announce();
    }
    private void announce() {
        String message = route == Route.CALL
            ? microphoneSeen && speakerSeen ? "正在记录电话对话 · 已收到双方音频" : "正在记录电话对话 · 等待双方音频"
            : route == Route.AMBIENT ? "正在记录环境声音 · 可同时听音乐"
            : phone == RINGING ? "来电中 · 环境收音已暂停，接听后记录通话"
            : phone == ACTIVE || dialog || scene == 0 ? "环境收音已暂停 · 等待耳机通话音频，请通过耳机接听"
            : "正在等待耳机音频";
        if (!message.equals(lastStatus)) { lastStatus = message; output.status(message); }
    }
    void pcm(int channel, byte[] bytes, long now) {
        if (paused || bytes == null || bytes.length == 0) return;
        if (route == Route.AMBIENT && channel == 2) output.pcm(bytes);
        else if (route == Route.CALL && (channel == 1 || channel == 2)) {
            if (channel == 1) speakerSeen = true; else microphoneSeen = true;
            announce(); mixer.accept(channel, bytes, now);
        }
    }
    void tick(long now) { if (!paused && route == Route.CALL) mixer.drain(now, false); }
    void finish(long now) {
        if (route == Route.CALL) { if (!paused) mixer.drain(now, true); output.call(false); }
        mixer.reset(); route = Route.WAITING;
    }
}
