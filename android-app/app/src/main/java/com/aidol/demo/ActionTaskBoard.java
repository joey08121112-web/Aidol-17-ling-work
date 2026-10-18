package com.aidol.demo;

import android.app.*;
import android.graphics.Paint;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Owns local row updates, leaving the containing page and its scroll position intact. */
final class ActionTaskBoard implements AutoCloseable {
    private final Activity activity;
    private final Supplier<ApiClient> api;
    private final BooleanSupplier allowed;
    private final Consumer<JSONArray> changed;
    private final Consumer<String> error;
    private final Runnable notificationPermission;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JSONArray tasks = new JSONArray();
    private LinearLayout rows;
    private TextView count;
    private ScrollView scroll;
    private boolean closed;
    private long mutationVersion;
    private int mutations;
    private TextView notificationHint;
    private Dialog addDialog;
    private ApiClient sessionClient;
    private String sessionToken = "";
    private BiConsumer<String, String> openMeeting = (meetingId, citation) -> {};
    private void observeSession() {
        ApiClient c = api.get(); String t = c == null ? "" : c.vault.token();
        if (c != sessionClient || !t.equals(sessionToken)) { sessionClient = c; sessionToken = t; mutations = 0; mutationVersion++; }
    }
    ActionTaskBoard(Activity a, Supplier<ApiClient> api, BooleanSupplier allowed, Consumer<JSONArray> changed, Consumer<String> error, Runnable permission) {
        this.activity = a; this.api = api; this.allowed = allowed; this.changed = changed; this.error = error; this.notificationPermission = permission;
    }
    private int dp(float n) { return EditorUi.dp(activity, n); }
    /** Opens a task's source meeting at the sentence it cites (meeting id, first source id or ""). */
    void onOpenMeeting(BiConsumer<String, String> open) { openMeeting = open; }
    static String account(ApiClient c) { return ServerEndpoint.sessionNamespace(c.serverUrl) + ":" + c.vault.username(); }
    void render(LinearLayout parent, JSONArray value, ScrollView scroll) {
        observeSession(); tasks = value; this.scroll = scroll;
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(0, dp(20), 0, dp(8)); parent.addView(header);
        LinearLayout words = EditorUi.column(activity); header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = EditorUi.text(activity, "行动清单", 18, EditorUi.INK); title.setTypeface(null, android.graphics.Typeface.BOLD); words.addView(title);
        count = EditorUi.text(activity, "", 12, EditorUi.MUTED); words.addView(count); updateCount();
        TextView plus = EditorUi.button(activity, "+", false, this::addDialog); plus.setTextSize(27); plus.setPadding(0, 0, 0, 0);
        plus.setMinHeight(0); plus.setContentDescription("添加自定义行动清单");
        plus.setBackground(EditorUi.shape(activity, android.graphics.Color.WHITE, 50, EditorUi.ACCENT));
        LinearLayout.LayoutParams add = new LinearLayout.LayoutParams(dp(44), dp(44)); add.setMargins(dp(8), dp(3), 0, dp(3)); header.addView(plus, add);
        rows = EditorUi.column(activity); parent.addView(rows);
        for (int i = 0; i < tasks.length(); i++) { JSONObject task = tasks.optJSONObject(i); if (task != null) row(task, false); }
        emptyState();
        sync(value, false);
    }
    Runnable preserveViewport() {
        if (scroll == null || rows == null || !rows.isAttachedToWindow() || !rows.isShown()) return () -> {};
        final ScrollView viewport = scroll;
        final LinearLayout list = rows;
        int[] screen = new int[2]; viewport.getLocationOnScreen(screen);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i); int[] at = new int[2]; child.getLocationOnScreen(at);
            if (at[1] + child.getHeight() > screen[1] && at[1] < screen[1] + viewport.getHeight() && child.getTag() instanceof String) {
                final String id = (String) child.getTag(); final int top = at[1];
                return () -> {
                    android.view.ViewTreeObserver observer = viewport.getViewTreeObserver();
                    observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                        public boolean onPreDraw() {
                            if (viewport.getViewTreeObserver().isAlive()) viewport.getViewTreeObserver().removeOnPreDrawListener(this);
                            View anchor = list.findViewWithTag(id);
                            if (viewport == scroll && list == rows && list.isAttachedToWindow() && list.isShown()
                                    && anchor != null && anchor.isAttachedToWindow() && anchor.isShown()) {
                                int[] now = new int[2]; anchor.getLocationOnScreen(now); viewport.scrollBy(0, now[1] - top);
                            }
                            return true;
                        }
                    });
                    viewport.invalidate();
                };
            }
        }
        return () -> {};
    }
    private void updateCount() {
        int open = 0; for (int i = 0; i < tasks.length(); i++) if (!"done".equals(tasks.optJSONObject(i).optString("status"))) open++;
        if (count != null) count.setText(open + " 项待完成");
    }
    private void emptyState() {
        if (rows != null && tasks.length() == 0) { TextView empty = EditorUi.text(activity, "还没有待办，点击 + 添加。", 14, EditorUi.MUTED); empty.setTag("empty"); rows.addView(empty); }
    }
    private SwipeTaskRow row(JSONObject task, boolean first) {
        String id = task.optString("id"); boolean done = "done".equals(task.optString("status"));
        SwipeTaskRow shell = new SwipeTaskRow(activity); shell.setTag(id); shell.setClipToOutline(true);
        shell.setBackground(EditorUi.shape(activity, 0xfffff0f0, 20, 0xfff2d8dc));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2); outer.setMargins(0, dp(6), 0, dp(6)); rows.addView(shell, first ? 0 : rows.getChildCount(), outer);
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(actions, new android.widget.FrameLayout.LayoutParams(dp(176), -1, Gravity.END));
        TextView edit = EditorUi.button(activity, "编辑", false, () -> { if (shell.isEnabled()) taskDialog(task); });
        edit.setContentDescription("编辑待办：" + task.optString("content"));
        edit.setBackground(EditorUi.shape(activity, 0xffeaf1ff, 16, 0xffd0dfff));
        actions.addView(edit, new LinearLayout.LayoutParams(dp(88), -1));
        TextView delete = EditorUi.button(activity, "删除", false, () -> remove(task, shell)); delete.setTextColor(0xffc34f5a); delete.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        delete.setContentDescription("删除待办：" + task.optString("content"));
        actions.addView(delete, new LinearLayout.LayoutParams(dp(88), -1));
        LinearLayout card = EditorUi.column(activity); card.setPadding(dp(14), dp(10), dp(14), dp(12)); card.setBackground(EditorUi.shape(activity, -1, 20, EditorUi.LINE));
        shell.addView(card, new android.widget.FrameLayout.LayoutParams(-1, -2)); shell.foreground(card);
        CheckBox check = new CheckBox(activity); check.setText(task.optString("content")); check.setTextSize(15); check.setMinHeight(dp(48)); check.setFocusable(false);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(EditorUi.ACCENT)); check.setChecked(done); paint(check, done); card.addView(check);
        String due = task.optString("due_at");
        String deadline = due.isEmpty() ? task.optString("due", "待确认") : TimeFormat.format(due);
        String detail = "负责人：" + (task.optString("owner").isEmpty() ? "待确认" : task.optString("owner")) + "\n截止：" + (deadline.isEmpty() ? "待确认" : deadline);
        if (task.optInt("reminder_hours") > 0) detail += " · 提前" + (task.optInt("reminder_hours") == 24 ? "1天" : task.optInt("reminder_hours") + "小时") + "提醒";
        card.addView(EditorUi.text(activity, detail, 11, EditorUi.MUTED));
        JSONObject meeting = task.optJSONObject("meeting");
        if (meeting != null) {
            String title = meeting.optString("title", "会议记录"), when = TimeFormat.format(meeting.optString("started_at"));
            TextView source = EditorUi.text(activity, "来自会议：" + title + (when == null || when.isEmpty() ? "" : " · " + when) + "  ›", 12, EditorUi.ACCENT);
            source.setMinHeight(dp(40)); source.setGravity(Gravity.CENTER_VERTICAL); source.setClickable(true); source.setFocusable(true);
            source.setContentDescription("查看来源会议：" + title + "，跳到对应原话");
            JSONArray ids = task.optJSONArray("source_ids");
            String meetingId = meeting.optString("id"), citation = ids == null || ids.length() == 0 ? "" : ids.optString(0);
            source.setOnClickListener(v -> openMeeting.accept(meetingId, citation));
            card.addView(source);
        }
        check.setOnClickListener(v -> {
            if (!allowed.getAsBoolean()) { check.setChecked("done".equals(task.optString("status"))); return; }
            mutationVersion++; mutations++;
            final ScrollView viewport = scroll;
            final LinearLayout list = rows;
            boolean selected = check.isChecked(); int y = viewport == null ? 0 : viewport.getScrollY(); check.setEnabled(false);
            card.animate().alpha(.72f).setDuration(120).start();
            execute(c -> c.json("PATCH", "/v1/tasks/" + id, new JSONObject().put("status", selected ? "done" : "open")), r -> {
                mutations--;
                for (int i = 0; i < tasks.length(); i++) if (tasks.optJSONObject(i).optString("id").equals(id)) try { tasks.optJSONObject(i).put("status", selected ? "done" : "open"); } catch (JSONException ignored) {}
                try { task.put("status", selected ? "done" : "open"); } catch (JSONException ignored) {}
                paint(check, selected); check.setEnabled(true); card.animate().alpha(1).setDuration(180).start();
                updateCount(); publish();
                // A delayed reply must not move another work section or a rebuilt page.
                if (viewport != null && viewport == scroll && list != null && list == rows
                        && viewport.isAttachedToWindow() && list.isAttachedToWindow() && list.isShown())
                    viewport.scrollTo(0, y);
            }, () -> { mutations--; check.setChecked(!selected); check.setEnabled(true); card.animate().alpha(1).setDuration(180).start(); });
        });
        card.setOnLongClickListener(v -> { new AlertDialog.Builder(activity).setItems(new String[]{"编辑待办", "删除待办"}, (d, which) -> { if (which == 0) taskDialog(task); else remove(task, shell); }).show(); return true; });
        return shell;
    }
    private void paint(CheckBox view, boolean done) { view.setTextColor(done ? EditorUi.MUTED : EditorUi.INK); view.setPaintFlags(done ? view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG : view.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG); }
    private void remove(JSONObject task, View shell) {
        if (!allowed.getAsBoolean() || !shell.isEnabled()) return;
        mutationVersion++; mutations++; shell.setEnabled(false);
        execute(c -> c.json("DELETE", "/v1/tasks/" + task.optString("id"), null), r -> {
            mutations--; JSONArray next = new JSONArray(); for (int i = 0; i < tasks.length(); i++) if (!tasks.optJSONObject(i).optString("id").equals(task.optString("id"))) next.put(tasks.optJSONObject(i));
            tasks = next; TaskReminders.cancel(activity, task.optString("id")); publish(); updateCount();
            shell.animate().alpha(0).translationX(-dp(24)).setDuration(180).withEndAction(() -> { if (shell.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) shell.getParent()).removeView(shell); emptyState(); }).start();
        }, () -> { mutations--; shell.setEnabled(true); });
    }
    void addDialog() { taskDialog(null); }
    private void taskDialog(JSONObject existing) {
        if (!allowed.getAsBoolean() || addDialog != null) return;
        boolean editing = existing != null;
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        EditText content = EditorUi.input(activity, body, "内容", editing ? existing.optString("content") : "", true);
        EditText owner = EditorUi.input(activity, body, "负责人", editing ? existing.optString("owner") : "", false);
        ZonedDateTime[] due = {editing ? null : ZonedDateTime.now().plusDays(1).withSecond(0).withNano(0)};
        if (editing && !existing.optString("due_at").isEmpty()) try { due[0] = Instant.parse(existing.optString("due_at")).atZone(ZoneId.systemDefault()); } catch (Exception ignored) {}
        String originalDue = editing ? existing.optString("due") : "";
        body.addView(EditorUi.text(activity, "截止时间", 12, EditorUi.MUTED));
        TextView date = EditorUi.button(activity, due[0] != null ? TimeFormat.format(due[0].toInstant().toString())
            : originalDue.isEmpty() ? "未设置 · 选择日期和时间" : originalDue + " · 选择具体时间", false, () -> {}); body.addView(date);
        date.setOnClickListener(v -> {
            ZonedDateTime current = due[0] != null ? due[0] : ZonedDateTime.now().plusDays(1);
            DatePickerDialog picker = new DatePickerDialog(activity, (d, year, month, day) -> new TimePickerDialog(activity, (t, hour, minute) -> {
                due[0] = LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()); date.setText(TimeFormat.format(due[0].toInstant().toString()));
            }, current.getHour(), current.getMinute(), true).show(), current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()); picker.show();
        });
        body.addView(EditorUi.text(activity, "提前提醒", 12, EditorUi.MUTED));
        RadioGroup options = new RadioGroup(activity); options.setOrientation(LinearLayout.HORIZONTAL); int[] hours = {0, 1, 6, 24}; int[] choice = {editing ? existing.optInt("reminder_hours") : 1};
        for (int hour : hours) { RadioButton option = new RadioButton(activity); option.setText(hour == 0 ? "不提醒" : hour == 24 ? "1天" : hour + "h"); option.setId(View.generateViewId()); option.setTextSize(13);
            option.setPadding(0, dp(8), 0, dp(8)); options.addView(option, new RadioGroup.LayoutParams(0, -2, 1)); if (hour == choice[0]) option.setChecked(true);
            option.setOnClickListener(v -> choice[0] = hour); }
        options.setBackground(EditorUi.shape(activity, -1, 14, EditorUi.LINE)); body.addView(options);
        if (due[0] == null) body.addView(EditorUi.text(activity, "文字截止时间会保留；开启提醒需选择具体日期和时间。", 12, EditorUi.MUTED));
        else if (editing && !due[0].toInstant().isAfter(Instant.now())) body.addView(EditorUi.text(activity, "原截止时间已过，可继续编辑；调整到未来时间后才能收到提醒。", 12, EditorUi.MUTED));
        notificationHint = EditorUi.text(activity, "", 12, EditorUi.MUTED); body.addView(notificationHint); notificationsChanged();
        notificationHint.setOnClickListener(v -> { if (!activity.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName())); });
        TextView problem = EditorUi.text(activity, "", 12, 0xffc34f5a); body.addView(problem);
        Dialog dialog = EditorUi.dialog(activity, editing ? "编辑行动清单" : "添加行动清单", body, footer); addDialog = dialog;
        dialog.setOnDismissListener(d -> { notificationHint = null; addDialog = null; });
        String requestId = editing ? existing.optString("id") : UUID.randomUUID().toString();
        String saveLabel = editing ? "保存修改" : "添加";
        TextView save = EditorUi.button(activity, saveLabel, true, () -> {}); footer.addView(save);
        footer.addView(EditorUi.button(activity, "取消", false, dialog::dismiss));
        save.setOnClickListener(v -> {
            String text = content.getText().toString().trim();
            if (text.isEmpty() || text.length() > 500) { problem.setText("请输入1–500字待办内容"); return; }
            if (owner.length() > 100) { problem.setText("负责人最多100字"); return; }
            if (choice[0] > 0 && due[0] == null) { problem.setText("开启提醒前请选择具体截止时间"); return; }
            if (!editing && (due[0] == null || !due[0].toInstant().isAfter(Instant.now()))) { problem.setText("请选择未来的截止时间"); return; }
            String ownerText = owner.getText().toString().trim(), deadline = due[0] == null ? null : due[0].toInstant().toString();
            int lead = choice[0];
            mutationVersion++; mutations++; save.setEnabled(false); save.setText(editing ? "正在保存…" : "正在添加…");
            execute(c -> c.json(editing ? "PUT" : "POST", editing ? "/v1/tasks/" + requestId : "/v1/tasks",
                new JSONObject().put("task_id", requestId).put("content", text).put("owner", ownerText)
                    .put("due", originalDue).put("due_at", deadline == null ? JSONObject.NULL : deadline).put("reminder_hours", lead)), result -> {
                    mutations--;
                    Runnable restore = preserveViewport();
                    if (editing) {
                        for (int i = 0; i < tasks.length(); i++) if (requestId.equals(tasks.optJSONObject(i).optString("id"))) try { tasks.put(i, result); } catch (JSONException ignored) {}
                        if (rows != null && rows.isAttachedToWindow()) {
                            View old = rows.findViewWithTag(requestId);
                            if (old != null) { int index = rows.indexOfChild(old); rows.removeView(old); View replacement = row(result, false); rows.removeView(replacement); rows.addView(replacement, index);
                                replacement.setAlpha(.65f); replacement.animate().alpha(1).setDuration(180).start(); }
                        }
                    } else {
                        JSONArray next = new JSONArray().put(result); for (int i = 0; i < tasks.length(); i++) next.put(tasks.optJSONObject(i)); tasks = next;
                        if (rows != null && rows.isAttachedToWindow()) { View empty = rows.findViewWithTag("empty"); if (empty != null) rows.removeView(empty); row(result, true); }
                    }
                    publish(); updateCount(); dialog.dismiss(); restore.run();
                }, () -> { mutations--; save.setEnabled(true); save.setText(saveLabel); problem.setText("保存未完成，请重试；已填写的内容会保留。"); });
        });
        dialog.show(); dialog.getWindow().setLayout(activity.getResources().getDisplayMetrics().widthPixels - dp(28), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .78f));
        notificationPermission.run();
    }
    private interface Job { JSONObject run(ApiClient client) throws Exception; }
    private void execute(Job job, Consumer<JSONObject> success, Runnable failure) {
        observeSession(); ApiClient client = api.get(); String token = client.vault.token();
        worker.execute(() -> { try { JSONObject result = job.run(client); ui.post(() -> { if (current(client, token)) success.accept(result); }); }
            catch (Exception e) { ui.post(() -> { if (current(client, token)) { failure.run(); error.accept(e.getMessage() == null ? "操作未完成，请重试" : e.getMessage()); } }); } });
    }
    private boolean current(ApiClient client, String token) { return !closed && !activity.isDestroyed() && api.get() == client && !token.isEmpty() && token.equals(client.vault.token()); }
    private void publish() { changed.accept(tasks); sync(tasks, false); }
    private void sync(JSONArray value, boolean complete) { ApiClient c = api.get(); if (c != null && !c.vault.token().isEmpty()) TaskReminders.sync(activity, account(c), value, complete); }
    void notificationsChanged() {
        if (notificationHint != null) notificationHint.setText(activity.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()
            ? "到提醒时间由 App 发系统通知；省电策略可能延迟送达。"
            : "通知尚未开启，待办仍可保存。允许系统通知后才能收到提醒；点此打开通知设置。");
    }
    void refreshReminders() { refreshReminders(true); }
    void refreshReminders(boolean preserveTaskPosition) { observeSession(); notificationsChanged(); ApiClient c = api.get(); if (c == null || c.vault.token().isEmpty()) return;
        long version = mutationVersion;
        execute(client -> client.json("GET", "/v1/tasks", null), result -> {
            if (version != mutationVersion || mutations > 0) return;
            JSONArray latest = result.optJSONArray("tasks"); if (latest == null) latest = new JSONArray();
            sync(latest, true);
            if (!latest.toString().equals(tasks.toString())) {
                tasks = latest; changed.accept(tasks); updateCount();
                // Detached rows may belong to a cached work tab that will be reattached unchanged.
                if (rows != null) { Runnable restore = preserveTaskPosition ? preserveViewport() : () -> {}; rows.removeAllViews(); for (int i = 0; i < tasks.length(); i++) row(tasks.optJSONObject(i), false); emptyState(); restore.run(); }
            }
        }, () -> {}); }
    @Override public void close() { if (addDialog != null) addDialog.dismiss(); closed = true; worker.shutdownNow(); }
}
