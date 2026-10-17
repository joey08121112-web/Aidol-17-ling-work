package com.aidol.demo;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Offline visual review only. Fixtures never enter an account or touch provider APIs. */
public final class VisualReviewInstrumentation extends Instrumentation {
    private MainActivity activity;
    private File output;
    private String prefix;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        prefix = arguments == null ? "default" : arguments.getString("label", "default");
        // Keep output inside the test app's directory, including for command-line arguments.
        prefix = prefix.replaceAll("[^a-zA-Z0-9_-]", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            DevelopmentSettings settings = new DevelopmentSettings(getTargetContext());
            ApiClient client = new ApiClient(getTargetContext(), settings.serverUrl());
            if (!client.vault.token().isEmpty()) throw new IllegalStateException("Sign out of the debug app before offline visual review; existing accounts are never modified by this runner.");
            output = new File(getTargetContext().getExternalFilesDir(null), "visual-review");
            if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create output directory");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            capture("00-login");
            onMain(() -> { invoke("showAuth", new Class<?>[]{int.class}, 1); }); capture("01-register");
            onMain(() -> { invoke("showAuth", new Class<?>[]{int.class}, 2); }); capture("02-recovery");
            onMain(() -> {
                set("state", new JSONObject("{\"settings\":{\"mode\":\"life\"},\"memories\":[],\"transcripts\":[],\"tasks\":[]}"));
                set("tab", 0); invoke("showApp", new Class<?>[]{});
                ((EditText) get("chatInput")).setText("尚未发送的草稿");
                invoke("showApp", new Class<?>[]{});
                if (!"尚未发送的草稿".contentEquals(((EditText) get("chatInput")).getText())) throw new AssertionError("Refresh lost draft");
                invoke("switchTab", new Class<?>[]{int.class}, 1);
                invoke("switchTab", new Class<?>[]{int.class}, 0);
                if (!"尚未发送的草稿".contentEquals(((EditText) get("chatInput")).getText())) throw new AssertionError("Navigation lost draft");
                ((EditText) get("chatInput")).setText("");
            });
            capture("03-conversation");
            onMain(() -> {
                TextView toggle = findText(activity.getWindow().getDecorView(), "聆听方式");
                if (toggle == null) throw new AssertionError("Missing listening disclosure");
                toggle.performClick();
                TextView manual = findText(activity.getWindow().getDecorView(), "手动");
                if (manual == null || !manual.isShown()) throw new AssertionError("Listening options cannot be opened");
                toggle.performClick();
                if (manual.isShown()) throw new AssertionError("Listening options cannot be closed");
            });
            String[] pages = {"conversation", "memories", "work", "commute", "profile"};
            for (int tab = 1; tab < 5; tab++) {
                final int selected = tab;
                onMain(() -> { invoke("switchTab", new Class<?>[]{int.class}, selected); });
                capture("0" + (tab + 3) + "-" + pages[tab]);
            }
            onMain(() -> {
                set("state", new JSONObject("{\"settings\":{\"mode\":\"life\"},\"memories\":[{\"id\":\"fixture\",\"kind\":\"preference\",\"content\":\"喜欢安静的咖啡馆，周末想留一点时间给自己。\",\"pinned\":true,\"created_at\":\"2026-10-17T08:30:00+08:00\"}],\"transcripts\":[{\"speaker\":\"user\",\"text\":\"今天想慢下来，去附近走走。\",\"created_at\":\"2026-10-17T08:30:00+08:00\"},{\"speaker\":\"assistant\",\"text\":\"那就留一点时间给自己。想去公园散散步，还是找一家安静的咖啡馆？\",\"created_at\":\"2026-10-17T08:30:00+08:00\"}]}"));
                set("tab", 1); invoke("showApp", new Class<?>[]{});
            }); capture("08-memory-content");
            onMain(() -> { set("tab", 0); invoke("showApp", new Class<?>[]{}); });
            waitForIdleSync();
            onMain(() -> { ((android.widget.ScrollView) get("scroll")).fullScroll(View.FOCUS_DOWN); });
            capture("09-chat-content");
            onMain(() -> {
                invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject(
                    "{\"text\":\"我答应什么时候交方案？\",\"response\":\"周五交方案。\",\"citations\":[{\"text\":\"我答应周五交方案\",\"created_at\":\"2026-10-17T08:30:00+08:00\"}]}"));
                TextView evidence = findText(activity.getWindow().getDecorView(), "查看最近回答依据");
                if (evidence == null) throw new AssertionError("Missing answer evidence entry");
                evidence.performClick();
                android.app.AlertDialog sources = (android.app.AlertDialog) get("sourceDialog");
                if (!sources.isShowing() || findText(sources.getWindow().getDecorView(), "我答应周五交方案") == null)
                    throw new AssertionError("Missing citation original text");
                invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject(
                    "{\"interaction\":\"memory_forget\",\"text\":\"忘记刚才那条\",\"response\":\"已忘记这条记忆。\"}"));
                if (sources.isShowing()) throw new AssertionError("Forget left old evidence dialog visible");
                if (((org.json.JSONArray) get("lastCitations")).length() != 0
                        || ((JSONObject) get("state")).has("memories")
                        || ((JSONObject) get("state")).has("transcripts"))
                    throw new AssertionError("Forget retained private snapshots");
                if (findText(activity.getWindow().getDecorView(), "周五交方案") != null)
                    throw new AssertionError("Forget retained old chat evidence");
                set("state", new JSONObject("{\"settings\":{\"mode\":\"work\"},\"meeting_report\":{\"summary\":\"纪要草稿\",\"coverage\":{\"available_records\":12,\"summarized_records\":10,\"omitted_records\":2,\"partial\":true,\"note\":\"存在未纳入片段，无法确认持续采集。\"}}}"));
                set("tab", 2); invoke("showApp", new Class<?>[]{});
                if (findText(activity.getWindow().getDecorView(), "记录覆盖：已保存 12 条") == null
                        || findText(activity.getWindow().getDecorView(), "仅覆盖部分记录") == null)
                    throw new AssertionError("Meeting coverage missing");
            });
            runOnMainSync(() -> activity.finish());
            result.putString("stream", "Visual review passed: auth, five tabs, disclosure, draft retention, content fixtures, citations, forget cache invalidation, meeting coverage. Screenshots: " + output);
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable error) {
            if (activity != null) runOnMainSync(() -> activity.finish());
            result.putString("stream", "Visual review FAILED: " + android.util.Log.getStackTraceString(error));
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private interface Checked { void run() throws Exception; }
    private void onMain(Checked operation) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { operation.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    private Object get(String name) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
    private void set(String name, Object value) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception { Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args); }
    private TextView findText(View view, String prefix) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(prefix)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView match = findText(((ViewGroup) view).getChildAt(i), prefix); if (match != null) return match;
        }
        return null;
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(350);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(output, prefix + "-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); }
    }
}
