package com.aidol.demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Gesture decisions within a visible or explicitly enabled foreground-service session. */
final class HeadsetInteractionPolicy {
    enum Gesture { TOGGLE, NEXT_MODE, CANCEL, DISCARD_COMBINATION, IGNORE }
    enum Action { START, STOP_AND_SEND, CANCEL, CHANGE_MODE, DEFER_MODE }
    static final long GESTURE_SETTLE_MS = 400;
    private static final long DEBOUNCE_MS = 800, MAX_EVENT_AGE_MS = 1500;

    static final class Context {
        final boolean foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn;
        final String mode;
        final long sessionGeneration;
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode) {
            this(foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn, mode, 0);
        }
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode, long sessionGeneration) {
            this.foreground = foreground; this.controlsEnabled = controlsEnabled; this.loggedIn = loggedIn;
            this.ready = ready; this.inCall = inCall; this.busy = busy; this.active = active;
            this.recording = recording; this.notWorn = notWorn; this.mode = mode; this.sessionGeneration = sessionGeneration;
        }
        boolean enabled() { return foreground && controlsEnabled && loggedIn && ready && !inCall; }
    }
    static final class Decision {
        final List<Action> actions;
        final String mode, message;
        Decision(String mode, String message, Action... actions) {
            this.actions = Collections.unmodifiableList(Arrays.asList(actions)); this.mode = mode; this.message = message;
        }
        static Decision none() { return new Decision(null, ""); }
    }
    static final class Device {
        final boolean ready, inCall;
        final Boolean leftWorn, rightWorn;
        final Integer leftBattery, rightBattery;
        Device(boolean ready, boolean inCall, Boolean leftWorn, Boolean rightWorn, Integer leftBattery, Integer rightBattery) {
            this.ready = ready; this.inCall = inCall; this.leftWorn = leftWorn; this.rightWorn = rightWorn;
            this.leftBattery = leftBattery; this.rightBattery = rightBattery;
        }
    }
    static final class DeviceUpdate {
        final String stopReason;
        final List<String> notices;
        DeviceUpdate(String stopReason, List<String> notices) { this.stopReason = stopReason; this.notices = notices; }
    }
    private long lastSequence = -1, lastAcceptedAt = -1, pendingActionAt = -1;
    private long pendingSessionGeneration = -1;
    private Action pendingAction;
    private String pendingMode;
    private Gesture lastGesture;
    private Device previous;
    private int leftBatteryBand, rightBatteryBand;

    Decision event(Gesture gesture, long sequence, long receivedAt, long now, Context context) {
        if (sequence <= lastSequence) return Decision.none();
        lastSequence = sequence;
        if (gesture == Gesture.IGNORE || receivedAt > now || now - receivedAt > MAX_EVENT_AGE_MS) return Decision.none();
        if (!context.enabled()) { clearPending(); return Decision.none(); }
        if (gesture == Gesture.DISCARD_COMBINATION) { clearPending(); return Decision.none(); }
        if (lastAcceptedAt >= 0 && now - lastAcceptedAt < DEBOUNCE_MS && gesture == lastGesture) return Decision.none();
        lastGesture = gesture; lastAcceptedAt = now;
        if (gesture == Gesture.CANCEL) {
            clearPending();
            return context.active ? new Decision(null, "耳机触控已取消本轮；已提交内容请查看历史", Action.CANCEL) : Decision.none();
        }
        if (gesture == Gesture.NEXT_MODE) {
            clearPending();
            if (context.busy) return new Decision(null, "正在同步，请稍后再切换模式");
            if (context.active && !context.recording)
                return new Decision(null, "本轮正在处理，请结束后再切换模式");
            String next = "life".equals(context.mode) ? "work" : "work".equals(context.mode) ? "commute" : "life";
            // A subsequent triple event has no product action and can discard this combination.
            pendingActionAt = now + GESTURE_SETTLE_MS;
            pendingAction = Action.CHANGE_MODE; pendingMode = next;
            pendingSessionGeneration = context.sessionGeneration;
            return Decision.none();
        }
        if (context.busy) { clearPending(); return new Decision(null, "正在同步，请稍后再开始"); }
        if (context.active && !context.recording) {
            clearPending(); return new Decision(null, "本轮正在处理；长按可取消");
        }
        // Wait for a possible double before opening or ending the microphone.
        if (pendingActionAt < 0) {
            pendingActionAt = now + GESTURE_SETTLE_MS;
            pendingAction = context.active ? Action.STOP_AND_SEND : Action.START;
            pendingSessionGeneration = context.sessionGeneration;
        }
        return Decision.none();
    }
    Decision flush(long now, Context context) {
        if (pendingActionAt < 0 || now < pendingActionAt) return Decision.none();
        Action intended = pendingAction;
        String intendedMode = pendingMode;
        long generation = pendingSessionGeneration;
        clearPending();
        if (!context.enabled() || context.busy || generation != context.sessionGeneration) return Decision.none();
        if (intended == Action.CHANGE_MODE) {
            if (context.active) return new Decision(intendedMode, "本轮结束后切换模式；当前录音会保留", Action.DEFER_MODE);
            return new Decision(intendedMode, "", Action.CHANGE_MODE);
        }
        if (intended == Action.STOP_AND_SEND) return context.active && context.recording
            ? new Decision(null, "正在结束录音…", Action.STOP_AND_SEND) : Decision.none();
        if (intended != Action.START || context.active) return Decision.none();
        if (context.notWorn) return new Decision(null, "耳机当前未佩戴，请佩戴后再开始");
        return new Decision(null, "耳机触控开始单轮对话", Action.START);
    }
    boolean hasPending() { return pendingActionAt >= 0; }
    long pendingDelay(long now) { return Math.max(0, pendingActionAt - now); }
    void clearPending() { pendingActionAt = -1; pendingAction = null; pendingMode = null; pendingSessionGeneration = -1; }

    DeviceUpdate device(Device device, boolean active) {
        List<String> notices = new ArrayList<>();
        String stop = null;
        boolean bothOut = Boolean.FALSE.equals(device.leftWorn) && Boolean.FALSE.equals(device.rightWorn);
        boolean previouslyBothOut = previous != null && Boolean.FALSE.equals(previous.leftWorn) && Boolean.FALSE.equals(previous.rightWorn);
        if (previous != null && previous.ready && !device.ready) {
            clearPending();
            if (active) stop = "耳机连接已中断，本轮已停止；重新连接后请手动开始";
        } else if (device.inCall && (previous == null || !previous.inCall)) {
            clearPending();
            if (active) stop = "检测到来电或通话，本轮已暂停；通话结束后请手动开始";
        } else if (device.ready && bothOut && active) {
            clearPending();
            if (active) stop = "检测到耳机摘下，本轮已暂停；重新佩戴后请手动开始";
            else notices.add("耳机已摘下；重新佩戴后请手动开始");
        } else if (device.ready && bothOut && !previouslyBothOut) {
            notices.add("耳机当前未佩戴；请佩戴后手动开始");
        } else if (device.ready && previous != null && (Boolean.FALSE.equals(previous.leftWorn) && Boolean.TRUE.equals(device.leftWorn)
                || Boolean.FALSE.equals(previous.rightWorn) && Boolean.TRUE.equals(device.rightWorn))) {
            notices.add("耳机已佩戴，等待你手动开始");
        }
        if (device.ready) {
            leftBatteryBand = batteryNotice(device.leftBattery, leftBatteryBand, "左耳", notices);
            rightBatteryBand = batteryNotice(device.rightBattery, rightBatteryBand, "右耳", notices);
        }
        previous = device;
        return new DeviceUpdate(stop, notices);
    }
    private static int batteryNotice(Integer value, int previousBand, String side, List<String> notices) {
        if (value == null || value < 0 || value > 100) return previousBand;
        int band = value <= 10 ? 2 : value <= 20 ? 1 : 0;
        if (band > previousBand) notices.add(side + "电量较低（" + value + "%），请及时充电");
        return value > 25 ? 0 : Math.max(previousBand, band);
    }
}
