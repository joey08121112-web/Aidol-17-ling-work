package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Opt-in local USB smoke test. Creates and removes only its own named temporary tasks. */
public final class WorkFixesInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient api;
    private final List<String> created = new ArrayList<>();
    private File output;
    private boolean checkNotification = true;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); checkNotification = arguments == null || !"true".equals(arguments.getString("skip_reminder_wait")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1800); waitForIdleSync(); api = (ApiClient) field(activity, "api");
            if (!api.serverUrl.startsWith("http://127.0.0.1:") || api.vault.token().isEmpty()) throw new AssertionError("Requires a signed-in local USB development build");
            if (!getTargetContext().getSystemService(NotificationManager.class).areNotificationsEnabled()) throw new AssertionError("Enable notifications before reminder smoke test");
            output = new File(getTargetContext().getExternalFilesDir(null), "work-fixes"); output.mkdirs();
            String id = UUID.randomUUID().toString(), text = "Aidol UI smoke " + id.substring(0, 6);
            api.json("POST", "/v1/tasks", new JSONObject().put("task_id", id).put("content", text).put("owner", "Test")
                .put("due_at", Instant.now().plusSeconds(3620).toString()).put("reminder_hours", 1)); created.add(id);
            JSONObject state = api.json("GET", "/v1/state", null);
            main(() -> { set(activity, "state", state); set(activity, "tab", 2); invoke(activity, "showApp"); });
            SystemClock.sleep(500); waitForIdleSync();
            CheckBox check = (CheckBox) find(activity.getWindow().getDecorView(), text);
            if (check == null) throw new AssertionError("Task row missing");
            ScrollView scroll = (ScrollView) field(activity, "scroll");
            main(() -> { int[] p = new int[2], s = new int[2]; check.getLocationOnScreen(p); scroll.getLocationOnScreen(s); scroll.scrollBy(0, p[1] - s[1] - 80); });
            SystemClock.sleep(300); int before = scroll.getScrollY();
            main(() -> check.performClick()); awaitStatus(id, "done"); SystemClock.sleep(500);
            if (before != scroll.getScrollY()) throw new AssertionError("Checkbox moved page: " + before + " -> " + scroll.getScrollY());
            capture(activity.getWindow().getDecorView(), "01-check-position");
            main(() -> check.performClick()); awaitStatus(id, "open");
            if (before != scroll.getScrollY()) throw new AssertionError("Reopening task moved page");
            int[] pinnedAt = new int[2]; check.getLocationOnScreen(pinnedAt);
            JSONArray originalMeetings = ((JSONObject) field(activity, "state")).optJSONArray("work_meetings");
            JSONArray preview = new JSONArray(originalMeetings.toString());
            preview.put(new JSONObject().put("id", "ui-preview-only").put("title", "临时界面样例").put("summary_status", "ready").put("summary", "只用于布局回归，不保存。"));
            main(() -> { ((JSONObject) field(activity, "state")).put("work_meetings", preview); invoke(activity, "renderWorkMeetings"); });
            SystemClock.sleep(300); int[] afterUpdate = new int[2]; check.getLocationOnScreen(afterUpdate);
            if (afterUpdate[1] != pinnedAt[1]) throw new AssertionError("Meeting progress update moved task viewport");
            main(() -> { ((JSONObject) field(activity, "state")).put("work_meetings", originalMeetings); invoke(activity, "renderWorkMeetings"); });
            SystemClock.sleep(300);
            long end = SystemClock.elapsedRealtime() + 65000;
            boolean notified = !checkNotification;
            while (SystemClock.elapsedRealtime() < end) {
                for (android.service.notification.StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (id.equals(n.getTag())) notified = true;
                if (notified) break; SystemClock.sleep(500);
            }
            if (!notified) throw new AssertionError("Scheduled task notification did not arrive within smoke-test window");
            android.widget.FrameLayout shell = (android.widget.FrameLayout) check.getParent().getParent();
            int[] at = new int[2]; check.getLocationOnScreen(at); float density = activity.getResources().getDisplayMetrics().density;
            float x = at[0] + check.getWidth() - 16 * density, y = at[1] + check.getHeight() / 2f;
            long down = SystemClock.uptimeMillis(); sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0));
            for (int i = 1; i <= 8; i++) { SystemClock.sleep(30); sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x - i * 18 * density, y, 0)); }
            sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x - 144 * density, y, 0)); SystemClock.sleep(300);
            if (shell.getChildAt(1).getTranslationX() > -60 * density) throw new AssertionError("Swipe did not reveal deletion");
            capture(activity.getWindow().getDecorView(), "02-swipe-delete");
            main(() -> shell.getChildAt(0).performClick()); awaitStatus(id, null);
            main(() -> ((ActionTaskBoard) field(activity, "taskBoard")).addDialog()); SystemClock.sleep(400);
            Dialog add = latestDialog();
            if (add == null) throw new AssertionError("Custom task dialog missing");
            List<EditText> inputs = new ArrayList<>(); edits(add.getWindow().getDecorView(), inputs);
            main(() -> { inputs.get(0).setText("Aidol form smoke"); inputs.get(1).setText("Test"); });
            capture(add.getWindow().getDecorView(), "03-custom-task");
            main(() -> find(add.getWindow().getDecorView(), "添加").performClick()); SystemClock.sleep(1000);
            JSONArray all = api.json("GET", "/v1/tasks", null).getJSONArray("tasks");
            for (int i = 0; i < all.length(); i++) if ("Aidol form smoke".equals(all.getJSONObject(i).optString("content"))) created.add(all.getJSONObject(i).getString("id"));
            if (created.size() < 2) throw new AssertionError("Custom form did not save task");
            JSONObject doc = new JSONObject().put("title", "编辑测试");
            for (String key : new String[]{"summary","tasks","viewpoints","conflicts","events","topics","decisions","open_questions","chapters","highlights"}) doc.put(key, new JSONArray());
            doc.getJSONArray("summary").put(new JSONObject().put("id", "summary:0").put("text", "测试总结。"));
            doc.getJSONArray("tasks").put(new JSONObject().put("id", "tasks:0").put("content", "提交稿件").put("owner", "Old owner").put("due", "周五").put("source_ids", new JSONArray()));
            final MeetingRevisionEditor[] editor = new MeetingRevisionEditor[1];
            main(() -> { editor[0] = new MeetingRevisionEditor(activity, api, "work-fixes-fixture", new JSONObject().put("base_report_id", "fixture-base").put("document", doc), r -> {}); editor[0].show(); });
            SystemClock.sleep(400); Dialog editing = (Dialog) field(editor[0], "dialog");
            EditText owner = (EditText) find(editing.getWindow().getDecorView(), "Old owner");
            if (owner == null) throw new AssertionError("Revision owner field missing");
            main(() -> owner.setText("New owner"));
            if (!"New owner".equals(((JSONObject) field(editor[0], "document")).getJSONArray("tasks").getJSONObject(0).getString("owner"))) throw new AssertionError("Revision edits not retained");
            capture(editing.getWindow().getDecorView(), "04-revision-editor"); main(editing::dismiss);
            getTargetContext().getSharedPreferences("meeting_edit_drafts", Context.MODE_PRIVATE).edit().remove((String) field(editor[0], "draftKey")).commit();
            result.putString("stream", "PASS: checkbox/reopen preserve exact scroll; meeting updates preserve task viewport; " + (checkNotification ? "local alarm delivered system notification; " : "reminder timing skipped in layout-only rerun; ") + "swipe reveals deletion; custom form saves; correction editor retains text. Synthetic tasks cleaned up.");
            outcome = Activity.RESULT_OK;
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); }
        finally {
            if (api != null) for (String id : created) try { api.json("DELETE", "/v1/tasks/" + id, null); TaskReminders.cancel(getTargetContext(), id); } catch (Exception ignored) {}
        }
        finish(outcome, result);
    }
    private void awaitStatus(String id, String status) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            JSONArray all = api.json("GET", "/v1/tasks", null).getJSONArray("tasks"); JSONObject found = null;
            for (int i = 0; i < all.length(); i++) if (id.equals(all.getJSONObject(i).getString("id"))) found = all.getJSONObject(i);
            if (status == null ? found == null : found != null && status.equals(found.optString("status"))) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Task state did not settle");
    }
    private Dialog latestDialog() throws Exception { return (Dialog) field(field(activity, "taskBoard"), "addDialog"); }
    private void capture(View view, String name) throws Exception { main(() -> { Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new android.graphics.Canvas(bitmap)); try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle(); }); }
    private interface Checked { void run() throws Exception; }
    private void main(Checked call) throws Exception { java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>(); runOnMainSync(() -> { try { call.run(); } catch (Throwable e) { failure.set(e); } }); if (failure.get() != null) throw new Exception(failure.get()); }
    private Object field(Object owner, String name) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private void set(Object owner, String name, Object value) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private void invoke(Object owner, String name) throws Exception { Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner); }
    private TextView find(View root, String text) { if (root instanceof TextView && text.equals(((TextView) root).getText().toString())) return (TextView) root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { TextView v = find(((ViewGroup) root).getChildAt(i), text); if (v != null) return v; } return null; }
    private void edits(View root, List<EditText> found) { if (root instanceof EditText) found.add((EditText) root); if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) edits(((ViewGroup) root).getChildAt(i), found); }
}
