package com.aidol.demo.hardware;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.aidol.demo.SystemCaptureLease;
import com.vision.headset.sdk.HeadsetManager;
import com.vision.headset.sdk.OnAuraRecordListener;
import com.vision.headset.sdk.OnAuraTestEventListener;
import com.vision.headset.sdk.OnConnectChangeListener;
import com.vision.headset.sdk.OnDialogListener;
import com.vision.headset.sdk.OnResultListener;
import com.vision.headset.sdk.entity.PowerEntity;
import com.vision.headset.sdk.entity.AuraTestEventEntity;
import com.vision.headset.sdk.entity.RecordDataEntity;
import com.vision.headset.sdk.entity.SNEntity;
import com.vision.headset.sdk.enums.GaiaConnectStatus;
import com.vision.headset.sdk.util.AuraDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** The application's sole owner of the static vendor SDK. No SDK initialization in the constructor. */
public final class HeadsetController implements FusionDevice, AutoCloseable {
    public enum State { DISCONNECTED, INITIALIZING, CONNECTING, CONNECTED, READY, DISCONNECTING, ERROR }
    public interface Listener { void changed(Snapshot snapshot); }
    public enum ControlsState { DISABLED, WAITING_FOR_WEAR, ENABLING, ENABLED, DISABLING, ERROR }
    public enum ControlKind {
        TAP, DOUBLE_TAP, TRIPLE_TAP, SHORT_PRESS, LONG_PRESS, RELEASE, SLIDE_FORWARD, SLIDE_BACKWARD,
        PRESS_SINGLE, PRESS_DOUBLE, PRESS_TRIPLE, PRESS_LONG, PRESS_CLICK_HOLD, PRESS_QUADRUPLE, PRESS_QUINTUPLE
    }
    public interface ControlListener { void onControl(ControlEvent event); }
    public static final class ControlEvent {
        public final ControlKind kind;
        public final int code;
        /** Phone monotonic receive time, not a hardware timestamp. No ear-side claim is available. */
        public final long receivedAtMillis, sequence;
        ControlEvent(ControlKind kind, int code, long receivedAtMillis, long sequence) {
            this.kind = kind; this.code = code; this.receivedAtMillis = receivedAtMillis; this.sequence = sequence;
        }
    }
    public static final class DeviceChoice {
        /** Internal target identity; do not log or display this full value. */
        public final String address;
        public final String label;
        DeviceChoice(String address, String name) { this.address = address; label = name + " · " + masked(address); }
    }
    public static final class Snapshot {
        public final State state;
        public final String model, firmware, diagnostics, message;
        public final Integer leftBattery, rightBattery, caseBattery;
        public final Boolean leftWorn, rightWorn;
        public final boolean inCall;
        public final ControlsState controlsState;
        public final String controlsSummary, lastControlEvent;
        public final long controlEventCount;
        Snapshot(State state, String message, String model, Integer leftBattery, Integer rightBattery,
                Integer caseBattery, Boolean leftWorn, Boolean rightWorn, String diagnostics, boolean inCall,
                ControlsState controlsState, String controlsSummary, String lastControlEvent, long controlEventCount) {
            this.state = state; this.message = message; this.model = model; firmware = "未知";
            this.leftBattery = leftBattery; this.rightBattery = rightBattery; this.caseBattery = caseBattery;
            this.leftWorn = leftWorn; this.rightWorn = rightWorn; this.diagnostics = diagnostics;
            this.inCall = inCall; this.controlsState = controlsState; this.controlsSummary = controlsSummary;
            this.lastControlEvent = lastControlEvent; this.controlEventCount = controlEventCount;
        }
        public boolean ready() { return state == State.READY; }
        public String summary() { return message; }
    }

    private static HeadsetController sdkOwner;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ControlListener> controlListeners = new CopyOnWriteArrayList<>();
    private final ControlEventGate controlGate = new ControlEventGate();
    private final ControlWearGate controlWearGate = new ControlWearGate();
    private OnAuraTestEventListener vendorControlListener;
    private boolean controlsWanted, controlsFaulted;
    private ControlFailure controlsFailure;
    private long controlsOperation, controlEventCount;
    private ControlsState controlsState = ControlsState.DISABLED;
    private String controlsSummary = "触控联动未开启", lastControlEvent = "尚未收到实际触控";
    private Runnable controlsTimeout;
    private Runnable controlsWearTimeout;
    private boolean controlsWearTimedOut;
    private boolean reconnectPending, explicitReconnect;
    private int reconnectAttempts;
    private Runnable reconnectTask;
    private volatile Snapshot snapshot;
    private volatile String connectedMac;
    private boolean initialized, initializing, closed, inCall, releasing;
    /** Written on main, read by the background release thread only. */
    private volatile long releaseOperation;
    private long connectionGeneration, connectionStarted, readyElapsed;
    private String targetMac, model = "未知", lastCode = "none", phase = "idle";
    private Integer leftBattery, rightBattery, caseBattery;
    private Boolean leftWorn, rightWorn;
    private OnResultListener<PowerEntity> powerListener;
    private volatile FusionDevice.Listener captureOwner;
    private volatile FusionCaptureEvents captureEvents;
    private boolean localRecordingStopPending;
    private final Object captureLease = new Object();
    private long captureGeneration, captureStarted, firstPcmElapsed, microphoneBytes;
    private Runnable connectionTimeout, cancelTimeout;

    public HeadsetController(Context context) {
        app = context.getApplicationContext();
        snapshot = new Snapshot(State.DISCONNECTED, "耳机未连接", model, null, null, null, null, null,
                "SDK=6.7 not_initialized", false, controlsState, controlsSummary, lastControlEvent, 0);
    }
    public Snapshot snapshot() { return snapshot; }
    /** Read-only confirmation for UI end animations; a stop request alone is not release. Call on main. */
    public boolean isCaptureReleased() {
        if (closed || !snapshot.ready() || captureOwner != null || localRecordingStopPending) return false;
        try { return !HeadsetManager.isFusionRecordBusy(); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        main.post(() -> { if (!closed && listeners.contains(listener)) listener.changed(snapshot); });
    }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void addControlListener(ControlListener listener) { controlListeners.addIfAbsent(listener); }
    public void removeControlListener(ControlListener listener) { controlListeners.remove(listener); }
    /** UI owns foreground intent. Enabling never starts capture or changes a business mode itself. */
    public void setControlsEnabled(boolean enabled) { main.post(() -> {
        if (closed) return;
        if (controlsWanted == enabled) return;
        boolean changed = controlsWanted != enabled;
        controlsWanted = enabled;
        if (!enabled) {
            clearControlsWearWait();
            cancelReconnect();
            if (vendorControlListener != null) stopControls(false);
            else if (controlsState != ControlsState.DISABLING) updateControls(ControlsState.DISABLED, "触控联动未开启");
        } else {
            // A reconnect clears uncertainty. Repeated UI renders must not blindly retry failed firmware commands.
            if (changed && !controlsFaulted) maybeStartControls();
            else if (controlsState == ControlsState.DISABLED && !controlsFaulted) maybeStartControls();
            else if (controlsFaulted) {
                if (controlsFailure != null) publishControlFailure();
                else updateControls(ControlsState.ERROR, "触控上报状态未确认，请重新连接耳机");
            }
        }
    }); }

    private void maybeStartControls() {
        if (closed || !controlsWanted || controlsFaulted || vendorControlListener != null
                || controlsState == ControlsState.DISABLING) return;
        if (!snapshot.ready() || !deviceStillReady()) { updateControls(ControlsState.DISABLED, "等待耳机就绪后开启触控"); return; }
        // Product policy: one real worn position from this connection is enough. It is not a
        // meaning assigned to status=6 and does not retry a failed firmware registration.
        if (!controlWearGate.worn()) { waitForControlWear(); return; }
        clearControlsWearWait();
        final long operation = ++controlsOperation;
        final long token = controlGate.begin(connectedMac);
        final OnAuraTestEventListener requested = new OnAuraTestEventListener() {
            @Override public void onEvent(AuraTestEventEntity event) {
                if (event == null) return;
                final long receivedAtMillis = SystemClock.elapsedRealtime();
                final String deviceMac = event.getDeviceMac();
                final int eventCode = event.getEventCode();
                main.post(() -> {
                if (closed || !controlsWanted || vendorControlListener != this || operation != controlsOperation
                        || !deviceStillReady() || !HeadsetManager.isAuraTestEventListenerRegistered(this)) return;
                ControlEvent value = controlGate.accept(token, deviceMac, eventCode, receivedAtMillis);
                if (value == null) return;
                controlEventCount++; lastControlEvent = value.kind + " (0x" + Integer.toHexString(value.code) + ") #" + value.sequence;
                updateControls(ControlsState.ENABLED, "已收到实际触控 · " + lastControlEvent);
                for (ControlListener listener : controlListeners) {
                    if (!controlsWanted || vendorControlListener != this) break;
                    listener.onControl(value);
                }
                });
            }
            @Override public void onError(int code, String message) { main.post(() -> {
                if (vendorControlListener == this && operation == controlsOperation) controlFailure(ControlFailure.Stage.LISTENER, code, message);
            }); }
        };
        vendorControlListener = requested;
        updateControls(ControlsState.ENABLING, "正在请求耳机开启触控上报");
        controlsTimeout = () -> { if (operation == controlsOperation && vendorControlListener == requested)
            controlFailure(ControlFailure.Stage.OPEN, 2400, "应用等待开启确认超时"); };
        main.postDelayed(controlsTimeout, 5500); // SDK's command deadline is 5 seconds.
        try {
            HeadsetManager.registerAuraTestEventListener(requested, new OnResultListener<Void>() {
                @Override public void onResult(Void ignored) { main.post(() -> {
                    if (closed || !controlsWanted || operation != controlsOperation || vendorControlListener != requested) return;
                    if (!HeadsetManager.isAuraTestEventListenerRegistered(requested) || !controlGate.confirm(token)) {
                        controlFailure(ControlFailure.Stage.OPEN, 2400, "SDK 未确认当前监听"); return;
                    }
                    clearControlsTimeout();
                    updateControls(ControlsState.ENABLED, "固件已确认开启 · 等待实际触控");
                }); }
                @Override public void onError(int code, String message) { main.post(() -> {
                    if (operation == controlsOperation && vendorControlListener == requested) controlFailure(ControlFailure.Stage.OPEN, code, message);
                }); }
            });
        } catch (RuntimeException | LinkageError failure) { controlFailure(ControlFailure.Stage.OPEN, 2400, "触控调用异常"); }
    }
    private void waitForControlWear() {
        updateControls(ControlsState.WAITING_FOR_WEAR, controlWearGate.bothOut()
                ? "等待至少一耳佩戴后开启触控"
                : controlsWearTimedOut ? "尚未收到有效佩戴状态，继续等待；可检查耳机后重新连接"
                : "正在确认佩戴状态，等待至少一耳佩戴后开启触控");
        final long connection = connectionGeneration;
        requestWearQuery(connection);
        if (controlsWearTimeout != null) return;
        controlsWearTimeout = () -> {
            if (closed || connection != connectionGeneration || !controlsWanted || controlsFaulted
                    || vendorControlListener != null || controlsState != ControlsState.WAITING_FOR_WEAR) return;
            controlsWearTimedOut = true;
            if (!controlWearGate.worn() && !controlWearGate.bothOut())
                updateControls(ControlsState.WAITING_FOR_WEAR, "尚未收到有效佩戴状态，继续等待；可检查耳机后重新连接");
        };
        main.postDelayed(controlsWearTimeout, 4000);
    }
    private void requestWearQuery(long connection) {
        if (controlWearGate.claimQuery(connection)) HeadsetManager.refreshAuraPower();
    }
    private void clearControlsWearWait() {
        if (controlsWearTimeout != null) main.removeCallbacks(controlsWearTimeout);
        controlsWearTimeout = null; controlsWearTimedOut = false;
    }
    private void controlFailure(ControlFailure.Stage stage, int code, String message) {
        controlsFaulted = true; controlsFailure = new ControlFailure(stage, code, message);
        stopControls(true);
        publishControlFailure();
    }
    private void cleanupFailure(boolean preserveError, int code, String message) {
        controlsFaulted = true;
        controlsFailure = preserveError && controlsFailure != null ? controlsFailure.withCleanup(code, message)
                : new ControlFailure(ControlFailure.Stage.CLOSE, code, message);
        publishControlFailure();
    }
    private void publishControlFailure() {
        lastCode = controlsFailure.diagnostics();
        updateControls(ControlsState.ERROR, controlsFailure.summary());
    }
    private void stopControls(boolean preserveError) {
        clearControlsWearWait();
        final OnAuraTestEventListener previous = vendorControlListener;
        vendorControlListener = null; controlGate.invalidate(); clearControlsTimeout();
        final long operation = ++controlsOperation;
        if (previous == null) {
            if (!preserveError) updateControls(ControlsState.DISABLED, controlsWanted ? "等待耳机就绪后开启触控" : "触控联动未开启");
            return;
        }
        if (!preserveError) updateControls(ControlsState.DISABLING, "正在关闭耳机触控上报");
        controlsTimeout = () -> {
            if (operation != controlsOperation) return;
            cleanupFailure(preserveError, 2400, "应用等待关闭确认超时");
        };
        main.postDelayed(controlsTimeout, 10500); // Cancelling an in-flight registration may need enable + disable acknowledgements.
        try {
            HeadsetManager.unregisterAuraTestEventListener(previous, new OnResultListener<Void>() {
                @Override public void onResult(Void ignored) { main.post(() -> {
                    if (operation != controlsOperation) return;
                    clearControlsTimeout();
                    if (preserveError || controlsFaulted) return;
                    updateControls(ControlsState.DISABLED, controlsWanted ? "等待耳机就绪后开启触控" : "触控联动未开启");
                    maybeStartControls();
                }); }
                @Override public void onError(int code, String message) { main.post(() -> {
                    if (operation != controlsOperation) return;
                    clearControlsTimeout();
                    // The SDK may already be closing after rejecting enable. BUSY is cleanup,
                    // not the initiating failure; invalidate locally and never retry the command.
                    cleanupFailure(preserveError, code, message);
                }); }
            });
        } catch (RuntimeException | LinkageError failure) {
            clearControlsTimeout(); cleanupFailure(preserveError, 2400, "触控调用异常");
        }
    }
    private void clearControlsTimeout() { if (controlsTimeout != null) main.removeCallbacks(controlsTimeout); controlsTimeout = null; }
    private void updateControls(ControlsState state, String message) {
        controlsState = state; controlsSummary = message; publish(snapshot.state, snapshot.message);
    }

    @SuppressLint("MissingPermission")
    public List<DeviceChoice> pairedDevices() {
        List<DeviceChoice> devices = new ArrayList<>();
        if (!hasPermissions()) return devices;
        BluetoothManager manager = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return devices;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            // Discovery only. READY still requires the SDK handshake and matching target address.
            if (name != null && name.toLowerCase(Locale.ROOT).contains("viaim rise"))
                devices.add(new DeviceChoice(device.getAddress(), name));
        }
        devices.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return devices;
    }
    public void connect() {
        main.post(() -> {
            cancelReconnect();
            if (!hasPermissions()) { publish(State.ERROR, "请允许蓝牙连接和扫描权限后重试"); return; }
            List<DeviceChoice> devices = pairedDevices();
            if (devices.size() != 1) {
                publish(State.ERROR, devices.isEmpty() ? "请先在系统蓝牙中配对并连接 Rise 耳机" : "已配对多副 Rise，请选择目标耳机"); return;
            }
            connectSelected(devices.get(0).address);
        });
    }
    public void connect(String address) { main.post(() -> { cancelReconnect(); connectSelected(address); }); }

    /** Silent foreground maintenance reuses this sole SDK owner and any in-flight reconnect. */
    public void connectAutomatically() { main.post(() -> {
        if (closed || initializing || releasing || reconnectPending || reconnectTask != null || !hasPermissions()
                || snapshot.state != State.DISCONNECTED && snapshot.state != State.ERROR) return;
        List<DeviceChoice> devices = pairedDevices();
        String previous = app.getSharedPreferences("headset_connection", Context.MODE_PRIVATE).getString("last_ready", "");
        String selected = null;
        for (DeviceChoice device : devices) if (device.address.equalsIgnoreCase(previous)) selected = device.address;
        if (selected == null && devices.size() == 1) selected = devices.get(0).address;
        // Multiple unknown targets require the existing explicit selector; never connect arbitrarily.
        if (selected != null) connectSelected(selected);
    }); }

    private void connectSelected(String address) {
        if (closed) return;
        if (initializing || releasing) {
            publish(State.ERROR, "耳机服务尚未完成释放，请稍后重试；持续失败时重启应用"); return;
        }
        if (!hasPermissions()) { publish(State.ERROR, "请允许蓝牙连接和扫描权限后重试"); return; }
        if (!supportsSdkAbi()) { publish(State.ERROR, "Rise SDK 需要 ARM 安卓真机，请在模拟器选择模拟器麦克风"); return; }
        boolean paired = false;
        for (DeviceChoice device : pairedDevices()) if (device.address.equalsIgnoreCase(address)) paired = true;
        if (!paired) { publish(State.ERROR, "目标耳机未配对，请先在系统蓝牙中配对 Rise"); return; }
        if (snapshot.state == State.INITIALIZING || snapshot.state == State.CONNECTING
                || snapshot.state == State.CONNECTED || snapshot.state == State.DISCONNECTING) return;
        if (snapshot.ready()) {
            if (address.equalsIgnoreCase(connectedMac)) {
                if (controlsFaulted) { explicitReconnect = true; requestDisconnect(State.DISCONNECTED, "正在重新连接耳机"); }
                return;
            }
            publish(State.READY, "请先断开当前耳机再切换设备"); return;
        }
        synchronized (HeadsetController.class) {
            if (sdkOwner != null && sdkOwner != this) { publish(State.ERROR, "耳机服务正在释放，请稍后重试"); return; }
            sdkOwner = this;
        }
        final long token = ++connectionGeneration;
        targetMac = address; connectedMac = null; resetProperties();
        lastCode = "none";
        connectionStarted = SystemClock.elapsedRealtime(); readyElapsed = 0;
        connectionTimeout = () -> {
            if (token == connectionGeneration && !snapshot.ready()) recover("耳机连接超时，请确认系统蓝牙连接后重试", "connect_timeout");
        };
        main.postDelayed(connectionTimeout, 15_000);
        try {
            if (!initialized) {
                if (initializing) return;
                initializing = true; publish(State.INITIALIZING, "正在初始化耳机服务");
                HeadsetManager.openLog(false); // Vendor logs may contain full addresses and serials.
                HeadsetManager.init(app, () -> main.post(() -> {
                    initialized = true; initializing = false;
                    if (closed || token != connectionGeneration) {
                        requestDisconnect(closed ? State.DISCONNECTED : State.ERROR, "耳机初始化已取消，请重新连接"); return;
                    }
                    registerCalls(); connectTransport(token, address);
                }));
            } else connectTransport(token, address);
        } catch (RuntimeException | LinkageError failure) {
            initializing = false;
            recover("耳机服务初始化失败，请检查 SDK 和手机架构", "sdk_init");
        }
    }

    private void connectTransport(long token, String address) {
        if (closed || token != connectionGeneration) return;
        publish(State.CONNECTING, "正在连接耳机");
        HeadsetManager.connectSPP(address, new OnConnectChangeListener() {
            @Override public void onConnectChange(String mac, GaiaConnectStatus state, String ignoredInfo) {
                main.post(() -> {
                    if (closed || token != connectionGeneration || !sameAddress(address, mac)) return;
                    switch (state) {
                        case CONNECTING: publish(State.CONNECTING, "正在连接耳机"); break;
                        case CONNECTED: publish(State.CONNECTED, "耳机已连接，正在确认通信"); break;
                        case DISCONNECTING: publish(State.DISCONNECTING, "正在断开耳机"); break;
                        case DISCONNECTED:
                            if (controlsWanted && !reconnectPending) { reconnectPending = true; reconnectAttempts = 0; }
                            requestDisconnect(State.DISCONNECTED, "耳机已断开"); break;
                        case CONNECT_ERROR: recover("耳机连接失败，请检查系统蓝牙后重试", "connect_error"); break;
                        default: break;
                    }
                });
            }
            @Override public void onConnectReady(String mac) {
                main.post(() -> {
                    if (closed || token != connectionGeneration || !sameAddress(address, mac)) return;
                    if (!HeadsetManager.isDeviceReady() || HeadsetManager.getConnectedDeviceType() != AuraDevice.EARBUDS
                            || !sameAddress(address, HeadsetManager.getConnectedDeviceMac())) {
                        recover("当前设备未通过 Rise 通信确认", "wrong_device"); return;
                    }
                    connectedMac = mac; model = "Rise / Aura 耳机 (6510)";
                    app.getSharedPreferences("headset_connection", Context.MODE_PRIVATE).edit().putString("last_ready", mac).apply();
                    cancelReconnect();
                    readyElapsed = SystemClock.elapsedRealtime() - connectionStarted;
                    main.removeCallbacks(connectionTimeout); publish(State.READY, "耳机已就绪");
                    bindProperties(token, mac);
                    controlsFaulted = false; controlsFailure = null; maybeStartControls();
                });
            }
        });
    }

    private void registerCalls() {
        HeadsetManager.registerDialogListener(new OnDialogListener() {
            @Override public void onDialogActive() { main.post(() -> {
                inCall = true;
                FusionDevice.Listener owner = captureOwner;
                if (owner != null) {
                    if (owner.recordsPhoneCalls()) owner.phoneCall(true);
                    else { owner.error("来电或通话已中断耳机录音"); cancelFusion(owner); }
                }
                publish(snapshot.state, snapshot.message);
            }); }
            @Override public void onDialogNum(String ignoredPhoneNumber) { /* Never retain call identity. */ }
            @Override public void onDialogInActive() { main.post(() -> {
                inCall = false;
                FusionDevice.Listener owner = captureOwner;
                if (owner != null && owner.recordsPhoneCalls()) owner.phoneCall(false);
                publish(snapshot.state, snapshot.message);
            }); }
        });
    }

    private void bindProperties(long token, String mac) {
        unbindProperties();
        powerListener = new OnResultListener<PowerEntity>() {
            @Override public void onResult(PowerEntity power) {
                if (power == null) return;
                PowerEntity copy = new PowerEntity(power);
                main.post(() -> {
                    if (closed || token != connectionGeneration || !snapshot.ready() || !sameAddress(mac, copy.getMac())) return;
                    if (copy.hasAttribute(PowerEntity.ATTR_LEFT_POWER)) leftBattery = reportedBattery(copy.getLeftPowerValue());
                    if (copy.hasAttribute(PowerEntity.ATTR_RIGHT_POWER)) rightBattery = reportedBattery(copy.getRightPowerValue());
                    if (copy.hasAttribute(PowerEntity.ATTR_CASE_POWER)) caseBattery = reportedBattery(copy.getCasePowerValue());
                    if (copy.hasAttribute(PowerEntity.ATTR_POSITION)) {
                        leftWorn = reportedWear(copy.getLeftPositionCode()); rightWorn = reportedWear(copy.getRightPositionCode());
                        controlWearGate.observe(token, true, leftWorn, rightWorn);
                    }
                    publish(State.READY, "耳机已就绪");
                    if (copy.hasAttribute(PowerEntity.ATTR_POSITION)) maybeStartControls();
                });
            }
            @Override public void onError(int code, String ignoredMessage) { main.post(() -> {
                if (token == connectionGeneration) { lastCode = "power_" + code; publish(snapshot.state, snapshot.message); }
            }); }
        };
        HeadsetManager.addAuraPowerListener(powerListener); requestWearQuery(token);
        HeadsetManager.getSN(new OnResultListener<SNEntity>() {
            @Override public void onResult(SNEntity sn) { main.post(() -> {
                if (closed || token != connectionGeneration || sn == null) return;
                if (isA10(sn.getLeft()) || isA10(sn.getRight())) model = "viaim Rise VI-A10";
                publish(snapshot.state, snapshot.message);
            }); }
            @Override public void onError(int code, String ignoredMessage) { /* Unknown model stays unknown. */ }
        });
    }
    static Integer reportedBattery(int value) { return value >= 0 && value <= 100 ? value : null; }
    static Boolean reportedWear(int code) { return code == 1 ? Boolean.TRUE : code == 2 || code == 4 ? Boolean.FALSE : null; }
    private static boolean isA10(String serial) { return serial != null && (serial.startsWith("A10L") || serial.startsWith("A10R")); }

    @Override public void startFusion(FusionDevice.Listener listener) {
        // Checking before posting is insufficient: cancellation may invalidate the source while queued.
        main.post(() -> listener.startIfCurrent(() -> {
            if (closed || !snapshot.ready() || !deviceStillReady() || inCall && !listener.recordsPhoneCalls() || captureOwner != null || HeadsetManager.isFusionRecordBusy()) {
                listener.error(inCall ? "通话期间不能开启 AI 录音" : "耳机尚未就绪或录音未结束，请重新连接"); return;
            }
            if (!SystemCaptureLease.acquire(captureLease)) {
                listener.error("上一段录音仍在清理，请稍后重新开始"); return;
            }
            captureOwner = listener;
            if (listener.recordsPhoneCalls()) listener.phoneCall(inCall);
            final long token = ++captureGeneration;
            captureStarted = SystemClock.elapsedRealtime(); firstPcmElapsed = 0; microphoneBytes = 0;
            FusionCaptureEvents events = new FusionCaptureEvents(listener,
                    action -> main.post(() -> {
                        // SDK 6.7 serializes transitions with this synchronized read. Wait for the
                        // whole idle/onError callback stack before interpreting its cleanup state.
                        HeadsetManager.isFusionRecordBusy();
                        if (captureOwner == listener && token == captureGeneration) action.run();
                    }),
                    failure -> { lastCode = failure.diagnostics(); listener.error(failure.summary()); },
                    () -> releaseCapture(listener));
            captureEvents = events;
            try {
                HeadsetManager.startFusionRecord(new OnAuraRecordListener() {
                    @Override public void onState(String state, int scene) {
                        if (captureOwner != listener || token != captureGeneration) return;
                        phase = state;
                        events.state(state, scene);
                        if (!"idle".equals(state)) main.post(() -> { if (captureOwner == listener) publish(snapshot.state, snapshot.message); });
                    }
                    @Override public void onResult(RecordDataEntity data) {
                        if (captureOwner != listener || token != captureGeneration || data == null) return;
                        byte[] bytes = data.getAudioData();
                        if (data.getChannel() == 2 && bytes != null) {
                            microphoneBytes += bytes.length;
                            if (firstPcmElapsed == 0) firstPcmElapsed = SystemClock.elapsedRealtime() - captureStarted;
                        }
                        events.pcm(data.getChannel(), bytes);
                    }
                    @Override public void onError(int code, String message) {
                        if (captureOwner != listener || token != captureGeneration) return;
                        events.error(code, message);
                    }
                });
            } catch (RuntimeException | LinkageError failure) { listener.error("耳机录音无法启动，请重新连接"); }
        }));
    }
    @Override public void stopFusion(FusionDevice.Listener owner) {
        main.post(() -> {
            if (captureOwner != owner) return;
            phase = "stopping";
            try { HeadsetManager.stopFusionRecord(); }
            catch (RuntimeException | LinkageError failure) { owner.error("耳机结束命令失败，请重新连接"); }
        });
    }
    @Override public void cancelFusion(FusionDevice.Listener owner) {
        main.post(() -> {
            if (captureOwner != owner) return;
            stopFusion(owner);
            if (cancelTimeout != null) main.removeCallbacks(cancelTimeout);
            cancelTimeout = () -> { if (captureOwner == owner) recover("耳机停止未确认，请重新连接", "cancel_timeout"); };
            main.postDelayed(cancelTimeout, 2500);
        });
    }
    @Override public void requireReconnect(FusionDevice.Listener owner) {
        main.post(() -> {
            if (captureOwner != owner) return;
            boolean busy = HeadsetManager.isFusionRecordBusy();
            FusionCaptureEvents.Failure failure = captureEvents == null ? null : captureEvents.failure();
            if (failure != null && failure.rejectedStart() && deviceStillReady() && !busy) {
                // A rejected start has already returned the SDK to idle; it never owned microphone audio.
                releaseCapture(owner); lastCode = failure.diagnostics(); publish(State.READY, failure.summary());
            } else recover(failure == null ? "录音已中止，请重新连接耳机" : failure.summary() + "；请重新连接耳机",
                    failure == null ? "capture_recovery" : failure.diagnostics() + " capture_recovery");
        });
    }
    private void releaseCapture(FusionDevice.Listener owner) {
        if (captureOwner != owner) return;
        captureOwner = null; phase = "idle";
        SystemCaptureLease.release(captureLease);
        if (cancelTimeout != null) main.removeCallbacks(cancelTimeout);
        publish(snapshot.state, snapshot.message);
    }

    /** The SDK connection alone does not establish the Android media output route. */
    public boolean matchesOutput(AudioDeviceInfo output) {
        if (!snapshot.ready() || !deviceStillReady() || output == null || !output.isSink() || Build.VERSION.SDK_INT < 28) return false;
        int type = output.getType();
        boolean bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
        return bluetooth && sameAddress(connectedMac, output.getAddress());
    }
    private boolean deviceStillReady() {
        try { return HeadsetManager.isDeviceReady() && HeadsetManager.isDeviceConnected()
                && sameAddress(connectedMac, HeadsetManager.getConnectedDeviceMac()); }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }
    public void disconnect() { main.post(() -> {
        if (closed) return;
        cancelReconnect(); explicitReconnect = false;
        requestDisconnect(State.DISCONNECTED, "耳机未连接");
    }); }
    /** Explicit debug diagnostic; never automatically interrupts an existing headset recording. */
    public void stopLocalRecording(java.util.function.Consumer<String> result) {
        main.post(() -> {
            if (!snapshot.ready() || !deviceStillReady()) { result.accept("请先连接耳机，等待显示就绪"); return; }
            if (localRecordingStopPending || inCall || captureOwner != null || HeadsetManager.isFusionRecordBusy()) {
                result.accept("请先结束当前通话或等待耳机操作完成"); return;
            }
            localRecordingStopPending = true;
            long connection = connectionGeneration;
            try { HeadsetManager.stopCurrentFlashRecord(new OnResultListener<Void>() {
                @Override public void onResult(Void ignored) { main.post(() -> {
                    if (closed || connection != connectionGeneration) return;
                    localRecordingStopPending = false;
                    result.accept("耳机已确认本地录音停止命令，请回到对话页重新尝试");
                }); }
                @Override public void onError(int code, String ignoredMessage) { main.post(() -> {
                    if (closed || connection != connectionGeneration) return;
                    localRecordingStopPending = false;
                    result.accept("耳机本地录音停止命令未确认（代码 " + code + "）");
                }); }
            }); } catch (RuntimeException | LinkageError failure) {
                localRecordingStopPending = false; result.accept("耳机本地录音停止命令未能执行");
            }
        });
    }
    private void recover(String message, String code) {
        lastCode = code; requestDisconnect(State.ERROR, message);
    }
    private void requestDisconnect(State terminalState, String message) {
        // Invalidate every connection callback, including a late onConnectReady for the same address.
        stopControls(false); controlsOperation++; clearControlsTimeout(); controlGate.invalidate();
        controlsState = ControlsState.DISABLED;
        controlsSummary = controlsWanted ? "耳机断开，等待重新就绪" : "触控联动未开启";
        connectionGeneration++; clearCapture(message); connectedMac = null; resetProperties(); unbindProperties();
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (initializing) {
            // init() has no cancellation API. Keep the static owner until its callback finishes;
            // a new Activity cannot initialize a second SDK instance while this one is pending.
            publish(State.ERROR, "耳机服务初始化尚未结束，请稍后重试；持续失败时重启应用"); return;
        }
        if (!initialized) { releaseSdkOwner(); publish(terminalState, message); afterTransportRelease(); return; }
        releasing = true;
        final long operation = ++releaseOperation;
        publish(terminalState == State.ERROR ? State.ERROR : State.DISCONNECTING,
                terminalState == State.ERROR ? message : "正在断开耳机");
        Thread release = new Thread(() -> releaseTransport(operation, SystemClock.elapsedRealtime() + 5_500, terminalState, message),
                "aidol-headset-release");
        release.setDaemon(true);
        release.start();
    }
    /** Native transport cleanup runs off main; only its decided outcome returns to the main thread. */
    private void releaseTransport(long operation, long deadline, State terminalState, String message) {
        String code = null;
        try { HeadsetManager.stopFusionRecord(); HeadsetManager.disconnectDevice(); }
        catch (RuntimeException | LinkageError ignored) { code = "disconnect_error"; }
        HeadsetController.ReleaseWatch.Decision decision;
        while (true) {
            boolean released = false;
            try { released = HeadsetManager.transportsReleased(); }
            catch (RuntimeException | LinkageError ignored) { if (code == null) code = "release_error"; }
            decision = ReleaseWatch.decide(operation != releaseOperation, released, SystemClock.elapsedRealtime(), deadline);
            if (decision != ReleaseWatch.Decision.RETRY) break;
            try { Thread.sleep(ReleaseWatch.POLL_MILLIS); } catch (InterruptedException stopped) { return; }
        }
        if (decision == ReleaseWatch.Decision.STALE) return;
        boolean destroyed = false;
        if (decision == ReleaseWatch.Decision.RELEASED) {
            try { HeadsetManager.registerDialogListener(null); HeadsetManager.destroy(); destroyed = true; }
            catch (RuntimeException | LinkageError ignored) { /* Falls through to the unconfirmed error below. */ }
        }
        final String diagnostics = code;
        final boolean cleanupConfirmed = destroyed;
        main.post(() -> {
            if (operation != releaseOperation) return;
            if (diagnostics != null) lastCode = diagnostics;
            if (!cleanupConfirmed) {
                // Do not make another input source appear safe while native transport cleanup is unknown.
                publish(State.ERROR, "耳机连接释放未确认，请重启应用后重新连接"); return;
            }
            initialized = false; releasing = false; inCall = false;
            releaseSdkOwner(); publish(terminalState, message); afterTransportRelease();
        });
    }

    /** Pure step decision for the native release poll, so the spin itself never needs the main thread. */
    static final class ReleaseWatch {
        enum Decision { RELEASED, RETRY, STALE, TIMEOUT }
        static final long POLL_MILLIS = 50;
        static Decision decide(boolean superseded, boolean released, long nowElapsed, long deadlineElapsed) {
            if (superseded) return Decision.STALE;
            if (released) return Decision.RELEASED;
            if (nowElapsed >= deadlineElapsed) return Decision.TIMEOUT;
            return Decision.RETRY;
        }
    }
    private void releaseSdkOwner() {
        SystemCaptureLease.release(captureLease);
        synchronized (HeadsetController.class) { if (sdkOwner == this) sdkOwner = null; }
    }
    private void afterTransportRelease() {
        if (closed) return;
        if (explicitReconnect && targetMac != null) {
            explicitReconnect = false; connectSelected(targetMac); return;
        }
        if (!reconnectPending || !controlsWanted || targetMac == null) return;
        if (reconnectAttempts >= 3) {
            reconnectPending = false;
            publish(State.ERROR, "耳机重连未成功，已停止自动重试，请检查耳机后手动连接"); return;
        }
        final String originalTarget = targetMac;
        final int attempt = ++reconnectAttempts;
        long delay = attempt == 1 ? 2000 : attempt == 2 ? 5000 : 10000;
        publish(State.DISCONNECTED, "耳机已断开，将重连原耳机（" + attempt + "/3）");
        reconnectTask = () -> {
            reconnectTask = null;
            if (closed || !controlsWanted || !reconnectPending || !sameAddress(originalTarget, targetMac)) return;
            connectSelected(originalTarget);
            // Preflight failures (Bluetooth off / pairing removed) never enter SDK callbacks.
            if (!initialized && !initializing && !releasing && snapshot.state == State.ERROR) afterTransportRelease();
        };
        main.postDelayed(reconnectTask, delay);
    }
    private void cancelReconnect() {
        reconnectPending = false; reconnectAttempts = 0;
        if (reconnectTask != null) main.removeCallbacks(reconnectTask);
        reconnectTask = null;
    }
    private void clearCapture(String message) {
        FusionDevice.Listener owner = captureOwner; captureOwner = null; captureGeneration++; phase = "idle";
        if (cancelTimeout != null) main.removeCallbacks(cancelTimeout);
        if (owner != null) owner.error(message);
    }
    private void unbindProperties() {
        if (powerListener != null) { HeadsetManager.removeAuraPowerListener(powerListener); powerListener = null; }
    }
    private void resetProperties() {
        localRecordingStopPending = false;
        model = "未知"; leftBattery = rightBattery = caseBattery = null; leftWorn = rightWorn = null;
        controlWearGate.reset(connectionGeneration);
    }
    private void publish(State state, String message) {
        snapshot = new Snapshot(state, message, model, leftBattery, rightBattery, caseBattery, leftWorn, rightWorn,
                "SDK=6.7 state=" + state + " device=" + masked(connectedMac) + " ready_ms=" + readyElapsed
                + " capture=" + captureGeneration + " phase=" + phase + " mic_bytes=" + microphoneBytes
                + " first_pcm_ms=" + firstPcmElapsed + " error=" + lastCode
                + " controls=" + controlsState + " control_events=" + controlEventCount + " last_control=" + lastControlEvent,
                inCall, controlsState, controlsSummary, lastControlEvent, controlEventCount);
        for (Listener listener : listeners) listener.changed(snapshot);
    }
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) return app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private static boolean supportsSdkAbi() {
        // Do not load ARM libraries on x86 emulators, including those advertising secondary translation ABIs.
        String device = (Build.FINGERPRINT + " " + Build.MODEL + " " + Build.PRODUCT + " " + Build.HARDWARE).toLowerCase(Locale.ROOT);
        if (device.contains("generic") || device.contains("emulator") || device.contains("sdk_gphone")
                || device.contains("sdk_google") || device.contains("goldfish") || device.contains("ranchu")) return false;
        String primary = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        return primary.equals("arm64-v8a") || primary.equals("armeabi-v7a");
    }
    private static boolean sameAddress(String a, String b) { return a != null && b != null && !a.isEmpty() && a.equalsIgnoreCase(b); }
    private static String masked(String mac) { return mac == null || mac.length() < 5 ? "unknown" : "**:**:**:**:" + mac.substring(mac.length() - 5); }
    @Override public void close() { main.post(() -> {
        if (closed) return;
        closed = true; controlsWanted = false; cancelReconnect(); explicitReconnect = false;
        listeners.clear(); controlListeners.clear(); requestDisconnect(State.DISCONNECTED, "耳机服务已关闭");
    }); }
}
