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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.aidol.demo.hardware.HeadsetController;
import com.aidol.demo.hardware.VendorCaptureSource;

/** Foreground Android client. Credentials and provider protocols stay outside the UI. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff0a1115, CARD = 0xff131f25, LINE = 0xff27383f;
    private static final int INK = 0xffedf7f6, MUTED = 0xff91a7ac, TEAL = 0xff8ce8d4, RED = 0xffff9c99;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ApiClient api;
    private DevelopmentSettings development;
    private HeadsetController headset;
    private TextView headsetStatus, deviceDetails, voiceHint, developmentSummary;
    private long sessionGeneration;
    private String diagnosticSession = "尚未开始";
    private boolean sessionUsesCapture;
    private ModeChangeRetry modeChange;
    private Runnable modeChangeDelay;
    private boolean sessionIsReplay;
    private String sessionOperationNotice;
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
        renderHeadset();
        if (development.audioMode() == AudioMode.RISE_SDK) {
            HeadsetInteractionPolicy.DeviceUpdate update = headsetPolicy.device(new HeadsetInteractionPolicy.Device(
                snapshot.ready(), snapshot.inCall, snapshot.leftWorn, snapshot.rightWorn,
                snapshot.leftBattery, snapshot.rightBattery), hasHeadsetBoundSession() || this.repeatArmed);
            if (update.stopReason != null) cancelSession(update.stopReason);
            if (!update.notices.isEmpty()) headsetNotice(android.text.TextUtils.join("；", update.notices));
        }
        syncHeadsetControls();
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
    private OrbView orb;
    private HostedSession session;
    private boolean busy, recording, visible;
    private int tab, authMode;
    private String memoryFilter = "", lastNotice = "", lastAnswer = "", lastUser = "";
    private byte[] exportData;
    private android.location.LocationListener locationListener;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable locationTimeout = () -> { stopLocation(); showError("定位超时，请到开阔位置重试，或检查系统定位开关"); };
    private int listenMode;
    private boolean repeatArmed, permissionPending, turnSucceeded;
    private long repeatAt;
    private final Runnable repeatTick = new Runnable() {
        @Override public void run() {
            if (!repeatArmed || !visible || tab != 0 || api.vault.token().isEmpty()) return;
            if (listenMode == 2 && !"life".equals(mode())) { stopRepeat(); lastNotice = "场景已切换，定时聆听已停止"; showApp(); return; }
            long remaining = repeatAt - android.os.SystemClock.elapsedRealtime();
            if (session == null && !busy && locationListener == null && !permissionPending && remaining <= 0) { startVoice(); return; }
            if (session == null && !busy && status != null && remaining > 0) status.setText("●  " + (listenMode == 2 ? "定时聆听" : "连续对话") + " · " + Math.max(1, (remaining + 999) / 1000) + " 秒后继续 · 点停止结束");
            handler.postDelayed(this, 1000);
        }
    };
    private interface Job { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject value) throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        development = new DevelopmentSettings(this);
        headsetControlsOptIn = getSharedPreferences("headset_controls", MODE_PRIVATE).getBoolean("touch_enabled", false);
        headset = new HeadsetController(getApplicationContext());
        headset.addListener(headsetListener);
        headset.addControlListener(headsetControlListener);
        connectBackend();
        if (api.vault.token().isEmpty()) showAuth(0); else { showApp(); refresh(); }
    }

    private void base() {
        diagnosticsView = null;
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
        authMode = mode; status = null; voiceButton = null; cancelButton = null; content = null; base();
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true);
        root.addView(sc, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout body = column(); body.setPadding(dp(26), dp(24), dp(26), dp(30)); sc.addView(body);
        TextView brand = label(body, "A I D O L    /    聆", 14, TEAL); brand.setLetterSpacing(.12f);
        OrbView mark = new OrbView(); body.addView(mark, new LinearLayout.LayoutParams(-1, dp(156)));
        label(body, mode == 1 ? "开启你的专属空间" : mode == 2 ? "找回与你的连接" : "让每一刻，都被听见。", 27, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(body, mode == 1 ? "一个账号，连接对话、记忆和工作。" : mode == 2 ? "使用注册时保存的恢复码设置新密码。" : "一个懂你的随身伙伴，随时倾听，记住重要。", 14, MUTED);
        if (BuildConfig.DEBUG) label(body, "当前：" + (development.localBackend() ? "本地电脑服务（USB）" : "远端服务器") + " · 两处账号独立", 12, TEAL);
        gap(body, 18);
        if (mode != 2) {
            LinearLayout tabs = row(); body.addView(tabs);
            segment(tabs, "登录", mode == 0, () -> { if (!busy) showAuth(0); });
            segment(tabs, "创建账号", mode == 1, () -> { if (!busy) showAuth(1); });
            gap(body, 12);
        }
        EditText username = field(body, "账号", "3–24 位字母、数字、下划线", false, 24);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText password = field(body, mode == 2 ? "新密码" : "密码", "8–128 位，请妥善保存", true, 128);
        EditText confirm = mode == 0 ? null : field(body, "确认密码", "再输入一次密码", true, 128);
        EditText recovery = mode == 2 ? field(body, "账号恢复码", "粘贴之前保存的恢复码", false, 128) : null;
        CheckBox consent = new CheckBox(this); consent.setTextColor(MUTED); consent.setTextSize(12);
        consent.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
        consent.setText("我已了解：录音和文字会发送到服务器及 AI 服务处理；账号数据可导出、清空或删除。");
        if (mode == 1) body.addView(consent);
        authError = label(body, "", 13, RED); authError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button submit = button(body, mode == 1 ? "创建账号  →" : mode == 2 ? "重设密码并登录  →" : "进入 Aidol  →", true, () -> {
            if (busy) return;
            String name = value(username), pass = password.getText().toString();
            if (!name.matches("[a-zA-Z0-9_]{3,24}")) { authError.setText("账号需为 3–24 位字母、数字或下划线。"); return; }
            if (pass.length() < 8 || pass.length() > 128) { authError.setText("密码长度需为 8–128 位。"); return; }
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
        button(body, mode == 0 ? "忘记密码？使用恢复码" : "返回登录", false, () -> { if (!busy) showAuth(mode == 0 ? 2 : 0); });
        TextView note = label(body, "登录后自动记住，下次打开直接进入\n无需邮箱或短信 · 账号不区分大小写\n测试体验版  /  前台按次录音", 11, MUTED);
        note.setGravity(Gravity.CENTER); gap(body, 8);
        developmentEntry(body);
    }

    private void recoveryDialog(String code) {
        LinearLayout body = dialogBody();
        label(body, "这是找回账号的唯一凭证。不会再次显示，重置密码后旧恢复码会失效。", 14, MUTED);
        TextView secret = label(body, code, 18, TEAL); secret.setTextIsSelectable(true); secret.setTypeface(Typeface.MONOSPACE);
        button(body, "复制恢复码", true, () -> copy("Aidol 账号恢复码", code));
        new AlertDialog.Builder(this).setTitle("请保存账号恢复码").setView(body).setCancelable(false)
            .setPositiveButton("我已妥善保存", (d, w) -> {}).show();
    }

    private void showApp() {
        authError = null; base();
        LinearLayout head = row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(23), dp(18), dp(18), dp(10));
        root.addView(head);
        LinearLayout wordmark = column(); head.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        TextView logo = label(wordmark, "aidol", 28, INK); logo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); logo.setLetterSpacing(-.04f);
        label(wordmark, "YOUR EVERYDAY COMPANION", 8, MUTED).setLetterSpacing(.2f);
        TextView refresh = action(head, "↻", () -> { if (canAct()) { lastNotice = ""; refresh(); } }); refresh.setTextSize(25); refresh.setContentDescription("刷新服务器数据");
        TextView avatar = action(head, api.vault.username().isEmpty() ? "A" : api.vault.username().substring(0, 1).toUpperCase(Locale.ROOT), () -> switchTab(4));
        avatar.setBackground(shape(0xff243e40, 26, TEAL)); avatar.setTextColor(TEAL);
        status = label(root, "●  连接你的专属空间", 11, MUTED); status.setPadding(dp(24), 0, dp(24), dp(9));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page = column(); page.setPadding(dp(22), dp(12), dp(22), dp(18)); scroll.addView(page);
        composer = column(); composer.setPadding(dp(18), dp(7), dp(18), dp(7)); root.addView(composer);
        LinearLayout nav = row(); nav.setPadding(dp(12), dp(7), dp(12), dp(8)); nav.setBackgroundColor(0xff101b20); root.addView(nav);
        String[] names = {"对话", "记忆", "工作", "通勤", "我的"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(5), 0, 0);
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(56), 1));
            IconView icon = new IconView(i, tab == i ? TEAL : MUTED); item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView name = label(item, names[i], 10, tab == i ? TEAL : MUTED); name.setGravity(Gravity.CENTER); name.setPadding(0, dp(3), 0, 0);
            item.setContentDescription(names[i]); item.setOnClickListener(v -> switchTab(index));
        }
        if (tab == 0) conversation(); else if (tab == 1) memories(); else if (tab == 2) work(); else if (tab == 3) commute(); else profile();
        updateStatus();
        syncHeadsetControls();
    }

    private void switchTab(int next) {
        if (tab == next) return;
        clearHeadsetGesture();
        if (session != null) cancelSession("已离开对话页，本轮已取消");
        stopRepeat(); tab = next; hideKeyboard(); showApp();
    }

    private void conversation() {
        label(page, "此刻，与你同频", 24, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "说出想法，其余交给聆。", 13, MUTED);
        headsetStatus = label(page, "", 12, TEAL);
        headsetStatus.setOnClickListener(v -> showDeviceDetails()); renderHeadset();
        LinearLayout modes = row(); modes.setPadding(0, dp(14), 0, 0); page.addView(modes);
        String[] keys = {"life", "work", "commute"}, names = {"日常陪伴", "工作记录", "出行交流"};
        for (int i = 0; i < keys.length; i++) {
            final String mode = keys[i]; segment(modes, names[i], mode.equals(mode()), () -> changeMode(mode));
        }
        LinearLayout hero = card(page); hero.setGravity(Gravity.CENTER);
        orb = new OrbView(); hero.addView(orb, new LinearLayout.LayoutParams(-1, dp(155)));
        label(hero, "LISTEN. REMEMBER. CONNECT.", 8, TEAL).setLetterSpacing(.2f);
        TextView hint = label(hero, "work".equals(mode()) ? "工作模式 · 安静记录，稍后整理" : "你的声音，有了回响", 17, INK); hint.setGravity(Gravity.CENTER);
        voiceHint = label(hero, voiceHintText(), 11, MUTED); voiceHint.setGravity(Gravity.CENTER);
        LinearLayout listenChoices = row(); hero.addView(listenChoices);
        String[] listenNames = {"手动", "连续对话", "定时聆听"};
        for (int i = 0; i < listenNames.length; i++) { final int selection = i; segment(listenChoices, listenNames[i], listenMode == i, () -> {
            if (!canAct()) return;
            if (selection == 2 && !"life".equals(mode())) { toast("定时聆听用于日常陪伴，请先切换日常模式"); return; }
            stopRepeat(); listenMode = selection; showApp();
        }); }
        if (listenMode != 0) label(hero, listenMode == 1 ? "点击开始后自动接续；语音停顿自动结束本轮。仅当前页面生效。" : "点击开始后，每 " + settings().optInt("life_poll_seconds", 300) + " 秒聆听一次，每次最多 " + settings().optInt("listen_seconds", 30) + " 秒。仅当前页面生效。", 10, MUTED);
        LinearLayout voiceRow = row(); hero.addView(voiceRow);
        voiceButton = button(voiceRow, "◉  开始说话", true, () -> {
            if (session != null && recording) { recording = false; voiceButton.setText("正在处理…"); session.stopRecording(); }
            else if (session == null) startVoice();
        });
        voiceButton.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1));
        cancelButton = action(voiceRow, "取消", () -> { cancelSession("已取消；未提交的录音已丢弃，已提交内容请查看历史"); });
        cancelButton.setVisibility(session == null && !repeatArmed ? View.GONE : View.VISIBLE);
        if (BuildConfig.DEBUG) {
            LinearLayout diagnosticCard = card(page);
            label(diagnosticCard, "开发诊断", 15, TEAL);
            diagnosticsView = label(diagnosticCard, "", 11, MUTED);
            diagnosticsView.setTextIsSelectable(true);
            renderDiagnostics();
            button(diagnosticCard, "开发测试设置", false, this::showDevelopmentSettings);
        }
        LinearLayout histHead = row(); histHead.setGravity(Gravity.CENTER_VERTICAL); page.addView(histHead);
        TextView historyTitle = label(histHead, "最近对话", 17, INK); histHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        label(histHead, settings().optBoolean("retain_transcripts", true) ? (development.localBackend() ? "本地后端同步" : "云端同步") : "不保存转写", 10, MUTED);
        content = column(); page.addView(content);
        JSONArray history = array(state, "transcripts");
        if (history.length() == 0 && lastAnswer.isEmpty()) empty(content, "从一句你好开始", "可以聊今天的计划，也可以让聆记住你的偏好。");
        for (int i = Math.max(0, history.length() - 30); i < history.length(); i++) {
            JSONObject line = history.optJSONObject(i); if (line == null) continue;
            chatBubble(content, "assistant".equals(line.optString("speaker")), line.optString("text"), line.optString("created_at"));
            if (!"assistant".equals(line.optString("speaker"))) {
                TextView remember = label(content, "＋ 存为记忆", 11, TEAL); remember.setMinHeight(dp(40));
                remember.setOnClickListener(v -> { if (canAct()) mutate("POST", "/v1/memories/from-transcript/" + line.optString("id"), json()); });
            }
        }
        if (!settings().optBoolean("retain_transcripts", true) && !lastAnswer.isEmpty()) {
            chatBubble(content, false, lastUser, "本次对话"); chatBubble(content, true, lastAnswer, "本次对话");
        }
        LinearLayout inputRow = row(); inputRow.setGravity(Gravity.CENTER_VERTICAL); composer.addView(inputRow);
        chatInput = input("或者，打字聊聊…", false, 6000); chatInput.setMinLines(1); chatInput.setMaxLines(4);
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));
        TextView send = action(inputRow, "↑", () -> {
            if (!canAct()) return; String text = value(chatInput); if (text.isEmpty()) return;
            hideKeyboard(); chatInput.setText(""); startSession(text);
        }); send.setTextSize(25); send.setTextColor(BG); send.setBackground(shape(TEAL, 26, TEAL)); send.setContentDescription("发送文字消息");
    }

    private void chatBubble(LinearLayout parent, boolean assistant, String text, String time) {
        LinearLayout bubble = card(parent); bubble.setBackground(shape(assistant ? CARD : 0xff1c3337, 18, assistant ? LINE : 0xff325653));
        label(bubble, (assistant ? "聆  /  AIDOL" : "你") + "    " + date(time), 10, assistant ? TEAL : MUTED);
        TextView copyable = label(bubble, text, 15, INK); copyable.setTextIsSelectable(true);
        if (assistant && !text.isEmpty()) {
            LinearLayout actions = row(); bubble.addView(actions);
            small(actions, "播放", () -> { if (canAct()) { newSession(); sessionIsReplay = true; session.speak(text); } });
            small(actions, "复制", () -> copy("Aidol 回复", text));
        }
    }

    private void memories() {
        label(page, "重要的，替你记得", 24, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "把散落的片段，变成随时可找回的记忆。", 13, MUTED);
        LinearLayout search = card(page);
        label(search, "问问你的记忆", 16, INK);
        EditText question = input("例如：我喜欢什么口味？", false, 500); search.addView(question);
        button(search, "检索记忆  ↗", true, () -> {
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
        });
        LinearLayout tools = row(); page.addView(tools); tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(tools, "记忆库  " + array(state, "memories").length(), 17, INK); tools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        small(tools, "+ 新记忆", () -> { if (canAct()) editMemory(null); });
        LinearLayout filters = row(); page.addView(filters);
        String[] keys = {"", "preference", "fact", "task", "trip"}, names = {"全部", "偏好", "事实", "待办", "出行"};
        for (int i = 0; i < keys.length; i++) { final String key = keys[i]; segment(filters, names[i], memoryFilter.equals(key), () -> { memoryFilter = key; showApp(); }); }
        JSONArray items = array(state, "memories"); int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject memory = items.optJSONObject(i); if (memory == null || (!memoryFilter.isEmpty() && !memoryFilter.equals(memory.optString("kind")))) continue;
            count++; LinearLayout card = card(page);
            label(card, (pinned(memory) ? "◆  已置顶 · " : "") + kind(memory.optString("kind")) + "  /  " + date(memory.optString("created_at")), 10, TEAL);
            label(card, memory.optString("content"), 15, INK).setTextIsSelectable(true);
            LinearLayout actions = row(); card.addView(actions);
            small(actions, "编辑", () -> { if (canAct()) editMemory(memory); });
            small(actions, pinned(memory) ? "取消置顶" : "置顶", () -> {
                if (canAct()) mutate("PUT", "/v1/memories/" + memory.optString("id"), json("content", memory.optString("content"), "kind", memory.optString("kind"), "pinned", !pinned(memory)));
            });
            small(actions, "删除", () -> confirm("删除这条记忆？", "删除后无法恢复。", () -> mutate("DELETE", "/v1/memories/" + memory.optString("id"), null)));
            if (memory.optString("source").startsWith("transcript:")) small(actions, "原文", () -> { if (canAct()) request(() -> api.json("GET", "/v1/memories/" + memory.optString("id") + "/source", null), r -> { LinearLayout body = dialogBody(); label(body, r.optString("text", r.toString()), 15, INK); scrollDialog("记忆来源", body); }); });
        }
        if (count == 0) empty(page, "给记忆一个起点", "添加一个偏好、一件小事，或在日常对话里说“记住”。");
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
            request(() -> api.json(memory == null ? "POST" : "PUT", "/v1/memories" + (memory == null ? "" : "/" + memory.optString("id")), payload), result -> { dialog.dismiss(); refresh(); });
        })); dialog.show();
    }

    private void work() {
        label(page, "留心当下，事后有序", 24, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "工作记录 → 会议纪要 → 行动清单", 13, MUTED);
        LinearLayout intro = card(page); label(intro, "WORKSPACE", 9, TEAL).setLetterSpacing(.2f);
        label(intro, "把讨论，变成下一步", 21, INK);
        label(intro, "在工作模式分段记录讨论，再整理最近 24 小时的内容。生成结果是待核对的草稿。", 13, MUTED);
        button(intro, "开始工作记录  ↗", true, () -> { if (canAct()) saveModeAndOpenWork(); });
        button(intro, "生成会议纪要", false, () -> {
            if (!canAct()) return;
            String[] options = {"最近 1 小时", "最近 24 小时", "最近 7 天"}; int[] hours = {1, 24, 168};
            new AlertDialog.Builder(this).setTitle("选择工作记录范围").setItems(options, (d, w) -> request(() -> api.json("POST", "/v1/meetings", json("hours", hours[w])), result -> refresh())).setNegativeButton("取消", null).show();
        });
        JSONObject report = state.optJSONObject("meeting_report");
        if (report != null) {
            LinearLayout summary = card(page); label(summary, "最近纪要  ·  " + date(report.optString("created_at")), 11, TEAL);
            label(summary, report.optString("summary"), 15, INK).setTextIsSelectable(true);
            JSONArray decisions = array(report, "decisions");
            if (decisions.length() > 0) label(summary, "已记录的决策", 14, INK);
            for (int i = 0; i < decisions.length(); i++) { JSONObject d = decisions.optJSONObject(i); if (d != null) label(summary, "• " + d.optString("text"), 13, MUTED); }
            LinearLayout actions = row(); summary.addView(actions);
            small(actions, "复制纪要", () -> copy("Aidol 会议纪要", report.optString("summary")));
            small(actions, "查看原始记录", () -> showSources(array(report, "sources")));
        } else empty(page, "你的会议纪要会出现在这里", "工作模式下记录的内容，会成为纪要的来源。");
        JSONArray tasks = array(state, "tasks"); int open = 0; for (int i = 0; i < tasks.length(); i++) if (!"done".equals(tasks.optJSONObject(i).optString("status"))) open++;
        label(page, "行动清单  ·  " + open + " 项待完成", 17, INK);
        if (tasks.length() == 0) label(page, "纪要中的明确待办将自动汇集到这里。", 13, MUTED);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            boolean done = "done".equals(task.optString("status")); LinearLayout taskCard = card(page);
            CheckBox check = new CheckBox(this); check.setText(task.optString("content")); check.setTextColor(done ? MUTED : INK); check.setTextSize(14);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL)); check.setChecked(done); taskCard.addView(check);
            if (done) check.setPaintFlags(check.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            check.setOnClickListener(v -> { if (canAct()) mutate("PATCH", "/v1/tasks/" + task.optString("id"), json("status", check.isChecked() ? "done" : "open")); else check.setChecked(done); });
            label(taskCard, "负责人：" + fallback(task.optString("owner"), "待确认") + "    截止：" + fallback(task.optString("due"), "待确认"), 11, MUTED);
        }
    }

    private void commute() {
        label(page, "路上，也有聆陪你", 24, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "目的地、路线与周边，让每一程更从容。", 13, MUTED);
        LinearLayout destination = card(page); label(destination, "NEXT STOP", 9, TEAL).setLetterSpacing(.2f);
        label(destination, settings().isNull("destination_lon") ? "先选一个想去的地方" : settings().optString("destination_name", "选择目的地"), 22, INK);
        EditText city = field(destination, "城市", "例如 上海", false, 80); city.setText(settings().optString("transit_city", "上海"));
        EditText query = input("搜索公司、车站、餐厅…", false, 100); destination.addView(query);
        button(destination, "搜索目的地", true, () -> {
            if (!canAct() || value(query).isEmpty()) return; hideKeyboard();
            request(() -> api.json("GET", "/v1/maps/search?q=" + Uri.encode(value(query)) + "&city=" + Uri.encode(value(city)), null), this::poiResults);
        });
        LinearLayout methods = row(); destination.addView(methods); gap(destination, 5);
        String[] methodsKeys = {"driving", "walking", "transit"}, methodsNames = {"驾车", "步行", "公共交通"};
        for (int i = 0; i < methodsKeys.length; i++) { String key = methodsKeys[i]; segment(methods, methodsNames[i], key.equals(settings().optString("transport", "driving")), () -> {
            if (!canAct()) return; JSONObject payload = cloneJson(settings()); put(payload, "transport", key); mutate("PUT", "/v1/settings", payload);
        }); }
        button(destination, "◎  定位并更新路线", false, this::locate);
        label(destination, "仅在点击时获取位置。路线是规划估算，请结合实际路况。", 11, MUTED);
        JSONObject agent = state.optJSONObject("agent"), route = agent == null ? null : agent.optJSONObject("route");
        if (route != null) {
            LinearLayout routeCard = card(page);
            if (route.optBoolean("available")) {
                label(routeCard, String.format(Locale.CHINA, "%d 分钟   /   %.1f km", Math.max(1, Math.round(route.optDouble("eta_seconds") / 60)), route.optDouble("distance_meters") / 1000), 24, TEAL);
                label(routeCard, "前往 " + route.optString("destination"), 15, INK); label(routeCard, route.optString("note"), 11, MUTED);
                JSONArray steps = array(route, "steps");
                for (int i = 0; i < Math.min(steps.length(), 10); i++) { JSONObject step = steps.optJSONObject(i); if (step != null) label(routeCard, String.format(Locale.CHINA, "%02d  %s", i + 1, step.optString("instruction", step.optString("name", step.toString()))), 13, INK); }
                if (steps.length() > 10) small(routeCard, "查看全部路线", () -> { LinearLayout body = dialogBody(); for (int i = 0; i < steps.length(); i++) { JSONObject step = steps.optJSONObject(i); if (step != null) label(body, (i + 1) + ". " + step.optString("instruction", step.toString()), 14, INK); } scrollDialog("路线详情", body); });
            } else label(routeCard, route.optString("reason", "暂时没有可用路线"), 14, MUTED);
        }
        LinearLayout around = card(page); label(around, "探索附近", 17, INK);
        EditText keyword = input("咖啡、美食、公园…", false, 80); keyword.setText("咖啡"); around.addView(keyword);
        button(around, "搜索周边 3 km", false, () -> {
            if (!canAct()) return; JSONObject current = state.optJSONObject("agent"), location = current == null ? null : current.optJSONObject("location");
            if (location == null) { toast("请先点击“定位并更新路线”"); return; }
            JSONObject payload = cloneJson(location); put(payload, "keyword", fallback(value(keyword), "美食"));
            request(() -> api.json("POST", "/v1/maps/nearby", payload), this::poiResults);
        });
        button(around, "查看目的地天气", false, () -> {
            if (!canAct()) return; String code = getPreferences(MODE_PRIVATE).getString(weatherPreferenceKey(), "");
            if (settings().isNull("destination_lon")) { toast("先从搜索结果选择目的地，即可查询当地天气"); return; }
            String weatherCode = code.matches("[0-9]{6}") ? code : "";
            request(() -> api.json("GET", "/v1/maps/weather?city=" + Uri.encode(weatherCode), null), weather -> {
                if (!weather.optBoolean("available")) { showError(weather.optString("reason", "天气暂不可用")); return; }
                new AlertDialog.Builder(this).setTitle(weather.optString("city") + "  " + weather.optString("temperature") + "°C")
                    .setMessage(weather.optString("weather") + "\n" + weather.optString("wind_direction") + "风 " + weather.optString("wind_power") + "级\n湿度 " + weather.optString("humidity") + "%\n\n" + weather.optString("note") + "\n发布时间：" + weather.optString("report_time")).setPositiveButton("知道了", null).show();
            });
        });
        LinearLayout notice = card(page); label(notice, "到达提醒草稿", 17, INK);
        EditText contact = field(notice, "联系人称呼", "例如 同事", false, 100); contact.setText(settings().optString("contact", "同事"));
        EditText minutes = field(notice, "预计到达前几分钟提醒", "1–60", false, 2); minutes.setInputType(InputType.TYPE_CLASS_NUMBER); minutes.setText(String.valueOf(settings().optInt("arrival_notice_minutes", 5)));
        button(notice, "保存提醒偏好", false, () -> { if (!canAct()) return; int m; try { m = Integer.parseInt(value(minutes)); } catch (Exception e) { minutes.setError("请输入 1–60"); return; } if (m < 1 || m > 60) { minutes.setError("请输入 1–60"); return; } JSONObject payload = cloneJson(settings()); put(payload, "contact", value(contact)); put(payload, "arrival_notice_minutes", m); mutate("PUT", "/v1/settings", payload); });
        label(notice, "定位更新后生成草稿，由你复制并发送给联系人。不会自动发送消息。", 11, MUTED);
        JSONArray drafts = array(state, "drafts"); int pending = 0;
        for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.optJSONObject(i); if (draft == null || !"pending".equals(draft.optString("status"))) continue; pending++;
            LinearLayout draftCard = card(page); label(draftCard, "发给 " + draft.optString("contact"), 11, TEAL); label(draftCard, draft.optString("text"), 15, INK);
            LinearLayout actions = row(); draftCard.addView(actions);
            small(actions, "复制草稿", () -> { if (!canAct()) return; copy("到达提醒草稿", draft.optString("text")); mutate("POST", "/v1/drafts/" + draft.optString("id") + "/copied", json()); });
            small(actions, "忽略", () -> mutate("POST", "/v1/drafts/" + draft.optString("id") + "/dismissed", json()));
        }
        if (pending == 0) label(page, "暂无待处理的到达提醒。", 12, MUTED);
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

    private void locate() {
        if (!canAct()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 2); return; }
        android.location.LocationManager manager = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        boolean precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        String provider = precise && manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ? android.location.LocationManager.GPS_PROVIDER : android.location.LocationManager.NETWORK_PROVIDER;
        if (!manager.isProviderEnabled(provider)) { new AlertDialog.Builder(this).setTitle("请开启系统定位").setMessage("定位只会在你主动更新路线时使用。")
            .setNegativeButton("取消", null).setPositiveButton("打开定位设置", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show(); return; }
        lastNotice = "正在获取当前位置…"; updateStatus();
        locationListener = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location location) {
                stopLocation(); if (!visible) return;
                JSONObject point = json("longitude", location.getLongitude(), "latitude", location.getLatitude(), "accuracy", location.getAccuracy(), "source", "gps"); if (location.hasSpeed()) put(point, "speed", location.getSpeed());
                JSONObject payload = cloneJson(settings()); put(payload, "mode", "commute"); put(payload, "auto_mode", false);
                request(() -> { api.json("PUT", "/v1/settings", payload); api.json("POST", "/v1/location", point); return api.json("GET", "/v1/state", null); }, r -> { state = r; lastNotice = "定位已更新 · 位置精度约 " + Math.round(location.getAccuracy()) + " m"; showApp(); });
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
            @Override public void onStatusChanged(String p, int status, Bundle extras) {}
        };
        try { manager.requestLocationUpdates(provider, 0, 0, locationListener, android.os.Looper.getMainLooper()); handler.postDelayed(locationTimeout, 25000); }
        catch (Exception e) { stopLocation(); showError("无法获取位置，请检查定位权限与系统设置"); }
    }
    private void stopLocation() { handler.removeCallbacks(locationTimeout); if (locationListener != null) { try { ((android.location.LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(locationListener); } catch (Exception ignored) {} locationListener = null; } }

    private void profile() {
        developmentEntry(page);
        label(page, "你的专属空间", 24, INK).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout device = card(page); label(device, "耳机与音频", 17, INK);
        headsetStatus = label(device, "", 12, TEAL); renderHeadset();
        button(device, "设备详情", false, this::showDeviceDetails);
        LinearLayout account = card(page); label(account, "@" + api.vault.username(), 24, TEAL);
        label(account, "每个账号独立保存对话、记忆和工作记录。", 12, MUTED);
        LinearLayout settings = card(page); label(settings, "陪伴偏好", 17, INK);
        EditText name = field(settings, "助手称呼", "你想怎么称呼聆", false, 40); name.setText(settings().optString("name", "小聆"));
        EditText preferences = field(settings, "关于你的偏好", "喜欢什么、如何与你交流…", false, 2000); preferences.setMinLines(2); preferences.setText(settings().optString("preferences"));
        CheckBox retain = new CheckBox(this); retain.setText("保存转写与对话历史"); retain.setTextColor(INK); retain.setTextSize(13); retain.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL)); retain.setChecked(settings().optBoolean("retain_transcripts", true)); settings.addView(retain);
        label(settings, "关闭后不新增转写历史；仍可能自动提取记忆并保存请求结果，已有数据也会保留。需要移除账号业务数据请使用清空数据。", 11, MUTED);
        CheckBox autoMode = new CheckBox(this); autoMode.setText("按工作时间自动切换模式"); autoMode.setTextColor(INK); autoMode.setTextSize(13); autoMode.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL)); autoMode.setChecked(settings().optBoolean("auto_mode", false)); settings.addView(autoMode);
        LinearLayout hours = row(); settings.addView(hours); EditText start = input("上班小时", false, 2), end = input("下班小时", false, 2); start.setInputType(InputType.TYPE_CLASS_NUMBER); end.setInputType(InputType.TYPE_CLASS_NUMBER); start.setText(String.valueOf(settings().optInt("work_start", 9))); end.setText(String.valueOf(settings().optInt("work_end", 18))); hours.addView(start, new LinearLayout.LayoutParams(0, -2, 1)); hours.addView(end, new LinearLayout.LayoutParams(0, -2, 1));
        label(settings, "自动模式按以上小时及下方时区判断；手动切换会关闭自动模式。", 11, MUTED);
        EditText zone = field(settings, "时区", "例如 Asia/Shanghai", false, 80); zone.setText(settings().optString("timezone", "Asia/Shanghai"));
        label(settings, "前台聆听设置", 15, INK);
        EditText interval = field(settings, "定时聆听间隔（秒）", "60–3600，默认 300", false, 4); interval.setInputType(InputType.TYPE_CLASS_NUMBER); interval.setText(String.valueOf(settings().optInt("life_poll_seconds", 300)));
        EditText duration = field(settings, "定时单次最长录音（秒）", "5–60，默认 30", false, 2); duration.setInputType(InputType.TYPE_CLASS_NUMBER); duration.setText(String.valueOf(settings().optInt("listen_seconds", 30)));
        EditText threshold = field(settings, "语音检测阈值", "0.005–0.2，默认 0.025", false, 5); threshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); threshold.setText(String.valueOf(settings().optDouble("vad_threshold", .025)));
        label(settings, "阈值越低越敏感。定时或连续聆听都需要在对话页主动开始；锁屏、离开页面即停止。", 11, MUTED);
        button(settings, "保存偏好", true, () -> {
            if (!canAct()) return; if (value(name).isEmpty()) { name.setError("称呼不能为空"); return; }
            JSONObject payload = cloneJson(settings()); put(payload, "name", value(name)); put(payload, "preferences", value(preferences)); put(payload, "retain_transcripts", retain.isChecked());
            int from, until; try { from = Integer.parseInt(value(start)); until = Integer.parseInt(value(end)); } catch (Exception e) { toast("请输入有效的工作时间"); return; }
            if (from < 0 || until > 24 || until <= from) { toast("工作时间需要在 0–24 时内，结束晚于开始"); return; }
            put(payload, "work_start", from); put(payload, "work_end", until); put(payload, "auto_mode", autoMode.isChecked()); put(payload, "timezone", value(zone));
            try { int every = Integer.parseInt(value(interval)), seconds = Integer.parseInt(value(duration)); double level = Double.parseDouble(value(threshold));
                if (every < 60 || every > 3600 || seconds < 5 || seconds > 60 || level < .005 || level > .2) throw new IllegalArgumentException();
                put(payload, "life_poll_seconds", every); put(payload, "listen_seconds", seconds); put(payload, "vad_threshold", level);
            } catch (Exception e) { toast("请检查聆听间隔、录音时长和检测阈值的范围"); return; }
            mutate("PUT", "/v1/settings", payload);
        });
        LinearLayout privacy = card(page); label(privacy, "账号与隐私", 17, INK);
        button(privacy, "修改登录密码   ›", false, this::changePassword);
        button(privacy, "导出我的数据   ↗", false, this::export);
        button(privacy, "数据处理说明   ›", false, this::privacyInfo);
        button(privacy, "清空对话、记忆与工作数据", false, () -> destructive(false)).setTextColor(RED);
        button(privacy, "永久注销账号", false, () -> destructive(true)).setTextColor(RED);
        button(page, "退出登录", false, () -> {
            if (!canAct()) return;
            confirm("退出当前账号？", "服务器数据会保留，下次登录后继续使用。", () -> request(() -> api.json("POST", "/auth/logout", json()), r -> signOut()));
        });
        TextView foot = label(page, "Aidol · 聆  /  " + BuildConfig.VERSION_NAME + "\n" + api.serverUrl + "\n安卓体验版 · 不提供后台持续监听", 10, MUTED); foot.setGravity(Gravity.CENTER);
    }

    private void changePassword() {
        if (!canAct()) return;
        LinearLayout body = dialogBody(); EditText old = field(body, "原密码", "当前密码", true, 128), next = field(body, "新密码", "至少 8 位", true, 128), confirm = field(body, "确认新密码", "再输入一次", true, 128);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修改密码").setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            if (!canAct()) return;
            String pass = next.getText().toString(); if (pass.length() < 8) { next.setError("密码至少 8 位"); return; }
            if (!pass.equals(confirm.getText().toString())) { confirm.setError("两次密码不一致"); return; }
            request(() -> api.json("PUT", "/auth/password", json("old_password", old.getText().toString(), "new_password", pass)), r -> {
                api.vault.save(r.getString("token"), api.vault.username()); dialog.dismiss(); toast("密码已更新，其他登录已失效");
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
                dialog.dismiss(); lastAnswer = ""; lastUser = ""; if (account) signOut(); else refresh();
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

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20) return;
        byte[] payload = exportData; exportData = null;
        if (result != RESULT_OK || data == null || payload == null) return;
        Uri uri = data.getData();
        worker.execute(() -> { try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (output == null) throw new Exception("文件无法写入"); output.write(payload); runOnUiThread(() -> toast("数据已导出")); } catch (Exception e) { runOnUiThread(() -> showError("导出失败，请选择其他保存位置")); } });
    }

    private void privacyInfo() {
        new AlertDialog.Builder(this).setTitle("数据处理说明").setMessage("Aidol 测试版由测试负责人运营。\n\n账号使用唯一用户名和密码登录，无需邮箱或手机号。忘记密码时需使用注册时保存的恢复码。\n\n仅在你点击开始录音后采集麦克风，每次最多 60 秒。录音上传服务器用于语音识别；文字发送 AI 服务生成回复与记忆；回复可转换为语音播放。原始录音不保存到账号数据库。\n\n对话、偏好、记忆和会议待办按账号存储在服务器。你可以关闭新的转写保存、导出、清空数据或注销账号。测试负责人可管理服务器及备份。\n\n录制他人讲话前，请先征得对方同意。AI 回复与纪要需要自行核对。\n\n账号密钥保存在设备加密存储中；服务商 API Key 不下发到手机。").setPositiveButton("了解", null).show();
    }

    private void showSources(JSONArray sources) {
        LinearLayout body = dialogBody();
        if (sources.length() == 0) label(body, "暂无原始记录", 14, MUTED);
        for (int i = 0; i < sources.length(); i++) { JSONObject source = sources.optJSONObject(i); if (source != null) { label(body, date(source.optString("created_at")), 10, TEAL); label(body, source.optString("text"), 14, INK); } }
        scrollDialog("纪要来源", body);
    }

    private void changeMode(String mode) { changeMode(mode, false); }
    private void changeMode(String mode, boolean openConversation) {
        if (busy || locationListener != null || permissionPending) { toast("上一项操作正在进行，请稍后切换模式"); return; }
        if (session != null && !session.cancelIfRecording()) { headsetNotice("本轮正在处理，请结束后再切换模式"); return; }
        if (session != null) cancelSession("正在切换模式，未发送的录音已取消");
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
            finishModeChange(modeChange);
            lastNotice = "模式尚未切换，请稍后重新选择"; updateStatus();
        }
    }
    private void submitModeChange(ModeChangeRetry change, ApiClient client, JSONObject body, boolean openConversation) {
        if (!currentModeChange(change) || !change.canAttempt(android.os.SystemClock.elapsedRealtime(), api, sessionGeneration, visible)) {
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
                    lastNotice = appliedNotice + "；等待你手动开始";
                    if (openConversation) tab = 0;
                    showApp(); if (visible) toast(lastNotice);
                    // Commit the real settings result first. Playback cannot undo or repeat that update.
                    if (visible && client == api && change.matches(api, sessionGeneration) && session == null) {
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
                    api, sessionGeneration, visible);
                if (delay >= 0) {
                    lastNotice = "上一轮正在收尾，稍后切换" + modeName(change.mode); updateStatus();
                    modeChangeDelay = () -> submitModeChange(change, client, body, openConversation);
                    handler.postDelayed(modeChangeDelay, delay);
                    return;
                }
                finishModeChange(change);
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
    private void mutate(String method, String path, JSONObject body) { if (canAct()) request(() -> api.json(method, path, body), r -> refresh()); }
    private void refresh() {
        if (busy || session != null || api.vault.token().isEmpty()) return;
        request(() -> api.json("GET", "/v1/state", null), r -> { state = r; showApp(); });
    }
    private void request(Job job, Result result) {
        if (busy) return; busy = true; updateStatus();
        worker.execute(() -> {
            try { JSONObject value = job.run(); runOnUiThread(() -> {
                busy = false; if (isDestroyed()) return; lastNotice = ""; updateStatus();
                try { result.accept(value); } catch (Exception e) { showError("操作未完成，请稍后重试"); }
            }); } catch (Exception e) { runOnUiThread(() -> { busy = false; if (!isDestroyed()) { lastNotice = fallback(e.getMessage(), "无法连接服务器，请检查网络后重试"); diagnosticError = lastNotice; renderDiagnostics(); updateStatus(); showError(lastNotice); } }); }
        });
    }
    private void signOut() { cancelSession(""); api.vault.clear(); syncHeadsetControls(); state = new JSONObject(); lastAnswer = ""; lastUser = ""; lastNotice = ""; showAuth(0); }
    private boolean canAct() { if (busy || session != null || locationListener != null) { toast("上一项操作正在进行，请稍候"); return false; } return true; }

    private void startVoice() {
        if (!visible || permissionPending || !canAct()) return;
        if (development.audioMode() == AudioMode.RISE_SDK) {
            HeadsetController.Snapshot snapshot = headset.snapshot();
            if (snapshot.inCall) { stopRepeat(); toast("通话期间不能开启 AI 录音"); return; }
            if (Boolean.FALSE.equals(snapshot.leftWorn) && Boolean.FALSE.equals(snapshot.rightWorn)) {
                stopRepeat(); toast("耳机当前未佩戴，请佩戴后再开始"); return;
            }
        }
        ArrayList<String> missing = audioPermissions(development.audioMode() == AudioMode.RISE_SDK);
        if (!missing.isEmpty()) { permissionPending = true; requestPermissions(missing.toArray(new String[0]), 1); return; }
        if (development.audioMode() == AudioMode.RISE_SDK && !headset.snapshot().ready()) {
            stopRepeat(); showDeviceDetails(); toast("请先连接耳机，显示就绪后再点击开始"); return;
        }
        repeatArmed = listenMode != 0; handler.removeCallbacks(repeatTick); hideKeyboard(); startSession(null);
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
    private void startSession(String text) { if (text != null) stopRepeat(); newSession(); sessionUsesCapture = text == null; if (text == null) {
        session.configure(listenMode == 2 ? settings().optInt("listen_seconds", 30) : 60, listenMode != 0 && !"work".equals(mode()), settings().optDouble("vad_threshold", .025)); session.start();
    } else { lastUser = text; session.startText(text); } }
    private void newSession() { newSession(null); }
    private void newSession(String operationNotice) {
        final boolean operationCue = operationNotice != null;
        final long generation = ++sessionGeneration;
        sessionUsesCapture = false; sessionIsReplay = operationCue; sessionOperationNotice = operationNotice;
        turnSucceeded = operationCue; recording = false;
        lastNotice = operationCue ? operationNotice + "；正在播放操作提示" : "正在连接…";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        diagnosticInput = operationCue ? "操作提示，不采集录音" : "本轮尚未录音";
        diagnosticOutput = "本轮尚未播放"; diagnosticError = "无"; diagnosticRms = 0; diagnosticBytes = 0;
        HostedSession.Listener listener = new HostedSession.Listener() {
            @Override public void status(String text) { if (!currentSession(generation)) return; lastNotice = operationCue && !text.startsWith(operationNotice) ? operationNotice + "；" + text : text; diagnosticStage = text; renderDiagnostics(); if (text.contains("正在识别") || text.contains("正在思考") || text.contains("正在结束")) { recording = false; if (voiceButton != null) voiceButton.setText("正在处理…"); } if (status != null) status.setText("●  " + lastNotice); }
            @Override public void failure(String text) { if (!currentSession(generation)) return; turnSucceeded = false; stopRepeat(); lastNotice = operationCue ? operationNotice + "；操作提示未播放，请检查耳机连接" : text; diagnosticError = text; renderDiagnostics(); updateStatus(); }
            @Override public void input(String text) { if (!currentSession(generation)) return; diagnosticInput = text; renderDiagnostics(); }
            @Override public void output(String text) { if (!currentSession(generation)) return; diagnosticOutput = text; renderDiagnostics(); }
            @Override public void diagnostic(String text) { if (!currentSession(generation)) return; diagnosticSession = text; renderDiagnostics(); }
            @Override public void result(JSONObject value) {
                if (!currentSession(generation) || operationCue) return;
                turnSucceeded = true;
                lastUser = value.optString("text"); lastAnswer = value.optString("response");
                if (!isDestroyed() && content != null) { chatBubble(content, false, lastUser, "刚刚"); if (!lastAnswer.isEmpty()) chatBubble(content, true, lastAnswer, "刚刚"); scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }
            }
            @Override public void recording() { if (!currentSession(generation)) return; recording = true; if (voiceButton != null) voiceButton.setText("■  结束并发送"); }
            @Override public void meter(long bytes, double rms) { if (currentSession(generation)) { diagnosticBytes = bytes; diagnosticRms = rms; renderDiagnostics(); if (status != null) status.setText(String.format(Locale.CHINA, "●  正在聆听  %.1f / %d 秒", bytes / 32000.0, listenMode == 2 ? settings().optInt("listen_seconds", 30) : 60)); if (orb != null) orb.level = (float) Math.min(1, rms * 9); } }
            @Override public void done() {
                if (!currentSession(generation)) return;
                ++sessionGeneration;
                session = null; recording = false; sessionOperationNotice = null;
                if (!isDestroyed()) {
                    if (operationCue) {
                        stopRepeat();
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (voiceButton != null) voiceButton.setText("◉  开始说话");
                        if (cancelButton != null) cancelButton.setVisibility(View.GONE);
                        if (orb != null) orb.level = 0;
                        if (turnSucceeded) lastNotice = operationNotice + "；等待你手动开始";
                        updateStatus();
                        return; // Local operation cues do not refresh business data or start another recording.
                    }
                    if (!turnSucceeded || !visible || (listenMode == 2 && !"life".equals(mode()))) stopRepeat();
                    if (repeatArmed) { repeatAt = android.os.SystemClock.elapsedRealtime() + (listenMode == 2 ? settings().optInt("life_poll_seconds", 300) * 1000L : 1000); handler.removeCallbacks(repeatTick); handler.postDelayed(repeatTick, 1000); }
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (voiceButton != null) voiceButton.setText("◉  开始说话"); if (cancelButton != null) cancelButton.setVisibility(repeatArmed ? View.VISIBLE : View.GONE); if (orb != null) orb.level = 0;
                    if (api.vault.token().isEmpty()) { showAuth(0); authError.setText("登录已失效，请重新登录"); } else { updateStatus(); if (visible) refresh(); }
                }
            }
        };
        AudioMode input = development.audioMode();
        if (input == AudioMode.RISE_SDK) session = new HostedSession(this, api, input, new VendorCaptureSource(headset), headset::matchesOutput, listener);
        else session = new HostedSession(this, api, input, listener);
        if (voiceButton != null) voiceButton.setText(operationCue ? "正在播放操作提示…" : "正在准备…"); if (cancelButton != null) cancelButton.setVisibility(View.VISIBLE); updateStatus();
    }
    private boolean currentSession(long generation) { return !isDestroyed() && generation == sessionGeneration; }
    private boolean hasHeadsetBoundSession() { return session != null && (sessionUsesCapture || sessionIsReplay); }
    private void cancelSession(String reason) {
        stopRepeat(); clearHeadsetGesture(); ++sessionGeneration;
        String appliedNotice = sessionOperationNotice; sessionOperationNotice = null;
        HostedSession old = session; session = null; recording = false; turnSucceeded = false;
        if (old != null) old.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (voiceButton != null) voiceButton.setText("◉  开始说话");
        if (cancelButton != null) cancelButton.setVisibility(View.GONE);
        if (orb != null) orb.level = 0;
        lastNotice = appliedNotice == null ? reason : appliedNotice + "；操作提示已停止";
        diagnosticStage = "已取消"; renderDiagnostics(); updateStatus();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 2) { if (grants.length > 0 && (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)) locate(); else toast("定位未授权，可继续使用目的地搜索与天气查询"); return; }
        if (code != 1 && code != 3) return; permissionPending = false;
        for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle(development.audioMode().requiresBluetooth() ? "需要麦克风与附近设备权限" : "需要麦克风权限").setMessage("用于所选音频模式的采音与播放。你仍然可以使用文字对话；也可在系统设置中开启权限。")
                .setNegativeButton("使用文字", null).setPositiveButton("打开设置", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))).show(); return;
        }
        if (grants.length > 0 && visible) { if (code == 3) connectHeadset(); else startVoice(); }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; syncHeadsetControls();
        if (!pendingHeadsetNotice.isEmpty()) { String notice = pendingHeadsetNotice; pendingHeadsetNotice = ""; headsetNotice(notice); }
        if (orb != null) orb.invalidate();
    }
    private void stopRepeat() { repeatArmed = false; listenMode = 0; handler.removeCallbacks(repeatTick); if (session == null) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    @Override protected void onPause() { visible = false; pauseModeChange(); clearHeadsetGesture(); syncHeadsetControls(); if (session != null) cancelSession("离开前台，本轮已取消；返回后请手动开始"); else stopRepeat(); stopLocation(); super.onPause(); }
    @Override protected void onDestroy() { cancelSession(""); if (headset != null) { headset.removeListener(headsetListener); headset.removeControlListener(headsetControlListener); headset.close(); } worker.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() { if (session != null) { cancelSession("本轮已取消"); return; } if (!api.vault.token().isEmpty() && tab != 0) { switchTab(0); return; } if (api.vault.token().isEmpty() && authMode != 0) { showAuth(0); return; } super.onBackPressed(); }

    private void syncHeadsetControls() {
        if (headset == null || development == null || api == null) return;
        boolean wanted = visible && headsetControlsOptIn && development.audioMode() == AudioMode.RISE_SDK && !api.vault.token().isEmpty();
        if (controlsRequested == wanted) return;
        controlsRequested = wanted;
        if (!wanted) clearHeadsetGesture();
        headset.setControlsEnabled(wanted);
    }
    private HeadsetInteractionPolicy.Context headsetInteractionContext() {
        HeadsetController.Snapshot snapshot = headset.snapshot();
        return new HeadsetInteractionPolicy.Context(visible, headsetControlsOptIn && development.audioMode() == AudioMode.RISE_SDK
            && snapshot.controlsState == HeadsetController.ControlsState.ENABLED, !api.vault.token().isEmpty(), snapshot.ready(),
            snapshot.inCall, busy || permissionPending || locationListener != null, session != null || repeatArmed, recording,
            Boolean.FALSE.equals(snapshot.leftWorn) && Boolean.FALSE.equals(snapshot.rightWorn), mode(), sessionGeneration);
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
                    cancelSession(decision.mode == null ? decision.message : "触控切换模式，本轮已取消；已提交内容请查看历史");
                    break;
                case CHANGE_MODE: changeMode(decision.mode); break;
                case START:
                    stopRepeat();
                    if (tab != 0) { tab = 0; showApp(); }
                    startVoice();
                    break;
                case STOP_AND_SEND:
                    stopRepeat();
                    if (session != null && recording) {
                        recording = false; if (voiceButton != null) voiceButton.setText("正在处理…"); session.stopRecording();
                    }
                    break;
            }
        }
    }
    private void clearHeadsetGesture() { headsetPolicy.clearPending(); handler.removeCallbacks(settleHeadsetGesture); }
    private void headsetNotice(String message) {
        if (!visible) { pendingHeadsetNotice = message; return; }
        lastNotice = message; updateStatus(); toast(message);
    }
    private static String modeName(String value) { return "work".equals(value) ? "工作模式" : "commute".equals(value) ? "通勤模式" : "生活模式"; }

    private void updateStatus() { if (status != null) { status.setText("●  " + (busy ? "正在同步…" : !lastNotice.isEmpty() ? lastNotice : "已连接  ·  " + api.vault.username())); status.setTextColor(busy ? MUTED : TEAL); } }
    private void connectBackend() {
        ApiClient next = new ApiClient(this, development.serverUrl());
        api = next;
        next.setDiagnostics(message -> runOnUiThread(() -> {
            if (isDestroyed() || api != next) return;
            if (message.contains("request_id=") && (session == null || !message.contains("request_id=" + session.requestId()))) return;
            diagnosticRequest = message; renderDiagnostics();
        }));
    }
    private String audioModeLabel() { return development.audioMode().label(); }
    private String voiceHintText() { return (development.audioMode() == AudioMode.RISE_SDK ? "在设备详情连接 Rise，耳机就绪后开始\n触控联动就绪后：按一下开始/结束，按两下切模式，长按取消" : "连接蓝牙耳机后，轻触下方开始") + "\n每次最长 60 秒（含结束收尾）· 离开页面自动停止"; }
    private String weatherPreferenceKey() { return "weather." + ServerEndpoint.sessionNamespace(api.serverUrl) + "." + api.vault.username(); }
    private void developmentEntry(LinearLayout parent) {
        if (!BuildConfig.DEBUG) return;
        developmentSummary = label(parent, "开发版 · " + audioModeLabel() + "\n" + api.serverUrl, 11, TEAL);
        button(parent, "开发测试设置", false, this::showDevelopmentSettings);
        button(parent, "检查后端连接", false, () -> { if (canAct()) request(() -> api.json("GET", "/healthz", null), r -> {
            new AlertDialog.Builder(this).setTitle("后端可达").setMessage(api.serverUrl + "\n服务配置：" + (r.optBoolean("ready") ? "齐备（供应商调用仍需验证）" : "未齐备，请检查电脑后端配置")).setPositiveButton("完成", null).show();
        }); });
    }
    private RadioGroup choices(LinearLayout parent, String[] options, int selected) {
        RadioGroup group = new RadioGroup(this); parent.addView(group);
        for (int i = 0; i < options.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setText(options[i]);
            option.setTextColor(INK); option.setTextSize(13); option.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
            group.addView(option); if (i == selected) group.check(option.getId());
        }
        return group;
    }
    private void showDevelopmentSettings() {
        if (!BuildConfig.DEBUG || permissionPending || !canAct()) return;
        stopRepeat(); clearHeadsetGesture();
        LinearLayout body = dialogBody();
        label(body, "音频模式", 15, TEAL);
        AudioMode[] audioOptions = {AudioMode.RISE_SDK, AudioMode.BLUETOOTH};
        String[] audioNames = new String[audioOptions.length]; for (int i = 0; i < audioOptions.length; i++) audioNames[i] = audioOptions[i].label();
        RadioGroup audio = choices(body, audioNames, development.audioMode() == AudioMode.RISE_SDK ? 0 : 1);
        label(body, "Rise 使用耳机专用收音；系统蓝牙是独立兼容来源。来源失败不会自动切换。", 11, MUTED);
        label(body, "后端", 15, TEAL);
        RadioGroup backend = choices(body, new String[]{backendChoiceLabel("本地电脑服务（USB）", ServerEndpoint.USB_LOCAL), backendChoiceLabel("远端服务器", BuildConfig.SERVER_URL)}, development.localBackend() ? 0 : 1);
        label(body, "本地和远端账号独立，各登录一次后自动记住。切换回来会恢复该服务的登录；凭证失效时才需重登。只切音频不会退出账号或清空页面。", 11, MUTED);
        ScrollView container = new ScrollView(this); container.addView(body);
        new AlertDialog.Builder(this).setTitle("开发测试设置").setView(container).setNegativeButton("取消", null)
            .setPositiveButton("应用", (dialog, which) -> {
                if (permissionPending || !canAct()) return;
                AudioMode selected = audioOptions[audio.indexOfChild(audio.findViewById(audio.getCheckedRadioButtonId()))];
                boolean localBackend = backend.getCheckedRadioButtonId() == backend.getChildAt(0).getId();
                if (selected == development.audioMode() && localBackend == development.localBackend()) return;
                cancelSession(""); if (development.audioMode() == AudioMode.RISE_SDK && selected != AudioMode.RISE_SDK) headset.disconnect();
                development.save(selected, localBackend);
                syncHeadsetControls();
                if (api.serverUrl.equals(development.serverUrl())) {
                    // Audio selection does not change the account, view tree, drafts or results.
                    if (voiceHint != null) voiceHint.setText(voiceHintText());
                    if (developmentSummary != null) developmentSummary.setText("开发版 · " + audioModeLabel() + "\n" + api.serverUrl);
                    renderHeadset();
                    toast("音频已切换，登录和当前页面已保留");
                    return;
                }
                connectBackend(); state = new JSONObject(); tab = 0; lastAnswer = ""; lastUser = ""; lastNotice = ""; exportData = null;
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
        label(body, "先在手机系统配对耳机。耳机就绪后仍需点击开始录音；连接成功不代表已核验声音来源和播放。", 12, MUTED);
        if (BuildConfig.DEBUG && development.audioMode() == AudioMode.RISE_SDK) {
            button(body, "连接 / 重新连接 Rise", true, () -> { if (canAct()) connectHeadset(); });
            button(body, "断开耳机", false, () -> { cancelSession("耳机已断开"); headset.disconnect(); });
            CheckBox controls = new CheckBox(this); controls.setText("开启耳机触控联动（仅 App 前台）");
            controls.setTextColor(INK); controls.setChecked(headsetControlsOptIn); body.addView(controls);
            controls.setOnCheckedChangeListener((button, checked) -> {
                headsetControlsOptIn = checked;
                getSharedPreferences("headset_controls", MODE_PRIVATE).edit().putBoolean("touch_enabled", checked).apply();
                clearHeadsetGesture(); syncHeadsetControls();
                toast(checked ? "已申请触控联动；请看设备状态并实际测试手势" : "触控联动已关闭");
            });
            label(body, "按一下（单击 / 单捏）：开始或结束发送单轮对话\n按两下（双击 / 双捏）：生活 → 工作 → 通勤\n长按 / 长捏：取消本轮\n单击、双击会等待组合手势判定。三击不绑定动作，并撤销尚未执行的组合手势；短按、释放及滑动不映射动作。\n\n以上只响应 SDK 实际上报的事件，不修改耳机系统手势或固件。显示已开启不等于每种手势已实测。", 11, MUTED);
            label(body, "之前确认佩戴后，双耳都明确未佩戴时暂停；单耳及未知状态不推断摘下。戴回、重连只恢复就绪，不自动开麦。电量 ≤20% 和 ≤10% 分级提醒，回升超过25%后才重新提醒。模式切换、开始和结束录音播放简短操作提示；工作模式的业务回答仍保持静音。", 11, MUTED);
        } else label(body, "当前使用 " + audioModeLabel() + "。Rise 接入在开发设置中选择，实物验收后再调整发布策略。", 12, MUTED);
        button(body, "打开系统蓝牙设置", false, () -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        ScrollView container = new ScrollView(this); container.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("耳机与音频").setView(container).setPositiveButton("完成", null).create();
        dialog.setOnDismissListener(d -> deviceDetails = null); dialog.show();
    }
    private void connectHeadset() {
        if (!BuildConfig.DEBUG || development.audioMode() != AudioMode.RISE_SDK || permissionPending || !canAct()) return;
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
    private void scrollDialog(String title, LinearLayout body) { ScrollView sc = new ScrollView(this); sc.addView(body); new AlertDialog.Builder(this).setTitle(title).setView(sc).setPositiveButton("完成", null).show(); }
    private void copy(String label, String text) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text)); toast("已复制"); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void hideKeyboard() { View focus = getCurrentFocus(); if (focus != null) { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); } }
    private JSONObject settings() { JSONObject s = state.optJSONObject("settings"); return s == null ? new JSONObject() : s; }
    private String mode() { JSONObject agent = state.optJSONObject("agent"); return agent == null ? settings().optString("mode", "life") : agent.optString("mode", settings().optString("mode", "life")); }
    private static JSONArray array(JSONObject object, String key) { JSONArray a = object.optJSONArray(key); return a == null ? new JSONArray() : a; }
    private static JSONObject cloneJson(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception e) { return new JSONObject(); } }
    private static JSONObject json(Object... pairs) { JSONObject value = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(value, pairs[i].toString(), pairs[i + 1]); return value; }
    private static void put(JSONObject value, String key, Object object) { try { value.put(key, object); } catch (Exception ignored) { } }
    private static String fallback(String text, String other) { return text == null || text.isEmpty() ? other : text; }
    private static String value(EditText field) { return field.getText().toString().trim(); }
    private static String date(String raw) { try { return java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")); } catch (Exception e) { return raw; } }
    private static String kind(String raw) { return "preference".equals(raw) ? "偏好" : "task".equals(raw) ? "待办" : "trip".equals(raw) ? "出行" : "事实"; }
    private static boolean pinned(JSONObject memory) { return memory.optBoolean("pinned") || memory.optInt("pinned", 0) == 1; }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout dialogBody() { LinearLayout body = column(); body.setPadding(dp(24), dp(8), dp(24), dp(16)); return body; }
    private LinearLayout card(LinearLayout parent) { LinearLayout view = column(); view.setPadding(dp(17), dp(15), dp(17), dp(15)); view.setBackground(shape(CARD, 22, LINE)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(12), 0, dp(9)); parent.addView(view, p); return view; }
    private GradientDrawable shape(int color, int radius, int border) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), border); return d; }
    private TextView label(LinearLayout parent, String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(5), 0, dp(5)); parent.addView(view); return view; }
    private EditText input(String hint, boolean password, int max) {
        EditText view = new EditText(this); view.setTextColor(INK); view.setHintTextColor(0xff6d858c); view.setTextSize(14); view.setHint(hint); view.setPadding(dp(15), dp(14), dp(15), dp(14)); view.setBackground(shape(0xff0f1a20, 13, LINE));
        view.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)); view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)}); view.setSingleLine(password); return view;
    }
    private EditText field(LinearLayout parent, String title, String hint, boolean password, int max) { label(parent, title, 11, MUTED); EditText input = input(hint, password, max); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(9); parent.addView(input, p); return input; }
    private Button button(LinearLayout parent, String text, boolean primary, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setTextSize(14); view.setAllCaps(false); view.setTextColor(primary ? BG : INK); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setBackground(shape(primary ? TEAL : 0xff17272d, 14, primary ? TEAL : LINE)); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(9); p.bottomMargin = dp(3); parent.addView(view, p); view.setOnClickListener(v -> action.run()); return view;
    }
    private TextView action(LinearLayout parent, String text, Runnable action) { TextView v = new TextView(this); v.setText(text); v.setTextColor(TEAL); v.setTextSize(14); v.setGravity(Gravity.CENTER); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(46)); p.leftMargin = dp(7); parent.addView(v, p); v.setOnClickListener(w -> action.run()); return v; }
    private void small(LinearLayout parent, String text, Runnable action) { TextView v = label(parent, text, 12, TEAL); v.setPadding(dp(2), dp(12), dp(18), dp(8)); v.setMinHeight(dp(44)); v.setOnClickListener(w -> action.run()); }
    private void segment(LinearLayout parent, String text, boolean selected, Runnable action) { TextView v = new TextView(this); v.setText(text); v.setTextSize(11); v.setTextColor(selected ? TEAL : MUTED); v.setGravity(Gravity.CENTER); v.setBackground(shape(selected ? 0xff1c373a : 0xff111e24, 12, selected ? 0xff3a6a64 : LINE)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1); p.setMargins(0, 0, dp(5), 0); parent.addView(v, p); v.setOnClickListener(w -> action.run()); }
    private void empty(LinearLayout parent, String title, String subtitle) { LinearLayout box = column(); box.setPadding(dp(10), dp(20), dp(10), dp(20)); parent.addView(box); label(box, title, 15, MUTED); label(box, subtitle, 12, MUTED); }

    private final class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ringPath = new Path();
        private RadialGradient glow;
        float level;
        OrbView() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setContentDescription("聆听声波"); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); if (w > 0 && h > 0) glow = new RadialGradient(w / 2f, h / 2f, Math.min(w, h) * .31f * 1.7f, new int[]{0x554bdac1, 0x0a4bdac1, 0x004bdac1}, new float[]{0, .7f, 1}, Shader.TileMode.CLAMP); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = Math.min(getWidth(), getHeight()) * .31f;
            float phase = (System.currentTimeMillis() % 10000) / 10000f * (float) Math.PI * 2;
            paint.setStyle(Paint.Style.FILL); paint.setShader(glow); c.drawCircle(cx, cy, radius * 1.7f, paint); paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            for (int ring = 0; ring < 5; ring++) {
                paint.setStrokeWidth(dp(ring == 0 ? 1.5f : .7f)); paint.setColor(ring == 0 ? TEAL : 0x684dddc5); Path path = ringPath; path.reset();
                for (int i = 0; i <= 180; i++) { double a = i * Math.PI / 90; float r = radius + ring * dp(4) + (float) Math.sin(a * 3 + phase + ring * .6) * dp(3 + level * 10); float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r; if (i == 0) path.moveTo(x, y); else path.lineTo(x, y); }
                path.close(); c.drawPath(path, paint);
            }
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(dp(3)); paint.setColor(TEAL);
            for (int i = -3; i <= 3; i++) { float h = dp(6 + (3 - Math.abs(i)) * 5) * (1 + level * .8f) + (float) Math.sin(phase * 2 + i) * dp(3); c.drawLine(cx + dp(i * 8), cy - h / 2, cx + dp(i * 8), cy + h / 2, paint); }
            if (isShown() && visible) postInvalidateDelayed(50);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateDelayed(100); }
    }
    private final class IconView extends View {
        final int icon, color; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        IconView(int i, int c) { super(MainActivity.this); icon = i; color = c; }
        @Override protected void onDraw(Canvas c) { c.save(); c.scale(getWidth() / 24f, getHeight() / 24f); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setStrokeCap(Paint.Cap.ROUND);
            if (icon == 0) { c.drawRoundRect(3, 3, 21, 18, 5, 5, p); c.drawLine(7, 18, 7, 22, p); c.drawLine(7, 22, 12, 18, p); c.drawLine(8, 9, 8, 12, p); c.drawLine(12, 7, 12, 14, p); c.drawLine(16, 9, 16, 12, p); }
            else if (icon == 1) { c.drawRoundRect(4, 3, 20, 21, 3, 3, p); c.drawLine(8, 8, 16, 8, p); c.drawLine(8, 12, 16, 12, p); c.drawLine(8, 16, 12, 16, p); }
            else if (icon == 2) { c.drawRoundRect(2, 7, 22, 21, 3, 3, p); c.drawRoundRect(8, 3, 16, 7, 2, 2, p); c.drawLine(3, 12, 21, 12, p); c.drawLine(12, 10, 12, 15, p); }
            else if (icon == 3) { path.reset(); path.moveTo(12, 22); path.cubicTo(8, 16, 4, 12, 4, 8); path.cubicTo(4, -1, 20, -1, 20, 8); path.cubicTo(20, 12, 16, 16, 12, 22); c.drawPath(path, p); c.drawCircle(12, 8, 3, p); }
            else { c.drawCircle(12, 7, 4, p); c.drawArc(4, 13, 20, 28, 180, 180, false, p); } c.restore(); }
    }
}
