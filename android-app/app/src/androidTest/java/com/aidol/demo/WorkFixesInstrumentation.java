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
    private final List<String> createdMeetings = new ArrayList<>();
    private File output;
    private boolean checkNotification = true, optionsOnly, editOnly, meetingsOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); meetingsOnly = arguments != null && "true".equals(arguments.getString("meeting_management_only")); editOnly = arguments != null && "true".equals(arguments.getString("task_edit_only")); optionsOnly = arguments != null && "true".equals(arguments.getString("work_options_only")); checkNotification = arguments == null || !"true".equals(arguments.getString("skip_reminder_wait")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            SystemClock.sleep(1800); waitForIdleSync(); api = (ApiClient) field(activity, "api");
            if (!getTargetContext().getSystemService(PowerManager.class).isInteractive() || getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new AssertionError("Unlock and wake the phone before physical gesture tests");
            if (!api.serverUrl.startsWith("http://127.0.0.1:") || api.vault.token().isEmpty()) throw new AssertionError("Requires a signed-in local USB development build");
            if (!getTargetContext().getSystemService(NotificationManager.class).areNotificationsEnabled()) throw new AssertionError("Enable notifications before reminder smoke test");
            output = new File(getTargetContext().getExternalFilesDir(null), "work-fixes"); output.mkdirs();
            if (meetingsOnly) {
                reviewMeetingManagement();
                result.putString("stream", "PASS: bookmark fills red, multiple pins sort newest first, unpin restores order, reread retains pins; true horizontal swipe reveals delete, cancel preserves meeting/scroll, confirmed DELETE removes only fixture and preserves page position through polling. No audio or model calls. Temporary meetings cleaned up.");
                outcome = Activity.RESULT_OK;
            } else if (editOnly) {
                reviewTaskEditing();
                result.putString("stream", "PASS: real swipe exposes edit/delete; same task editor prefills content, owner, deadline and lead; cancel preserves data; date/time and lead changes save through PUT without moving page or changing ID/status/order; " + (checkNotification ? "rescheduled alarm delivers edited content; " : "notification timing skipped in layout rerun; ") + "location callback is silent and Bluetooth popup remains. Temporary task removed.");
                outcome = Activity.RESULT_OK;
            } else if (optionsOnly) {
                reviewWorkOptions();
                result.putString("stream", "PASS: rounded work pickers show current selection, highlight changes, confirm selection and preserve scrollY/header position on cancel and simulated save/report results. No server settings changed or AI requests made.");
                finish(Activity.RESULT_OK, result); return;
            }
            if (!editOnly && !meetingsOnly) {
            String id = UUID.randomUUID().toString(), text = "Aidol UI smoke " + id.substring(0, 6);
            api.json("POST", "/v1/tasks", new JSONObject().put("task_id", id).put("content", text).put("owner", "Test")
                .put("due_at", Instant.now().plusSeconds(3620).toString()).put("reminder_hours", 1)); created.add(id);
            JSONObject state = api.json("GET", "/v1/state", null);
            main(() -> { set(activity, "state", state); set(activity, "tab", 2); set(activity, "workSection", 2); invoke(activity, "showApp"); });
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
            main(() -> find(shell.getChildAt(0), "删除").performClick()); awaitStatus(id, null);
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
            }
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); }
        finally {
            if (api != null) for (String id : createdMeetings) try { api.json("DELETE", "/v1/work-meetings/" + id, null); } catch (Exception ignored) {}
            if (api != null) for (String id : created) try { api.json("DELETE", "/v1/tasks/" + id, null); TaskReminders.cancel(getTargetContext(), id); } catch (Exception ignored) {}
        }
        finish(outcome, result);
    }
    private void reviewMeetingManagement() throws Exception {
        JSONObject initial = api.json("GET", "/v1/state", null);
        if (!"work".equals(initial.getJSONObject("settings").optString("mode"))) throw new AssertionError("Requires existing work mode; test does not change user settings");
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString();
            api.json("POST", "/v1/work-meetings", new JSONObject().put("meeting_id", id).put("started_at", Instant.now().minusSeconds(10 + i * 60).toString()));
            createdMeetings.add(id);
            api.json("POST", "/v1/work-meetings/" + id + "/finish", new JSONObject().put("segment_count", 0).put("complete", true));
        }
        String newest = createdMeetings.get(0), middle = createdMeetings.get(1), oldest = createdMeetings.get(2);
        JSONObject state = api.json("GET", "/v1/state", null);
        main(() -> { set(activity, "state", state); set(activity, "tab", 2); invoke(activity, "showApp"); }); SystemClock.sleep(400);
        ScrollView scroll = (ScrollView) field(activity, "scroll");
        placeInViewport(scroll, meetingRow(oldest)); int before = scroll.getScrollY();
        main(() -> findType(meetingRow(oldest), MeetingBookmarkView.class).performClick()); awaitPin(oldest, true); SystemClock.sleep(400);
        if (scroll != field(activity, "scroll") || scroll.getScrollY() != before) throw new AssertionError("Bookmark moved page");
        main(() -> findType(meetingRow(middle), MeetingBookmarkView.class).performClick()); awaitPin(middle, true); SystemClock.sleep(400);
        LinearLayout rows = (LinearLayout) field(activity, "workMeetingList");
        if (!middle.equals(rows.getChildAt(0).getTag()) || !oldest.equals(rows.getChildAt(1).getTag())) throw new AssertionError("Multiple pinned meetings not sorted by time");
        if (!findType(meetingRow(middle), MeetingBookmarkView.class).isSelected()) throw new AssertionError("Bookmark not filled");
        placeInViewport(scroll, meetingRow(middle)); capture(activity.getWindow().getDecorView(), "09-meeting-bookmarks");
        main(() -> findType(meetingRow(oldest), MeetingBookmarkView.class).performClick()); awaitPin(oldest, false); SystemClock.sleep(350);
        if (!middle.equals(rows.getChildAt(0).getTag()) || !newest.equals(rows.getChildAt(1).getTag()) || findType(meetingRow(oldest), MeetingBookmarkView.class).isSelected()) throw new AssertionError("Unpin did not restore time order");
        JSONObject reread = api.json("GET", "/v1/state", null);
        main(() -> { set(activity, "state", reread); invoke(activity, "showApp"); }); SystemClock.sleep(300);
        if (!findType(meetingRow(middle), MeetingBookmarkView.class).isSelected()) throw new AssertionError("Pin did not persist on reread");
        ScrollView reopenedScroll = (ScrollView) field(activity, "scroll");
        View row = meetingRow(newest); placeInViewport(reopenedScroll, row); int deleteY = reopenedScroll.getScrollY();
        int[] at = new int[2]; row.getLocationOnScreen(at); float density = activity.getResources().getDisplayMetrics().density;
        float x = at[0] + row.getWidth() - 20 * density, y = at[1] + row.getHeight() / 2f;
        long down = SystemClock.uptimeMillis(); sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int i = 1; i <= 8; i++) { SystemClock.sleep(25); sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x - i * 18 * density, y, 0)); }
        sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x - 144 * density, y, 0)); SystemClock.sleep(250);
        FrameLayout shell = (FrameLayout) row;
        if (Math.abs(shell.getChildAt(1).getTranslationX() + 88 * density) > 2) { capture(activity.getWindow().getDecorView(), "10-meeting-swipe-failed"); throw new AssertionError("Meeting swipe did not reveal delete: " + shell.getChildAt(1).getTranslationX()); }
        capture(activity.getWindow().getDecorView(), "10-meeting-delete-swipe");
        main(() -> shell.getChildAt(0).performClick()); SystemClock.sleep(250);
        Dialog dialog = (Dialog) field(activity, "meetingDeleteDialog");
        if (dialog == null) throw new AssertionError("Delete confirmation missing");
        capture(dialog.getWindow().getDecorView(), "11-meeting-delete-confirm"); main(() -> find(dialog.getWindow().getDecorView(), "取消").performClick()); SystemClock.sleep(200);
        api.json("GET", "/v1/work-meetings/" + newest, null);
        if (reopenedScroll.getScrollY() != deleteY) throw new AssertionError("Cancel delete moved page");
        main(() -> shell.getChildAt(0).performClick()); SystemClock.sleep(150);
        Dialog confirmed = (Dialog) field(activity, "meetingDeleteDialog"); main(() -> find(confirmed.getWindow().getDecorView(), "删除会议").performClick());
        for (int i = 0; i < 40 && meetingRow(newest) != null; i++) SystemClock.sleep(100);
        SystemClock.sleep(3000);
        if (meetingRow(newest) != null || reopenedScroll != field(activity, "scroll") || reopenedScroll.getScrollY() != deleteY) throw new AssertionError("Delete restored row or moved page");
        JSONArray finalRows = api.json("GET", "/v1/work-meetings", null).getJSONArray("meetings");
        for (int i = 0; i < finalRows.length(); i++) if (newest.equals(finalRows.getJSONObject(i).getString("id"))) throw new AssertionError("Deleted meeting still on server");
        api.json("GET", "/v1/work-meetings/" + middle, null); api.json("GET", "/v1/work-meetings/" + oldest, null);
    }
    private View meetingRow(String id) throws Exception { return ((LinearLayout) field(activity, "workMeetingList")).findViewWithTag(id); }
    private void awaitPin(String id, boolean expected) throws Exception {
        for (int i = 0; i < 30; i++) { if (api.json("GET", "/v1/work-meetings/" + id, null).optBoolean("pinned") == expected) return; SystemClock.sleep(100); }
        throw new AssertionError("Pin did not persist");
    }
    private void reviewTaskEditing() throws Exception {
        String id = UUID.randomUUID().toString(), text = "Aidol edit smoke " + id.substring(0, 6), changed = text + " updated";
        String initialDue = Instant.now().plusSeconds(172800).toString();
        JSONObject initial = api.json("POST", "/v1/tasks", new JSONObject().put("task_id", id).put("content", text).put("owner", "Before")
            .put("due_at", initialDue).put("reminder_hours", 1)); created.add(id);
        JSONObject state = api.json("GET", "/v1/state", null);
        main(() -> { set(activity, "state", state); set(activity, "tab", 2); set(activity, "workSection", 2); invoke(activity, "showApp"); }); SystemClock.sleep(500);
        ScrollView scroll = (ScrollView) field(activity, "scroll");
        CheckBox check = (CheckBox) find(activity.getWindow().getDecorView(), text);
        if (check == null) throw new AssertionError("Edit fixture missing");
        placeInViewport(scroll, check); int y = scroll.getScrollY();
        FrameLayout shell = (FrameLayout) check.getParent().getParent();
        int[] at = new int[2]; check.getLocationOnScreen(at); float density = activity.getResources().getDisplayMetrics().density;
        float x = at[0] + check.getWidth() - 16 * density, sy = at[1] + check.getHeight() / 2f;
        long down = SystemClock.uptimeMillis(); sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, sy, 0));
        for (int i = 1; i <= 10; i++) { SystemClock.sleep(25); sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x - i * 20 * density, sy, 0)); }
        sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x - 200 * density, sy, 0)); SystemClock.sleep(250);
        if (Math.abs(shell.getChildAt(1).getTranslationX() + 176 * density) > 2) throw new AssertionError("Edit/delete swipe width incorrect");
        capture(activity.getWindow().getDecorView(), "07-task-edit-swipe");
        main(() -> find(shell.getChildAt(0), "编辑").performClick()); SystemClock.sleep(300);
        Dialog dialog = latestDialog(); List<EditText> inputs = new ArrayList<>(); edits(dialog.getWindow().getDecorView(), inputs);
        if (!text.equals(inputs.get(0).getText().toString()) || !"Before".equals(inputs.get(1).getText().toString())) throw new AssertionError("Editor did not prefill content/owner");
        if (find(dialog.getWindow().getDecorView(), TimeFormat.format(initial.optString("due_at"))) == null || !((RadioButton) find(dialog.getWindow().getDecorView(), "1h")).isChecked()) throw new AssertionError("Editor did not prefill deadline/reminder");
        main(() -> { inputs.get(0).setText("Canceled draft"); find(dialog.getWindow().getDecorView(), "取消").performClick(); }); SystemClock.sleep(250);
        if (!text.equals(taskById(id).optString("content")) || scroll.getScrollY() != y) throw new AssertionError("Cancel changed task/page");
        main(() -> find(shell.getChildAt(0), "编辑").performClick()); SystemClock.sleep(200);
        Dialog editing = latestDialog(); List<EditText> fields = new ArrayList<>(); edits(editing.getWindow().getDecorView(), fields);
        main(() -> { fields.get(0).setText(changed); fields.get(1).setText("After"); find(editing.getWindow().getDecorView(), "6h").performClick(); find(editing.getWindow().getDecorView(), TimeFormat.format(initial.optString("due_at"))).performClick(); });
        SystemClock.sleep(200);
        java.time.ZonedDateTime target = java.time.ZonedDateTime.now().plusHours(6).plusSeconds(15).withSecond(0).withNano(0);
        DatePicker picker = findType(activity, DatePicker.class);
        if (picker == null) throw new AssertionError("Deadline date picker missing");
        main(() -> { picker.updateDate(target.getYear(), target.getMonthValue() - 1, target.getDayOfMonth()); ((TextView) windowWith(picker).findViewById(android.R.id.button1)).performClick(); });
        SystemClock.sleep(200); TimePicker time = findType(activity, TimePicker.class);
        if (time == null) throw new AssertionError("Deadline time picker missing");
        main(() -> { time.setHour(target.getHour()); time.setMinute(target.getMinute()); ((TextView) windowWith(time).findViewById(android.R.id.button1)).performClick(); });
        SystemClock.sleep(250); capture(editing.getWindow().getDecorView(), "08-task-edit-form");
        main(() -> find(editing.getWindow().getDecorView(), "保存修改").performClick());
        for (int i = 0; i < 40 && !changed.equals(taskById(id).optString("content")); i++) SystemClock.sleep(100);
        JSONObject saved = taskById(id);
        if (!changed.equals(saved.optString("content")) || !"After".equals(saved.optString("owner")) || saved.optInt("reminder_hours") != 6
                || !target.toInstant().equals(Instant.parse(saved.optString("due_at"))) || !initial.optString("created_at").equals(saved.optString("created_at")) || !"open".equals(saved.optString("status"))) throw new AssertionError("Edit did not preserve/save task fields");
        SystemClock.sleep(500);
        if (scroll != field(activity, "scroll") || scroll.getScrollY() != y) throw new AssertionError("Saving edit moved page");
        JSONArray latest = api.json("GET", "/v1/tasks", null).getJSONArray("tasks");
        if (!id.equals(latest.getJSONObject(0).optString("id"))) throw new AssertionError("Edit changed task order");
        CheckBox updated = (CheckBox) find(activity.getWindow().getDecorView(), changed);
        FrameLayout updatedRow = (FrameLayout) updated.getParent().getParent();
        main(() -> find(updatedRow.getChildAt(0), "编辑").performClick()); SystemClock.sleep(250);
        Dialog reopened = latestDialog();
        if (reopened == null || find(reopened.getWindow().getDecorView(), changed) == null
                || !((RadioButton) find(reopened.getWindow().getDecorView(), "6h")).isChecked()) throw new AssertionError("Saved editor did not reopen prefilled");
        main(reopened::dismiss);
        final ForegroundLocationMonitor.Listener listener = (ForegroundLocationMonitor.Listener) field(field(activity, "locationMonitor"), "listener");
        main(() -> { ((TransientStatusPopup) field(activity, "statusPopup")).dismiss(); listener.updated(); });
        Object popup = field(activity, "statusPopup");
        if (field(popup, "bubble") != null || !((java.util.ArrayDeque<?>) field(popup, "pending")).isEmpty()) throw new AssertionError("Location still showed a popup");
        main(() -> ((TransientStatusPopup) popup).show("蓝牙已连接"));
        if (!"蓝牙已连接".equals(field(popup, "shown"))) throw new AssertionError("Bluetooth popup removed");
        main(() -> ((TransientStatusPopup) popup).dismiss());
        long until = SystemClock.elapsedRealtime() + 120000; boolean delivered = !checkNotification;
        while (SystemClock.elapsedRealtime() < until && !delivered) {
            for (android.service.notification.StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())
                if (id.equals(n.getTag())) { delivered = changed.equals(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT).toString()); }
            if (!delivered) SystemClock.sleep(500);
        }
        if (!delivered) {
            JSONObject local = new JSONObject(getTargetContext().getSharedPreferences("task_reminders", Context.MODE_PRIVATE).getString("tasks", "{}"));
            JSONObject snapshot = local.optJSONObject(id);
            throw new AssertionError("Edited reminder not delivered: now=" + Instant.now() + " testTask=" + snapshot
                + " next=" + (snapshot == null ? -2 : TaskReminders.reminderAt(snapshot, System.currentTimeMillis())));
        }
    }
    private JSONObject taskById(String id) throws Exception {
        JSONArray tasks = api.json("GET", "/v1/tasks", null).getJSONArray("tasks");
        for (int i = 0; i < tasks.length(); i++) if (id.equals(tasks.getJSONObject(i).optString("id"))) return tasks.getJSONObject(i);
        throw new AssertionError("Task missing: " + id);
    }
    private View windowWith(View child) { return child.getRootView(); }
    private <T extends View> T findType(Activity ignored, Class<T> type) throws Exception {
        Class<?> globalType = Class.forName("android.view.WindowManagerGlobal"); Object global = globalType.getMethod("getInstance").invoke(null);
        Field views = globalType.getDeclaredField("mViews"); views.setAccessible(true);
        for (Object root : (List<?>) views.get(global)) { T found = findType((View) root, type); if (found != null) return found; }
        return null;
    }
    private <T extends View> T findType(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { T found = findType(((ViewGroup) root).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private void reviewWorkOptions() throws Exception {
        JSONObject original = api.json("GET", "/v1/state", null);
        try {
            main(() -> { set(activity, "state", new JSONObject(original.toString())); set(activity, "tab", 2); invoke(activity, "showApp"); });
            SystemClock.sleep(400);
            ScrollView scroll = (ScrollView) field(activity, "scroll");
            TextView retention = (TextView) field(activity, "workRetentionAction");
            placeInViewport(scroll, retention); int retentionY = scroll.getScrollY();
            main(() -> invoke(activity, "showRecordingRetentionOptions")); SystemClock.sleep(250);
            RoundedChoiceDialog first = (RoundedChoiceDialog) field(activity, "workOptionsDialog");
            int initial = first.selectedIndex(), next = (initial + 1) % 5;
            main(() -> first.optionAt(next).performClick());
            if (!first.optionAt(next).isSelected() || first.optionAt(initial).isSelected()) throw new AssertionError("Retention selection highlight missing");
            if (((android.graphics.drawable.GradientDrawable) first.optionAt(next).getBackground()).getCornerRadius() <= 0) throw new AssertionError("Option has square corners");
            capture(first.getWindow().getDecorView(), "05-recording-options"); main(first::cancel); SystemClock.sleep(250);
            if (scroll.getScrollY() != retentionY) throw new AssertionError("Retention cancel moved page");
            main(() -> invoke(activity, "showRecordingRetentionOptions")); SystemClock.sleep(200);
            RoundedChoiceDialog reopened = (RoundedChoiceDialog) field(activity, "workOptionsDialog");
            if (reopened.selectedIndex() != initial) throw new AssertionError("Canceled setting was applied");
            main(reopened::cancel);
            JSONObject settings = new JSONObject(original.getJSONObject("settings").toString()).put("work_audio_retention_days", 90);
            main(() -> invokeJson("applyRecordingRetentionResult", settings)); SystemClock.sleep(250);
            if (scroll != field(activity, "scroll") || scroll.getScrollY() != retentionY || !retention.getText().toString().contains("90")) throw new AssertionError("Saving setting rebuilt or moved page");
            main(() -> ((LinearLayout) field(activity, "workSectionTabs")).getChildAt(1).performClick()); SystemClock.sleep(250);
            TextView range = (TextView) field(activity, "historyRangeAction"); placeInViewport(scroll, range); int rangeY = scroll.getScrollY();
            int[] before = new int[2]; range.getLocationOnScreen(before);
            main(() -> invoke(activity, "showHistoryRangeOptions")); SystemClock.sleep(200);
            RoundedChoiceDialog history = (RoundedChoiceDialog) field(activity, "workOptionsDialog");
            main(() -> history.optionAt(2).performClick());
            if (history.selectedIndex() != 2 || !history.optionAt(2).isSelected()) throw new AssertionError("History highlight missing");
            capture(history.getWindow().getDecorView(), "06-history-options"); main(history::cancel); SystemClock.sleep(200);
            if (scroll.getScrollY() != rangeY) throw new AssertionError("History cancel moved page");
            JSONObject report = new JSONObject().put("summary", "离线界面样例：检查整理结果原地更新。\n新增内容只改变历史纪要区域。")
                .put("created_at", Instant.now().toString()).put("hours", 168).put("decisions", new JSONArray());
            int[] confirmed = {-1}; final RoundedChoiceDialog[] confirm = new RoundedChoiceDialog[1];
            main(() -> { confirm[0] = new RoundedChoiceDialog(activity, "选择工作记录范围", "", new String[]{"最近 1 小时", "最近 24 小时", "最近 7 天"}, 1, "开始整理", index -> {
                confirmed[0] = index; try { invokeJson("applyHistoryMeetingReport", report); } catch (Exception e) { throw new RuntimeException(e); }
            }); confirm[0].show(); confirm[0].optionAt(2).performClick(); find(confirm[0].getWindow().getDecorView(), "开始整理").performClick(); });
            SystemClock.sleep(700); int[] after = new int[2]; range.getLocationOnScreen(after);
            if (confirmed[0] != 2 || scroll != field(activity, "scroll") || scroll.getScrollY() != rangeY || before[1] != after[1]) throw new AssertionError("History result moved or rebuilt page");
        } finally { main(() -> { RoundedChoiceDialog open = (RoundedChoiceDialog) field(activity, "workOptionsDialog"); if (open != null) open.dismiss(); set(activity, "state", original); invoke(activity, "showApp"); }); }
    }
    private void invokeJson(String name, JSONObject value) throws Exception { Method m = activity.getClass().getDeclaredMethod(name, JSONObject.class); m.setAccessible(true); m.invoke(activity, value); }
    private void placeInViewport(ScrollView scroll, View view) throws Exception {
        main(() -> { int[] p = new int[2], s = new int[2]; view.getLocationOnScreen(p); scroll.getLocationOnScreen(s); scroll.scrollBy(0, p[1] - s[1] - scroll.getHeight() / 3); });
        SystemClock.sleep(250);
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
