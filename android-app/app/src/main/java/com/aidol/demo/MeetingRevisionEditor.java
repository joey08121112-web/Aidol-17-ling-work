package com.aidol.demo;

import android.app.*;
import android.content.Context;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Text editing by section; identifiers/citations remain hidden and cannot be accidentally edited. */
final class MeetingRevisionEditor {
    private static final String[] KEYS = {"summary", "tasks", "viewpoints", "conflicts", "events", "topics", "decisions", "open_questions", "chapters", "highlights"};
    private static final String[] TITLES = {"总结", "行动项", "人物观点", "冲突与澄清", "重要事件", "讨论议题", "决策", "待确认", "智能章节", "重点原话"};
    private static final String[][] FIELDS = {{"text"}, {"content", "owner", "due"}, {"person", "text"}, {"text"}, {"content", "time", "location", "task"}, {"text"}, {"text"}, {"text"}, {"title", "summary"}, {"text"}};
    private static final String[][] LABELS = {{"总结段落"}, {"内容", "负责人", "截止时间"}, {"人物或角色", "观点"}, {"内容"}, {"事件", "时间", "地点", "相关任务"}, {"内容"}, {"内容"}, {"内容"}, {"标题", "章节摘要"}, {"原话"}};
    private final Activity activity;
    private final ApiClient client;
    private final String meetingId, baseId, draftKey;
    private final java.util.function.Consumer<JSONObject> submitted;
    private JSONObject document;
    private Dialog dialog;
    private boolean accepted, sending;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable persist = this::persist;
    MeetingRevisionEditor(Activity activity, ApiClient client, String meetingId, JSONObject response, java.util.function.Consumer<JSONObject> submitted) {
        this.activity = activity; this.client = client; this.meetingId = meetingId; this.submitted = submitted;
        this.baseId = response.optString("base_report_id");
        this.draftKey = ActionTaskBoard.account(client) + ":" + meetingId + ":" + baseId;
        this.document = response.optJSONObject("document");
        try {
            String local = activity.getSharedPreferences("meeting_edit_drafts", Context.MODE_PRIVATE).getString(draftKey, "");
            if (!local.isEmpty()) document = new JSONObject(local);
        } catch (JSONException ignored) {}
    }
    private void changed() { ui.removeCallbacks(persist); ui.postDelayed(persist, 300); }
    private void persist() { if (!accepted && document != null) activity.getSharedPreferences("meeting_edit_drafts", Context.MODE_PRIVATE).edit().putString(draftKey, document.toString()).apply(); }
    private void field(LinearLayout parent, String label, JSONObject item, String key, boolean multiline) {
        EditText input = EditorUi.input(activity, parent, label, item.optString(key), multiline);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { try { item.put(key, s.toString()); } catch (JSONException ignored) {} changed(); }
            public void afterTextChanged(Editable e) {}
        });
    }
    void show() {
        if (document == null || activity.isDestroyed()) return;
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        body.addView(EditorUi.text(activity, "你的修改优先；AI 会同步其余关联表述。原始录音和原文保留。", 12, EditorUi.MUTED));
        field(body, "会议标题", document, "title", false);
        for (int section = 0; section < KEYS.length; section++) section(body, section);
        TextView problem = EditorUi.text(activity, "", 12, 0xffc34f5a); footer.addView(problem);
        dialog = EditorUi.dialog(activity, "修正并重新生成纪要", body, footer);
        TextView submit = EditorUi.button(activity, "按修正重新生成", true, () -> {}); footer.addView(submit);
        footer.addView(EditorUi.button(activity, "稍后继续", false, () -> { if (!sending) dialog.dismiss(); }));
        String requestId = UUID.randomUUID().toString();
        submit.setOnClickListener(v -> {
            if (sending) return;
            if (document.optString("title").trim().isEmpty()) { problem.setText("请填写会议标题"); return; }
            sending = true; submit.setEnabled(false); submit.setText("正在提交…"); dialog.setCancelable(false); persist();
            final String token = client.vault.token();
            final JSONObject bodyCopy;
            try { bodyCopy = new JSONObject(document.toString()); } catch (JSONException e) { return; }
            worker.execute(() -> {
                try {
                    JSONObject result = client.json("POST", "/v1/work-meetings/" + android.net.Uri.encode(meetingId) + "/revise-summary",
                        new JSONObject().put("request_id", requestId).put("base_report_id", baseId).put("document", bodyCopy));
                    ui.post(() -> {
                        if (activity.isDestroyed() || !token.equals(client.vault.token())) return;
                        accepted = true; activity.getSharedPreferences("meeting_edit_drafts", Context.MODE_PRIVATE).edit().remove(draftKey).apply();
                        dialog.dismiss(); submitted.accept(result);
                    });
                } catch (Exception e) { ui.post(() -> {
                    if (activity.isDestroyed()) return;
                    sending = false; dialog.setCancelable(true); submit.setEnabled(true); submit.setText("按修正重新生成");
                    problem.setText(e.getMessage() == null ? "提交失败，编辑稿已保留，请重试" : e.getMessage());
                }); }
            });
        });
        dialog.setOnDismissListener(d -> { ui.removeCallbacks(persist); persist(); worker.shutdown(); });
        dialog.show(); dialog.getWindow().setLayout(activity.getResources().getDisplayMetrics().widthPixels - EditorUi.dp(activity, 28), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .9f));
    }
    private void section(LinearLayout parent, int section) {
        String key = KEYS[section]; JSONArray items = document.optJSONArray(key);
        if (items == null) { items = new JSONArray(); try { document.put(key, items); } catch (JSONException ignored) {} }
        final JSONArray entries = items;
        LinearLayout shell = EditorUi.column(activity); shell.setPadding(EditorUi.dp(activity, 12), EditorUi.dp(activity, 8), EditorUi.dp(activity, 12), EditorUi.dp(activity, 12));
        shell.setBackground(EditorUi.shape(activity, -1, 18, EditorUi.LINE)); LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2); margin.topMargin = EditorUi.dp(activity, 12); parent.addView(shell, margin);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL); shell.addView(header);
        TextView heading = EditorUi.text(activity, TITLES[section] + " · " + entries.length(), 16, EditorUi.INK); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout list = EditorUi.column(activity); shell.addView(list); list.setVisibility(section == 0 || section == 1 ? View.VISIBLE : View.GONE);
        header.setOnClickListener(v -> { list.setVisibility(list.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        TextView add = EditorUi.button(activity, "+", false, () -> {
            JSONObject item = new JSONObject(); try { item.put("id", "new-" + UUID.randomUUID()); if (section != 0) item.put("source_ids", new JSONArray()); for (String f : FIELDS[section]) item.put(f, ""); } catch (JSONException ignored) {}
            entries.put(item); list.setVisibility(View.VISIBLE); entry(list, item, entries, heading, section); heading.setText(TITLES[section] + " · " + entries.length()); changed();
        }); add.setContentDescription("添加" + TITLES[section]); header.addView(add, new LinearLayout.LayoutParams(EditorUi.dp(activity, 44), EditorUi.dp(activity, 44)));
        for (int i = 0; i < entries.length(); i++) entry(list, entries.optJSONObject(i), entries, heading, section);
    }
    private void entry(LinearLayout list, JSONObject item, JSONArray entries, TextView heading, int section) {
        if (item == null) return;
        LinearLayout card = EditorUi.column(activity); card.setPadding(0, EditorUi.dp(activity, 10), 0, EditorUi.dp(activity, 10)); list.addView(card);
        for (int i = 0; i < FIELDS[section].length; i++) { String field = FIELDS[section][i]; field(card, LABELS[section][i], item, field, field.equals("text") || field.equals("content") || field.equals("summary")); }
        TextView remove = EditorUi.button(activity, "删除此项", false, () -> {
            for (int i = 0; i < entries.length(); i++) if (entries.optJSONObject(i) == item) { entries.remove(i); break; }
            card.animate().alpha(0).setDuration(150).withEndAction(() -> list.removeView(card)).start(); heading.setText(TITLES[section] + " · " + entries.length()); changed();
        }); remove.setTextColor(0xffc34f5a); card.addView(remove);
        card.setAlpha(0); card.animate().alpha(1).setDuration(180).start();
    }
}
