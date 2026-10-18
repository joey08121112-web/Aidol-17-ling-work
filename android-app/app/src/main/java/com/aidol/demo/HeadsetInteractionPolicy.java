package com.aidol.demo;

import com.aidol.demo.hardware.HeadsetController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Gesture decisions within a visible or explicitly enabled foreground-service session. */
final class HeadsetInteractionPolicy {
    enum Gesture { TOGGLE, NEXT_MODE, CANCEL, STEM_HOLD, VOLUME_UP, VOLUME_DOWN, DISCARD_COMBINATION, IGNORE }
    enum Action { START, STOP_AND_SEND, INTERRUPT, CANCEL, CHANGE_MODE, DEFER_MODE, MARK, VOLUME_UP, VOLUME_DOWN }
    static final long GESTURE_SETTLE_MS = 400;
    private static final long DEBOUNCE_MS = 800, MAX_EVENT_AGE_MS = 1500;

    /** SDK forward/backward direction must be confirmed on the physical stem. */
    static Gesture gesture(HeadsetController.ControlKind kind) {
        switch (kind) {
            case TAP: case PRESS_SINGLE: return Gesture.TOGGLE;
            case DOUBLE_TAP: case PRESS_DOUBLE: return Gesture.NEXT_MODE;
            case TRIPLE_TAP: case PRESS_TRIPLE: return Gesture.DISCARD_COMBINATION;
            case LONG_PRESS: return Gesture.CANCEL; // Logo touch, not a stem pinch.
            case PRESS_LONG: case PRESS_CLICK_HOLD: return Gesture.STEM_HOLD;
            case SLIDE_FORWARD: return Gesture.VOLUME_UP;
            case SLIDE_BACKWARD: return Gesture.VOLUME_DOWN;
            default: return Gesture.IGNORE;
        }
    }

    static final class Context {
        final boolean foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn;
        final String mode;
        final long sessionGeneration;
        final boolean modeCue, replying;
        /** A work meeting is capturing (not saving): double tap marks a key point instead of switching mode. */
        final boolean workRecording;
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode) {
            this(foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn, mode, 0);
        }
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode, long sessionGeneration) {
            this(foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn, mode, sessionGeneration, false);
        }
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode, long sessionGeneration, boolean modeCue) {
            this(foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn, mode, sessionGeneration, modeCue, false);
        }
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode, long sessionGeneration, boolean modeCue, boolean replying) {
            this(foreground, controlsEnabled, loggedIn, ready, inCall, busy, active, recording, notWorn, mode, sessionGeneration, modeCue, replying, false);
        }
        Context(boolean foreground, boolean controlsEnabled, boolean loggedIn, boolean ready, boolean inCall,
                boolean busy, boolean active, boolean recording, boolean notWorn, String mode, long sessionGeneration, boolean modeCue, boolean replying,
                boolean workRecording) {
            this.workRecording = workRecording;
            this.foreground = foreground; this.controlsEnabled = controlsEnabled; this.loggedIn = loggedIn;
            this.ready = ready; this.inCall = inCall; this.busy = busy; this.active = active;
            this.recording = recording; this.notWorn = notWorn; this.mode = mode; this.sessionGeneration = sessionGeneration;
            this.modeCue = modeCue; this.replying = replying;
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
    private long lastVolumeAt = -1;
    private long pendingSessionGeneration = -1;
    private Action pendingAction;
    private String pendingMode;
    private Gesture lastGesture;
    private Device previous;
    private boolean wearAnnounced;
    private int leftBatteryBand, rightBatteryBand;

    Decision event(Gesture gesture, long sequence, long receivedAt, long now, Context context) {
        if (sequence <= lastSequence) return Decision.none();
        lastSequence = sequence;
        if (gesture == Gesture.IGNORE || receivedAt > now || now - receivedAt > MAX_EVENT_AGE_MS) return Decision.none();
        if (!context.enabled()) { clearPending(); return Decision.none(); }
        // Never end a meeting or claim an ANC change on a stem hold. SDK 6.7
        // has no verified ANC command; firmware remains responsible for it.
        if (gesture == Gesture.STEM_HOLD) { clearPending(); return Decision.none(); }
        if (gesture == Gesture.VOLUME_UP || gesture == Gesture.VOLUME_DOWN) {
            if (context.notWorn) return Decision.none();
            if (lastVolumeAt >= 0 && now - lastVolumeAt < 150) return Decision.none();
            lastVolumeAt = now;
            // Volume is independent of recording, network work and pending mode changes.
            return new Decision(null, "", gesture == Gesture.VOLUME_UP ? Action.VOLUME_UP : Action.VOLUME_DOWN);
        }
        if (gesture == Gesture.DISCARD_COMBINATION) { clearPending(); return Decision.none(); }
        if (lastAcceptedAt >= 0 && now - lastAcceptedAt < DEBOUNCE_MS && gesture == lastGesture) return Decision.none();
        lastGesture = gesture; lastAcceptedAt = now;
        if (gesture == Gesture.CANCEL) {
            clearPending();
            return context.active ? new Decision(null, "正在结束会话；工作记录会提交", Action.CANCEL) : Decision.none();
        }
        if (context.notWorn) { clearPending(); return new Decision(null, "双耳已摘下，请佩戴后操作"); }
        if (gesture == Gesture.NEXT_MODE && context.workRecording) {
            // Overrides a settling single tap; a following triple still discards it.
            clearPending();
            pendingActionAt = now + GESTURE_SETTLE_MS;
            pendingAction = Action.MARK;
            pendingSessionGeneration = context.sessionGeneration;
            return Decision.none();
        }
        if (gesture == Gesture.NEXT_MODE) {
            clearPending();
            if (context.busy) return new Decision(null, "正在同步，请稍后再切换模式");
            String next = "life".equals(context.mode) ? "work" : "work".equals(context.mode) ? "commute" : "life";
            // A subsequent triple event has no product action and can discard this combination.
            pendingActionAt = now + GESTURE_SETTLE_MS;
            pendingAction = Action.CHANGE_MODE; pendingMode = next;
            pendingSessionGeneration = context.sessionGeneration;
            return Decision.none();
        }
        if (context.busy) { clearPending(); return new Decision(null, "正在同步，请稍后再开始"); }
        // Wait for a possible double before opening or ending the microphone.
        if (pendingActionAt < 0) {
            pendingActionAt = now + GESTURE_SETTLE_MS;
            pendingAction = context.modeCue ? Action.START : context.active ? Action.STOP_AND_SEND : Action.START;
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
        if (context.notWorn) return new Decision(null, "双耳已摘下，请佩戴后操作");
        if (intended == Action.MARK) return context.workRecording
            ? new Decision(null, "", Action.MARK) : Decision.none();  // Never a mode switch after saving began.
        if (intended == Action.CHANGE_MODE) {
            if (context.active && !context.modeCue) return new Decision(intendedMode, "结束当前会话后切换模式；工作记录会提交", Action.DEFER_MODE);
            return new Decision(intendedMode, "", Action.CHANGE_MODE);
        }
        if (intended == Action.STOP_AND_SEND) return context.active
            ? new Decision(null, "", Action.STOP_AND_SEND) : Decision.none();
        if (intended != Action.START || context.active && !context.modeCue) return Decision.none();
        return new Decision(null, "正在接通 AI…", Action.START);
    }
    boolean hasPending() { return pendingActionAt >= 0; }
    // Natural cue completion must not swallow a double tap still settling.
    void modeCueFinished(long generation) {
        turnFinished(generation);
    }
    // Only an already-chosen mode may cross a natural turn boundary. A tap must
    // never become a new start/stop on the following recording.
    void turnFinished(long generation) {
        if ((pendingAction == Action.CHANGE_MODE || pendingAction == Action.STOP_AND_SEND) && pendingSessionGeneration == generation)
            pendingSessionGeneration = generation + 1;
    }
    long pendingDelay(long now) { return Math.max(0, pendingActionAt - now); }
    void clearPending() { pendingActionAt = -1; pendingAction = null; pendingMode = null; pendingSessionGeneration = -1; }

    DeviceUpdate device(Device device, boolean active) {
        return device(device, active, false);
    }
    DeviceUpdate device(Device device, boolean active, boolean workCalls) {
        List<String> notices = new ArrayList<>();
        String stop = null;
        WearState wear = WearState.from(device.leftWorn, device.rightWorn);
        boolean bothOut = wear == WearState.REMOVED;
        boolean previouslyBothOut = previous != null && Boolean.FALSE.equals(previous.leftWorn) && Boolean.FALSE.equals(previous.rightWorn);
        if (!device.ready || bothOut) wearAnnounced = false;
        if (!device.ready) {
            clearPending();
            if (active) stop = "耳机连接已中断，通话已结束；重连后单击重新接通";
        } else if (device.inCall && !workCalls) {
            clearPending();
            if (active) stop = "检测到手机通话，AI 通话已结束；来电结束后单击重新接通";
        } else if (device.ready && bothOut && active) {
            clearPending();
            stop = "双耳已摘下，通话已结束；戴回后单击重新接通";
        } else if (device.ready && bothOut && !previouslyBothOut) {
            clearPending(); notices.add("双耳已摘下；戴回后单击接通");
        } else if (device.ready && wear.worn() && !wearAnnounced) {
            wearAnnounced = true;
            if (!active) notices.add("耳机已佩戴，已就绪；单击接通常态对话");
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
