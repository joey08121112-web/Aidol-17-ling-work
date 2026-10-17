package com.aidol.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.aidol.demo.hardware.HeadsetController;
import com.aidol.demo.hardware.VendorCaptureSource;

/** Foreground Android client. Credentials and provider protocols stay outside the UI. */
public final class MainActivity extends Activity {
    private static final int BG = 0xffffffff, CARD = 0xfff6f7f9, LINE = 0xffe3e6ec;
    private static final int INK = 0xff202632, MUTED = 0xff697381, ACCENT = 0xff2468db, RED = 0xffb42318;
    private static final int SOFT = 0xffedf1f7;
    private static final int MEMORY = 0xff7356a6, WORK = 0xff305fb5, COMMUTE = 0xff24786d;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ApiClient api;
    private DevelopmentSettings development;
    private HeadsetController headset;
    private QuickFeedback quickFeedback;
    private ActionTaskBoard taskBoard;
    private TextView workRetentionAction, historyRangeAction;
    private LinearLayout historyReportBox;
    private RoundedChoiceDialog workOptionsDialog;
    private LinearLayout lastRenderedMeetingList;
    private String lastRenderedMeetingSnapshot = "";
    private ForegroundLocationMonitor locationMonitor;
    private TransientStatusPopup statusPopup;
    private TextView maintenanceStatus, meetingMaintenanceStatus;
    private String maintenanceLocationStatus = "定位：等待更新", maintenanceEntry = "app_open";
    private boolean maintenanceHeadsetReady, automaticHeadsetSuppressed;
    private long automaticHeadsetAttempt = -30000;
    private final Runnable automaticHeadsetTick = this::maintainHeadset;
    private TextView dialogFeedback;
    private TextView headsetStatus, deviceDetails, voiceHint, developmentSummary;
    private long sessionGeneration;
    private String diagnosticSession = "尚未开始";
    private boolean sessionUsesCapture;
    private ModeChangeRetry modeChange;
    private Runnable modeChangeDelay;
    private boolean sessionIsReplay;
    private String sessionOperationNotice;
    private String deferredMode;
    private boolean deferredOpenConversation;
    private VoiceSessionService voiceService;
    private boolean voiceServiceBound;
    private boolean callStartPending;
    private VoicePreparation voicePreparation;
    private long preparationAttemptAt = -60000;
    private final Runnable preparationTick = this::prepareVoice;
    private void clearVoicePreparation() {
        handler.removeCallbacks(preparationTick);
        if (voicePreparation != null) { voicePreparation.close(); voicePreparation = null; }
    }
    private void prepareVoice() {
        handler.removeCallbacks(preparationTick);
        if (headset == null || api == null) return;
        HeadsetController.Snapshot device = headset.snapshot();
        boolean eligible = visible && tab == 0 && (session == null || sessionOperationNotice != null)
            && !api.vault.token().isEmpty() && !"work".equals(mode())
            && state.optJSONObject("capabilities") != null
            && state.optJSONObject("capabilities").optBoolean("voice_prepare")
            && device.ready() && !device.inCall
            && WearState.from(device.leftWorn, device.rightWorn) != WearState.REMOVED
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!eligible) { clearVoicePreparation(); return; }
        if (voicePreparation != null && !voicePreparation.usable(api)) clearVoicePreparation();
        // Keep standby alive while eligible, including after an expired/failed
        // preparation. A stale object must never suppress future preparation.
        handler.postDelayed(preparationTick, 1000);
        if (busy || permissionPending) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (voicePreparation == null && now - preparationAttemptAt >= 12000) {
            preparationAttemptAt = now;
            voicePreparation = new VoicePreparation(this, api, headset::matchesOutput, handler);
        }
    }
    private long audioPermissionGeneration = -1;
    private final android.content.ServiceConnection voiceConnection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            voiceService = ((VoiceSessionService.LocalBinder) binder).service();
            if (!visible || isDestroyed() || !voiceService.enabled()) { stopLockScreenSession(); return; }
            voiceService.onStopped(reason -> {
                cancelSession(reason);
                disconnectVoiceService();
                syncHeadsetControls();
                if (visible && !isDestroyed()) showApp();
            });
            syncHeadsetControls();
            lastNotice = "耳机会话已开启 · 通话可锁屏继续 · 通知可挂断";
            showApp();
            if (callStartPending) { callStartPending = false; startVoice(); }
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            cancelSession("耳机会话服务已中断，请回到 App 重新开启");
            disconnectVoiceService(); syncHeadsetControls();
        }
    };
    private boolean headsetControlsOptIn, controlsRequested;
    private String pendingHeadsetNotice = "";
    private final HeadsetInteractionPolicy headsetPolicy = new HeadsetInteractionPolicy();
    private final Runnable settleHeadsetGesture = () -> applyHeadsetDecision(headsetPolicy.flush(
        android.os.SystemClock.elapsedRealtime(), headsetInteractionContext()));
    private final HeadsetController.ControlListener headsetControlListener = event -> runOnUiThread(() -> {
        if (!isDestroyed()) handleHeadsetControl(event);
    });
    private final HeadsetController.Listener headsetListener = snapshot -> runOnUiThread(() -> {
        if (isDestroyed()) return;
        if (snapshot.ready() && !maintenanceHeadsetReady && this.visible && statusPopup != null) statusPopup.show("蓝牙已连接");
        maintenanceHeadsetReady = snapshot.ready();
        renderHeadset();
        renderMaintenanceStatus();
        if (quickFeedback != null && !canSpeakFeedback()) quickFeedback.stop();
        if (development.audioMode() == AudioMode.RISE_SDK) {
            HeadsetInteractionPolicy.DeviceUpdate update = headsetPolicy.device(new HeadsetInteractionPolicy.Device(
                snapshot.ready(), snapshot.inCall, snapshot.leftWorn, snapshot.rightWorn,
                snapshot.leftBattery, snapshot.rightBattery), hasHeadsetBoundSession() || this.repeatArmed || lockScreenEnabled());
            if (update.stopReason != null) {
                cancelSession(update.stopReason);
                // Reconnect and call end do not silently restore lock-screen authorization.
                stopLockScreenSession();
            }
            if (!update.notices.isEmpty()) headsetNotice(android.text.TextUtils.join("；", update.notices));
        }
        syncHeadsetControls();
        prepareVoice();
    });
    private TextView diagnosticsView;
    private String diagnosticInput = "尚未录音", diagnosticOutput = "尚未播放", diagnosticRequest = "尚未请求", diagnosticStage = "空闲", diagnosticError = "无";
    private double diagnosticRms;
    private long diagnosticBytes;
    private JSONObject state = new JSONObject();
    private LinearLayout root, page, content, composer;
    private ScrollView scroll;
    private TextView status, voiceButton, cancelButton, authError;
    private EditText chatInput;
    private CompanionOrbView orb;
    private String chatDraft = "";
    private HostedSession session;
    private boolean busy, recording, visible;
    private int tab, authMode;
    private String memoryFilter = "", lastNotice = "", lastAnswer = "", lastUser = "";
    private JSONArray lastCitations = new JSONArray(), lastTools = new JSONArray();
    private LinearLayout turnDetails;
    private AlertDialog sourceDialog;
    private LinearLayout workMeetingList, workMeetingDetail;
    private String openWorkMeetingId = "", workMeetingDetailSnapshot = "", workMeetingJumpId = "";
    private boolean workMeetingSyncError, workMeetingAudioPlayable, meetingSeeking;
    private TextView workClock, meetingTitle, meetingMeta;
    private LinearLayout workCaptionBox, conversationCaptionBox;
    // Live captions of the meeting being recorded: final sentences in ink, the interim text in grey.
    private final java.util.ArrayList<SpeakerParagraphs.Sentence> workCaptionSentences = new java.util.ArrayList<>();
    private String workCaptionPartial = "", workCaptionMeetingId = "", workCaptionStartedAt = "";
    private String workMeetingAudioText = "";
    private android.app.Dialog meetingPage;
    private ScrollView meetingScroll;
    private LinearLayout meetingNav;
    private android.widget.SeekBar meetingSeek;
    private final java.util.LinkedHashMap<String, View> meetingSections = new java.util.LinkedHashMap<>();
    private boolean workMeetingPollInFlight;
    private MeetingPlayback meetingPlayback;
    private MeetingTimeline workMeetingTimeline;
    private final java.util.HashMap<String, TextView> workMeetingLines = new java.util.HashMap<>();
    // Sentence -> character range in its paragraph, and the spans currently marking playing / jumped sentences.
    private final java.util.HashMap<String, int[]> workMeetingRanges = new java.util.HashMap<>();
    private final Object[] playingMark = new Object[1], jumpMark = new Object[1];
    private String workMeetingPlayingId = "";
    private LinearLayout workMeetingPlayer;
    private TextView workMeetingPlayerStatus;
    private final Runnable workMeetingPlaybackTick = this::tickMeetingPlayback;
    private long workMeetingReadGeneration;
    private final Runnable workMeetingPoll = this::pollWorkMeetings;
    private String lastBusinessRequestId = "", uncertainTurnId = "";
    private boolean turnHasResult;
    private byte[] exportData;
    private android.location.LocationListener locationListener;
    private boolean startupLocationAttempted, automaticLocation;
    private final Runnable startupLocationTick = this::updateStartupLocation;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable locationTimeout = () -> locationFailed("暂未取得准确位置，可报城市查天气，或到开阔处重新定位");
    private int listenMode = VoiceInteraction.CALL;
    private boolean continuingCall;
    private TextView callCaption, liveWords;
    private LinearLayout modeChoices;
    private TextView modeHint;
    private String liveTranscript = "";
    private String liveReplyText = "";
    private final ConversationFeed conversationFeed = new ConversationFeed();
    private LinearLayout liveTurn, conversationStarters;
    private TextView liveUserBubble, liveAssistantBubble;
    private boolean followingConversation = true, conversationScrollQueued;
    private boolean repeatArmed, permissionPending, turnSucceeded;
    private long repeatAt;
    private final Runnable repeatTick = new Runnable() {
        @Override public void run() {
            if (!repeatArmed || !sessionAvailable() || (tab != 0 && listenMode != VoiceInteraction.CALL) || api.vault.token().isEmpty()) return;
            if (listenMode == 2 && !"life".equals(mode())) { stopRepeat(); lastNotice = "场景已切换，定时聆听已停止"; showApp(); return; }
            long remaining = repeatAt - android.os.SystemClock.elapsedRealtime();
            if (session == null && !busy && locationListener == null && !permissionPending && remaining <= 0) { startVoice(); return; }
            if (session == null && !busy && status != null && remaining > 0) setStatusText((listenMode == 2 ? "定时聆听" : InteractionCopy.name(listenMode)) + " · " + Math.max(1, (remaining + 999) / 1000) + " 秒后继续 · 点击停止结束");
            handler.postDelayed(this, listenMode == VoiceInteraction.SCHEDULED ? 1000 : 180);
        }
    };
    private interface Job { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject value) throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? BG : INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            (Build.VERSION.SDK_INT >= 27 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        development = new DevelopmentSettings(this);
        headsetControlsOptIn = getSharedPreferences("headset_controls", MODE_PRIVATE).getBoolean("touch_enabled", true);
        headset = new HeadsetController(getApplicationContext());
        statusPopup = new TransientStatusPopup(this, () -> meetingPage != null && meetingPage.isShowing()
            ? meetingPage.getWindow().getDecorView() : getWindow().getDecorView());
        locationMonitor = new ForegroundLocationMonitor(this, new ForegroundLocationMonitor.Listener() {
            @Override public void status(String message) { maintenanceLocationStatus = message; renderMaintenanceStatus(); }
            @Override public void updated() { if (visible && !isDestroyed()) statusPopup.show("定位已更新"); }
        });
        quickFeedback = new QuickFeedback(this, headset::matchesOutput, this::canSpeakFeedback);
        meetingPlayback = new MeetingPlayback(this, headset::matchesOutput, this::meetingPlaybackChanged);
        headset.addListener(headsetListener);
        headset.addControlListener(headsetControlListener);
        taskBoard = new ActionTaskBoard(this, () -> api, () -> !busy && session == null,
            tasks -> { put(state, "tasks", tasks); }, message -> { lastNotice = message; updateStatus(); }, () -> {
                if (Build.VERSION.SDK_INT >= 33 && !permissionPending && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionPending = true; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
                }
            });
        connectBackend();
        if (getIntent().getBooleanExtra("open_work_tasks", false)) tab = 2;
        if (api.vault.token().isEmpty()) showAuth(0); else { showApp(); refresh(); }
    }

    private void base() {
        maintenanceStatus = null;
        diagnosticsView = null;
        turnDetails = null;
        voiceButton = null; cancelButton = null; orb = null; chatInput = null;
        callCaption = null; liveWords = null; content = null; workCaptionBox = null; conversationCaptionBox = null;
        liveTurn = null; liveUserBubble = null; liveAssistantBubble = null; conversationStarters = null;
        conversationScrollQueued = false;
        headsetStatus = null;
        voiceHint = null;
        developmentSummary = null;
        root = column(); root.setBackgroundColor(BG);
        root.setFitsSystemWindows(false);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showAuth(int mode) {
        chatDraft = "";
        authMode = mode; status = null; voiceButton = null; cancelButton = null; content = null; base();
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true);
        root.addView(sc, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout body = column(); body.setPadding(dp(28), dp(24), dp(28), dp(30)); sc.addView(body);
        TextView brand = label(body, "aidol", 25, INK); brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); brand.setLetterSpacing(-.04f);
        if (mode == 0) {
            CompanionOrbView mark = new CompanionOrbView(this); body.addView(mark, new LinearLayout.LayoutParams(-1, dp(160)));
        }
        gap(body, 12);
        label(body, mode == 1 ? "创建你的空间。" : mode == 2 ? "找回账号。" : "好好说话。\n慢慢了解你。", 32, INK).setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (mode != 2) label(body, mode == 1 ? "从一次对话，开始彼此了解。" : "登录 Aidol，让每一次对话都有回响。", 14, MUTED);
        if (mode == 2) label(body, "使用恢复码重设密码。", 14, MUTED);
        if (BuildConfig.DEBUG) label(body, development.localBackend() ? "本地账号 · USB" : "远端账号", 12, ACCENT);
        gap(body, 18);
        if (mode != 2) {
            LinearLayout tabs = row(); body.addView(tabs);
            segment(tabs, "登录", mode == 0, () -> { if (!busy) showAuth(0); });
            segment(tabs, "创建账号", mode == 1, () -> { if (!busy) showAuth(1); });
            gap(body, 12);
        }
        EditText username = field(body, mode == 1 ? "账号（英文不区分大小写）" : "账号", "起个好记的名字，中文也可以", false, 128);
        username.setInputType(InputType.TYPE_CLASS_TEXT);
        username.setSingleLine(true);
        EditText password = field(body, mode == 2 ? "新密码" : "密码", "填一个好记的密码即可", true, 128);
        EditText confirm = mode == 0 ? null : field(body, "确认密码", "再输入一次密码", true, 128);
        EditText recovery = mode == 2 ? field(body, "账号恢复码", "粘贴之前保存的恢复码", false, 128) : null;
        CheckBox consent = new CheckBox(this); consent.setTextColor(MUTED); consent.setTextSize(12);
        consent.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        consent.setText("我已了解：录音和文字会发送到服务器及 AI 服务处理；账号数据可导出、清空或删除。");
        if (mode == 1) body.addView(consent);
        authError = label(body, "", 13, RED); authError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button submit = button(body, mode == 1 ? "创建账号" : mode == 2 ? "重设密码并登录" : "登录", true, () -> {
            if (busy) return;
            String name = value(username), pass = password.getText().toString();
            if (name.isEmpty()) { authError.setText("请输入账号。"); return; }
            if (name.codePointCount(0, name.length()) > 64) { authError.setText("账号最多 64 位。"); return; }
            if (pass.isEmpty()) { authError.setText("请输入密码。"); return; }
            if (confirm != null && !pass.equals(confirm.getText().toString())) { authError.setText("两次密码不一致，请检查。"); return; }
            if (mode == 1 && !consent.isChecked()) { authError.setText("请先阅读并勾选数据处理说明。"); return; }
            if (recovery != null && value(recovery).length() < 16) { authError.setText("请输入完整的账号恢复码。"); return; }
            hideKeyboard(); authError.setText("正在连接…");
            JSONObject bodyJson = json("username", name, "password", pass);
            if (recovery != null) put(bodyJson, "recovery_code", value(recovery));
            request(() -> api.json("POST", "/auth/" + (mode == 1 ? "register" : mode == 2 ? "recover" : "login"), bodyJson), result -> {
                api.vault.save(result.getString("token"), result.getJSONObject("user").getString("username"));
                state = new JSONObject(); tab = 0; showApp();
                String code = result.optString("recovery_code");
                if (!code.isEmpty()) recoveryDialog(code);
                refresh();
            });
        });
        submit.setEnabled(!busy);
        button(body, mode == 0 ? "忘记密码" : "返回登录", false, () -> { if (!busy) showAuth(mode == 0 ? 2 : 0); });
        gap(body, 8);
        developmentEntry(body);
    }

    private void recoveryDialog(String code) {
        LinearLayout body = dialogBody();
        label(body, "这是找回账号的唯一凭证。不会再次显示，重置密码后旧恢复码会失效。", 14, MUTED);
        TextView secret = label(body, code, 18, ACCENT); secret.setTextIsSelectable(true); secret.setTypeface(Typeface.MONOSPACE);
        button(body, "复制恢复码", true, () -> copy("Aidol 账号恢复码", code));
        new AlertDialog.Builder(this).setTitle("请保存账号恢复码").setView(body).setCancelable(false)
            .setPositiveButton("我已妥善保存", (d, w) -> {}).show();
    }

    private void showApp() {
        handler.removeCallbacks(workMeetingPoll);
        workMeetingList = null;
        boolean restoreComposerFocus = tab == 0 && chatInput != null && chatInput.hasFocus();
        int draftSelection = chatInput == null ? 0 : chatInput.getSelectionStart();
        if (chatInput != null) chatDraft = chatInput.getText().toString();
        authError = null; base();
        LinearLayout head = row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(24), dp(8), dp(24), dp(4));
        root.addView(head);
        LinearLayout wordmark = column(); head.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        TextView logo = label(wordmark, "aidol", 25, INK); logo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); logo.setLetterSpacing(-.04f);
        TextView refresh = action(head, "↻", () -> { if (canAct()) { lastNotice = ""; refresh(); } }); refresh.setTextSize(25); refresh.setContentDescription("刷新服务器数据");
        TextView avatar = action(head, api.vault.username().isEmpty() ? "A" : api.vault.username().substring(0, 1).toUpperCase(Locale.ROOT), () -> switchTab(4));
        avatar.setBackground(ripple(SOFT, 24, LINE)); avatar.setTextColor(ACCENT); avatar.setContentDescription("打开我的空间");
        status = label(root, "", 11, MUTED); status.setPadding(dp(24), 0, dp(24), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        maintenanceStatus = label(root, "", 10, MUTED);
        maintenanceStatus.setPadding(dp(24), 0, dp(24), dp(5));
        renderMaintenanceStatus();
        if (tab != 0 && repeatArmed && listenMode == VoiceInteraction.CALL) {
            LinearLayout activeCall = row(); activeCall.setPadding(dp(24), dp(4), dp(24), dp(4)); root.addView(activeCall);
            button(activeCall, "AI 通话中 · 返回", false, () -> switchTab(0));
            button(activeCall, "挂断", false, () -> { cancelSession("通话已结束"); showApp(); });
        }
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page = column(); page.setPadding(dp(24), dp(12), dp(24), dp(32)); scroll.addView(page);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (tab == 0 && v == scroll) followingConversation = ConversationFeed.shouldFollow(y, page.getHeight(), scroll.getHeight());
        });
        composer = column(); composer.setPadding(dp(20), dp(8), dp(20), dp(8)); root.addView(composer);
        composer.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        View divider = new View(this); divider.setBackgroundColor(SOFT); root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout nav = row(); nav.setPadding(dp(12), dp(5), dp(12), dp(5)); nav.setBackgroundColor(BG); root.addView(nav);
        String[] names = {"对话", "记忆", "工作", "通勤", "我的"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(7), 0, dp(5));
            item.setBackground(ripple(tab == i ? SOFT : BG, 18, tab == i ? SOFT : BG));
            item.setSelected(tab == i); item.setFocusable(true);
            item.setMinimumHeight(dp(60)); nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            IconView icon = new IconView(i, tab == i ? INK : MUTED); item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView name = label(item, names[i], 11, tab == i ? INK : MUTED); name.setGravity(Gravity.CENTER); name.setPadding(0, dp(4), 0, 0);
            item.setContentDescription(names[i]); item.setOnClickListener(v -> switchTab(index));
        }
        if (tab == 0) conversation(); else if (tab == 1) memories(); else if (tab == 2) work(); else if (tab == 3) commute(); else profile();
        if (restoreComposerFocus && chatInput != null) {
            chatInput.requestFocus(); chatInput.setSelection(Math.max(0, Math.min(draftSelection, chatInput.length())));
        }
        updateStatus();
        syncHeadsetControls();
        prepareVoice();
        if (tab == 2 && visible) handler.post(workMeetingPoll);
    }

    private void switchTab(int next) {
        if (tab == next) return;
        clearHeadsetGesture();
        boolean keepCall = sessionUsesCapture && session != null || repeatArmed;
        if (session != null && !keepCall) cancelSession("已结束当前操作");
        if (!keepCall) stopRepeat(); tab = next; if (next == 0) followingConversation = true; hideKeyboard(); showApp();
        enterMaintenance("page_return");
    }

    private void conversation() {
        if (session == null && !repeatArmed) listenMode = "work".equals(mode()) ? VoiceInteraction.NOTES : VoiceInteraction.CALL;
        LinearLayout modes = row(); modes.setPadding(dp(4), dp(4), dp(4), dp(4)); modes.setBackground(shape(CARD, 26, CARD)); page.addView(modes);
        modeChoices = modes;
        String[] keys = {"life", "work", "commute"};
        for (int i = 0; i < keys.length; i++) {
            final String mode = keys[i]; segment(modes, ModeCopy.name(mode), mode.equals(mode()), () -> changeMode(mode));
        }
        LinearLayout hero = card(page); hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(8), dp(18), dp(8), dp(20)); hero.setBackgroundColor(BG);
        TextView eyebrow = label(hero, "你的随身 AI 伙伴", 12, MUTED); eyebrow.setGravity(Gravity.CENTER); eyebrow.setLetterSpacing(.08f);
        TextView hint = label(hero, conversationHeading(), 30, INK);
        modeHint = hint;
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); hint.setGravity(Gravity.CENTER);
        orb = new CompanionOrbView(this); hero.addView(orb, new LinearLayout.LayoutParams(-1, dp(160)));
        gap(hero, 4);
        LinearLayout voiceRow = row(); voiceRow.setGravity(Gravity.CENTER_VERTICAL); hero.addView(voiceRow, new LinearLayout.LayoutParams(-1, -2));
        voiceButton = button(voiceRow, InteractionCopy.start(listenMode), true, this::toggleVoice);
        voiceButton.setTextColor(BG); voiceButton.setBackground(ripple(INK, 28, INK));
        voiceButton.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); voiceButton.setMinHeight(dp(54));
        cancelButton = action(voiceRow, InteractionCopy.stop(listenMode), this::endVoice);
        cancelButton.setTextColor(RED); cancelButton.setBackground(ripple(CARD, 24, CARD));
        cancelButton.setVisibility(session == null && !repeatArmed ? View.GONE : View.VISIBLE);
        callCaption = label(hero, InteractionCopy.ready(listenMode), 12, MUTED); callCaption.setGravity(Gravity.CENTER);
        liveWords = label(hero, "试着说：上次答应客户的事是什么？", 13, MUTED);
        liveWords.setGravity(Gravity.CENTER); liveWords.setMinHeight(dp(48)); liveWords.setSingleLine(true); liveWords.setEllipsize(TextUtils.TruncateAt.END);
        if (session != null) {
            liveWords.setText(recording ? liveTranscript : liveReplyText);
            voiceButton.setText(listenMode == VoiceInteraction.NOTES ? "结束并保存记录" : "结束会话");
        }

        headsetStatus = label(page, "", 12, ACCENT); headsetStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        headsetStatus.setMinHeight(dp(48)); headsetStatus.setBackground(ripple(CARD, 16, CARD));
        headsetStatus.setOnClickListener(v -> showDeviceDetails()); renderHeadset();
        LinearLayout listeningDetails = disclosure(page, "通话设置", InteractionCopy.name(listenMode), false);
        voiceHint = label(listeningDetails, voiceHintText(), 12, MUTED);
        label(listeningDetails, "生活与通勤：单击开始连续问答，停顿后自动回复，文字和音频逐步返回；再次单击结束。工作：单击录音转写，再次单击结束并保存。双击结束当前会话并切换模式，切换后待机。通话中持续收音，说“小万”可打断 AI 播报。", 12, MUTED);
        button(listeningDetails, lockScreenEnabled() ? "停止锁屏耳机会话" : "开启本次锁屏耳机会话", false, () -> {
            if (lockScreenEnabled()) { stopLockScreenSession(); showApp(); }
            else enableLockScreenSession();
        });
        label(listeningDetails, "开启锁屏会话后，AI 通话可在锁屏时继续；通知可随时挂断。通话会持续到挂断；未通话的待命一小时后关闭。关闭 App、来电或断连后需重新开启。定时与记录仅在当前页面运行。", 12, MUTED);
        if (BuildConfig.DEBUG) {
            LinearLayout diagnosticCard = disclosure(listeningDetails, "开发诊断", "", false);
            diagnosticsView = label(diagnosticCard, "", 12, MUTED); diagnosticsView.setTextIsSelectable(true);
            renderDiagnostics(); button(diagnosticCard, "连接设置", false, this::showDevelopmentSettings);
        }
        gap(page, 12);
        if ("work".equals(mode())) {
            conversationTranscript();
            content = column(); page.addView(content);  // Keeps the shared live-turn plumbing valid.
        } else {
        LinearLayout histHead = row(); histHead.setGravity(Gravity.CENTER_VERTICAL); page.addView(histHead);
        TextView historyTitle = label(histHead, "最近对话", 17, INK); histHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (!settings().optBoolean("retain_transcripts", true)) label(histHead, "不保存转写", 10, MUTED);
        content = column(); page.addView(content);
        JSONArray history = array(state, "transcripts");
        if (history.length() == 0 && lastAnswer.isEmpty()) {
            conversationStarters = column(); content.addView(conversationStarters); gap(conversationStarters, 12);
            LinearLayout starters = row(); conversationStarters.addView(starters);
            segment(starters, "聊聊今天", false, () -> fillPrompt("想和你聊聊今天发生的事。"));
            segment(starters, "记住偏好", false, () -> fillPrompt("请帮我记住："));
            segment(starters, "理清思路", false, () -> fillPrompt("有件事，想请你帮我理清思路。"));
        }
        for (int i = Math.max(0, history.length() - 30); i < history.length(); i++) {
            JSONObject line = history.optJSONObject(i); if (line == null) continue;
            chatBubble(content, "assistant".equals(line.optString("speaker")), line.optString("text"), line.optString("created_at"));
            if (!"assistant".equals(line.optString("speaker")) && !line.optString("id").isEmpty()) {
                TextView remember = label(content, "＋ 存为记忆", 11, ACCENT); remember.setMinHeight(dp(40));
                remember.setOnClickListener(v -> { if (canAct()) mutate("POST", "/v1/memories/from-transcript/" + line.optString("id"), json()); });
            }
        }
        if (!settings().optBoolean("retain_transcripts", true))
            for (ConversationFeed.Line line : conversationFeed.unsaved()) chatBubble(content, line.assistant, line.text, line.time);
        }
        renderLiveTurn();
        turnDetails = column(); page.addView(turnDetails); renderTurnDetails();
        LinearLayout inputRow = row(); inputRow.setGravity(Gravity.CENTER_VERTICAL); composer.addView(inputRow);
        chatInput = input("说点什么…", false, 6000); chatInput.setMinLines(1); chatInput.setMaxLines(4); chatInput.setText(chatDraft);
        chatInput.setContentDescription("文字消息");
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));
        TextView send = action(inputRow, "↑", () -> {
            String text = value(chatInput); if (text.isEmpty()) return;
            if (session != null && session.isDuplexCall()) {
                session.sendDuplexText(text); conversationFeed.transcript(text); renderLiveTurn(); hideKeyboard(); chatInput.setText(""); chatDraft = ""; return;
            }
            if (!canAct()) return;
            hideKeyboard(); chatInput.setText(""); chatDraft = ""; startSession(text);
        }); send.setTextSize(25); send.setTextColor(BG); send.setBackground(ripple(ACCENT, 18, ACCENT)); send.setContentDescription("发送文字消息");
        if (session != null) followConversation();
    }

    /**
     * Work mode on the 对话 page: the meeting's text record in the reference layout — a ruled meta
     * block (topic, date and time range, speakers) and speaker paragraphs that grow while recording.
     */
    private void conversationTranscript() {
        boolean live = session != null && sessionUsesCapture && "work".equals(mode());
        JSONArray meetings = array(state, "work_meetings");
        JSONObject latest = meetings.length() > 0 ? meetings.optJSONObject(0) : null;
        boolean showLatest = !live && workCaptionSentences.isEmpty() && latest != null;
        TextView heading = label(page, "文字记录", 20, INK); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        String started = showLatest ? latest.optString("started_at") : workCaptionStartedAt;
        String ended = showLatest ? latest.optString("ended_at") : "";
        String title = showLatest && !latest.isNull("title") ? fallback(latest.optString("title"), "会议记录") : live ? "本次会议（记录中）" : "本次会议";
        java.util.LinkedHashSet<String> speakers = new java.util.LinkedHashSet<>();
        for (SpeakerParagraphs.Sentence sentence : workCaptionSentences) if (!sentence.speaker.isEmpty()) speakers.add(SpeakerParagraphs.name(sentence.speaker));
        LinearLayout meta = metaBlock(page);
        label(meta, "录音主题：" + title, 14, MUTED);
        if (!started.isEmpty()) label(meta, "录音时间：" + TimeFormat.chineseDay(started, zone) + " " + TimeFormat.hourMinute(started, zone)
            + " - " + (live ? "进行中" : fallback(TimeFormat.hourMinute(ended, zone), "…")) + "（" + TimeFormat.gmt(zone) + "）", 14, MUTED);
        if (!showLatest) label(meta, "说话人：" + (speakers.isEmpty() ? (live ? "按声音自动区分中" : "未区分") : String.join("、", speakers)), 14, MUTED);
        if (showLatest) {
            // Show the latest meeting's text record inline; a new recording replaces it with live captions.
            LinearLayout box = column(); page.addView(box);
            String latestId = latest.optString("id");
            if (latestMeetingDetail != null && latestId.equals(latestMeetingDetail.optString("id"))) renderLatestMeeting(box, latestMeetingDetail);
            else {
                label(box, "正在加载最近一场会议的文字记录…", 13, MUTED);
                ApiClient client = api;
                worker.execute(() -> {
                    JSONObject detail = null;
                    try { detail = client.json("GET", "/v1/work-meetings/" + Uri.encode(latestId), null); } catch (Exception ignored) {}
                    JSONObject loaded = detail;
                    runOnUiThread(() -> {
                        if (loaded == null || client != api) { if (box.isAttachedToWindow()) { box.removeAllViews(); label(box, "文字记录暂未加载，下拉刷新或点下面查看。", 13, MUTED); } return; }
                        latestMeetingDetail = loaded;
                        if (box.isAttachedToWindow()) renderLatestMeeting(box, loaded);
                    });
                });
            }
            button(page, "查看本场纪要、章节与回放", false, () -> openWorkMeeting(latestId));
            return;
        }
        conversationCaptionBox = column(); conversationCaptionBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(conversationCaptionBox);
        renderWorkCaption();
        if (!live && !workCaptionMeetingId.isEmpty())
            button(page, "查看本场纪要、章节与回放", false, () -> openWorkMeeting(workCaptionMeetingId));
    }
    private JSONObject latestMeetingDetail;
    private void renderLatestMeeting(LinearLayout box, JSONObject meeting) {
        box.removeAllViews();
        java.util.List<SpeakerParagraphs.Sentence> sentences = new java.util.ArrayList<>();
        for (MeetingTimeline.Entry entry : new MeetingTimeline(meeting).sentences())
            sentences.add(new SpeakerParagraphs.Sentence(entry.id, entry.speaker, entry.startMs, entry.text));
        java.util.List<SpeakerParagraphs.Paragraph> paragraphs = SpeakerParagraphs.build(sentences);
        if (paragraphs.isEmpty()) { label(box, "这场会议没有保存到文字。", 13, MUTED); return; }
        for (SpeakerParagraphs.Paragraph paragraph : paragraphs) {
            StringBuilder text = new StringBuilder();
            for (SpeakerParagraphs.Sentence sentence : paragraph.sentences) text.append(sentence.text);
            transcriptParagraph(box, paragraph.header(), text).setTextIsSelectable(true);
        }
    }
    /** A quiet block with a thin rule on the left, like the reference header. */
    private LinearLayout metaBlock(LinearLayout parent) {
        LinearLayout ruled = row(); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(8));
        parent.addView(ruled, p);
        View rule = new View(this); rule.setBackgroundColor(LINE); ruled.addView(rule, new LinearLayout.LayoutParams(dp(3), -1));
        LinearLayout lines = column(); lines.setPadding(dp(12), 0, 0, 0); ruled.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        return lines;
    }
    private TextView chatBubble(LinearLayout parent, boolean assistant, String text, String time) {
        LinearLayout bubble = card(parent); bubble.setBackground(shape(assistant ? CARD : 0xffeef5ff, 22, assistant ? CARD : 0xffeef5ff));
        LinearLayout.LayoutParams bubbleParams = (LinearLayout.LayoutParams) bubble.getLayoutParams();
        if (assistant) bubbleParams.rightMargin = dp(20); else bubbleParams.leftMargin = dp(24);
        label(bubble, (assistant ? "聆" : "你") + "    " + date(time), 10, assistant ? ACCENT : MUTED);
        TextView copyable = label(bubble, text, 15, INK); copyable.setTextIsSelectable(true);
        if (assistant && !text.isEmpty()) {
            LinearLayout actions = row(); bubble.addView(actions);
            small(actions, "播放", () -> { if (canAct()) { newSession(); sessionIsReplay = true; session.speak(text); } });
            small(actions, "复制", () -> copy("Aidol 回复", text));
        }
        return copyable;
    }

    private void removeLiveTurn() {
        if (liveTurn != null && content != null) content.removeView(liveTurn);
        liveTurn = null; liveUserBubble = null; liveAssistantBubble = null;
    }
    private void clearLiveTurn() { conversationFeed.resetPending(); removeLiveTurn(); }
    private void hideConversationStarters() {
        if (conversationStarters != null && content != null) content.removeView(conversationStarters);
        conversationStarters = null;
    }
    private void renderLiveTurn() {
        if (content == null || tab != 0) return;
        if (conversationFeed.user().isEmpty() && conversationFeed.reply().isEmpty()) { removeLiveTurn(); return; }
        hideConversationStarters();
        if (liveTurn == null) { liveTurn = column(); content.addView(liveTurn); }
        if (!conversationFeed.user().isEmpty()) {
            if (liveUserBubble == null) {
                liveUserBubble = chatBubble(liveTurn, false, "", "识别中");
                View bubble = (View) liveUserBubble.getParent(); liveTurn.removeView(bubble); liveTurn.addView(bubble, 0);
            }
            liveUserBubble.setText(conversationFeed.user());
        } else if (liveUserBubble != null) { liveTurn.removeView((View) liveUserBubble.getParent()); liveUserBubble = null; }
        if (!conversationFeed.reply().isEmpty()) {
            if (liveAssistantBubble == null) liveAssistantBubble = chatBubble(liveTurn, true, "", "回答中");
            liveAssistantBubble.setText(conversationFeed.reply());
        } else if (liveAssistantBubble != null) { liveTurn.removeView((View) liveAssistantBubble.getParent()); liveAssistantBubble = null; }
        followConversation();
    }
    private void followConversation() {
        if (!followingConversation || conversationScrollQueued || content == null || tab != 0) return;
        ScrollView target = scroll; conversationScrollQueued = true;
        target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (scroll == target) {
                    conversationScrollQueued = false;
                    if (tab == 0 && followingConversation) target.scrollTo(0, Math.max(0, page.getHeight() - target.getHeight()));
                }
                return true;
            }
        });
        target.invalidate();
    }

    private void renderTurnDetails() {
        if (turnDetails == null) return;
        turnDetails.removeAllViews();
        if (lastCitations.length() > 0) button(turnDetails, "查看最近回答依据（" + lastCitations.length() + " 条）", false,
            () -> showSources("回答依据", lastCitations));
        if (lastTools.length() > 0) button(turnDetails, "查看本轮查询来源", false, this::showToolResults);
        if (!uncertainTurnId.isEmpty()) button(turnDetails, "查询上次请求处理结果", false, this::recoverTurnResult);
    }

    private void showToolResults() {
        LinearLayout body = dialogBody();
        for (int i = 0; i < lastTools.length(); i++) {
            JSONObject receipt = lastTools.optJSONObject(i);
            if (receipt == null) continue;
            JSONObject result = receipt.optJSONObject("result");
            if (result == null) continue;
            String source = result.optString("source", "查询结果");
            label(body, source.startsWith("amap") ? "高德地图" : source.equals("bocha") ? "博查网页搜索" : "查询结果", 16, INK);
            if (!result.optBoolean("available")) { label(body, result.optString("reason", "本次查询未完成"), 14, MUTED); continue; }
            JSONArray rows = array(result, "results");
            if (rows.length() == 0) {
                String summary = result.optString("address", result.optString("datetime", result.optString("note", "")));
                if (result.has("temperature")) summary = result.optString("city") + " " + result.optString("weather") + " " + result.optString("temperature") + "°C";
                if (result.has("eta_seconds")) summary += "\n预计 " + Math.max(1, result.optInt("eta_seconds") / 60) + " 分钟，" + result.optInt("distance_meters") + " 米";
                if (result.has("report_time")) summary += "\n发布时间：" + result.optString("report_time");
                label(body, summary, 14, INK);
                JSONArray pois = array(result, "pois");
                for (int j = 0; j < pois.length(); j++) { JSONObject poi = pois.optJSONObject(j); if (poi != null) label(body, poi.optString("name") + " · " + poi.optString("address"), 14, INK); }
                continue;
            }
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.optJSONObject(j); if (row == null) continue;
                label(body, row.optString("title"), 15, INK);
                label(body, row.optString("summary") + "\n" + row.optString("published"), 13, MUTED);
                String link = row.optString("url"); Uri uri = Uri.parse(link);
                if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null)
                    button(body, "打开来源网页", false, () -> { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (RuntimeException error) { feedback("未找到可打开网页的应用", LocalCue.BROWSER); } });
            }
        }
        scrollDialog("本轮查询来源", body);
    }

    private void recoverTurnResult() {
        if (!canAct() || uncertainTurnId.isEmpty()) return;
        final String requestId = uncertainTurnId;
        request(() -> api.json("GET", "/v1/turns/" + Uri.encode(requestId), null), result -> {
            if (!requestId.equals(uncertainTurnId)) return;
            JSONObject completed = result.optJSONObject("result");
            if ("completed".equals(result.optString("status")) && completed != null) {
                acceptBusinessResult(completed);
                lastNotice = "已找回上次处理结果；未重新提交录音或自动播放";
                showApp(); refresh();
            } else {
                String message = "failed".equals(result.optString("status"))
                    ? "上次请求处理失败，未取得完整结果。请先核对历史；本次查询没有重放请求。"
                    : "上次请求仍未取得完整结果，可稍后再次查询。请勿重复提交同一事项。";
                new AlertDialog.Builder(this).setTitle("请求处理状态").setMessage(message).setPositiveButton("知道了", null).show();
            }
        });
    }

    private void acceptBusinessResult(JSONObject value) {
        String interaction = value.optString("interaction");
        String resultMode = value.optString("mode");
        if (resultMode.equals("life") || resultMode.equals("work") || resultMode.equals("commute")) {
            JSONObject agent = state.optJSONObject("agent"); if (agent == null) agent = new JSONObject();
            put(agent, "mode", resultMode); put(state, "agent", agent);
            if ("mode_switch".equals(interaction)) {
                put(settings(), "mode", resultMode); put(settings(), "auto_mode", false);
                stopRepeat();
                // A confirmed spoken target wins over an unsubmitted gesture in this same segment.
                deferredMode = null; deferredOpenConversation = false; clearHeadsetGesture();
            }
            renderModeSelection();
        }
        if ("memory_correct".equals(interaction) || "memory_forget".equals(interaction)) clearPrivateSnapshots();
        else if (sourceDialog != null) { sourceDialog.dismiss(); sourceDialog = null; }
        turnHasResult = true; uncertainTurnId = "";
        lastUser = value.optString("text"); lastAnswer = value.optString("response");
        String time = java.time.OffsetDateTime.now().toString();
        boolean retain = settings().optBoolean("retain_transcripts", true);
        List<ConversationFeed.Line> lines = conversationFeed.finish(lastUser, lastAnswer, time, retain);
        removeLiveTurn();
        if (retain && !lastUser.isEmpty()) {
            JSONArray previous = array(state, "transcripts"), recent = new JSONArray();
            for (int i = Math.max(0, previous.length() - 28); i < previous.length(); i++) recent.put(previous.opt(i));
            for (ConversationFeed.Line line : lines) recent.put(json("speaker", line.assistant ? "assistant" : "user", "text", line.text, "created_at", line.time));
            put(state, "transcripts", recent);
        }
        if (liveWords != null && !lastAnswer.isEmpty()) liveWords.setText(lastAnswer);
        lastCitations = array(value, "citations"); lastTools = array(value, "tool_results");
        if (!isDestroyed() && content != null) {
            hideConversationStarters();
            for (ConversationFeed.Line line : lines) chatBubble(content, line.assistant, line.text, line.time);
            followConversation();
        }
        renderTurnDetails();
    }

    /** Invalidate local evidence before refreshing: a failed GET must not restore obsolete text. */
    private void clearPrivateSnapshots() {
        ++workMeetingReadGeneration;
        JSONObject clean = json("settings", settings(), "agent", json("mode", mode()));
        for (String key : new String[]{"user", "capabilities"}) if (state.has(key)) put(clean, key, state.opt(key));
        state = clean;
        conversationFeed.clear(); removeLiveTurn(); liveTranscript = ""; liveReplyText = "";
        lastTools = new JSONArray(); lastCitations = new JSONArray(); lastAnswer = ""; lastUser = ""; exportData = null;
        uncertainTurnId = ""; lastBusinessRequestId = "";
        if (sourceDialog != null) { sourceDialog.dismiss(); sourceDialog = null; }
        if (content != null) content.removeAllViews();
        renderTurnDetails();
    }

    private void memories() {
        pageHeading("记忆");
        LinearLayout search = featureCard(page, 0xfff3eff9);
        eyebrow(search, "RECALL  /  回想", MEMORY);
        label(search, "记得你说过的事", 21, INK);
        label(search, "问一句，找回对话里的细节。", 13, MUTED);
        gap(search, 10);
        EditText question = input("例如：我喜欢什么口味？", false, 500); search.addView(question);
        button(search, "查找记忆", true, () -> {
            if (!canAct() || value(question).isEmpty()) return;
            String q = value(question); hideKeyboard();
            request(() -> api.json("POST", "/v1/recall", json("question", q)), reply -> {
                LinearLayout view = dialogBody(); TextView answer = label(view, reply.optString("answer"), 15, INK); answer.setTextIsSelectable(true);
                JSONArray citations = array(reply, "citations");
                for (int n = 0; n < citations.length(); n++) {
                    JSONObject citation = citations.optJSONObject(n); if (citation != null) label(view, "[" + (n + 1) + "]  " + date(citation.optString("created_at")) + "\n" + citation.optString("text"), 12, MUTED);
                }
                scrollDialog("记忆检索", view);
            });
        }).setBackground(ripple(MEMORY, 16, MEMORY));
        JSONArray allMemories = array(state, "memories"); int pinnedCount = 0;
        for (int i = 0; i < allMemories.length(); i++) if (allMemories.optJSONObject(i) != null && pinned(allMemories.optJSONObject(i))) pinnedCount++;
        sectionHeader(page, "我的记忆", allMemories.length() + " 条 · " + pinnedCount + " 置顶", "＋ 新记忆", () -> { if (canAct()) editMemory(null); });
        LinearLayout filters = row(); filters.setPadding(dp(3), dp(3), dp(3), dp(3)); filters.setBackground(shape(CARD, 16, CARD)); page.addView(filters);
        String[] keys = {"", "preference", "fact", "task", "trip"}, names = {"全部", "偏好", "事实", "待办", "出行"};
        for (int i = 0; i < keys.length; i++) { final String key = keys[i]; segment(filters, names[i], memoryFilter.equals(key), () -> { memoryFilter = key; showApp(); }); }
        JSONArray items = array(state, "memories"); int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject memory = items.optJSONObject(i); if (memory == null || (!memoryFilter.isEmpty() && !memoryFilter.equals(memory.optString("kind")))) continue;
            count++; LinearLayout card = card(page);
            LinearLayout meta = row(); meta.setGravity(Gravity.CENTER_VERTICAL); card.addView(meta);
            badge(meta, kind(memory.optString("kind")), MEMORY, 0xfff3eff9);
            if (pinned(memory)) { TextView pin = label(meta, "  已置顶", 11, MUTED); pin.setPadding(dp(8), 0, 0, 0); }
            meta.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView more = action(meta, "···", () -> {}); more.setTextColor(MUTED); more.setTextSize(22); more.setContentDescription("记忆操作：" + memory.optString("content"));
            more.setOnClickListener(v -> memoryMenu(more, memory));
            label(card, memory.optString("content"), 16, INK).setTextIsSelectable(true);
            gap(card, 8);
            label(card, date(memory.optString("created_at")), 11, MUTED);
        }
        if (count == 0) empty(page, memoryFilter.isEmpty() ? "留住第一件小事" : "这个分类还没有记忆", memoryFilter.isEmpty() ? "一句偏好、一个约定，都值得被记住。\n点击「新记忆」，也可以在对话中说“记住”。" : "试试其他分类，或添加一条新记忆。");
    }

    private void memoryMenu(View anchor, JSONObject memory) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "编辑记忆");
        menu.getMenu().add(0, 1, 1, pinned(memory) ? "取消置顶" : "置顶记忆");
        if (memory.optString("source").startsWith("transcript:")) menu.getMenu().add(0, 2, 2, "查看原文");
        menu.getMenu().add(0, 3, 3, "删除记忆");
        menu.setOnMenuItemClickListener(item -> {
            if (!canAct()) return true;
            switch (item.getItemId()) {
                case 0: editMemory(memory); break;
                case 1: mutate("PUT", "/v1/memories/" + memory.optString("id"), json("content", memory.optString("content"), "kind", memory.optString("kind"), "pinned", !pinned(memory))); break;
                case 2: request(() -> api.json("GET", "/v1/memories/" + memory.optString("id") + "/source", null), r -> { LinearLayout body = dialogBody(); label(body, r.optString("text", r.toString()), 15, INK); scrollDialog("记忆来源", body); }); break;
                case 3: confirm("删除这条记忆？", "删除后无法恢复。", () -> mutate("DELETE", "/v1/memories/" + memory.optString("id"), null)); break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    private void editMemory(JSONObject memory) {
        LinearLayout body = dialogBody(); EditText text = input("想记住什么？", false, 6000); text.setMinLines(4); body.addView(text);
        String[] kinds = {"preference", "fact", "task", "trip"}; String[] names = {"偏好", "事实", "待办", "出行"};
        final String[] choice = {memory == null ? "fact" : memory.optString("kind", "fact")};
        if (memory != null) text.setText(memory.optString("content"));
        label(body, "分类", 12, MUTED); android.widget.Spinner picker = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names); picker.setAdapter(adapter);
        for (int i = 0; i < kinds.length; i++) if (choice[0].equals(kinds[i])) picker.setSelection(i);
        body.addView(picker);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(memory == null ? "添加记忆" : "编辑记忆").setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            if (!canAct()) return; if (value(text).isEmpty()) { text.setError("记忆不能为空"); return; }
            JSONObject payload = json("content", value(text), "kind", kinds[picker.getSelectedItemPosition()], "pinned", memory != null && pinned(memory));
            request(() -> api.json(memory == null ? "POST" : "PUT", "/v1/memories" + (memory == null ? "" : "/" + memory.optString("id")), payload), result -> {
                dialog.dismiss(); if (memory != null) { clearPrivateSnapshots(); showApp(); } refresh();
            });
        })); dialog.show();
    }

    private String workMeetingProgress(JSONObject meeting) {
        String capture = meeting.optString("status"), summary = meeting.optString("summary_status");
        if ("recording".equals(capture)) {
            if (session != null && meeting.optString("id").equals(session.workMeetingId()))
                return session.workSaving() ? "正在保存录音 · 请稍候" : "正在录音 · 结束后识别整场发言人";
            return "录音尚未确认结束 · 可结束后识别已保存录音";
        }
        if ("empty".equals(capture)) return "未识别到讲话 · 没有可整理的原文";
        if ("pending".equals(meeting.optString("transcription_status")))
            return "failed".equals(summary) ? "整场转写未完成 · 可重试" : "录音已保存 · 正在识别整场发言人";
        String prefix = "partial".equals(capture) ? "原文有缺段 · " : "原文已保存 · ";
        if ("pending".equals(summary)) {
            String stage = meeting.optString("summary_stage");
            if ("analyzing".equals(stage)) return prefix + "正在总结 · 阅读整场文稿";
            return prefix + ("synthesizing".equals(stage) ? "正在总结 · 汇总中" : "正在总结 · 等待处理");
        }
        if ("ready".equals(summary)) return prefix + "总结完成";
        if ("fallback".equals(summary)) return prefix + "AI 整理未完成，可重试";
        if ("failed".equals(summary)) return prefix + "整理失败，可重试";
        return prefix + "可查看原文";
    }
    private String meetingPlace(JSONObject meeting) {
        return MeetingPlace.label(meeting);
    }
    private void renderWorkMeetings() {
        if (workMeetingList == null || tab != 2) return;
        String snapshot = array(state, "work_meetings").toString() + workMeetingSyncError;
        if (lastRenderedMeetingList == workMeetingList && lastRenderedMeetingSnapshot.equals(snapshot)) return;
        lastRenderedMeetingList = workMeetingList; lastRenderedMeetingSnapshot = snapshot;
        if (taskBoard != null) taskBoard.refreshReminders();
        Runnable restoreTaskPosition = taskBoard == null ? () -> {} : taskBoard.preserveViewport();
        workMeetingList.removeAllViews();
        if (workMeetingSyncError) label(workMeetingList, "进度暂未同步，正在重试；下面显示上次确认的状态。", 12, MUTED);
        JSONArray meetings = array(state, "work_meetings");
        if (meetings.length() == 0) {
            LinearLayout empty = card(workMeetingList);
            label(empty, "还没有会议记录", 15, INK);
            label(empty, "开始录音，结束后在这里查看本次原文、摘要与处理进度。", 13, MUTED);
        }
        for (int i = 0; i < meetings.length(); i++) {
            JSONObject meeting = meetings.optJSONObject(i); if (meeting == null) continue;
            LinearLayout item = card(workMeetingList);
            String title = meeting.isNull("title") ? "会议记录" : fallback(meeting.optString("title"), "会议记录");
            label(item, title, 17, INK);
            label(item, date(meeting.optString("started_at")) + " · 时长 " + TimeFormat.clock(meeting.optLong("duration_ms"))
                + " · 已保存 " + meeting.optInt("transcript_count") + " 段原文", 11, MUTED);
            label(item, meetingPlace(meeting), 11, MUTED);
            label(item, workMeetingProgress(meeting), 13, WORK);
            String summary = meeting.isNull("summary") ? "" : meeting.optString("summary");
            if (!summary.isEmpty()) label(item, summary.length() > 120 ? summary.substring(0, 120) + "…" : summary, 13, MUTED);
            button(item, "查看原文与纪要", false, () -> openWorkMeeting(meeting.optString("id")));
        }
        restoreTaskPosition.run();
    }
    private void pollWorkMeetings() {
        if (!visible || tab != 2 || isDestroyed() || api.vault.token().isEmpty()) return;
        if (workMeetingPollInFlight) return;
        workMeetingPollInFlight = true;
        ApiClient client = api;
        long readGeneration = workMeetingReadGeneration;
        String detailId = openWorkMeetingId;
        worker.execute(() -> {
            JSONObject list = null, detail = null;
            try {
                list = client.json("GET", "/v1/work-meetings", null);
                if (!detailId.isEmpty()) detail = client.json("GET", "/v1/work-meetings/" + Uri.encode(detailId), null);
            } catch (Exception ignored) { /* Keep confirmed state and retry the read, never replay PCM. */ }
            JSONObject loadedList = list, loadedDetail = detail;
            runOnUiThread(() -> {
                workMeetingPollInFlight = false;
                if (isDestroyed() || client != api || client.vault.token().isEmpty()) return;
                if (readGeneration == workMeetingReadGeneration) {
                    if (loadedList != null) put(state, "work_meetings", array(loadedList, "meetings"));
                    workMeetingSyncError = loadedList == null;
                    renderWorkMeetings();
                    if (loadedDetail != null && detailId.equals(openWorkMeetingId)) renderWorkMeetingDetail(loadedDetail);
                }
                handler.removeCallbacks(workMeetingPoll);
                if (visible && tab == 2) handler.postDelayed(workMeetingPoll, 2500);
            });
        });
    }
    /** Full-screen meeting page: header, section navigation, one scrolling body and a persistent player bar. */
    private void openWorkMeeting(String meetingId) {
        if (sourceDialog != null) { sourceDialog.dismiss(); sourceDialog = null; }
        if (meetingPage != null) meetingPage.dismiss();
        openWorkMeetingId = meetingId; workMeetingDetailSnapshot = "";
        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(4), dp(6), dp(16), dp(2)); shell.addView(header);
        TextView back = label(header, "‹", 30, INK); back.setGravity(Gravity.CENTER);
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); back.setContentDescription("返回会议列表");
        back.setBackground(ripple(BG, 24, BG)); back.setFocusable(true); back.setOnClickListener(v -> { if (meetingPage != null) meetingPage.dismiss(); });
        LinearLayout titles = column(); header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        meetingTitle = label(titles, "会议记录", 20, INK); meetingTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        meetingTitle.setMaxLines(2); meetingTitle.setEllipsize(TextUtils.TruncateAt.END);
        meetingMeta = label(titles, "", 12, MUTED);
        meetingMaintenanceStatus = label(titles, "", 10, MUTED); renderMaintenanceStatus();
        TextView save = label(header, "保存", 14, ACCENT); save.setGravity(Gravity.CENTER); save.setMinHeight(dp(48));
        save.setPadding(dp(12), 0, dp(4), 0); save.setBackground(ripple(BG, 12, BG)); save.setFocusable(true);
        save.setContentDescription("把本场会议纪要和原文保存为文件"); save.setOnClickListener(v -> saveMeetingFile());
        android.widget.HorizontalScrollView navScroll = new android.widget.HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        meetingNav = row(); meetingNav.setPadding(dp(16), dp(4), dp(16), dp(8)); navScroll.addView(meetingNav); shell.addView(navScroll);
        meetingScroll = new ScrollView(this);
        workMeetingDetail = column(); workMeetingDetail.setPadding(dp(20), dp(4), dp(20), dp(24));
        meetingScroll.addView(workMeetingDetail); shell.addView(meetingScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        label(workMeetingDetail, "正在加载会议…", 14, MUTED);
        workMeetingPlayer = column(); workMeetingPlayer.setPadding(dp(16), dp(8), dp(16), dp(10));
        workMeetingPlayer.setBackground(shape(CARD, 0, LINE)); shell.addView(workMeetingPlayer);
        meetingPage = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        meetingPage.setContentView(shell);
        android.app.Dialog page = meetingPage;
        meetingPage.setOnDismissListener(d -> {
            if (meetingPage != page) return;
            meetingPage = null; meetingScroll = null; meetingNav = null; meetingTitle = null; meetingMeta = null; meetingSeek = null;
            openWorkMeetingId = ""; workMeetingDetail = null; workMeetingAudioText = "";
            workMeetingPlayer = null; workMeetingPlayerStatus = null; workMeetingTimeline = null; workMeetingLines.clear(); workMeetingPlayingId = "";
            meetingSections.clear(); meetingPlayback.stop(); workMeetingOpen = null;
            meetingMaintenanceStatus = null; enterMaintenance("page_return");
        });
        meetingPage.show();
        enterMaintenance("page_return");
        handler.removeCallbacks(workMeetingPoll); handler.post(workMeetingPoll);
    }
    private void renderWorkMeetingDetail(JSONObject meeting) {
        if (workMeetingDetail == null || !meeting.optString("id").equals(openWorkMeetingId)) return;
        JSONObject report = meeting.optJSONObject("report");
        JSONArray sources = array(meeting, "sources");
        MeetingTimeline timeline = new MeetingTimeline(meeting);
        boolean playable = MeetingTimeline.audioPlayable(meeting);
        if (meetingTitle != null) meetingTitle.setText(report == null ? "本次会议" : fallback(report.optString("title"), "本次会议"));
        workMeetingOpen = meeting;
        if (meetingMeta != null) {
            String started = meeting.optString("audio_started_at", meeting.optString("started_at"));
            String ended = TimeFormat.clockOf(meeting.optString("ended_at"));
            java.util.List<String> speakers = timeline.speakers();
            meetingMeta.setText(TimeFormat.dayOf(started) + " · " + TimeFormat.clockOf(started) + (ended.isEmpty() ? "" : "–" + ended)
                + " · 时长 " + TimeFormat.clock(timeline.audioEndMs()) + "\n"
                + (speakers.isEmpty() ? "未区分说话人" : speakers.size() + " 位说话人") + " · " + timeline.sentences().size() + " 句 · "
                + workMeetingProgress(meeting));
        }
        String snapshot = json("sources", sources, "report", report, "summary_status", meeting.optString("summary_status"),
            "status", meeting.optString("status"), "error", meeting.optString("error"), "utterances", array(meeting, "utterances"),
            "events", array(meeting, "events"), "audio", meeting.optJSONObject("audio")).toString();
        if (snapshot.equals(workMeetingDetailSnapshot)) return;
        workMeetingDetailSnapshot = snapshot; workMeetingDetail.removeAllViews(); meetingSections.clear();
        workMeetingTimeline = timeline; workMeetingLines.clear(); workMeetingRanges.clear(); workMeetingPlayingId = "";
        playingMark[0] = null; jumpMark[0] = null;
        if (!playable && meeting.optString("id").equals(meetingPlayback.meetingId())) meetingPlayback.stop();
        workMeetingAudioText = meetingAudioText(meeting); workMeetingAudioPlayable = playable;
        if (!meeting.optString("error").isEmpty()) label(workMeetingDetail, meeting.optString("error"), 13, MUTED);

        // Reference header: topic, date and time range with zone, speakers and length, in a ruled block.
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        String recordedFrom = meeting.optString("audio_started_at", meeting.optString("started_at"));
        java.util.List<String> speakerNames = new java.util.ArrayList<>();
        for (String label : timeline.speakers()) speakerNames.add(SpeakerParagraphs.name(label));
        LinearLayout meta = metaBlock(workMeetingDetail);
        label(meta, "录音主题：" + (report == null ? "本次会议" : fallback(report.optString("title"), "本次会议")), 14, MUTED);
        label(meta, "录音时间：" + TimeFormat.chineseDay(recordedFrom, zone) + " " + TimeFormat.hourMinute(recordedFrom, zone) + " - "
            + ("recording".equals(meeting.optString("status")) ? "进行中" : fallback(TimeFormat.hourMinute(meeting.optString("ended_at"), zone), "…"))
            + "（" + TimeFormat.gmt(zone) + "）", 14, MUTED);
        label(meta, "录音时长：" + TimeFormat.hms(timeline.audioEndMs()) + " · " + timeline.sentences().size() + " 句", 14, MUTED);
        label(meta, "说话人：" + (speakerNames.isEmpty() ? "未区分" : String.join("、", speakerNames)), 14, MUTED);
        label(meta, meetingPlace(meeting), 14, MUTED);
        meetingSection("总结", "总结");
        if (report == null) label(workMeetingDetail, workMeetingProgress(meeting) + "。原文可先在下方查看，AI 整理完成后自动更新。", 14, MUTED);
        else {
            label(workMeetingDetail, report.optString("summary"), 15, INK).setTextIsSelectable(true);
            JSONArray topics = array(report, "topics");
            for (int i = 0; i < topics.length(); i++) {
                JSONObject topic = topics.optJSONObject(i); if (topic == null) continue;
                label(workMeetingDetail, "• " + topic.optString("text"), 14, INK).setTextIsSelectable(true);
                meetingEvidence(meeting, timeline, array(topic, "source_ids"));
            }
            label(workMeetingDetail, report.optString("notice", "AI 整理稿，请结合原文核对。")
                + " 摘要已自动保存在本场记录中；点右上角“保存”可连同原文存为文件。", 11, MUTED);
        }
        if (report != null) {
            meetingSection("章节", "智能章节");
            JSONArray chapters = array(report, "chapters");
            if (!report.has("chapters")) label(workMeetingDetail, "这份纪要生成时还没有章节功能，点页面底部“修正并重新生成纪要”即可补上。", 13, MUTED);
            else if (chapters.length() == 0) label(workMeetingDetail, "内容较短，未划分章节。", 13, MUTED);
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject chapter = chapters.optJSONObject(i); if (chapter == null) continue;
                MeetingTimeline.Entry first = firstCited(timeline, array(chapter, "source_ids"));
                long at = chapter.isNull("start_ms") || !chapter.has("start_ms") ? (first == null ? 0 : first.startMs) : chapter.optLong("start_ms");
                LinearLayout card = card(workMeetingDetail); card.setPadding(dp(14), dp(10), dp(14), dp(10));
                LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL); card.addView(top);
                boolean timedChapter = first != null || chapter.has("start_ms") && !chapter.isNull("start_ms");
                badge(top, timedChapter ? TimeFormat.clock(at) : "用户补充", WORK, 0xffeef3fc);
                TextView heading = label(top, chapter.optString("title"), 15, INK); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                if (!chapter.optString("summary").isEmpty()) label(card, chapter.optString("summary"), 13, MUTED);
                String target = first == null ? null : first.id;
                card.setFocusable(timedChapter); card.setContentDescription("章节 " + (timedChapter ? TimeFormat.clock(at) : "用户补充") + "，" + chapter.optString("title"));
                if (timedChapter) card.setOnClickListener(v -> seekOrJump(meeting, timeline, at, target));
            }
            meetingSection("观点", "各人物观点");
            meetingItems(workMeetingDetail, meetingAnalysisItems(report, "viewpoints"), meeting, timeline, false);
            meetingSection("冲突", "内容冲突与澄清");
            meetingItems(workMeetingDetail, array(report, "conflicts"), meeting, timeline, false);
            meetingSection("事件", "重要事件");
            meetingItems(workMeetingDetail, meetingAnalysisItems(report, "events"), meeting, timeline, false);
            meetingSection("决策", "关键决策");
            meetingItems(workMeetingDetail, array(report, "decisions"), meeting, timeline, false);
            meetingSection("待办", "行动待办");
            meetingItems(workMeetingDetail, array(report, "tasks"), meeting, timeline, true);
        }
        meetingSection("重点", "重点");
        meetingMarks(meeting, timeline, report == null ? new JSONArray() : array(report, "highlights"));
        if (report != null && array(report, "open_questions").length() > 0) {
            meetingSection("待确认", "待确认事项");
            meetingItems(workMeetingDetail, array(report, "open_questions"), meeting, timeline, false);
        }

        meetingSection("原文", "文字记录");
        label(workMeetingDetail, "语音识别原文，未经 AI 改写；说话人按声音自动区分，不代表真实身份。"
            + (playable ? "点任意一句从那里播放。" : ""), 11, MUTED);
        if (timeline.sentences().isEmpty()) label(workMeetingDetail, "暂无已保存原文；录音转写后会陆续显示。", 14, MUTED);
        StringBuilder raw = new StringBuilder();
        java.util.List<SpeakerParagraphs.Sentence> run = new java.util.ArrayList<>();
        for (MeetingTimeline.Entry entry : timeline.entries) {
            if (entry.kind == MeetingTimeline.Entry.Kind.SENTENCE) {
                run.add(new SpeakerParagraphs.Sentence(entry.id, entry.speaker, entry.startMs, entry.text));
                String wall = TimeFormat.clockOf(entry.wallTime);
                if (raw.length() > 0) raw.append("\n");
                raw.append("[").append(wall.isEmpty() ? TimeFormat.hms(entry.startMs) : wall).append("] ")
                    .append(entry.speaker.isEmpty() ? "" : SpeakerParagraphs.name(entry.speaker) + "：").append(entry.text);
                continue;
            }
            meetingParagraphs(run, meeting, timeline, playable); run.clear();
            switch (entry.kind) {
                case PAUSE: label(workMeetingDetail, "‖ 暂停 " + TimeFormat.hms(entry.startMs) + " · " + date(entry.wallTime), 12, MUTED); break;
                case RESUME: label(workMeetingDetail, "▶ 继续 · " + date(entry.wallTime), 12, MUTED); break;
                default: label(workMeetingDetail, "★ 重点标记 " + TimeFormat.hms(entry.startMs), 12, WORK); break;
            }
        }
        meetingParagraphs(run, meeting, timeline, playable);
        gap(workMeetingDetail, 12);
        if (raw.length() > 0) {
            button(workMeetingDetail, "保存为文件（纪要 + 原文）", true, this::saveMeetingFile);
            button(workMeetingDetail, "复制本次会议原文", false, () -> copy("会议原文", raw.toString()));
        }
        String summaryStatus = meeting.optString("summary_status");
        if ("recording".equals(meeting.optString("status")) && (session == null || !openWorkMeetingId.equals(session.workMeetingId()))) {
            label(workMeetingDetail, "若录音已经中断，可结束本场并整理已上传的片段；未上传的音频无法恢复。", 12, MUTED);
            button(workMeetingDetail, "结束本场，整理已保存片段", false, () -> {
                if (!canAct()) return;
                request(() -> api.json("POST", "/v1/work-meetings/" + Uri.encode(meeting.optString("id")) + "/finish",
                    json("segment_count", meeting.optInt("received_segments"), "complete", false)), r -> {
                    workMeetingDetailSnapshot = ""; renderWorkMeetingDetail(r);
                    handler.removeCallbacks(workMeetingPoll); handler.post(workMeetingPoll);
                });
            });
        }
        JSONObject revision = meeting.optJSONObject("revision_request");
        if (revision != null && "failed".equals(revision.optString("status")))
            label(workMeetingDetail, "上次修正分析未完成，编辑稿和上一版纪要均已保留。请点下方按钮继续编辑并重试。", 13, 0xffbb5a54);
        if (!"recording".equals(meeting.optString("status")) && !"pending".equals(summaryStatus)) {
            if (report != null) button(workMeetingDetail, "修正并重新生成纪要", false, () -> {
                if (!canAct()) return;
                String id = meeting.optString("id");
                request(() -> api.json("GET", "/v1/work-meetings/" + Uri.encode(id) + "/revision", null), draft ->
                    new MeetingRevisionEditor(this, api, id, draft, result -> {
                        workMeetingDetailSnapshot = ""; renderWorkMeetingDetail(result);
                        handler.removeCallbacks(workMeetingPoll); handler.post(workMeetingPoll);
                        taskBoard.refreshReminders();
                    }).show());
            });
            else if (sources.length() > 0 || "pending".equals(meeting.optString("transcription_status")) || "failed".equals(meeting.optString("transcription_status")))
                button(workMeetingDetail, "重试原文识别与整理", false, () -> {
                    if (!canAct()) return;
                    request(() -> api.json("POST", "/v1/work-meetings/" + Uri.encode(meeting.optString("id")) + "/retry-summary", json()), result -> {
                        workMeetingDetailSnapshot = ""; renderWorkMeetingDetail(result); handler.post(workMeetingPoll);
                    });
                });
        }
        JSONObject audio = meeting.optJSONObject("audio");
        String audioStatus = audio == null ? "" : audio.optString("status");
        if (("saved".equals(audioStatus) || "partial".equals(audioStatus)) && !"recording".equals(meeting.optString("status")))
            small(workMeetingDetail, "删除本场录音（保留文字）", () -> confirm("删除本场录音", "录音删除后无法恢复，文字原文与纪要会保留。", () -> {
                if (meeting.optString("id").equals(meetingPlayback.meetingId())) meetingPlayback.stop();
                request(() -> api.json("DELETE", "/v1/work-meetings/" + Uri.encode(meeting.optString("id")) + "/audio", null), r -> {
                    workMeetingDetailSnapshot = ""; renderWorkMeetingDetail(r);
                });
            }));
        renderMeetingPlayer(meeting.optString("id"));
        updateMeetingHighlight();
        if (!workMeetingJumpId.isEmpty()) { String jump = workMeetingJumpId; workMeetingJumpId = ""; scrollToSentence(jump); }
    }
    private JSONArray meetingAnalysisItems(JSONObject report, String key) {
        JSONArray items = array(report, key), display = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String text;
            if ("viewpoints".equals(key)) {
                text = fallback(item.optString("person"), "身份未明确的发言") + "\n" + item.optString("text");
            } else {
                text = item.optString("content") + "\n时间：" + fallback(item.optString("time"), "未明确")
                    + "\n地点：" + fallback(item.optString("location"), "未明确")
                    + "\n相关任务：" + fallback(item.optString("task"), "未明确");
            }
            display.put(json("text", text, "source_ids", array(item, "source_ids")));
        }
        return display;
    }
    /** Speaker paragraphs whose sentences stay individually tappable (play from there) and highlightable. */
    private void meetingParagraphs(java.util.List<SpeakerParagraphs.Sentence> sentences, JSONObject meeting, MeetingTimeline timeline, boolean playable) {
        for (SpeakerParagraphs.Paragraph paragraph : SpeakerParagraphs.build(sentences)) {
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
            TextView body = transcriptParagraph(workMeetingDetail, paragraph.header(), "");
            for (SpeakerParagraphs.Sentence sentence : paragraph.sentences) {
                int start = text.length(); text.append(sentence.text);
                workMeetingLines.put(sentence.id, body); workMeetingRanges.put(sentence.id, new int[]{start, text.length()});
                if (!playable) continue;
                long at = sentence.startMs; String id = sentence.id;
                text.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(View widget) { seekOrJump(meeting, timeline, at, id); }
                    @Override public void updateDrawState(android.text.TextPaint paint) { paint.setColor(INK); paint.setUnderlineText(false); }
                }, start, text.length(), 0);
            }
            body.setText(text, TextView.BufferType.SPANNABLE);
            if (playable) { body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance()); body.setHighlightColor(Color.TRANSPARENT); }
            else body.setTextIsSelectable(true);
        }
    }
    /** Background on one sentence inside its paragraph; null colour clears it. */
    private void markSentence(String id, Integer colour, Object[] holder) {
        TextView view = workMeetingLines.get(id); int[] range = workMeetingRanges.get(id);
        if (view == null || range == null || !(view.getText() instanceof android.text.Spannable)) return;
        android.text.Spannable text = (android.text.Spannable) view.getText();
        if (holder[0] != null) text.removeSpan(holder[0]);
        holder[0] = null;
        if (colour == null || range[1] > text.length()) return;
        holder[0] = new android.text.style.BackgroundColorSpan(colour);
        text.setSpan(holder[0], range[0], range[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    /** Pixel offset of a sentence within the scrolling body. */
    private int sentenceTop(String id) {
        TextView view = workMeetingLines.get(id); int[] range = workMeetingRanges.get(id);
        if (view == null) return -1;
        android.text.Layout layout = view.getLayout();
        int line = layout == null || range == null ? 0 : layout.getLineForOffset(Math.min(range[0], Math.max(0, view.getText().length() - 1)));
        return view.getTop() + (layout == null ? 0 : layout.getLineTop(line));
    }
    /** A section heading that the navigation row scrolls to. */
    private void meetingSection(String nav, String title) {
        TextView heading = label(workMeetingDetail, title, 18, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); heading.setPadding(0, dp(18), 0, dp(4));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        meetingSections.put(nav, heading);
        if (meetingNav == null) return;
        if (meetingSections.size() == 1) meetingNav.removeAllViews();
        TextView chip = label(meetingNav, nav, 13, INK); chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8)); chip.setMinHeight(dp(40)); chip.setBackground(ripple(CARD, 18, CARD));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8); chip.setLayoutParams(p);
        chip.setFocusable(true); chip.setContentDescription("跳到" + title);
        chip.setOnClickListener(v -> { if (meetingScroll != null) meetingScroll.smoothScrollTo(0, Math.max(0, heading.getTop() - dp(4))); });
    }
    private static MeetingTimeline.Entry firstCited(MeetingTimeline timeline, JSONArray ids) {
        MeetingTimeline.Entry first = null;
        for (int k = 0; k < ids.length(); k++) {
            MeetingTimeline.Entry cited = timeline.sentence(ids.optString(k));
            if (cited != null && (first == null || cited.startMs < first.startMs)) first = cited;
        }
        return first;
    }
    private void meetingItems(LinearLayout parent, JSONArray items, JSONObject meeting, MeetingTimeline timeline, boolean tasks) {
        if (items.length() == 0) label(parent, "暂无明确记录", 13, MUTED);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            label(parent, (tasks ? "☐ " : "• ") + item.optString(tasks ? "content" : "text"), 15, INK).setTextIsSelectable(true);
            if (tasks) label(parent, "负责人：" + fallback(item.optString("owner"), "未明确") + " · 截止：" + fallback(item.optString("due"), "未提及"), 12, MUTED);
            meetingEvidence(meeting, timeline, array(item, "source_ids"));
        }
    }
    /** Compact "依据" links: the cited sentence's time and opening words; tap to jump (and play when saved). */
    private void meetingEvidence(JSONObject meeting, MeetingTimeline timeline, JSONArray ids) {
        for (int k = 0; k < ids.length() && k < 3; k++) {
            if (ids.optString(k).startsWith("user-edit:")) { label(workMeetingDetail, "↳ 依据：用户修正（未改动录音原文）", 12, ACCENT); continue; }
            MeetingTimeline.Entry cited = timeline.sentence(ids.optString(k));
            if (cited == null) continue;
            String excerpt = cited.text.length() > 28 ? cited.text.substring(0, 28) + "…" : cited.text;
            TextView link = label(workMeetingDetail, "↳ 依据 " + TimeFormat.clock(cited.startMs) + " · " + excerpt, 12, ACCENT);
            link.setMinHeight(dp(40)); link.setPadding(dp(12), dp(2), 0, dp(2)); link.setFocusable(true);
            link.setContentDescription("跳到原文 " + TimeFormat.clock(cited.startMs) + "，" + cited.text);
            link.setOnClickListener(v -> seekOrJump(meeting, timeline, cited.startMs, cited.id));
        }
    }
    private void meetingMarks(JSONObject meeting, MeetingTimeline timeline, JSONArray highlights) {
        if (timeline.marks().isEmpty() && highlights.length() == 0) {
            label(workMeetingDetail, "录音时点“标记重点”或在耳机上双击，这里会列出标记时刻；AI 也会摘出关键原话。", 13, MUTED); return;
        }
        for (MeetingTimeline.Entry mark : timeline.marks()) {
            MeetingTimeline.Entry at = timeline.sentenceAt(mark.startMs);
            TextView line = label(workMeetingDetail, "★ 我的标记 " + TimeFormat.clock(mark.startMs) + (at == null ? " · 该时刻没有识别到讲话" : " · " + at.text), 14, at == null ? MUTED : INK);
            if (at == null) continue;
            line.setMinHeight(dp(44)); line.setFocusable(true); line.setBackground(ripple(BG, 8, BG));
            // Start at the sentence being spoken when marked, so the moment is heard in context.
            long start = Math.min(mark.startMs, at.startMs);
            line.setOnClickListener(v -> seekOrJump(meeting, timeline, start, at.id));
        }
        for (int i = 0; i < highlights.length(); i++) {
            JSONObject quote = highlights.optJSONObject(i); if (quote == null) continue;
            MeetingTimeline.Entry cited = firstCited(timeline, array(quote, "source_ids"));
            TextView line = label(workMeetingDetail, "“" + quote.optString("text") + "”" + (cited == null ? "" : "  " + TimeFormat.clock(cited.startMs)), 14, INK);
            line.setPadding(dp(10), dp(6), 0, dp(6));
            if (cited == null) continue;
            line.setMinHeight(dp(44)); line.setFocusable(true); line.setBackground(ripple(BG, 8, BG));
            line.setOnClickListener(v -> seekOrJump(meeting, timeline, cited.startMs, cited.id));
        }
    }
    /** Every timed link: scroll to its sentence, and play from that moment when the recording is saved. */
    private void seekOrJump(JSONObject meeting, MeetingTimeline timeline, long startMs, String sentenceId) {
        if (sentenceId != null) scrollToSentence(sentenceId);
        if (MeetingTimeline.audioPlayable(meeting)) playMeetingAudio(meeting.optString("id"), startMs, timeline);
    }
    private void jumpToSentence(JSONObject meeting, String sentenceId) { scrollToSentence(sentenceId); }
    private void scrollToSentence(String sentenceId) {
        TextView target = workMeetingLines.get(sentenceId);
        if (target == null || meetingScroll == null) return;
        target.post(() -> {
            if (meetingScroll == null) return;
            meetingScroll.smoothScrollTo(0, Math.max(0, sentenceTop(sentenceId) - dp(48)));
            if (sentenceId.equals(workMeetingPlayingId)) return;
            markSentence(sentenceId, 0xffeef3fc, jumpMark);
            target.postDelayed(() -> markSentence(sentenceId, null, jumpMark), 1600);
        });
    }
    private String meetingAudioText(JSONObject meeting) {
        JSONObject audio = meeting.optJSONObject("audio"); if (audio == null) return "";
        String status = audio.optString("status");
        return "saved".equals(status) || "partial".equals(status)
            ? ("partial".equals(status) ? "录音部分已保存" : "录音已保存") + " · " + String.format(java.util.Locale.ROOT, "%.1f MB", audio.optLong("bytes") / 1048576.0)
                + (audio.isNull("expires_at") ? " · 结束后开始计算保存期限" : " · 保存至 " + date(audio.optString("expires_at")))
            : "expired".equals(status) ? "录音已过保存期限并删除；文字原文保留" : "deleted".equals(status) ? "录音已删除；文字原文保留"
            : "recording".equals(meeting.optString("status")) ? "录音进行中，结束后可回放" : "未保存录音（保存期限设为不保存，或录音保存失败）";
    }
    /** Meeting audio plays only on the verified headset and never alongside a capture or call. */
    private boolean canPlayMeetingAudio() {
        if (session != null || repeatArmed || callStartPending) {
            feedback("录音或通话进行中，结束后再播放会议录音", null); return false;
        }
        HeadsetController.Snapshot snapshot = headset.snapshot();
        if (!snapshot.ready()) { feedback("请先连接耳机；会议录音只在已核验的耳机中播放", null); return false; }
        if (snapshot.inCall || getSystemService(android.media.AudioManager.class).getMode() != android.media.AudioManager.MODE_NORMAL) {
            feedback("手机通话结束后再播放会议录音", null); return false;
        }
        return true;
    }
    private void playMeetingAudio(String meetingId, long startMs, MeetingTimeline timeline) {
        if (!canAct() || !canPlayMeetingAudio()) return;
        if (quickFeedback != null) quickFeedback.stop();
        meetingPlayback.play(api, meetingId, startMs, timeline.audioEndMs());
    }
    /** The persistent bottom bar: play/pause, position, seek and stop; audio status when nothing can play. */
    private void renderMeetingPlayer(String meetingId) {
        LinearLayout bar = workMeetingPlayer; if (bar == null) return;
        bar.removeAllViews(); workMeetingPlayerStatus = null; meetingSeek = null;
        MeetingTimeline timeline = workMeetingTimeline;
        if (timeline == null || !workMeetingAudioPlayable) { label(bar, workMeetingAudioText.isEmpty() ? "正在加载录音信息…" : workMeetingAudioText, 12, MUTED); return; }
        boolean ours = meetingId.equals(meetingPlayback.meetingId());
        MeetingPlayback.State playback = ours ? meetingPlayback.state() : MeetingPlayback.State.IDLE;
        long end = timeline.audioEndMs();
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER_VERTICAL); bar.addView(controls);
        boolean running = playback == MeetingPlayback.State.PLAYING || playback == MeetingPlayback.State.LOADING;
        TextView toggle = label(controls, running ? "❚❚" : "▶", 18, BG); toggle.setGravity(Gravity.CENTER);
        toggle.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); toggle.setBackground(ripple(WORK, 24, WORK));
        toggle.setFocusable(true); toggle.setContentDescription(running ? "暂停播放" : "播放会议录音");
        toggle.setOnClickListener(v -> {
            MeetingPlayback.State now = meetingId.equals(meetingPlayback.meetingId()) ? meetingPlayback.state() : MeetingPlayback.State.IDLE;
            if (now == MeetingPlayback.State.PLAYING || now == MeetingPlayback.State.LOADING) meetingPlayback.pause();
            else if (now == MeetingPlayback.State.PAUSED) { if (canAct() && canPlayMeetingAudio()) meetingPlayback.resume(api); }
            else playMeetingAudio(meetingId, 0, timeline);
        });
        LinearLayout middle = column(); middle.setPadding(dp(10), 0, dp(6), 0); controls.addView(middle, new LinearLayout.LayoutParams(0, -2, 1));
        String error = ours && playback == MeetingPlayback.State.IDLE ? meetingPlayback.error() : "";
        workMeetingPlayerStatus = label(middle, playback == MeetingPlayback.State.IDLE
            ? (error.isEmpty() ? "0:00 / " + TimeFormat.clock(end) + " · 点任意一句可从那里播放" : "播放已停止：" + error)
            : meetingPlayerText() + " / " + TimeFormat.clock(end), 12, error.isEmpty() ? MUTED : RED);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(1, end)));
        seek.setProgress((int) Math.min(end, ours ? meetingPlayback.positionMs() : 0));
        seek.setContentDescription("播放进度");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int progress, boolean user) {
                if (user && workMeetingPlayerStatus != null) workMeetingPlayerStatus.setText("跳到 " + TimeFormat.clock(progress) + " / " + TimeFormat.clock(end));
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) { meetingSeeking = true; }
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                meetingSeeking = false;
                MeetingTimeline.Entry at = timeline.sentenceAt(s.getProgress());
                if (at != null) scrollToSentence(at.id);
                playMeetingAudio(meetingId, s.getProgress(), timeline);
            }
        });
        middle.addView(seek, new LinearLayout.LayoutParams(-1, dp(32)));
        meetingSeek = seek;
        if (playback != MeetingPlayback.State.IDLE) {
            TextView stop = label(controls, "■", 16, INK); stop.setGravity(Gravity.CENTER);
            stop.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48))); stop.setFocusable(true); stop.setContentDescription("停止播放");
            stop.setBackground(ripple(CARD, 22, CARD)); stop.setOnClickListener(v -> meetingPlayback.stop());
        }
    }
    private String meetingPlayerText() {
        String at = TimeFormat.clock(meetingPlayback.positionMs());
        String notice = meetingPlayback.notice().isEmpty() ? "" : " · " + meetingPlayback.notice();
        switch (meetingPlayback.state()) {
            case LOADING: return "正在连接耳机 " + at + notice;
            case PLAYING: return "正在播放 " + at + notice;
            case PAUSED: return "已暂停 " + at;
            default: return "";
        }
    }
    private void meetingPlaybackChanged() {
        handler.removeCallbacks(workMeetingPlaybackTick);
        // Rebuild only the control bar; the transcript keeps its scroll position.
        renderMeetingPlayer(openWorkMeetingId);
        updateMeetingHighlight();
        if (meetingPlayback.active()) handler.postDelayed(workMeetingPlaybackTick, 250);
    }
    private void tickMeetingPlayback() {
        MeetingTimeline timeline = workMeetingTimeline;
        if (workMeetingPlayerStatus != null && !meetingSeeking && timeline != null)
            workMeetingPlayerStatus.setText(meetingPlayerText() + " / " + TimeFormat.clock(timeline.audioEndMs()));
        if (meetingSeek != null && !meetingSeeking && meetingPlayback.meetingId().equals(openWorkMeetingId))
            meetingSeek.setProgress((int) Math.min(meetingSeek.getMax(), meetingPlayback.positionMs()));
        updateMeetingHighlight();
        if (meetingPlayback.active()) handler.postDelayed(workMeetingPlaybackTick, 250);
    }
    private void updateMeetingHighlight() {
        MeetingTimeline timeline = workMeetingTimeline;
        String id = "";
        if (timeline != null && meetingPlayback.state() != MeetingPlayback.State.IDLE && meetingPlayback.meetingId().equals(openWorkMeetingId)) {
            MeetingTimeline.Entry playing = timeline.sentencePlaying(meetingPlayback.positionMs());
            if (playing != null) id = playing.id;
        }
        if (id.equals(workMeetingPlayingId)) return;
        String previous = workMeetingPlayingId;
        markSentence(previous, null, playingMark);
        workMeetingPlayingId = id;
        if (id.isEmpty()) return;
        markSentence(id, 0xffdde8fb, playingMark);
        // Follow the voice only while the reader is following it (the previous sentence was on screen).
        if (meetingScroll != null && !previous.isEmpty() && meetingPlayback.state() == MeetingPlayback.State.PLAYING) {
            int view = meetingScroll.getScrollY(), height = meetingScroll.getHeight();
            int before = sentenceTop(previous), now = sentenceTop(id);
            boolean following = before >= view && before <= view + height;
            if (following && now > view + height - dp(96)) meetingScroll.smoothScrollTo(0, Math.max(0, now - dp(96)));
        }
    }

    /** Live captions in the reference layout, on the work page (recent part) and the 对话 page (all). */
    private void renderWorkCaption() {
        if (workCaptionBox != null) {
            renderCaptions(workCaptionBox, 80);
            if (workCaptionBox.getParent() instanceof ScrollView) {
                ScrollView inner = (ScrollView) workCaptionBox.getParent();
                inner.post(() -> inner.fullScroll(View.FOCUS_DOWN));
            }
        }
        if (conversationCaptionBox != null) { renderCaptions(conversationCaptionBox, Integer.MAX_VALUE); followConversation(); }
    }
    private void renderCaptions(LinearLayout box, int maxSentences) {
        box.removeAllViews();
        java.util.List<SpeakerParagraphs.Sentence> recent = workCaptionSentences.subList(
            Math.max(0, workCaptionSentences.size() - maxSentences), workCaptionSentences.size());
        java.util.List<SpeakerParagraphs.Paragraph> paragraphs = SpeakerParagraphs.build(recent);
        if (paragraphs.isEmpty() && workCaptionPartial.isEmpty()) {
            label(box, session != null && sessionUsesCapture && "work".equals(mode())
                ? "正在聆听，开始说话后这里会实时显示说话人和文字…" : "开始会议记录后，这里会实时显示“说话人 1 / 说话人 2”、时间和文字。", 14, MUTED);
            return;
        }
        TextView last = null;
        for (SpeakerParagraphs.Paragraph paragraph : paragraphs) {
            StringBuilder text = new StringBuilder();
            for (SpeakerParagraphs.Sentence sentence : paragraph.sentences) text.append(sentence.text);
            last = transcriptParagraph(box, paragraph.header(), text);
        }
        if (!workCaptionPartial.isEmpty()) {
            // Interim words stay grey at the end of the last paragraph until the server confirms them.
            if (last == null) last = transcriptParagraph(box, "正在识别", new StringBuilder());
            android.text.SpannableStringBuilder grey = new android.text.SpannableStringBuilder(last.getText());
            int start = grey.length(); grey.append(workCaptionPartial);
            grey.setSpan(new android.text.style.ForegroundColorSpan(MUTED), start, grey.length(), 0);
            last.setText(grey);
        }
    }
    /** Reference layout: small grey "说话人 1  00:00:41" header above a readable paragraph. */
    private TextView transcriptParagraph(LinearLayout parent, String header, CharSequence text) {
        TextView head = label(parent, header, 13, MUTED); head.setPadding(0, dp(14), 0, dp(2));
        TextView body = label(parent, "", 17, INK); body.setText(text);
        body.setLineSpacing(dp(6), 1.05f); body.setPadding(0, 0, 0, dp(6));
        return body;
    }
    private void work() {
        pageHeading("工作");
        LinearLayout intro = featureCard(page, 0xffeef3fc);
        eyebrow(intro, "FOCUS  /  专注当下", WORK);
        label(intro, "你专心，琐事交给我。", 22, INK);
        label(intro, "安静记录讨论，结束后整理重点与待办。", 13, MUTED);
        gap(intro, 12);
        LinearLayout process = row(); intro.addView(process);
        badge(process, "记录", WORK, BG); badge(process, "整理", WORK, BG); badge(process, "行动", WORK, BG);
        for (int i = 0; i < process.getChildCount(); i++) {
            TextView step = (TextView) process.getChildAt(i); step.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (i > 0) p.leftMargin = dp(6); step.setLayoutParams(p);
        }
        gap(intro, 6);
        boolean recordingMeeting = session != null && sessionUsesCapture && "work".equals(mode());
        button(intro, recordingMeeting ? "结束并保存本次会议" : "work".equals(mode()) ? "开始会议记录" : "切换到工作模式", true, () -> {
            if (session != null && sessionUsesCapture && "work".equals(mode())) endVoice();
            else if (canAct()) { if ("work".equals(mode())) startVoice(); else changeMode("work"); }
        });
        if (recordingMeeting && !session.workMeetingId().isEmpty() && !session.workSaving()) {
            HostedSession active = session;
            workClock = label(intro, active.workPaused() ? "已暂停于 " + TimeFormat.clock(active.workOffsetMs()) + " · 暂停期间不记录、不上传"
                : "已记录 " + TimeFormat.clock(active.workOffsetMs()) + (active.workMarks() > 0 ? " · 已标记 " + active.workMarks() + " 处" : ""), 12, WORK);
            workClock.setGravity(Gravity.CENTER);
            LinearLayout controls = row(); intro.addView(controls);
            button(controls, active.workPaused() ? "继续记录" : "暂停", false, () -> {
                if (session != active) return;
                if (active.workPaused() ? active.resumeWork() : active.pauseWork()) showApp();
            });
            button(controls, "标记重点", false, () -> {
                if (session != active) return;
                if (active.markWork("app")) { feedback("已标记 " + TimeFormat.clock(active.workOffsetMs()), null); showApp(); }
            });
        }
        if (recordingMeeting) {
            LinearLayout captions = card(page);
            label(captions, "实时字幕", 15, INK).setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (!workCaptionStartedAt.isEmpty())
                label(captions, TimeFormat.dayOf(workCaptionStartedAt) + " · " + TimeFormat.clockOf(workCaptionStartedAt) + " 开始", 12, WORK);
            label(captions, "灰色为正在识别的内容，变黑表示已确认并保存；“说话人 1/2”按声音自动区分，不代表真实身份。", 11, MUTED);
            ScrollView captionScroll = new ScrollView(this);
            workCaptionBox = column(); workCaptionBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            captionScroll.addView(workCaptionBox);
            captions.addView(captionScroll, new LinearLayout.LayoutParams(-1, dp(320)));
            renderWorkCaption();
        } else workCaptionBox = null;
        label(intro, "结束后自动保存原文，再由 AI 整理摘要、决策与待办", 11, MUTED).setGravity(Gravity.CENTER);
        int retention = settings().optInt("work_audio_retention_days", 30);
        workRetentionAction = sectionHeader(page, "会议记录", "每次录音独立保存 · 处理进度自动更新",
            retention == 0 ? "录音：不保存" : "录音保存 " + retention + " 天", this::showRecordingRetentionOptions);
        label(page, "录音按账号保存在服务器，到期自动删除；缩短期限会立即删除超期录音。文字原文与纪要不受影响。", 11, MUTED);
        workMeetingList = column(); page.addView(workMeetingList); renderWorkMeetings();
        historyRangeAction = sectionHeader(page, "历史记录整理", "按时间范围整理较早的工作记录", "选择范围", this::showHistoryRangeOptions);
        historyReportBox = column(); page.addView(historyReportBox); renderHistoryReport();
        taskBoard.render(page, array(state, "tasks"), scroll);
    }
    private void showRecordingRetentionOptions() {
        if (!canAct()) return;
        int current = settings().optInt("work_audio_retention_days", 30);
        String[] labels = {"不保存录音（只保留文字）", "保存 7 天", "保存 30 天", "保存 90 天", "保存 365 天"};
        int[] days = {0, 7, 30, 90, 365}; int selected = 2;
        for (int i = 0; i < days.length; i++) if (days[i] == current) selected = i;
        workOptionsDialog = new RoundedChoiceDialog(this, "会议录音保存期限", "缩短期限会立即清理超期录音；文字和纪要保留。",
            labels, selected, "保存设置", index -> {
                if (!canAct() || days[index] == settings().optInt("work_audio_retention_days", 30)) return;
                JSONObject payload = cloneJson(settings()); put(payload, "work_audio_retention_days", days[index]);
                request(() -> api.json("PUT", "/v1/settings", payload), this::applyRecordingRetentionResult);
            });
        workOptionsDialog.show();
    }
    private void applyRecordingRetentionResult(JSONObject result) {
        put(state, "settings", result);
        int days = result.optInt("work_audio_retention_days", 30);
        if (workRetentionAction != null && workRetentionAction.isAttachedToWindow())
            workRetentionAction.setText(days == 0 ? "录音：不保存" : "录音保存 " + days + " 天");
    }
    private String historyRangeKey() { return "history_hours." + ServerEndpoint.sessionNamespace(api.serverUrl) + "." + api.vault.username(); }
    private void showHistoryRangeOptions() {
        if (!canAct()) return;
        String[] labels = {"最近 1 小时", "最近 24 小时", "最近 7 天"}; int[] hours = {1, 24, 168};
        JSONObject previous = state.optJSONObject("meeting_report");
        int selectedHours = getPreferences(MODE_PRIVATE).getInt(historyRangeKey(), previous == null ? 24 : previous.optInt("hours", 24));
        int selected = 1; for (int i = 0; i < hours.length; i++) if (hours[i] == selectedHours) selected = i;
        workOptionsDialog = new RoundedChoiceDialog(this, "选择工作记录范围", "选择要整理的历史记录，确认后开始生成纪要。",
            labels, selected, "开始整理", index -> {
                if (!canAct()) return;
                getPreferences(MODE_PRIVATE).edit().putInt(historyRangeKey(), hours[index]).apply();
                request(() -> api.json("POST", "/v1/meetings", json("hours", hours[index])), this::applyHistoryMeetingReport);
            });
        workOptionsDialog.show();
    }
    private void applyHistoryMeetingReport(JSONObject result) {
        put(state, "meeting_report", result);
        if (tab != 2 || historyReportBox == null || !historyReportBox.isAttachedToWindow()) return;
        int[] header = new int[2], viewport = new int[2];
        historyRangeAction.getLocationOnScreen(header); scroll.getLocationOnScreen(viewport);
        boolean keepTasks = header[1] + historyRangeAction.getHeight() <= viewport[1];
        Runnable restore = taskBoard == null || !keepTasks ? () -> {} : taskBoard.preserveViewport();
        renderHistoryReport(); restore.run();
        if (taskBoard != null) taskBoard.refreshReminders(keepTasks);
    }
    private void renderHistoryReport() {
        if (historyReportBox == null) return;
        historyReportBox.removeAllViews();
        JSONObject report = state.optJSONObject("meeting_report");
        if (report != null && !report.has("meeting_id")) {
            LinearLayout summary = card(historyReportBox);
            badge(summary, "最近纪要 · AI 草稿", WORK, 0xffeef3fc);
            label(summary, date(report.optString("created_at")), 11, MUTED);
            label(summary, report.optString("summary"), 16, INK).setTextIsSelectable(true);
            JSONArray decisions = array(report, "decisions");
            if (decisions.length() > 0) { gap(summary, 10); label(summary, "已记录的决策", 14, INK); }
            for (int i = 0; i < decisions.length(); i++) { JSONObject d = decisions.optJSONObject(i); if (d != null) label(summary, "• " + d.optString("text"), 13, MUTED); }
            JSONObject coverage = report.optJSONObject("coverage");
            LinearLayout coverageView = disclosure(summary, "记录覆盖", coverage != null && coverage.optBoolean("partial") ? "仅覆盖部分记录" : "请核对原文", false);
            if (coverage != null) {
                label(coverageView, "记录覆盖：已保存 " + coverage.optInt("available_records") + " 条 · 纳入 "
                    + coverage.optInt("summarized_records") + " 条 · 未纳入 " + coverage.optInt("omitted_records") + " 条", 12, ACCENT);
                label(coverageView, coverage.optBoolean("partial") ? "仅覆盖部分记录（可能有未纳入或截短片段）" : "已纳入本次提供的记录", 12, MUTED);
                String first = coverage.optString("first_record_at", ""), last = coverage.optString("last_record_at", "");
                if (!first.isEmpty() && !"null".equals(first)) label(coverageView, "记录时间：" + date(first) + " — " + date(last), 11, MUTED);
                label(coverageView, coverage.optString("note", "记录时间范围不代表持续采集，不能据此确认没有缺段。"), 11, MUTED);
            } else label(coverageView, "此纪要未提供记录覆盖信息，无法确认有无缺段。", 11, MUTED);
            LinearLayout actions = row(); summary.addView(actions);
            small(actions, "复制纪要", () -> copy("Aidol 会议纪要", report.optString("summary")));
            small(actions, "查看原始记录", () -> showSources(array(report, "sources")));
        } else empty(historyReportBox, "下一次讨论，从这里开始", "完成记录后，点击「生成纪要」。\n重点和决策会留在这里，方便回看。");
    }

    private void commute() {
        pageHeading("通勤");
        JSONObject agent = state.optJSONObject("agent"), route = agent == null ? null : agent.optJSONObject("route");
        if (route != null) {
            sectionHeader(page, "本次路线", "出发前，再确认一下", "", null);
            LinearLayout routeCard = card(page);
            if (route.optBoolean("available")) {
                badge(routeCard, "路线估算", COMMUTE, 0xffedf6f2);
                LinearLayout metrics = row(); routeCard.addView(metrics); gap(routeCard, 8);
                metric(metrics, String.valueOf(Math.max(1, Math.round(route.optDouble("eta_seconds") / 60))), "分钟", COMMUTE);
                metric(metrics, String.format(Locale.CHINA, "%.1f", route.optDouble("distance_meters") / 1000), "公里", INK);
                label(routeCard, "前往 " + route.optString("destination"), 16, INK); label(routeCard, route.optString("note"), 12, MUTED);
                JSONArray steps = array(route, "steps");
                if (steps.length() > 0) {
                    LinearLayout routeSteps = disclosure(routeCard, "路线详情", steps.length() + " 个路段", false);
                    for (int i = 0; i < Math.min(steps.length(), 10); i++) { JSONObject step = steps.optJSONObject(i); if (step != null) label(routeSteps, String.format(Locale.CHINA, "%02d   %s", i + 1, step.optString("instruction", step.optString("name", step.toString()))), 14, INK); }
                    if (steps.length() > 10) small(routeSteps, "查看全部路线", () -> { LinearLayout body = dialogBody(); for (int i = 0; i < steps.length(); i++) { JSONObject step = steps.optJSONObject(i); if (step != null) label(body, (i + 1) + ". " + step.optString("instruction", step.toString()), 14, INK); } scrollDialog("路线详情", body); });
                }
            } else label(routeCard, route.optString("reason", "暂时没有可用路线"), 14, MUTED);
        }
        boolean hasDestination = settings().has("destination_lon") && !settings().isNull("destination_lon");
        LinearLayout destination = featureCard(page, 0xffedf6f2);
        eyebrow(destination, "ON THE WAY  /  在路上", COMMUTE);
        label(destination, hasDestination ? settings().optString("destination_name", "已选目的地") : "今天，想去哪里？", 23, INK);
        label(destination, hasDestination ? "目的地已就绪，选择你的出发方式。" : "选好目的地，让出发更从容。", 13, MUTED);
        LinearLayout searchDestination;
        if (hasDestination) searchDestination = disclosure(destination, "更换目的地", "", false);
        else { searchDestination = column(); destination.addView(searchDestination); gap(searchDestination, 14); }
        LinearLayout searchFields = row(); searchDestination.addView(searchFields);
        LinearLayout cityField = column(); LinearLayout.LayoutParams cityLayout = new LinearLayout.LayoutParams(dp(80), -2); cityLayout.rightMargin = dp(8); searchFields.addView(cityField, cityLayout);
        LinearLayout placeField = column(); searchFields.addView(placeField, new LinearLayout.LayoutParams(0, -2, 1));
        EditText city = field(cityField, "所在城市", "城市", false, 80); city.setSingleLine(true); city.setText(settings().optString("transit_city", "上海"));
        EditText query = field(placeField, "目的地", "公司、车站、餐厅…", false, 100); query.setSingleLine(true);
        button(searchDestination, "查找地点", false, () -> {
            if (!canAct() || value(query).isEmpty()) return; hideKeyboard();
            request(() -> api.json("GET", "/v1/maps/search?q=" + Uri.encode(value(query)) + "&city=" + Uri.encode(value(city)), null), this::poiResults);
        });
        gap(destination, 12); label(destination, "出行方式", 12, MUTED);
        LinearLayout methods = row(); destination.addView(methods); gap(destination, 5);
        String[] methodsKeys = {"driving", "walking", "transit", "bicycling"}, methodsNames = {"驾车", "步行", "公共交通", "骑行"};
        for (int i = 0; i < methodsKeys.length; i++) { String key = methodsKeys[i]; segment(methods, methodsNames[i], key.equals(settings().optString("transport", "driving")), () -> {
            if (!canAct()) return; JSONObject payload = cloneJson(settings()); put(payload, "transport", key); mutate("PUT", "/v1/settings", payload);
        }); }
        button(destination, "定位并更新路线", true, this::locate).setBackground(ripple(COMMUTE, 16, COMMUTE));
        label(destination, "仅在点击时获取位置 · 路线为规划估算", 11, MUTED).setGravity(Gravity.CENTER);
        sectionHeader(page, "沿途安排", "给路上的自己，多一点照顾", "", null);
        LinearLayout around = disclosure(page, "附近与天气", "找个好去处，看看晴雨", false);
        EditText keyword = input("咖啡、美食、公园…", false, 80); keyword.setText("咖啡"); around.addView(keyword);
        button(around, "搜索周边 3 km", false, () -> {
            if (!canAct()) return; JSONObject current = state.optJSONObject("agent"), location = current == null ? null : current.optJSONObject("location");
            if (location == null) { feedback("请先点击“定位并更新路线”", LocalCue.LOCATION); return; }
            JSONObject payload = cloneJson(location); put(payload, "keyword", fallback(value(keyword), "美食"));
            request(() -> api.json("POST", "/v1/maps/nearby", payload), this::poiResults);
        });
        button(around, "附近地铁站", false, () -> {
            if (!canAct()) return;
            JSONObject nearbyAgent = state.optJSONObject("agent"), location = nearbyAgent == null ? null : nearbyAgent.optJSONObject("location");
            if (location == null) { feedback("请先点击定位并更新路线", LocalCue.LOCATION); return; }
            JSONObject payload = cloneJson(location); put(payload, "metro_only", true); put(payload, "radius", 2000);
            request(() -> api.json("POST", "/v1/maps/nearby", payload), this::poiResults);
        });
        button(around, "目的地天气预报", false, () -> {
            if (!canAct()) return;
            if (settings().isNull("destination_lon")) { feedback("请先选择目的地", LocalCue.DESTINATION); return; }
            request(() -> api.json("GET", "/v1/maps/weather?forecast=true", null), weather -> {
                if (!weather.optBoolean("available")) { showError(weather.optString("reason", "暂无预报")); return; }
                LinearLayout body = dialogBody(); JSONArray days = array(weather, "forecast");
                for (int i = 0; i < days.length(); i++) { JSONObject day = days.optJSONObject(i); if (day != null)
                    label(body, day.optString("date") + "  " + day.optString("dayweather") + " / " + day.optString("nightweather") + "\n" + day.optString("nighttemp") + "–" + day.optString("daytemp") + "°C", 15, INK); }
                label(body, "高德预报 · " + weather.optString("report_time"), 12, MUTED);
                scrollDialog(weather.optString("city") + "天气预报", body);
            });
        });
        button(around, "查看目的地天气", false, () -> {
            if (!canAct()) return; String code = getPreferences(MODE_PRIVATE).getString(weatherPreferenceKey(), "");
            if (settings().isNull("destination_lon")) { feedback("先从搜索结果选择目的地，即可查询当地天气", LocalCue.DESTINATION); return; }
            String weatherCode = code.matches("[0-9]{6}") ? code : "";
            request(() -> api.json("GET", "/v1/maps/weather?city=" + Uri.encode(weatherCode), null), weather -> {
                if (!weather.optBoolean("available")) { showError(weather.optString("reason", "天气暂不可用")); return; }
                new AlertDialog.Builder(this).setTitle(weather.optString("city") + "  " + weather.optString("temperature") + "°C")
                    .setMessage(weather.optString("weather") + "\n" + weather.optString("wind_direction") + "风 " + weather.optString("wind_power") + "级\n湿度 " + weather.optString("humidity") + "%\n\n" + weather.optString("note") + "\n发布时间：" + weather.optString("report_time")).setPositiveButton("知道了", null).show();
            });
        });
        LinearLayout notice = disclosure(page, "到达提醒", "提前准备一句报平安", false);
        EditText contact = field(notice, "联系人称呼", "例如 同事", false, 100); contact.setText(settings().optString("contact", "同事"));
        EditText minutes = field(notice, "预计到达前几分钟提醒", "1–60", false, 2); minutes.setInputType(InputType.TYPE_CLASS_NUMBER); minutes.setText(String.valueOf(settings().optInt("arrival_notice_minutes", 5)));
        button(notice, "保存提醒偏好", false, () -> { if (!canAct()) return; int m; try { m = Integer.parseInt(value(minutes)); } catch (Exception e) { minutes.setError("请输入 1–60"); return; } if (m < 1 || m > 60) { minutes.setError("请输入 1–60"); return; } JSONObject payload = cloneJson(settings()); put(payload, "contact", value(contact)); put(payload, "arrival_notice_minutes", m); mutate("PUT", "/v1/settings", payload); });
        label(notice, "定位更新后生成草稿，由你复制并发送给联系人。不会自动发送消息。", 11, MUTED);
        JSONArray drafts = array(state, "drafts"); int pending = 0;
        for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.optJSONObject(i); if (draft == null || !"pending".equals(draft.optString("status"))) continue; pending++;
            LinearLayout draftCard = card(page); badge(draftCard, "待发送 · 给" + draft.optString("contact"), COMMUTE, 0xffedf6f2); label(draftCard, draft.optString("text"), 15, INK);
            LinearLayout actions = row(); draftCard.addView(actions);
            small(actions, "复制草稿", () -> { if (!canAct()) return; copy("到达提醒草稿", draft.optString("text")); mutate("POST", "/v1/drafts/" + draft.optString("id") + "/copied", json()); });
            small(actions, "忽略", () -> mutate("POST", "/v1/drafts/" + draft.optString("id") + "/dismissed", json()));
        }
    }

    private void poiResults(JSONObject result) {
        JSONArray pois = array(result, "pois"); if (pois.length() == 0) { showError(result.optString("reason", "没有找到地点，换个关键词试试")); return; }
        LinearLayout body = dialogBody(); ScrollView list = new ScrollView(this); list.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择目的地").setView(list).setNegativeButton("取消", null).create();
        for (int i = 0; i < pois.length(); i++) {
            JSONObject poi = pois.optJSONObject(i); if (poi == null) continue;
            LinearLayout item = card(body); label(item, poi.optString("name"), 16, INK); label(item, poi.optString("city") + " · " + poi.optString("address"), 12, MUTED);
            button(item, "设为目的地", false, () -> {
                if (!canAct()) return; JSONObject payload = cloneJson(settings()); put(payload, "destination_name", poi.optString("name")); put(payload, "destination_lon", poi.optDouble("longitude")); put(payload, "destination_lat", poi.optDouble("latitude")); put(payload, "transit_city", fallback(poi.optString("city"), settings().optString("transit_city", "上海")));
                request(() -> api.json("PUT", "/v1/settings", payload), r -> { getPreferences(MODE_PRIVATE).edit().putString(weatherPreferenceKey(), poi.optString("adcode")).apply(); dialog.dismiss(); refresh(); });
            });
        }
        dialog.show();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void enterMaintenance(String trigger) {
        if (!visible || isDestroyed()) return;
        maintenanceEntry = trigger;
        startupLocationAttempted = false;
        handler.removeCallbacks(startupLocationTick); handler.post(startupLocationTick);
        handler.removeCallbacks(automaticHeadsetTick); handler.post(automaticHeadsetTick);
    }
    private boolean hasMaintenanceBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }
    private void maintainHeadset() {
        handler.removeCallbacks(automaticHeadsetTick);
        if (!visible || isDestroyed() || headset == null || api == null) return;
        handler.postDelayed(automaticHeadsetTick, 1000);
        renderMaintenanceStatus();
        if (api.vault.token().isEmpty() || automaticHeadsetSuppressed || busy || permissionPending || session != null
                || repeatArmed || callStartPending || meetingPlayback != null && meetingPlayback.active()) return;
        HeadsetController.State connection = headset.snapshot().state;
        if (connection != HeadsetController.State.DISCONNECTED && connection != HeadsetController.State.ERROR) return;
        if (!hasMaintenanceBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31 && !getPreferences(MODE_PRIVATE).getBoolean("auto_bluetooth_asked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("auto_bluetooth_asked", true).apply();
                permissionPending = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 6);
            }
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - automaticHeadsetAttempt < 30000) return;
        automaticHeadsetAttempt = now;
        headset.connectAutomatically();
    }
    private void renderMaintenanceStatus() {
        if (headset == null) return;
        HeadsetController.Snapshot device = headset.snapshot();
        String bluetooth = !hasMaintenanceBluetoothPermission() ? "需要附近设备权限"
            : device.ready() ? "已连接" : device.state == HeadsetController.State.CONNECTING
            || device.state == HeadsetController.State.INITIALIZING || device.state == HeadsetController.State.CONNECTED ? "连接中" : "未连接";
        String text = "蓝牙：" + bluetooth + " · " + maintenanceLocationStatus;
        if (maintenanceStatus != null && !text.contentEquals(maintenanceStatus.getText())) maintenanceStatus.setText(text);
        if (meetingMaintenanceStatus != null && !text.contentEquals(meetingMaintenanceStatus.getText())) meetingMaintenanceStatus.setText(text);
    }
    private void updateStartupLocation() {
        handler.removeCallbacks(startupLocationTick);
        if (!visible || isDestroyed() || api == null || startupLocationAttempted) return;
        if (permissionPending) { handler.postDelayed(startupLocationTick, 500); return; }
        if (api.vault.token().isEmpty()) return;
        if (!hasLocationPermission()) {
            if (getPreferences(MODE_PRIVATE).getBoolean("startup_location_asked", false)) {
                maintenanceLocationStatus = "定位：需要位置权限"; renderMaintenanceStatus(); return;
            }
            if (busy || session != null || repeatArmed) { handler.postDelayed(startupLocationTick, 1000); return; }
            getPreferences(MODE_PRIVATE).edit().putBoolean("startup_location_asked", true).apply();
            permissionPending = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return;
        }
        startupLocationAttempted = true;
        locationMonitor.enter(api, maintenanceEntry);
    }
    private void locationFailed(String message) {
        boolean quiet = automaticLocation;
        stopLocation();
        if (!visible) return;
        if (quiet) { lastNotice = message; updateStatus(); }
        else showError(message);
    }
    private void locate() { locate(true); }
    private void locate(boolean updateRoute) {
        if (!canAct()) return;
        if (!hasLocationPermission()) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("startup_location_asked", true).apply();
            permissionPending = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 2); return;
        }
        automaticLocation = !updateRoute;
        android.location.LocationManager manager = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        boolean precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        List<String> providers = new ArrayList<>();
        if (manager != null) {
            List<String> enabled = manager.getProviders(true);
            // Start network/fused alongside GPS: satellite-only requests can time out indoors.
            for (String provider : new String[]{android.location.LocationManager.NETWORK_PROVIDER, "fused", android.location.LocationManager.GPS_PROVIDER}) {
                if (enabled.contains(provider) && (precise || !android.location.LocationManager.GPS_PROVIDER.equals(provider))) providers.add(provider);
            }
        }
        if (providers.isEmpty()) {
            if (!updateRoute) { locationFailed("系统定位未开启，可报城市查天气，或在系统设置开启定位"); return; }
            new AlertDialog.Builder(this).setTitle("请开启系统定位").setMessage("用于查询当地天气、附近地点和路线。")
            .setNegativeButton("取消", null).setPositiveButton("打开定位设置", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show(); return; }
        lastNotice = "正在获取当前位置…"; updateStatus();
        final ApiClient locationApi = api;
        final String locationToken = api.vault.token();
        locationListener = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location location) {
                if (locationListener != this || api != locationApi || !locationToken.equals(api.vault.token())) return;
                if (!usableLocation(location)) return;
                if (BuildConfig.DEBUG) android.util.Log.d("AidolLocation", "Accepted provider=" + location.getProvider() + " accuracy_m=" + location.getAccuracy());
                stopLocation(); if (!visible) return;
                JSONObject point = json("longitude", location.getLongitude(), "latitude", location.getLatitude(), "accuracy", location.getAccuracy(), "source", "gps"); if (location.hasSpeed()) put(point, "speed", location.getSpeed());
                JSONObject payload = cloneJson(settings()); put(payload, "mode", "commute"); put(payload, "auto_mode", false);
                request(() -> {
                    if (updateRoute) locationApi.json("PUT", "/v1/settings", payload);
                    locationApi.json("POST", "/v1/location", point);
                    return locationApi.json("GET", "/v1/state", null);
                }, r -> {
                    if (api != locationApi || !locationToken.equals(api.vault.token())) return;
                    state = r; lastNotice = "定位已更新 · 可查询当地天气与附近地点"; showApp();
                });
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
            @Override public void onStatusChanged(String p, int status, Bundle extras) {}
        };
        android.location.Location cached = null;
        int subscribed = 0;
        for (String provider : providers) {
            // A provider may reject a request even while another remains usable.
            try {
                android.location.Location candidate = manager.getLastKnownLocation(provider);
                if (usableLocation(candidate) && (cached == null || candidate.getElapsedRealtimeNanos() > cached.getElapsedRealtimeNanos())) cached = candidate;
            } catch (RuntimeException ignored) {}
            try {
                manager.requestLocationUpdates(provider, 0, 0, locationListener, android.os.Looper.getMainLooper());
                subscribed++;
                if (BuildConfig.DEBUG) android.util.Log.d("AidolLocation", "Requested provider=" + provider);
            } catch (RuntimeException ignored) {}
        }
        if (cached != null) locationListener.onLocationChanged(cached);
        else if (subscribed > 0) handler.postDelayed(locationTimeout, 25000);
        else locationFailed("无法获取位置，可报城市查天气，或检查定位权限与系统设置");
    }
    private static boolean usableLocation(android.location.Location location) {
        if (location == null) return false;
        long age = android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= 60000000000L && location.hasAccuracy()
            && Float.isFinite(location.getAccuracy()) && location.getAccuracy() >= 0 && location.getAccuracy() <= 200;
    }
    private void stopLocation() { handler.removeCallbacks(locationTimeout); if (locationListener != null) { try { ((android.location.LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(locationListener); } catch (Exception ignored) {} locationListener = null; } }

    private void profile() {
        pageHeading("我的");
        LinearLayout account = featureCard(page, SOFT);
        LinearLayout identity = row(); identity.setGravity(Gravity.CENTER_VERTICAL); account.addView(identity);
        TextView initial = label(identity, api.vault.username().isEmpty() ? "A" : api.vault.username().substring(0, 1).toUpperCase(Locale.ROOT), 26, BG);
        initial.setGravity(Gravity.CENTER); initial.setBackground(shape(INK, 30, INK)); initial.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout identityText = column(); identityText.setPadding(dp(16), 0, 0, 0); identity.addView(identityText, new LinearLayout.LayoutParams(0, -2, 1));
        label(identityText, fallback(api.vault.username(), "你的空间"), 22, INK);
        label(identityText, "每一点偏好，都是独一无二的你。", 12, MUTED);
        sectionHeader(page, "设备与陪伴", "按你的习惯来", "", null);
        LinearLayout device = card(page); label(device, "耳机与音频", 17, INK);
        headsetStatus = label(device, "", 12, ACCENT); renderHeadset();
        button(device, "设备详情", false, this::showDeviceDetails);
        LinearLayout settings = disclosure(page, "陪伴偏好", "日常习惯与聆听方式", false);
        EditText preferences = field(settings, "关于你的偏好", "喜欢什么、如何与你交流…", false, 2000); preferences.setMinLines(2); preferences.setText(settings().optString("preferences"));
        CheckBox retain = new CheckBox(this); retain.setText("保存转写与对话历史"); retain.setTextColor(INK); retain.setTextSize(13); retain.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT)); retain.setChecked(settings().optBoolean("retain_transcripts", true)); settings.addView(retain);
        label(settings, "关闭后不新增聊天转写历史；明确开始的工作记录仍保存转写。仍可能自动提取记忆并保存请求结果，已有数据也会保留。需要移除账号业务数据请使用清空数据。", 11, MUTED);
        LinearLayout hours = row(); settings.addView(hours); EditText start = input("上班小时", false, 2), end = input("下班小时", false, 2); start.setInputType(InputType.TYPE_CLASS_NUMBER); end.setInputType(InputType.TYPE_CLASS_NUMBER); start.setText(String.valueOf(settings().optInt("work_start", 9))); end.setText(String.valueOf(settings().optInt("work_end", 18))); hours.addView(start, new LinearLayout.LayoutParams(0, -2, 1)); hours.addView(end, new LinearLayout.LayoutParams(0, -2, 1));
        EditText zone = field(settings, "时区", "例如 Asia/Shanghai", false, 80); zone.setText(settings().optString("timezone", "Asia/Shanghai"));
        label(settings, "前台聆听设置", 15, INK);
        EditText interval = field(settings, "定时聆听间隔（秒）", "60–3600，默认 300", false, 4); interval.setInputType(InputType.TYPE_CLASS_NUMBER); interval.setText(String.valueOf(settings().optInt("life_poll_seconds", 300)));
        EditText duration = field(settings, "定时单次最长录音（秒）", "5–60，默认 30", false, 2); duration.setInputType(InputType.TYPE_CLASS_NUMBER); duration.setText(String.valueOf(settings().optInt("listen_seconds", 30)));
        EditText threshold = field(settings, "语音检测阈值", "0.005–0.2，默认 0.025", false, 5); threshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); threshold.setText(String.valueOf(settings().optDouble("vad_threshold", .025)));
        label(settings, "阈值越低越敏感。AI 通话始终自动停句；开启通知会话后可锁屏继续。定时和安静记录只在前台运行。", 11, MUTED);
        button(settings, "保存偏好", true, () -> {
            if (!canAct()) return;
            JSONObject payload = cloneJson(settings()); put(payload, "preferences", value(preferences)); put(payload, "retain_transcripts", retain.isChecked());
            int from, until; try { from = Integer.parseInt(value(start)); until = Integer.parseInt(value(end)); } catch (Exception e) { feedback("请输入有效的工作时间", LocalCue.SETTINGS); return; }
            if (from < 0 || until > 24 || until <= from) { feedback("工作时间需要在 0–24 时内，结束晚于开始", LocalCue.SETTINGS); return; }
            put(payload, "work_start", from); put(payload, "work_end", until); put(payload, "auto_mode", false); put(payload, "timezone", value(zone));
            try { int every = Integer.parseInt(value(interval)), seconds = Integer.parseInt(value(duration)); double level = Double.parseDouble(value(threshold));
                if (every < 60 || every > 3600 || seconds < 5 || seconds > 60 || level < .005 || level > .2) throw new IllegalArgumentException();
                put(payload, "life_poll_seconds", every); put(payload, "listen_seconds", seconds); put(payload, "vad_threshold", level);
            } catch (Exception e) { feedback("请检查聆听间隔、录音时长和检测阈值的范围", LocalCue.SETTINGS); return; }
            mutate("PUT", "/v1/settings", payload);
        });
        LinearLayout privacy = disclosure(page, "账号与隐私", "密码、数据导出与管理", false);
        button(privacy, "修改登录密码   ›", false, this::changePassword);
        button(privacy, "导出我的数据   ↗", false, this::export);
        button(privacy, "数据处理说明   ›", false, this::privacyInfo);
        button(privacy, "清空对话、记忆与工作数据", false, () -> destructive(false)).setTextColor(RED);
        button(privacy, "永久注销账号", false, () -> destructive(true)).setTextColor(RED);
        button(page, "退出登录", false, () -> {
            if (!canAct()) return;
            confirm("退出当前账号？", "服务器数据会保留，下次登录后继续使用。", () -> request(() -> api.json("POST", "/auth/logout", json()), r -> signOut()));
        });
        developmentEntry(page);
        TextView foot = label(page, "Aidol · " + BuildConfig.VERSION_NAME, 11, MUTED); foot.setGravity(Gravity.CENTER);
    }

    private void changePassword() {
        if (!canAct()) return;
        LinearLayout body = dialogBody(); EditText old = field(body, "原密码", "当前密码", true, 128), next = field(body, "新密码", "填一个好记的密码即可", true, 128), confirm = field(body, "确认新密码", "再输入一次", true, 128);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修改密码").setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            if (!canAct()) return;
            String pass = next.getText().toString(); if (pass.isEmpty()) { next.setError("请输入新密码"); return; }
            if (!pass.equals(confirm.getText().toString())) { confirm.setError("两次密码不一致"); return; }
            request(() -> api.json("PUT", "/auth/password", json("old_password", old.getText().toString(), "new_password", pass)), r -> {
                api.vault.save(r.getString("token"), api.vault.username()); dialog.dismiss(); feedback("密码已更新，其他登录已失效", LocalCue.PASSWORD);
            });
        })); dialog.show();
    }

    private void destructive(boolean account) {
        if (!canAct()) return;
        LinearLayout body = dialogBody(); label(body, account ? "账号和全部个人数据将永久删除，无法恢复。" : "将删除所有对话、记忆、会议纪要及待办；账号与设置保留。此操作无法撤销。", 14, RED);
        EditText password = field(body, "输入登录密码确认", "当前密码", true, 128);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(account ? "永久注销账号" : "清空我的数据").setView(body).setNegativeButton("取消", null).setPositiveButton(account ? "永久注销" : "确认清空", null).create();
        dialog.setOnShowListener(v -> { dialog.getButton(-1).setTextColor(RED); dialog.getButton(-1).setOnClickListener(w -> {
            if (!canAct()) return; if (password.length() == 0) { password.setError("请输入密码"); return; }
            request(() -> api.json("DELETE", account ? "/auth/account" : "/v1/data", json("password", password.getText().toString())), r -> {
                dialog.dismiss(); TaskReminders.clear(this); getSharedPreferences("meeting_edit_drafts", MODE_PRIVATE).edit().clear().apply(); clearPrivateSnapshots(); if (account) signOut(); else { showApp(); refresh(); }
            });
        }); }); dialog.show();
    }

    private void export() {
        if (!canAct()) return;
        request(() -> api.json("GET", "/v1/export", null), data -> {
            exportData = data.toString(2).getBytes(StandardCharsets.UTF_8);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json"); intent.putExtra(Intent.EXTRA_TITLE, "aidol-" + api.vault.username() + ".json"); startActivityForResult(intent, 20);
        });
    }

    private JSONObject workMeetingOpen;
    /** Save the open meeting (details, minutes, timed transcript) as a Markdown file the user picks. */
    private void saveMeetingFile() {
        JSONObject meeting = workMeetingOpen;
        if (meeting == null) { feedback("会议还在加载，请稍候再保存", null); return; }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        exportData = MeetingExport.markdown(meeting, zone).getBytes(StandardCharsets.UTF_8);
        exportNotice = "会议纪要已保存为文件";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/markdown"); intent.putExtra(Intent.EXTRA_TITLE, MeetingExport.fileName(meeting, zone));
        startActivityForResult(intent, 21);
    }
    private String exportNotice = "数据已导出";

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 && request != 21) return;
        String notice = request == 21 ? exportNotice : "数据已导出";
        LocalCue cue = request == 21 ? null : LocalCue.EXPORTED;
        byte[] payload = exportData; exportData = null;
        if (result != RESULT_OK || data == null || payload == null) return;
        Uri uri = data.getData();
        worker.execute(() -> { try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (output == null) throw new Exception("文件无法写入"); output.write(payload); runOnUiThread(() -> feedback(notice, cue)); } catch (Exception e) { runOnUiThread(() -> showError("导出失败，请选择其他保存位置")); } });
    }

    private void privacyInfo() {
        new AlertDialog.Builder(this).setTitle("数据处理说明").setMessage("Aidol 测试版由测试负责人运营。\n\n账号使用唯一用户名和密码登录，无需邮箱或手机号。忘记密码时需使用注册时保存的恢复码。\n\n在你点击或通过耳机接通 AI 后采集麦克风，每句最多 60 秒；AI 通话会在回复后继续听，挂断即可停止。录音上传服务器用于语音识别；文字发送 AI 服务生成回复与记忆；回复可转换为语音播放。工作会议录音按账号保存在服务器，到期（默认30天，可设为不保存）自动删除；其他对话的原始录音不保存。\n\n对话、偏好、记忆和会议待办按账号存储在服务器。你可以关闭新的转写保存、导出、清空数据或注销账号。测试负责人可管理服务器及备份。\n\n录制他人讲话前，请先征得对方同意。AI 回复与纪要需要自行核对。\n\n账号密钥保存在设备加密存储中；服务商 API Key 不下发到手机。").setPositiveButton("了解", null).show();
    }

    private void showSources(JSONArray sources) {
        showSources("纪要来源", sources);
    }
    private void showSources(String title, JSONArray sources) {
        LinearLayout body = dialogBody();
        if (sources.length() == 0) label(body, "暂无原始记录", 14, MUTED);
        for (int i = 0; i < sources.length(); i++) { JSONObject source = sources.optJSONObject(i); if (source != null) { label(body, date(source.optString("created_at")), 10, ACCENT); label(body, source.optString("text"), 14, INK); } }
        scrollDialog(title, body);
    }

    private void changeMode(String mode) { changeMode(mode, false); }
    private void changeMode(String mode, boolean openConversation) {
        if (busy || locationListener != null || permissionPending) { feedback("上一项操作正在进行，请稍后切换模式", LocalCue.BUSY); return; }
        clearVoicePreparation(); preparationAttemptAt = -60000;
        if (session != null && sessionOperationNotice != null) cancelSession("");
        if (session != null && "work".equals(mode()) && sessionUsesCapture) {
            deferredMode = mode; deferredOpenConversation = openConversation;
            stopRepeat(); session.stopRecording();
            lastNotice = "正在结束并保存工作记录，保存成功后切换"; updateStatus(); return;
        }
        if (session != null || repeatArmed || callStartPending) cancelSession("会话已结束");
        stopRepeat(); clearHeadsetGesture();
        JSONObject body = cloneJson(settings()); put(body, "mode", mode); put(body, "auto_mode", false);
        ModeChangeRetry change = new ModeChangeRetry(mode, api, sessionGeneration, android.os.SystemClock.elapsedRealtime());
        modeChange = change; busy = true; lastNotice = "正在切换" + modeName(mode); updateStatus();
        submitModeChange(change, api, body, openConversation);
    }
    private boolean currentModeChange(ModeChangeRetry change) {
        return !isDestroyed() && modeChange == change && change.matches(api, sessionGeneration);
    }
    private void finishModeChange(ModeChangeRetry change) {
        if (modeChange != change) return;
        modeChange = null; busy = false;
        if (modeChangeDelay != null) handler.removeCallbacks(modeChangeDelay);
        modeChangeDelay = null;
    }
    private void abandonModeChange(ModeChangeRetry change) {
        if (modeChange != change) return;
        stopRepeat();
        finishModeChange(change);
        if (!isDestroyed()) {
            lastNotice = "模式切换已停止，请刷新确认当前模式"; updateStatus();
        }
    }
    private void pauseModeChange() {
        if (modeChange == null) return;
        modeChange.stopRetrying();
        // A scheduled retry follows a confirmed rejection: there is no update to wait for.
        // An in-flight request may already have applied, so let its existing response finish.
        if (modeChangeDelay != null) {
            stopRepeat();
            finishModeChange(modeChange);
            lastNotice = "模式尚未切换，请稍后重新选择"; updateStatus();
        }
    }
    private void submitModeChange(ModeChangeRetry change, ApiClient client, JSONObject body, boolean openConversation) {
        if (!currentModeChange(change) || !change.canAttempt(android.os.SystemClock.elapsedRealtime(), api, sessionGeneration, sessionAvailable())) {
            abandonModeChange(change); return;
        }
        modeChangeDelay = null;
        worker.execute(() -> {
            try {
                JSONObject result = client.json("PUT", "/v1/settings", body);
                runOnUiThread(() -> {
                    if (!currentModeChange(change)) { abandonModeChange(change); return; }
                    finishModeChange(change);
                    put(state, "settings", result);
                    String appliedMode = result.optString("mode", change.mode);
                    JSONObject agent = state.optJSONObject("agent"); if (agent == null) agent = new JSONObject();
                    put(agent, "mode", appliedMode); put(state, "agent", agent);
                    diagnosticError = "无";
                    String appliedNotice = "已切换" + modeName(appliedMode);
                    lastNotice = appliedNotice + (repeatArmed ? "；提示后继续聆听" : "；等待你手动开始");
                    listenMode = "work".equals(appliedMode) ? VoiceInteraction.NOTES : VoiceInteraction.CALL;
                    if (openConversation) tab = 0;
                    showApp();

                    // Commit the real settings result first. Playback cannot undo or repeat that update.
                    if (sessionAvailable() && client == api && change.matches(api, sessionGeneration) && session == null) {
                        try {
                            newSession(appliedNotice);
                            session.announceMode(appliedMode);
                        } catch (RuntimeException unavailable) {
                            cancelSession("");
                            lastNotice = appliedNotice + "；操作提示未播放，请检查耳机连接";
                            diagnosticError = "操作提示无法启动"; renderDiagnostics(); updateStatus();
                        }
                    }
                });
            } catch (Exception error) { runOnUiThread(() -> {
                if (!currentModeChange(change)) { abandonModeChange(change); return; }
                int statusCode = error instanceof ApiClient.ApiException ? ((ApiClient.ApiException) error).status : 0;
                long delay = change.delayAfterFailure(statusCode, error.getMessage(), android.os.SystemClock.elapsedRealtime(),
                    api, sessionGeneration, sessionAvailable());
                if (delay >= 0) {
                    lastNotice = "上一轮正在收尾，稍后切换" + modeName(change.mode); updateStatus();
                    modeChangeDelay = () -> submitModeChange(change, client, body, openConversation);
                    handler.postDelayed(modeChangeDelay, delay);
                    return;
                }
                finishModeChange(change);
                stopRepeat();
                if (statusCode == 401) client.vault.clear();
                lastNotice = statusCode == 409 ? "上一轮仍在处理，模式尚未切换；请稍后再试"
                    : statusCode == 401 ? "登录已失效，请重新登录"
                    : statusCode == 429 ? "操作较频繁，请稍后再切换模式"
                    : "未能确认模式切换，请检查连接并刷新确认";
                diagnosticError = lastNotice; renderDiagnostics(); updateStatus();
                if (visible) headsetNotice(lastNotice);
            }); }
        });
    }
    private void saveModeAndOpenWork() { changeMode("work", true); }
    private void mutate(String method, String path, JSONObject body) { if (canAct()) request(() -> api.json(method, path, body), r -> {
        if (path.startsWith("/v1/memories/") && ("DELETE".equals(method) || "PUT".equals(method))) { clearPrivateSnapshots(); showApp(); }
        refresh();
    }); }
    private void refresh() {
        if (busy || session != null || api.vault.token().isEmpty()) return;
        // Background state sync must not erase the reason a voice turn just failed.
        // The explicit refresh button clears lastNotice before calling this method.
        final String notice = lastNotice;
        request(() -> api.json("GET", "/v1/state", null), r -> {
            state = r; lastNotice = notice; showApp();
            if (taskBoard != null) taskBoard.refreshReminders();
            handler.post(startupLocationTick);
        });
    }
    private void request(Job job, Result result) {
        if (busy) return; busy = true; updateStatus();
        worker.execute(() -> {
            try { JSONObject value = job.run(); runOnUiThread(() -> {
                busy = false; if (isDestroyed()) return; lastNotice = ""; updateStatus();
                try { result.accept(value); } catch (Exception e) { showError("操作未完成，请稍后重试"); }
            }); } catch (Exception e) { runOnUiThread(() -> { busy = false; if (!isDestroyed()) { lastNotice = fallback(e.getMessage(), "无法连接服务器，请检查网络后重试"); diagnosticError = lastNotice; renderDiagnostics(); updateStatus();
                // A 409 on the sign-in screen (e.g. username taken) must replace "正在连接…"; elsewhere 409 stays quiet.
                if (authError != null || !(e instanceof ApiClient.ApiException) || ((ApiClient.ApiException) e).status != 409) showError(lastNotice); } }); }
        });
    }
    private void signOut() { TaskReminders.clear(this); getSharedPreferences("meeting_edit_drafts", MODE_PRIVATE).edit().clear().apply(); clearVoicePreparation(); preparationAttemptAt = -60000; if (quickFeedback != null) quickFeedback.stop(); chatDraft = ""; stopLockScreenSession(); cancelSession(""); clearPrivateSnapshots(); api.vault.clear(); syncHeadsetControls(); state = new JSONObject(); lastNotice = ""; showAuth(0); }
    private boolean canAct() { if (busy || session != null || locationListener != null) { feedback("上一项操作正在进行，请稍候", LocalCue.BUSY); return false; } return true; }

    private boolean lockScreenEnabled() { return voiceService != null && voiceService.enabled(); }
    private boolean sessionAvailable() { return visible || lockScreenEnabled(); }
    private void enableLockScreenSession() {
        if (!visible || permissionPending || voiceServiceBound || !canAct() || api.vault.token().isEmpty()) return;
        ArrayList<String> missing = audioPermissions(development.audioMode() == AudioMode.RISE_SDK);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) { permissionPending = true; requestPermissions(missing.toArray(new String[0]), 4); return; }
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel = notifications.getNotificationChannel("headset_session");
        if (!notifications.areNotificationsEnabled() || channel != null && channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
            feedback("请先在系统设置开启通知，以便随时停止锁屏耳机会话", LocalCue.NOTIFICATIONS); return;
        }
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        if (audio.getMode() == android.media.AudioManager.MODE_IN_CALL || audio.getMode() == android.media.AudioManager.MODE_IN_COMMUNICATION
            || headset.snapshot().inCall) { feedback("通话期间不能开启锁屏耳机会话", LocalCue.CALL); return; }
        if (development.audioMode() == AudioMode.RISE_SDK && !headset.snapshot().ready()) {
            feedback("请先连接耳机，显示就绪后再开启", LocalCue.CONNECT); return;
        }
        stopRepeat();
        Intent service = new Intent(this, VoiceSessionService.class);
        try {
            startForegroundService(service);
            voiceServiceBound = bindService(service, voiceConnection, BIND_AUTO_CREATE);
            if (!voiceServiceBound) { stopService(service); feedback("锁屏会话未能开启，请重试", LocalCue.RETRY); }
        } catch (RuntimeException unavailable) {
            disconnectVoiceService(); stopService(service);
            feedback("系统未允许开启锁屏会话，请保持 App 前台并检查权限", LocalCue.PERMISSION);
        }
    }
    private void disconnectVoiceService() {
        voiceService = null;
        if (voiceServiceBound) { voiceServiceBound = false; unbindService(voiceConnection); }
    }
    private void stopLockScreenSession() {
        callStartPending = false;
        if (voiceService != null) voiceService.stopSession("锁屏耳机会话已停止；下一次请手动开启");
        disconnectVoiceService();
        stopService(new Intent(this, VoiceSessionService.class));
        syncHeadsetControls();
    }

    private void toggleVoice() {
        if (session != null && sessionOperationNotice != null) { cancelSession(""); startVoice(); return; }
        if (session != null || repeatArmed || callStartPending) endVoice(); else startVoice();
    }
    private void endVoice() {
        if ("work".equals(mode()) && session != null && sessionUsesCapture) {
            stopRepeat(); session.stopRecording(); lastNotice = "正在结束并保存工作记录…"; updateStatus();
            if (visible) { tab = 2; showApp(); }  // Also hides pause/mark controls while saving.
            renderWorkMeetings();
        } else cancelSession("会话已结束，单击可重新开始");
    }
    private void startVoice() {
        listenMode = "work".equals(mode()) ? VoiceInteraction.NOTES : VoiceInteraction.CALL;
        if (listenMode == VoiceInteraction.CALL && (state.optJSONObject("capabilities") == null
                || !state.optJSONObject("capabilities").optBoolean("voice_realtime"))) {
            stopRepeat(); feedback("千问实时语音当前不可用，请配置后端并刷新连接状态后重试", LocalCue.RETRY); return;
        }
        if (!sessionAvailable() || permissionPending || !canAct()) return;
        if (!VoiceInteraction.allowed(listenMode, mode())) {
            stopRepeat(); headsetNotice(InteractionCopy.name(listenMode) + "不适用于" + modeName(mode()) + "，请重新选择通话与记录方式"); return;
        }
        if (listenMode == VoiceInteraction.NOTES && (state.optJSONObject("capabilities") == null
                || !state.optJSONObject("capabilities").optBoolean("work_asr"))) {
            stopRepeat(); feedback("工作录音转写当前不可用，请配置后端语音识别后重试", LocalCue.RETRY); return;
        }
        if (listenMode == VoiceInteraction.NOTES && !state.optJSONObject("capabilities").optBoolean("work_meetings")) {
            stopRepeat(); feedback("服务器尚未支持独立会议记录，请更新后端并刷新后再开始", LocalCue.RETRY); return;
        }
        int audioState = getSystemService(android.media.AudioManager.class).getMode();
        if (audioState == android.media.AudioManager.MODE_IN_CALL || audioState == android.media.AudioManager.MODE_IN_COMMUNICATION
            || audioState == android.media.AudioManager.MODE_RINGTONE) {
            stopRepeat(); stopLockScreenSession(); headsetNotice("来电或手机通话结束后再接通 AI"); return;
        }
        if (development.audioMode() == AudioMode.RISE_SDK) {
            HeadsetController.Snapshot snapshot = headset.snapshot();
            if (snapshot.inCall) { stopRepeat(); feedback("手机通话结束后再接通 AI", LocalCue.CALL); return; }
            if (WearState.from(snapshot.leftWorn, snapshot.rightWorn) == WearState.REMOVED) {
                stopRepeat(); feedback("耳机当前未佩戴，请佩戴后再开始", LocalCue.WEAR); return;
            }
        }
        ArrayList<String> missing = audioPermissions(development.audioMode() == AudioMode.RISE_SDK);
        if (visible && listenMode == VoiceInteraction.CALL && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("headset_controls", MODE_PRIVATE).getBoolean("notification_asked", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            getSharedPreferences("headset_controls", MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply();
        }
        if (!missing.isEmpty()) {
            if (!visible) { stopLockScreenSession(); return; }
            permissionPending = true; audioPermissionGeneration = sessionGeneration;
            requestPermissions(missing.toArray(new String[0]), 1); return;
        }
        if (development.audioMode() == AudioMode.RISE_SDK && !headset.snapshot().ready()) {
            stopRepeat(); showDeviceDetails(); feedback("请先连接耳机，显示就绪后再点击开始", LocalCue.CONNECT); return;
        }
        if (visible && listenMode == VoiceInteraction.CALL && !lockScreenEnabled() && !voiceServiceBound
                && getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            callStartPending = true; enableLockScreenSession();
            if (voiceServiceBound) { lastNotice = "正在接通 AI…"; updateStatus(); return; }
            callStartPending = false;
        }
        continuingCall = repeatArmed;
        repeatArmed = listenMode == VoiceInteraction.CALL; handler.removeCallbacks(repeatTick); hideKeyboard(); startSession(null);
    }
    private ArrayList<String> audioPermissions(boolean rise) {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
        if ((rise || development.audioMode().requiresBluetooth()) && Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (rise && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (rise && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return missing;
    }
    private void startSession(String text) { if (text != null) { stopRepeat(); clearVoicePreparation(); } newSession(); lastBusinessRequestId = session.requestId(); uncertainTurnId = ""; renderTurnDetails(); sessionUsesCapture = text == null; if (text == null) {
        boolean qwen = VoiceInteraction.directed(listenMode) && state.optJSONObject("capabilities") != null
            && state.optJSONObject("capabilities").optBoolean("voice_realtime");
        session.qwenRealtime(qwen);
        if (qwen && listenMode == VoiceInteraction.CALL && voicePreparation != null) {
            if (voicePreparation.claim(api, session)) preparationAttemptAt = -60000;
            voicePreparation = null;
            lastBusinessRequestId = session.requestId(); renderTurnDetails();
        } else clearVoicePreparation();
        session.workRecording(listenMode == VoiceInteraction.NOTES);
        session.streaming(qwen || state.optJSONObject("capabilities") != null && state.optJSONObject("capabilities").optBoolean("voice_stream"),
            state.optJSONObject("capabilities") != null && state.optJSONObject("capabilities").optBoolean("speech_stream"));
        session.interactionSource(VoiceInteraction.source(listenMode)).configure(listenMode == 2 ? settings().optInt("listen_seconds", 30) : 60,
            VoiceInteraction.endpoint(listenMode, settings().optBoolean("vad_enabled", true)), settings().optDouble("vad_threshold", .025))
            .callExperience(VoiceInteraction.directed(listenMode), !continuingCall,
                qwen || state.optJSONObject("capabilities") != null && state.optJSONObject("capabilities").optBoolean("voice_reply_stream")); session.start();
    } else { lastUser = text; conversationFeed.transcript(text); renderLiveTurn();
        session.streaming(false, state.optJSONObject("capabilities") != null && state.optJSONObject("capabilities").optBoolean("speech_stream"));
        session.startText(text); } }
    private void newSession() { newSession(null); }
    private void newSession(String operationNotice) {
        if (quickFeedback != null) quickFeedback.stop();
        // A recording or call owns the headset: release meeting playback before capture starts.
        if (meetingPlayback != null) meetingPlayback.stop();
        final boolean operationCue = operationNotice != null;
        final long generation = ++sessionGeneration;
        final int submittedInteraction = listenMode;
        sessionUsesCapture = false; sessionIsReplay = operationCue; sessionOperationNotice = operationNotice;
        turnSucceeded = operationCue; turnHasResult = false; recording = false;
        clearLiveTurn(); followingConversation = true;
        liveTranscript = ""; liveReplyText = ""; if (liveWords != null && !continuingCall) liveWords.setText("");
        lastNotice = operationCue ? operationNotice + "；正在播放操作提示" : continuingCall ? "我在，直接说就好" : "正在接通 AI…";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        diagnosticInput = operationCue ? "操作提示，不采集录音" : "本轮尚未录音";
        diagnosticOutput = "本轮尚未播放"; diagnosticError = "无"; diagnosticRms = 0; diagnosticBytes = 0;
        HostedSession.Listener listener = new HostedSession.Listener() {
            @Override public void status(String text) { if (!currentSession(generation)) return; lastNotice = operationCue && !text.startsWith(operationNotice) ? operationNotice + "；" + text : text; diagnosticStage = text; renderDiagnostics(); setStatusText(lastNotice); }
            @Override public void failure(String text) {
                if (!currentSession(generation)) return;
                if (!operationCue && !turnHasResult && session != null && session.requestId().equals(lastBusinessRequestId)) uncertainTurnId = lastBusinessRequestId;
                turnSucceeded = false; stopRepeat(); lastNotice = operationCue ? operationNotice + "；操作提示未播放，请检查耳机连接" : text;
                clearLiveTurn(); diagnosticError = text; renderDiagnostics(); renderTurnDetails(); updateStatus();
            }
            @Override public void input(String text) { if (!currentSession(generation)) return; diagnosticInput = text; renderDiagnostics(); }
            @Override public void output(String text) { if (!currentSession(generation)) return; diagnosticOutput = text; renderDiagnostics(); }
            @Override public void diagnostic(String text) { if (!currentSession(generation)) return; diagnosticSession = text; renderDiagnostics(); }
            @Override public void submitted(String id) {
                if (!currentSession(generation)) return;
                lastBusinessRequestId = id; turnHasResult = false;
            }
            @Override public void captionFinals(JSONArray sentences) {
                if (!currentSession(generation)) return;
                for (int i = 0; i < sentences.length(); i++) {
                    JSONObject sentence = sentences.optJSONObject(i);
                    if (sentence == null || sentence.optString("text").isEmpty()) continue;
                    workCaptionSentences.add(new SpeakerParagraphs.Sentence(sentence.optString("id"), sentence.optString("speaker"),
                        sentence.optLong("start_ms"), sentence.optString("text")));
                }
                renderWorkCaption();
            }
            @Override public void captionPartial(String text) {
                if (!currentSession(generation)) return;
                workCaptionPartial = text == null ? "" : text; renderWorkCaption();
            }
            @Override public void meeting(JSONObject value) {
                if (!currentSession(generation)) return;
                if (!value.optString("id").equals(workCaptionMeetingId)) {
                    workCaptionMeetingId = value.optString("id"); workCaptionSentences.clear(); workCaptionPartial = "";
                    workCaptionStartedAt = value.optString("started_at"); showApp();
                }
                JSONArray previous = array(state, "work_meetings"), updated = new JSONArray();
                updated.put(value);
                for (int i = 0; i < previous.length(); i++) {
                    JSONObject item = previous.optJSONObject(i);
                    if (item != null && !item.optString("id").equals(value.optString("id"))) updated.put(item);
                }
                put(state, "work_meetings", updated);
                if (!"recording".equals(value.optString("status"))) {
                    turnSucceeded = "saved".equals(value.optString("status"));
                    turnHasResult = true; uncertainTurnId = "";
                }
                renderWorkMeetings();
                if (visible && tab == 2) { handler.removeCallbacks(workMeetingPoll); handler.post(workMeetingPoll); }
            }
            @Override public void result(JSONObject value) {
                if (!currentSession(generation) || operationCue) return;
                if (value.has("meeting_id")) { turnHasResult = true; uncertainTurnId = ""; return; }
                turnSucceeded = true;
                acceptBusinessResult(value);
                if (value.optBoolean("voice_error")) { turnSucceeded = false; stopRepeat(); }
            }
            @Override public void transcript(String text) { if (!currentSession(generation)) return; liveTranscript = text; if (liveWords != null) liveWords.setText(text); if (!operationCue && submittedInteraction != VoiceInteraction.NOTES && conversationFeed.transcript(text)) renderLiveTurn(); }
            @Override public void reply(String text) { if (!currentSession(generation)) return; liveReplyText = text; recording = false; if (liveWords != null) liveWords.setText(text); if (!operationCue && conversationFeed.reply(text)) renderLiveTurn(); if (voiceButton != null) voiceButton.setText("结束会话"); if (callCaption != null) callCaption.setText("正在回答 · 说“小万”可打断 · 单击结束通话"); }
            @Override public void processing() { if (!currentSession(generation)) return; recording = false; if (voiceButton != null) voiceButton.setText(submittedInteraction == VoiceInteraction.NOTES ? "正在保存…" : "结束会话"); if (callCaption != null) callCaption.setText(submittedInteraction == VoiceInteraction.NOTES ? "正在保存工作记录" : "正在思考 · 单击结束会话"); }
            @Override public void idle() { if (!currentSession(generation)) return; turnSucceeded = listenMode == VoiceInteraction.CALL; if (!turnSucceeded) stopRepeat(); uncertainTurnId = ""; }
            @Override public void recording() { if (!currentSession(generation)) return; recording = true; if (voiceButton != null) voiceButton.setText(listenMode == VoiceInteraction.NOTES ? "结束并保存记录" : "结束会话"); if (callCaption != null) callCaption.setText(InteractionCopy.recording(listenMode, settings().optBoolean("vad_enabled", true))); setStatusText("正在听你说…");
                if (listenMode == VoiceInteraction.NOTES && visible && tab == 2) showApp(); }  // Show pause/mark controls.
            @Override public void meter(long bytes, double rms) { if (currentSession(generation)) { diagnosticBytes = bytes; diagnosticRms = rms; renderDiagnostics(); if (orb != null) orb.level = (float) Math.min(1, rms * 9);
                if (workClock != null && workClock.isAttachedToWindow() && session != null && !session.workPaused())
                    workClock.setText("已记录 " + TimeFormat.clock(session.workOffsetMs()) + (session.workMarks() > 0 ? " · 已标记 " + session.workMarks() + " 处" : "")); } }
            @Override public void done() {
                if (!currentSession(generation)) return;
                if (session != null && session.isDuplexCall()) stopRepeat();
                if (turnSucceeded) headsetPolicy.turnFinished(generation);
                clearLiveTurn();
                ++sessionGeneration;
                if (voiceService != null) voiceService.release(session, repeatArmed && listenMode == VoiceInteraction.CALL && turnSucceeded);
                session = null; recording = false; sessionOperationNotice = null;
                if (!turnSucceeded) { deferredMode = null; deferredOpenConversation = false; }
                if (deferredMode != null) {
                    String nextMode = deferredMode; boolean open = deferredOpenConversation;
                    deferredMode = null; deferredOpenConversation = false;
                    if (busy || permissionPending || locationListener != null) {
                        stopRepeat(); lastNotice = "另一项操作尚未完成，场景未切换；请完成后重新接通"; updateStatus(); return;
                    }
                    changeMode(nextMode, open); return;
                }
                if (!isDestroyed()) {
                    if (operationCue) {
                        boolean resume = repeatArmed && turnSucceeded && sessionAvailable();
                        if (!resume) stopRepeat();
                        if (voiceButton != null) voiceButton.setText(InteractionCopy.start(listenMode));
                        if (cancelButton != null) cancelButton.setVisibility(resume ? View.VISIBLE : View.GONE);
                        if (orb != null) orb.level = 0;
                        if (turnSucceeded) lastNotice = operationNotice + (resume ? "；继续聆听" : "；等待你手动开始");
                        if (resume) scheduleNextTurn();
                        updateStatus();
                        return; // Resume only the call already active before this mode change.
                    }
                    if (!turnSucceeded || !sessionAvailable() || (!visible && listenMode != VoiceInteraction.CALL) || (listenMode == 2 && !"life".equals(mode()))) stopRepeat();
                    if (repeatArmed) scheduleNextTurn();
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (voiceButton != null) voiceButton.setText(InteractionCopy.start(listenMode)); if (cancelButton != null) cancelButton.setVisibility(repeatArmed ? View.VISIBLE : View.GONE); if (orb != null) orb.level = 0;
                    if (api.vault.token().isEmpty()) { showAuth(0); authError.setText("登录已失效，请重新登录"); } else {
                        if (visible && submittedInteraction == VoiceInteraction.NOTES && !operationCue) { tab = 2; showApp(); }
                        updateStatus(); if (visible && !repeatArmed) refresh();
                    }
                }
            }
        };
        AudioMode input = development.audioMode();
        session = new HostedSession(this, api, AudioMode.RISE_SDK, new VendorCaptureSource(headset, !operationCue), headset::matchesOutput, listener);
        if (lockScreenEnabled()) voiceService.own(session);
        if (voiceButton != null) voiceButton.setText(operationCue ? "正在播放操作提示…" : "正在准备…"); if (cancelButton != null) cancelButton.setVisibility(View.VISIBLE); updateStatus();
    }
    private boolean currentSession(long generation) { return !isDestroyed() && generation == sessionGeneration; }
    private boolean hasHeadsetBoundSession() { return session != null && (sessionUsesCapture || sessionIsReplay); }
    private void scheduleNextTurn() {
        long delay = VoiceInteraction.nextDelay(listenMode, settings().optInt("life_poll_seconds", 300));
        if (headsetPolicy.hasPending()) delay = Math.max(delay, headsetPolicy.pendingDelay(android.os.SystemClock.elapsedRealtime()) + 1);
        repeatAt = android.os.SystemClock.elapsedRealtime() + delay;
        handler.removeCallbacks(repeatTick); handler.postDelayed(repeatTick, delay);
    }
    private void cancelSession(String reason) {
        callStartPending = false;
        audioPermissionGeneration = -1;
        if (modeChange != null) { modeChange.stopRetrying(); finishModeChange(modeChange); }
        stopRepeat(); clearHeadsetGesture(); ++sessionGeneration;
        deferredMode = null; deferredOpenConversation = false;
        String appliedNotice = sessionOperationNotice; sessionOperationNotice = null;
        HostedSession old = session; session = null; recording = false; turnSucceeded = false;
        clearLiveTurn();
        if (voiceService != null) voiceService.release(old);
        if (old != null) old.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (voiceButton != null) voiceButton.setText(InteractionCopy.start(listenMode));
        if (cancelButton != null) cancelButton.setVisibility(View.GONE);
        if (orb != null) orb.level = 0;
        lastNotice = appliedNotice == null ? reason : appliedNotice + "；操作提示已停止";
        diagnosticStage = "已取消"; renderDiagnostics(); updateStatus();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 7) { permissionPending = false; if (taskBoard != null) taskBoard.refreshReminders(); return; }
        if (code == 6) { permissionPending = false; renderMaintenanceStatus(); return; }
        if (code == 4) {
            permissionPending = false;
            if (grants.length == 0) return;
            for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) {
                feedback("锁屏会话需要麦克风、附近设备与通知权限；仍可使用前台对话", LocalCue.PERMISSION); return;
            }
            if (visible) enableLockScreenSession();
            return;
        }
        if (code == 5) {
            permissionPending = false;
            if (hasLocationPermission()) handler.post(startupLocationTick);
            else { lastNotice = "定位未授权，可直接说城市查天气"; updateStatus(); }
            return;
        }
        if (code == 2) { permissionPending = false; if (grants.length > 0 && hasLocationPermission()) locate(); else feedback("定位未授权，可继续使用目的地搜索与天气查询", LocalCue.PERMISSION); return; }
        if (code != 1 && code != 3) return; permissionPending = false;
        if (code == 1) {
            long requestedGeneration = audioPermissionGeneration; audioPermissionGeneration = -1;
            if (requestedGeneration < 0 || requestedGeneration != sessionGeneration) return;
        }
        for (int i = 0; i < grants.length; i++) if (grants[i] != PackageManager.PERMISSION_GRANTED && !Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
            stopRepeat();
            new AlertDialog.Builder(this).setTitle(development.audioMode().requiresBluetooth() ? "需要麦克风与附近设备权限" : "需要麦克风权限").setMessage("用于所选音频模式的采音与播放。你仍然可以使用文字对话；也可在系统设置中开启权限。")
                .setNegativeButton("使用文字", null).setPositiveButton("打开设置", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))).show(); return;
        }
        if (grants.length > 0 && visible) { if (code == 3) connectHeadset(); else startVoice(); }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; syncHeadsetControls();
        automaticHeadsetSuppressed = false;
        enterMaintenance("app_open");
        prepareVoice();
        if (taskBoard != null) taskBoard.refreshReminders();
        if (!pendingHeadsetNotice.isEmpty()) { String notice = pendingHeadsetNotice; pendingHeadsetNotice = ""; headsetNotice(notice); }
        if (orb != null) orb.invalidate();
        if (tab == 2) { handler.removeCallbacks(workMeetingPoll); handler.post(workMeetingPoll); }
    }
    private void stopRepeat() { repeatArmed = false; continuingCall = false; handler.removeCallbacks(repeatTick); if (session == null) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    @Override protected void onPause() {
        handler.removeCallbacks(automaticHeadsetTick);
        if (locationMonitor != null) locationMonitor.stop();
        if (statusPopup != null) statusPopup.dismiss();
        handler.removeCallbacks(startupLocationTick);
        handler.removeCallbacks(workMeetingPoll);
        clearVoicePreparation();
        visible = false; if (quickFeedback != null) quickFeedback.stop(); if (meetingPlayback != null) meetingPlayback.stop(); if (!lockScreenEnabled()) pauseModeChange(); clearHeadsetGesture(); syncHeadsetControls();
        if ((session != null || repeatArmed) && !lockScreenEnabled()) endVoice();
        else if (listenMode != VoiceInteraction.CALL && session != null && sessionUsesCapture) endVoice();
        stopLocation(); super.onPause();
    }
    @Override protected void onDestroy() { if (workOptionsDialog != null) workOptionsDialog.dismiss(); if (taskBoard != null) taskBoard.close(); if (locationMonitor != null) locationMonitor.close(); if (statusPopup != null) statusPopup.dismiss(); handler.removeCallbacks(automaticHeadsetTick); if (quickFeedback != null) quickFeedback.close(); if (meetingPlayback != null) meetingPlayback.close(); handler.removeCallbacks(workMeetingPlaybackTick); if (sourceDialog != null) { sourceDialog.dismiss(); sourceDialog = null; } stopLockScreenSession(); cancelSession(""); if (headset != null) { headset.removeListener(headsetListener); headset.removeControlListener(headsetControlListener); headset.close(); } worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("open_work_tasks", false) && api != null && !api.vault.token().isEmpty()) switchTab(2);
    }
    @Override public void onBackPressed() { if (session != null) { endVoice(); return; } if (!api.vault.token().isEmpty() && tab != 0) { switchTab(0); return; } if (api.vault.token().isEmpty() && authMode != 0) { showAuth(0); return; } super.onBackPressed(); }

    private void syncHeadsetControls() {
        if (headset == null || development == null || api == null) return;
        boolean wanted = sessionAvailable() && headsetControlsOptIn && development.audioMode() == AudioMode.RISE_SDK && !api.vault.token().isEmpty();
        if (controlsRequested == wanted) return;
        controlsRequested = wanted;
        if (!wanted) clearHeadsetGesture();
        headset.setControlsEnabled(wanted);
    }
    private HeadsetInteractionPolicy.Context headsetInteractionContext() {
        HeadsetController.Snapshot snapshot = headset.snapshot();
        return new HeadsetInteractionPolicy.Context(sessionAvailable(), headsetControlsOptIn && development.audioMode() == AudioMode.RISE_SDK
            && snapshot.controlsState == HeadsetController.ControlsState.ENABLED, !api.vault.token().isEmpty(), snapshot.ready(),
            snapshot.inCall, busy || permissionPending || locationListener != null, session != null || repeatArmed || callStartPending || modeChange != null || audioPermissionGeneration >= 0, recording,
            WearState.from(snapshot.leftWorn, snapshot.rightWorn) == WearState.REMOVED, deferredMode == null ? mode() : deferredMode, sessionGeneration,
            session != null && sessionOperationNotice != null, session != null && sessionUsesCapture && session.isReplyPlaying(),
            session != null && sessionUsesCapture && session.workCapturing());
    }
    private void handleHeadsetControl(HeadsetController.ControlEvent event) {
        HeadsetInteractionPolicy.Gesture gesture;
        switch (event.kind) {
            case TAP: case PRESS_SINGLE: gesture = HeadsetInteractionPolicy.Gesture.TOGGLE; break;
            case DOUBLE_TAP: case PRESS_DOUBLE: gesture = HeadsetInteractionPolicy.Gesture.NEXT_MODE; break;
            case TRIPLE_TAP: case PRESS_TRIPLE: gesture = HeadsetInteractionPolicy.Gesture.DISCARD_COMBINATION; break;
            case LONG_PRESS: case PRESS_LONG: gesture = HeadsetInteractionPolicy.Gesture.CANCEL; break;
            default: gesture = HeadsetInteractionPolicy.Gesture.IGNORE; break;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        applyHeadsetDecision(headsetPolicy.event(gesture, event.sequence, event.receivedAtMillis, now, headsetInteractionContext()));
        handler.removeCallbacks(settleHeadsetGesture);
        if (headsetPolicy.hasPending()) handler.postDelayed(settleHeadsetGesture, headsetPolicy.pendingDelay(now));
    }
    private void applyHeadsetDecision(HeadsetInteractionPolicy.Decision decision) {
        if (!decision.message.isEmpty()) headsetNotice(decision.message);
        for (HeadsetInteractionPolicy.Action action : decision.actions) {
            switch (action) {
                case CANCEL:
                    endVoice();
                    break;
                case CHANGE_MODE: changeMode(decision.mode); break;
                case DEFER_MODE: changeMode(decision.mode); break;
                case START:
                    if (session != null && sessionOperationNotice != null) cancelSession("");
                    stopRepeat();
                    listenMode = VoiceInteraction.CALL;
                    int targetTab = "work".equals(mode()) ? 2 : 0;
                    if (tab != targetTab) { tab = targetTab; showApp(); }
                    startVoice();
                    break;
                case STOP_AND_SEND:
                    endVoice();
                    break;
                case MARK:
                    // No earbud cue: the capture owns the headset audio path while recording.
                    if (session != null && session.markWork("headset")) {
                        feedback("耳机已标记 " + TimeFormat.clock(session.workOffsetMs()), null);
                        if (visible && tab == 2) showApp();
                    }
                    break;
                case INTERRUPT:
                    if (session != null && sessionUsesCapture) session.interruptReply();
                    else if (repeatArmed) { handler.removeCallbacks(repeatTick); repeatAt = 0; handler.post(repeatTick); }
                    break;
            }
        }
    }
    private void clearHeadsetGesture() { headsetPolicy.clearPending(); handler.removeCallbacks(settleHeadsetGesture); }
    private void headsetNotice(String message) {
        if (!visible) { pendingHeadsetNotice = message; return; }
        LocalCue cue = message.contains("电量较低") ? LocalCue.BATTERY
            : message.contains("未佩戴") || message.contains("已摘下") ? LocalCue.WEAR
            : message.contains("已佩戴") ? LocalCue.READY : null;
        feedback(message, cue);
    }
    private static String modeName(String value) { return ModeCopy.name(value); }

    private String conversationHeading() {
        return "work".equals(mode()) ? "专注当下。" : "commute".equals(mode()) ? "一路，有我。" : "我在，听你说。";
    }

    private void renderModeSelection() {
        if (tab != 0 || modeChoices == null) return;
        String[] keys = {"life", "work", "commute"};
        for (int i = 0; i < keys.length && i < modeChoices.getChildCount(); i++) {
            TextView choice = (TextView) modeChoices.getChildAt(i);
            boolean selected = keys[i].equals(mode());
            styleSegment(choice, selected);
            choice.setContentDescription(ModeCopy.name(keys[i]) + (selected ? "，已选中" : ""));
        }
        if (modeHint != null) modeHint.setText(conversationHeading());
    }

    private void setStatusText(String text) {
        if (status == null) return;
        status.setText(text.isEmpty() ? "" : "●  " + text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void updateStatus() {
        setStatusText(busy ? "正在同步…" : lastNotice);
        if (status != null) status.setTextColor(busy ? MUTED : ACCENT);
        if (voiceButton != null && session == null) voiceButton.setText(callStartPending ? "正在接通…" : repeatArmed ? "通话中 · 我在听" : InteractionCopy.start(listenMode));
        if (cancelButton != null) { cancelButton.setVisibility(session != null || repeatArmed || callStartPending ? View.VISIBLE : View.GONE); cancelButton.setText(InteractionCopy.stop(listenMode)); }
        if (callCaption != null && session == null) callCaption.setText(InteractionCopy.ready(listenMode));
    }
    private void connectBackend() {
        if (locationMonitor != null) locationMonitor.stop();
        startupLocationAttempted = false;
        clearVoicePreparation(); preparationAttemptAt = -60000;
        ApiClient next = new ApiClient(this, development.serverUrl());
        if (api != null && !api.serverUrl.equals(next.serverUrl)) TaskReminders.clear(this);
        api = next;
        next.setDiagnostics(message -> runOnUiThread(() -> {
            if (isDestroyed() || api != next) return;
            if (message.contains("request_id=") && (session == null || !message.contains("request_id=" + session.requestId()))) return;
            diagnosticRequest = message; renderDiagnostics();
        }));
    }
    private String audioModeLabel() { return development.audioMode().label(); }
    private String voiceHintText() {
        return "work".equals(mode()) ? "工作录音只转写保存，不执行录音中的指令；再次单击结束并保存。"
            : "单击开始连续语音问答，再次单击结束整场会话；双击切换模式后待机。";
    }
    private String weatherPreferenceKey() { return "weather." + ServerEndpoint.sessionNamespace(api.serverUrl) + "." + api.vault.username(); }
    private void developmentEntry(LinearLayout parent) {
        LinearLayout details = disclosure(parent, "连接设置", "Rise · 本地或远端服务", false);
        developmentSummary = label(details, "音频 · " + audioModeLabel() + "\n" + api.serverUrl, 11, ACCENT);
        button(details, "连接设置", false, this::showDevelopmentSettings);
        button(details, "检查后端连接", false, () -> { if (canAct()) request(() -> {
            JSONObject health = api.json("GET", "/healthz", null);
            if (!api.vault.token().isEmpty()) health.put("account_state", api.json("GET", "/v1/state", null));
            return health;
        }, r -> {
            JSONObject current = r.optJSONObject("account_state");
            String availability = "请登录后检查实时语音配置";
            if (current != null) {
                state = current;
                JSONObject caps = state.optJSONObject("capabilities");
                availability = caps != null && caps.optBoolean("voice_realtime")
                    ? "千问实时语音已配置（实际通话仍需验证）" : "千问实时语音未配置，请检查电脑后端";
                showApp();
            }
            new AlertDialog.Builder(this).setTitle("后端可达").setMessage(api.serverUrl + "\n" + availability).setPositiveButton("完成", null).show();
        }); });
    }
    private RadioGroup choices(LinearLayout parent, String[] options, int selected) {
        RadioGroup group = new RadioGroup(this); parent.addView(group);
        for (int i = 0; i < options.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setText(options[i]);
            option.setTextColor(INK); option.setTextSize(13); option.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            group.addView(option); if (i == selected) group.check(option.getId());
        }
        return group;
    }
    private void showDevelopmentSettings() {
        if (permissionPending || !canAct()) return;
        stopRepeat(); clearHeadsetGesture();
        LinearLayout body = dialogBody();
        label(body, "音频模式", 15, ACCENT);
        label(body, "Rise SDK 麦克风", 15, INK);
        label(body, "连接 Rise 后即可通话，声音直接来自耳机麦克风。", 11, MUTED);
        label(body, "后端", 15, ACCENT);
        RadioGroup backend = choices(body, new String[]{backendChoiceLabel("本地电脑服务（USB）", ServerEndpoint.USB_LOCAL), backendChoiceLabel("远端服务器", BuildConfig.SERVER_URL)}, development.localBackend() ? 0 : 1);
        label(body, "默认连接本地电脑服务，通过 USB 连接手机。也可选择远端服务；两端账号独立，切回时恢复各自登录。", 11, MUTED);
        ScrollView container = new ScrollView(this); container.addView(body);
        new AlertDialog.Builder(this).setTitle("连接设置").setView(container).setNegativeButton("取消", null)
            .setPositiveButton("应用", (dialog, which) -> {
                if (permissionPending || !canAct()) return;
                AudioMode selected = AudioMode.RISE_SDK;
                boolean localBackend = backend.getCheckedRadioButtonId() == backend.getChildAt(0).getId();
                if (selected == development.audioMode() && localBackend == development.localBackend()) return;
                stopLockScreenSession(); cancelSession(""); if (development.audioMode() == AudioMode.RISE_SDK && selected != AudioMode.RISE_SDK) headset.disconnect();
                development.save(selected, localBackend);
                syncHeadsetControls();
                if (api.serverUrl.equals(development.serverUrl())) {
                    // Audio selection does not change the account, view tree, drafts or results.
                    if (voiceHint != null) voiceHint.setText(voiceHintText());
                    if (developmentSummary != null) developmentSummary.setText("音频 · " + audioModeLabel() + "\n" + api.serverUrl);
                    renderHeadset();
                    feedback("音频已切换，登录和当前页面已保留", LocalCue.AUDIO);
                    return;
                }
                chatDraft = ""; if (chatInput != null) chatInput.setText("");
                clearPrivateSnapshots(); connectBackend(); state = new JSONObject(); tab = 0; lastNotice = "";
                diagnosticInput = "尚未录音"; diagnosticOutput = "尚未播放"; diagnosticRequest = "尚未请求"; diagnosticSession = "尚未开始"; diagnosticStage = "空闲"; diagnosticError = "无"; diagnosticRms = 0; diagnosticBytes = 0;
                if (api.vault.token().isEmpty()) showAuth(0); else { showApp(); refresh(); }
            }).show();
    }
    private String backendChoiceLabel(String title, String serverUrl) {
        String endpoint = ServerEndpoint.validate(serverUrl, true);
        boolean saved = endpoint.equals(api.serverUrl) && !api.vault.token().isEmpty()
            || AuthVault.hasSavedSession(this, ServerEndpoint.sessionNamespace(endpoint));
        return title + "\n" + (saved ? "已保存登录 · 切回自动恢复" : "未登录 · 登录后自动记住");
    }
    private void renderDiagnostics() {
        if (!BuildConfig.DEBUG || diagnosticsView == null || isDestroyed()) return;
        diagnosticsView.setText("音频：" + audioModeLabel() + "\n后端：" + api.serverUrl
            + "\n输入：" + diagnosticInput + "\n输出：" + diagnosticOutput
            + String.format(Locale.CHINA, "\n录音 RMS：%.4f · %.1f 秒 · %d 字节", diagnosticRms, diagnosticBytes / 32000.0, diagnosticBytes)
            + "\n请求：" + diagnosticRequest + "\n会话：" + diagnosticSession
            + "\n设备：" + headset.snapshot().diagnostics + "\n流程：" + diagnosticStage + "\n最近错误：" + diagnosticError);
    }
    private void renderHeadset() {
        if (headset == null) return;
        HeadsetController.Snapshot snapshot = headset.snapshot();
        if (headsetStatus != null) headsetStatus.setText(development.audioMode() == AudioMode.RISE_SDK ? snapshot.summary() + " · 设备详情" : audioModeLabel() + " · 设备详情");
        if (deviceDetails != null) deviceDetails.setText(snapshot.summary() + "\n输入来源：" + audioModeLabel()
            + "\n型号：" + snapshot.model + "\n左耳电量：" + knownBattery(snapshot.leftBattery) + " · 右耳电量：" + knownBattery(snapshot.rightBattery)
            + "\n左耳佩戴：" + knownWorn(snapshot.leftWorn) + " · 右耳佩戴：" + knownWorn(snapshot.rightWorn)
            + "\n触控联动：" + snapshot.controlsSummary
            + "\n实际触控事件：" + snapshot.controlEventCount + " 次 · " + snapshot.lastControlEvent
            + "\n通话状态：" + (snapshot.inCall ? "检测到通话，已暂停 AI" : "未检测到通话")
            + "\n实际输入：" + diagnosticInput + "\n实际输出：" + diagnosticOutput
            + (BuildConfig.DEBUG ? "\n\n开发诊断\n" + snapshot.diagnostics : ""));
        renderDiagnostics();
    }
    private static String knownBattery(Integer value) { return value == null ? "未知" : value + "%"; }
    private static String knownWorn(Boolean value) { return value == null ? "未知" : value ? "已佩戴" : "未佩戴"; }
    private void showDeviceDetails() {
        LinearLayout body = dialogBody();
        deviceDetails = label(body, "", 13, INK); renderHeadset();
        label(body, "先在手机系统配对 Rise，再连接耳机。显示就绪后，点一下接通 AI 就可以一直聊。", 12, MUTED);
        if (development.audioMode() == AudioMode.RISE_SDK) {
            button(body, "连接 / 重新连接 Rise", true, () -> { if (canAct()) connectHeadset(); });
            button(body, "断开耳机", false, () -> { automaticHeadsetSuppressed = true; cancelSession("耳机已断开"); headset.disconnect(); });
            if (BuildConfig.DEBUG) button(body, "结束耳机本地录音（调试）", false, () -> confirm("结束耳机本地录音？",
                "这会结束耳机自身正在进行的录音片段。确认当前录音可以结束后再继续；结束后可回到对话页接通 AI。",
                () -> headset.stopLocalRecording(message -> { if (!isDestroyed()) feedback(message, null); })));
            CheckBox controls = new CheckBox(this); controls.setText("开启耳机触控联动（前台或已开启的锁屏会话）");
            controls.setTextColor(INK); controls.setChecked(headsetControlsOptIn); body.addView(controls);
            controls.setOnCheckedChangeListener((button, checked) -> {
                headsetControlsOptIn = checked;
                getSharedPreferences("headset_controls", MODE_PRIVATE).edit().putBoolean("touch_enabled", checked).apply();
                clearHeadsetGesture(); syncHeadsetControls();
                feedback(checked ? "已申请触控联动；请看设备状态并实际测试手势" : "触控联动已关闭", checked ? LocalCue.TOUCH : LocalCue.UNTOUCH);
            });
            label(body, "单击 / 单捏：待机时开始；聆听、思考或播报时均结束整场会话。工作录音停止后提交并保存。\n双击 / 双捏：生活 → 工作 → 通勤 → 生活；先结束当前会话，工作先保存，AI 停止播报；目标模式保持待机。\n长按 / 长捏：结束当前会话；工作录音停止后提交并保存。已提交内容可查看历史。\n\n单击、双击等待 400 毫秒组合判定；三击只撤销尚在判定的动作。短按、释放、滑动不绑定操作。\n\n仅响应 SDK 实际上报的事件，不修改耳机系统手势或固件。开启成功不等于所有手势已经实测。", 11, MUTED);
            label(body, "单击开始或结束；双击按生活、工作、通勤循环切换。工作记录先提交再切换。断连、摘下或来电后不会自动开麦。", 11, MUTED);
        } else label(body, "当前使用 " + audioModeLabel() + "。Rise 接入在开发设置中选择，实物验收后再调整发布策略。", 12, MUTED);
        button(body, "打开系统蓝牙设置", false, () -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        ScrollView container = new ScrollView(this); container.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("耳机与音频").setView(container).setPositiveButton("完成", null).create();
        dialog.setOnDismissListener(d -> deviceDetails = null); dialog.show();
    }
    private void connectHeadset() {
        if (permissionPending || !canAct()) return;
        automaticHeadsetSuppressed = false;
        ArrayList<String> missing = audioPermissions(true);
        if (!missing.isEmpty()) { permissionPending = true; requestPermissions(missing.toArray(new String[0]), 3); return; }
        try {
            java.util.List<HeadsetController.DeviceChoice> devices = headset.pairedDevices();
            if (devices.size() <= 1) { headset.connect(); return; }
            String[] labels = new String[devices.size()]; for (int i = 0; i < labels.length; i++) labels[i] = devices.get(i).label;
            new AlertDialog.Builder(this).setTitle("选择本次使用的 Rise").setItems(labels, (d, index) -> headset.connect(devices.get(index).address)).setNegativeButton("取消", null).show();
        } catch (SecurityException e) { showError("附近设备权限不可用，请在系统设置授权后重试"); }
    }
    private void showError(String message) {
        if (authError != null) { authError.setText(message); return; }
        if (message.contains("登录已失效")) { new AlertDialog.Builder(this).setTitle("请重新登录").setMessage(message).setCancelable(false).setPositiveButton("去登录", (d, w) -> signOut()).show(); return; }
        new AlertDialog.Builder(this).setTitle("暂时未能完成").setMessage(message).setPositiveButton("知道了", null).show();
    }
    private void confirm(String title, String message, Runnable action) { if (!canAct()) return; new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("确认", (d, w) -> action.run()).show(); }
    private void scrollDialog(String title, LinearLayout body) {
        if (sourceDialog != null) sourceDialog.dismiss();
        ScrollView sc = new ScrollView(this); sc.addView(body);
        sourceDialog = new AlertDialog.Builder(this).setTitle(title).setView(sc).setPositiveButton("完成", null).create();
        sourceDialog.show();
    }
    private void copy(String label, String text) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text)); feedback("已复制", LocalCue.COPIED); }
    private void feedback(String text, LocalCue cue) {
        if (isDestroyed()) return;
        lastNotice = text;
        updateStatus();
        setStatusText(text);
        if (dialogFeedback != null && dialogFeedback.isAttachedToWindow()) {
            dialogFeedback.setText(text);
            dialogFeedback.setVisibility(View.VISIBLE);
        }
        if (cue != null && quickFeedback != null) quickFeedback.say(cue);
    }
    private boolean canSpeakFeedback() {
        if (!visible || isDestroyed() || session != null || repeatArmed || callStartPending || headset == null
            || meetingPlayback != null && meetingPlayback.active()) return false;
        HeadsetController.Snapshot snapshot = headset.snapshot();
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        return snapshot.ready() && !snapshot.inCall
            && !(Boolean.FALSE.equals(snapshot.leftWorn) && Boolean.FALSE.equals(snapshot.rightWorn))
            && audio.getMode() == android.media.AudioManager.MODE_NORMAL;
    }
    private void hideKeyboard() { View focus = getCurrentFocus(); if (focus != null) { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); } }
    private JSONObject settings() { JSONObject s = state.optJSONObject("settings"); return s == null ? new JSONObject() : s; }
    private String mode() { JSONObject agent = state.optJSONObject("agent"); return agent == null ? settings().optString("mode", "life") : agent.optString("mode", settings().optString("mode", "life")); }
    private static JSONArray array(JSONObject object, String key) { JSONArray a = object.optJSONArray(key); return a == null ? new JSONArray() : a; }
    private static JSONObject cloneJson(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception e) { return new JSONObject(); } }
    private static JSONObject json(Object... pairs) { JSONObject value = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(value, pairs[i].toString(), pairs[i + 1]); return value; }
    private static void put(JSONObject value, String key, Object object) { try { value.put(key, object); } catch (Exception ignored) { } }
    private static String fallback(String text, String other) { return text == null || text.isEmpty() ? other : text; }
    private static String value(EditText field) { return field.getText().toString().trim(); }
    private static String date(String raw) { return TimeFormat.format(raw); }
    private static String kind(String raw) { return "preference".equals(raw) ? "偏好" : "task".equals(raw) ? "待办" : "trip".equals(raw) ? "出行" : "事实"; }
    private static boolean pinned(JSONObject memory) { return memory.optBoolean("pinned") || memory.optInt("pinned", 0) == 1; }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout dialogBody() {
        LinearLayout body = column(); body.setPadding(dp(24), dp(8), dp(24), dp(16));
        dialogFeedback = label(body, "", 12, ACCENT);
        dialogFeedback.setVisibility(View.GONE);
        return body;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(16), dp(18), dp(16)); view.setBackground(shape(BG, 20, LINE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(8)); parent.addView(view, p); return view;
    }
    private LinearLayout featureCard(LinearLayout parent, int tint) {
        LinearLayout view = card(parent); view.setPadding(dp(20), dp(20), dp(20), dp(18)); view.setBackground(shape(tint, 24, tint)); return view;
    }
    private int pageAccent() { return tab == 1 ? MEMORY : tab == 2 ? WORK : tab == 3 ? COMMUTE : ACCENT; }
    private void eyebrow(LinearLayout parent, String text, int color) {
        TextView view = label(parent, text, 10, color); view.setLetterSpacing(.12f); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }
    private void badge(LinearLayout parent, String text, int color, int fill) {
        TextView view = label(parent, text, 11, color); view.setPadding(dp(10), dp(5), dp(10), dp(5)); view.setBackground(shape(fill, 8, fill));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(6); view.setLayoutParams(p);
    }
    private void metric(LinearLayout parent, String number, String caption, int color) {
        LinearLayout box = column(); box.setPadding(0, dp(12), 0, 0); parent.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label(box, number, 36, color); value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label(box, caption, 11, MUTED);
    }
    private TextView sectionHeader(LinearLayout parent, String title, String subtitle, String actionText, Runnable action) {
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(0, dp(20), 0, dp(6)); parent.addView(header);
        LinearLayout text = column(); header.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView heading = label(text, title, 18, INK); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        if (!subtitle.isEmpty()) label(text, subtitle, 12, MUTED);
        if (action != null) {
            TextView link = label(header, actionText, 13, pageAccent()); link.setGravity(Gravity.CENTER); link.setMinHeight(dp(48)); link.setPadding(dp(12), dp(10), 0, dp(10));
            link.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); link.setBackground(ripple(Color.TRANSPARENT, 12, Color.TRANSPARENT)); link.setFocusable(true); link.setOnClickListener(v -> action.run());
            return link;
        }
        return null;
    }
    private GradientDrawable shape(int color, int radius, int border) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), border); return d; }
    private RippleDrawable ripple(int color, int radius, int border) { return new RippleDrawable(ColorStateList.valueOf(0x180071e3), shape(color, radius, border), shape(Color.WHITE, radius, Color.WHITE)); }
    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern");
        view.setLineSpacing(dp(3), 1.06f); view.setPadding(0, dp(4), 0, dp(4)); parent.addView(view); return view;
    }
    private void pageHeading(String title) {
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); page.addView(header);
        LinearLayout words = column(); header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView heading = label(words, title, 32, INK); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); heading.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        String subtitle = "记忆".equals(title) ? "重要的事，替你记得。" : "工作".equals(title) ? "把注意力，留给眼前。" : "通勤".equals(title) ? "从容出发，安心抵达。" : "你的日常，由你定义。";
        label(words, subtitle, 13, MUTED);
        if (getResources().getConfiguration().fontScale < 1.5f) {
            SceneMarkView mark = new SceneMarkView(this, tab, pageAccent()); header.addView(mark, new LinearLayout.LayoutParams(dp(76), dp(76)));
        }
        gap(page, 16);
    }
    private LinearLayout disclosure(LinearLayout parent, String title, String summary, boolean expanded) {
        LinearLayout shell = card(parent); shell.setPadding(dp(16), dp(8), dp(16), dp(8));
        shell.setBackground(shape(CARD, 18, CARD));
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); shell.addView(header);
        LinearLayout words = column(); header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle = label(words, title, 15, INK);
        toggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (!summary.isEmpty()) { TextView detail = label(words, summary, 12, MUTED); detail.setPadding(0, 0, 0, dp(4)); }
        TextView indicator = label(header, expanded ? "−" : "+", 20, MUTED); indicator.setGravity(Gravity.CENTER); indicator.setLayoutParams(new LinearLayout.LayoutParams(dp(32), -2));
        header.setMinimumHeight(dp(52)); header.setBackground(ripple(CARD, 12, CARD)); header.setFocusable(true);
        LinearLayout body = column(); body.setPadding(0, dp(8), 0, dp(14)); shell.addView(body); body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setContentDescription(title + (summary.isEmpty() ? "" : "，" + summary) + (expanded ? "，已展开，点击收起" : "，已收起，点击展开"));
        View.OnClickListener listener = v -> {
            boolean open = body.getVisibility() != View.VISIBLE; body.setVisibility(open ? View.VISIBLE : View.GONE);
            indicator.setText(open ? "−" : "+");
            header.setContentDescription(title + (summary.isEmpty() ? "" : "，" + summary) + (open ? "，已展开，点击收起" : "，已收起，点击展开"));
        };
        header.setOnClickListener(listener); toggle.setOnClickListener(listener);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return body;
    }
    private void fillPrompt(String prompt) {
        if (chatInput == null || !canAct()) return;
        chatInput.setText(prompt); chatInput.setSelection(chatInput.length()); chatInput.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT);
    }
    private EditText input(String hint, boolean password, int max) {
        EditText view = new EditText(this); view.setTextColor(INK); view.setHintTextColor(MUTED); view.setTextSize(14); view.setHint(hint); view.setContentDescription(hint);
        view.setMinHeight(dp(52)); view.setPadding(dp(16), dp(14), dp(16), dp(14)); view.setBackground(shape(BG, 14, LINE));
        view.setOnFocusChangeListener((v, focused) -> v.setBackground(shape(BG, 14, focused ? ACCENT : LINE)));
        view.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)}); view.setSingleLine(password); return view;
    }
    private EditText field(LinearLayout parent, String title, String hint, boolean password, int max) {
        TextView caption = label(parent, title, 12, MUTED); EditText input = input(hint, password, max);
        input.setId(View.generateViewId()); caption.setLabelFor(input.getId()); input.setContentDescription(title);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); parent.addView(input, p); return input;
    }
    private Button button(LinearLayout parent, String text, boolean primary, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setTextSize(14); view.setAllCaps(false); view.setTextColor(primary ? BG : ACCENT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setBackground(ripple(primary ? ACCENT : BG, 16, primary ? ACCENT : LINE));
        view.setStateListAnimator(null); view.setMinHeight(dp(52)); view.setMinimumHeight(dp(52)); view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = parent.getOrientation() == LinearLayout.HORIZONTAL
            ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL && parent.getChildCount() > 0) p.leftMargin = dp(8);
        p.topMargin = dp(10); p.bottomMargin = dp(4); parent.addView(view, p); view.setOnClickListener(v -> action.run()); return view;
    }
    private TextView action(LinearLayout parent, String text, Runnable action) {
        TextView v = new TextView(this); v.setText(text); v.setTextColor(ACCENT); v.setTextSize(14); v.setGravity(Gravity.CENTER); v.setFocusable(true); v.setBackground(ripple(BG, 18, BG));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48)); p.leftMargin = dp(8); parent.addView(v, p); v.setOnClickListener(w -> action.run()); return v;
    }
    private void small(LinearLayout parent, String text, Runnable action) {
        TextView v = label(parent, text, 12, ACCENT); v.setGravity(Gravity.CENTER); v.setPadding(dp(5), dp(10), dp(5), dp(10)); v.setMinHeight(dp(48));
        v.setLayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
        v.setBackground(ripple(Color.TRANSPARENT, 12, Color.TRANSPARENT)); v.setFocusable(true); v.setOnClickListener(w -> action.run());
    }
    private void segment(LinearLayout parent, String text, boolean selected, Runnable action) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(12); v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(10), dp(4), dp(10)); v.setMinHeight(dp(48)); styleSegment(v, selected);
        v.setFocusable(true); v.setContentDescription(text + (selected ? "，已选中" : ""));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(2), 0, dp(2), 0); parent.addView(v, p); v.setOnClickListener(w -> action.run());
    }
    private void styleSegment(TextView view, boolean selected) {
        view.setSelected(selected); view.setTextColor(selected ? INK : MUTED);
        view.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setBackground(ripple(selected ? BG : Color.TRANSPARENT, 12, selected ? LINE : Color.TRANSPARENT));
    }
    private void empty(LinearLayout parent, String title, String subtitle) {
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); box.setPadding(dp(16), dp(26), dp(16), dp(26));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); parent.addView(box, p);
        box.setBackground(shape(CARD, 20, CARD));
        IconView icon = new IconView(tab == 0 ? 0 : tab, pageAccent()); box.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26))); gap(box, 10);
        label(box, title, 15, INK).setGravity(Gravity.CENTER); label(box, subtitle, 12, MUTED).setGravity(Gravity.CENTER);
    }

    private final class IconView extends View {
        final int icon, color; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        IconView(int i, int c) { super(MainActivity.this); icon = i; color = c; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) { c.save(); c.scale(getWidth() / 24f, getHeight() / 24f); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setStrokeCap(Paint.Cap.ROUND);
            if (icon == 0) { c.drawRoundRect(3, 3, 21, 18, 5, 5, p); c.drawLine(7, 18, 7, 22, p); c.drawLine(7, 22, 12, 18, p); c.drawLine(8, 9, 8, 12, p); c.drawLine(12, 7, 12, 14, p); c.drawLine(16, 9, 16, 12, p); }
            else if (icon == 1) { c.drawRoundRect(4, 3, 20, 21, 3, 3, p); c.drawLine(8, 8, 16, 8, p); c.drawLine(8, 12, 16, 12, p); c.drawLine(8, 16, 12, 16, p); }
            else if (icon == 2) { c.drawRoundRect(2, 7, 22, 21, 3, 3, p); c.drawRoundRect(8, 3, 16, 7, 2, 2, p); c.drawLine(3, 12, 21, 12, p); c.drawLine(12, 10, 12, 15, p); }
            else if (icon == 3) { path.reset(); path.moveTo(12, 22); path.cubicTo(8, 16, 4, 12, 4, 8); path.cubicTo(4, -1, 20, -1, 20, 8); path.cubicTo(20, 12, 16, 16, 12, 22); c.drawPath(path, p); c.drawCircle(12, 8, 3, p); }
            else { c.drawCircle(12, 7, 4, p); c.drawArc(4, 13, 20, 28, 180, 180, false, p); } c.restore(); }
    }
}
