package com.aidol.demo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** Minimal foreground-only debug app. No backend, account system, or Viaim SDK. */
public final class MainActivity extends Activity {
    private EditText appId, accessKey, asrApiKey, arkKey, model;
    private TextView logView, transcript, reply, meter;
    private Button start, stop, cancel;
    private DirectSession session;
    private boolean visible;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(20, 29, 47));
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(36), dp(20), dp(28));
        scroll.setFillViewport(true); scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            column.setPadding(dp(20), insets.getSystemWindowInsetTop() + dp(20), dp(20), insets.getSystemWindowInsetBottom() + dp(20));
            return insets;
        });
        label(column, "Aidol · 耳机直连 Demo", 24).setTextColor(Color.rgb(28, 54, 100));
        label(column, "无需电脑后端 / 无需 Viaim Key\n耳机已连接安卓，打开“通话音频”和“媒体音频”。填写豆包凭证即可测试。", 14);
        appId = input(column, "火山语音 AppID / App Key", false);
        accessKey = input(column, "火山语音 Access Key（识别 + 合成）", true);
        arkKey = input(column, "火山方舟 Ark API Key（文本模型）", true);
        model = input(column, "文本模型名称", false);
        model.setText("doubao-seed-2-1-turbo-260628");
        asrApiKey = input(column, "可选：ASR API Key（仅识别独立凭证时填写）", true);
        label(column, "AppID/Access Key 用于语音合成，并默认用于识别。若识别使用独立 API Key，填入最后一栏。Key 仅保存在本次运行内存中。", 12);
        start = button(column, "① 开始录音", this::startSession);
        stop = button(column, "② 结束录音并回复", () -> { if (session != null) { stop.setEnabled(false); session.stopRecording(); } });
        cancel = button(column, "取消 / 停止播放", () -> { if (session != null) { stop.setEnabled(false); cancel.setEnabled(false); session.cancel(); log("已取消，正在释放音频设备…"); } });
        stop.setEnabled(false); cancel.setEnabled(false);
        meter = label(column, "等待开始", 14);
        transcript = label(column, "你说的话：", 16);
        reply = label(column, "豆包回复：", 16);
        logView = label(column, "", 12); logView.setTextIsSelectable(true);
        log("先等日志显示“实际麦克风”再说话。单轮最多 60 秒，离开页面自动取消。");
    }

    private void startSession() {
        if (session != null) return;
        if (value(appId).isEmpty() || value(accessKey).isEmpty() || value(arkKey).isEmpty() || value(model).isEmpty()) {
            log("请填写语音 AppID、Access Key、方舟 API Key 和模型名称。"); return;
        }
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 1); return; }
        transcript.setText("你说的话："); reply.setText("豆包回复："); meter.setText("正在连接…");
        session = new DirectSession(this, new DirectSession.Config(value(appId), value(accessKey), value(asrApiKey), value(arkKey), value(model)), new DirectSession.Listener() {
            @Override public void log(String message) { MainActivity.this.log(message); }
            @Override public void transcript(String text) { if (!isDestroyed()) transcript.setText("你说的话：" + text); }
            @Override public void reply(String text) { if (!isDestroyed()) reply.setText("豆包回复：" + text); }
            @Override public void recording() { if (!isDestroyed() && session != null) { stop.setEnabled(true); log("可以开始说话，说完点“结束录音并回复”。"); } }
            @Override public void processing() { if (!isDestroyed()) { stop.setEnabled(false); meter.setText("正在识别 / 回答…"); } }
            @Override public void meter(long bytes, double rms) {
                if (!isDestroyed()) meter.setText(String.format(Locale.ROOT, "已录 %.1f 秒 · 音量 %.1f%%", bytes / 32000.0, rms * 100));
            }
            @Override public void done() {
                session = null;
                if (!isDestroyed()) { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); controls(false); }
            }
        });
        controls(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session.start();
    }
    private String value(EditText view) { return view.getText().toString().trim(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 1) return;
        for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) { log("需要麦克风和附近设备权限。请允许后再开始。"); return; }
        if (grants.length > 0 && visible) startSession();
    }
    private void controls(boolean busy) {
        start.setEnabled(!busy); stop.setEnabled(false); cancel.setEnabled(busy);
        for (EditText view : new EditText[]{appId, accessKey, asrApiKey, arkKey, model}) view.setEnabled(!busy);
    }
    private void log(String message) {
        if (isDestroyed() || logView == null) return;
        String history = logView.getText().toString();
        if (history.length() > 6000) history = history.substring(history.length() - 4000);
        logView.setText(history + "\n" + new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date()) + "  " + message);
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setPadding(0, dp(6), 0, dp(6)); parent.addView(view); return view;
    }
    private EditText input(LinearLayout parent, String hint, boolean secret) {
        EditText view = new EditText(this); view.setHint(hint); view.setTextSize(14); view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        view.setSaveEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); parent.addView(view); return view;
    }
    @Override protected void onStart() { super.onStart(); visible = true; }
    @Override protected void onStop() { visible = false; if (session != null) session.cancel(); super.onStop(); }
    @Override protected void onDestroy() { if (session != null) session.cancel(); super.onDestroy(); }
}
