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

    @Override public void callActivityOnCreate(android.app.Activity target, Bundle state) {
        super.callActivityOnCreate(target, state);
        if (!"life".equals(prefix) || !(target instanceof MainActivity)) return;
        try {
            // Current empty-vault loading returns without settling its latch. Settle only this
            // signed-out fixture's in-memory state so maintenance ticks do not starve UI idle.
            Field apiField = MainActivity.class.getDeclaredField("api"); apiField.setAccessible(true);
            ApiClient fixture = (ApiClient) apiField.get(target);
            Field vaultState = AuthVault.class.getDeclaredField("state"); vaultState.setAccessible(true);
            ((AuthVault.VaultState) vaultState.get(fixture.vault)).clear();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
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
            if ("life".equals(prefix)) {
                reviewLifeConversation();
                runOnMainSync(() -> activity.finish());
                result.putString("stream", "PASS: life transcript alignment, pinned modes/orb/controls, streaming replacement, unsaved turns, draft across tabs/modes, keyboard, settings remain open across replies, source invalidation, dialog cleanup and end button. Offline UI fixtures only. Screenshots: " + output);
                finish(android.app.Activity.RESULT_OK, result);
                return;
            }
            if ("meetings".equals(prefix)) {
                reviewWorkMeetings();
                runOnMainSync(() -> activity.finish());
                result.putString("stream", "Meeting UI review passed: progress list, original ASR, pending minutes, automatic completion, source disclosures, fallback and 1.6x font. Screenshots: " + output);
                finish(android.app.Activity.RESULT_OK, result);
                return;
            }
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
                invoke("showLifeCallSettings", new Class<?>[]{});
                TextView manual = (TextView) get("voiceHint");
                if (manual == null || !manual.isShown()) throw new AssertionError("Listening options cannot be opened");
                ((android.app.AlertDialog) get("lifeCallSettingsDialog")).dismiss();
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
            onMain(() -> { findText(activity.getWindow().getDecorView(), "···").performClick(); });
            capture("08b-memory-menu");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            waitForIdleSync();
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
                        || ((JSONObject) get("state")).optString("transcripts").contains("周五交方案")
                        || ((JSONObject) get("state")).optString("transcripts").contains("今天想慢下来"))
                    throw new AssertionError("Forget retained private snapshots");
                if (findText(activity.getWindow().getDecorView(), "周五交方案") != null)
                    throw new AssertionError("Forget retained old chat evidence");
                set("state", new JSONObject("{\"settings\":{\"mode\":\"work\"},\"meeting_report\":{\"created_at\":\"2026-10-17T08:30:00+08:00\",\"summary\":\"本次讨论确认了新版本的体验方向：先完善核心页面，再安排下一轮设备测试。周五前整理反馈并确认优先级。\",\"decisions\":[{\"text\":\"先完成记忆、工作和通勤三个页面的体验优化。\"}],\"coverage\":{\"available_records\":12,\"summarized_records\":10,\"omitted_records\":2,\"partial\":true,\"note\":\"存在未纳入片段，无法确认持续采集。\"}},\"tasks\":[{\"id\":\"fixture-1\",\"content\":\"整理本轮体验反馈，确认下次讨论的优先级\",\"owner\":\"小林\",\"due\":\"周五\",\"status\":\"open\"},{\"id\":\"fixture-2\",\"content\":\"核对页面文案\",\"owner\":\"小林\",\"status\":\"done\"}]}"));
                set("tab", 2); invoke("showApp", new Class<?>[]{});
                if (findText(activity.getWindow().getDecorView(), "记录覆盖：已保存 12 条") == null
                        || findText(activity.getWindow().getDecorView(), "仅覆盖部分记录") == null)
                    throw new AssertionError("Meeting coverage missing");
            });
            capture("10-work-content");
            onMain(() -> { ((android.widget.ScrollView) get("scroll")).fullScroll(View.FOCUS_DOWN); });
            capture("11-work-tasks");
            onMain(() -> {
                TextView coverage = findText(activity.getWindow().getDecorView(), "记录覆盖");
                coverage.performClick();
                TextView details = findText(activity.getWindow().getDecorView(), "记录覆盖：已保存");
                if (details == null || !details.isShown()) throw new AssertionError("Coverage disclosure cannot open");
                coverage.performClick();
                set("state", new JSONObject("{\"settings\":{\"mode\":\"commute\",\"transport\":\"transit\",\"destination_lon\":121.4998,\"destination_name\":\"上海东方明珠广播电视塔\",\"transit_city\":\"上海\"},\"agent\":{\"route\":{\"available\":true,\"eta_seconds\":1680,\"distance_meters\":6400,\"destination\":\"上海东方明珠广播电视塔\",\"note\":\"离线视觉样例，非实时路线。\",\"steps\":[{\"instruction\":\"步行至地铁站，乘坐 2 号线。\"},{\"instruction\":\"在陆家嘴站出站，步行前往目的地。\"}]}},\"drafts\":[{\"id\":\"fixture-draft\",\"status\":\"pending\",\"contact\":\"同事\",\"text\":\"我正在路上，预计约半小时到达，我们稍后见。\"}]}"));
                set("tab", 3); invoke("showApp", new Class<?>[]{});
                if (findText(activity.getWindow().getDecorView(), "所在城市").isShown()) throw new AssertionError("Selected destination search should start collapsed");
                findText(activity.getWindow().getDecorView(), "更换目的地").performClick();
                if (!findText(activity.getWindow().getDecorView(), "所在城市").isShown()) throw new AssertionError("Destination search cannot open");
                findText(activity.getWindow().getDecorView(), "更换目的地").performClick();
            });
            capture("12-commute-route");
            onMain(() -> { ((android.widget.ScrollView) get("scroll")).fullScroll(View.FOCUS_DOWN); });
            capture("13-commute-details");
            onMain(() -> {
                findText(activity.getWindow().getDecorView(), "路线详情").performClick();
                TextView step = findText(activity.getWindow().getDecorView(), "01   步行");
                if (step == null || !step.isShown()) throw new AssertionError("Route steps cannot open");
                android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                config.fontScale = 1.3f;
                activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
                set("tab", 3); invoke("showApp", new Class<?>[]{});
            });
            capture("14-commute-large-font");
            onMain(() -> { set("tab", 2); invoke("showApp", new Class<?>[]{}); });
            capture("15-work-large-font");
            onMain(() -> { set("tab", 1); invoke("showApp", new Class<?>[]{}); });
            capture("16-memory-large-font");
            onMain(() -> {
                android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                config.fontScale = 1.6f;
                activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
                activity.getWindow().setLayout(Math.round(320 * activity.getResources().getDisplayMetrics().density), android.view.WindowManager.LayoutParams.MATCH_PARENT);
                set("tab", 3); invoke("showApp", new Class<?>[]{});
            });
            capture("17-commute-compact");
            onMain(() -> { set("tab", 2); invoke("showApp", new Class<?>[]{}); });
            capture("18-work-compact");
            onMain(() -> { set("tab", 1); invoke("showApp", new Class<?>[]{}); });
            capture("19-memory-compact");
            runOnMainSync(() -> activity.finish());
            result.putString("stream", "Visual review passed: auth, five tabs, disclosure, draft retention, content fixtures, citations, forget cache invalidation, meeting coverage, destination and route disclosures, 1.3x font, 320dp window with 1.6x font. Screenshots: " + output);
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable error) {
            if (activity != null) runOnMainSync(() -> activity.finish());
            result.putString("stream", "Visual review FAILED: " + android.util.Log.getStackTraceString(error));
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private interface Checked { void run() throws Exception; }
    private void reviewWorkMeetings() throws Exception {
        JSONObject meeting = new JSONObject("{\"id\":\"offline-meeting-fixture-01\",\"started_at\":\"2026-10-17T11:00:00+00:00\",\"ended_at\":\"2026-10-17T11:30:00+00:00\",\"status\":\"saved\",\"summary_status\":\"pending\",\"summary_stage\":\"analyzing\",\"processed_sections\":1,\"total_sections\":3,\"transcript_count\":2,\"sources\":[{\"id\":\"source-one\",\"segment_index\":0,\"text\":\"今天讨论新版设计稿。我们原计划周三提交，颜色暂定蓝色，需要先确认客户反馈。\"},{\"id\":\"source-two\",\"segment_index\":1,\"text\":\"更正一下，我们决定改为周五提交蓝色设计稿，小林负责整理。是否增加红色版本还没有确定，下次再讨论。\"}]}");
        meeting.put("whole_audio", true).put("transcription_status", "ready");
        meeting.getJSONArray("sources").getJSONObject(0).put("speaker", "发言人 1");
        meeting.getJSONArray("sources").getJSONObject(1).put("speaker", "发言人 2");
        onMain(() -> {
            set("state", new JSONObject().put("settings", new JSONObject().put("mode", "work"))
                .put("work_meetings", new org.json.JSONArray().put(meeting)));
            set("tab", 2); invoke("showApp", new Class<?>[]{});
            if (findText(activity.getWindow().getDecorView(), "原文已保存 · 正在总结 · 阅读整场文稿") == null)
                throw new AssertionError("Meeting progress is missing");
        });
        capture("00-progress-list");
        onMain(() -> { invoke("openWorkMeeting", new Class<?>[]{String.class}, meeting.getString("id"));
            invoke("renderWorkMeetingDetail", new Class<?>[]{JSONObject.class}, meeting); });
        onMain(() -> {
            android.app.AlertDialog dialog = (android.app.AlertDialog) get("sourceDialog");
            View root = dialog.getWindow().getDecorView();
            if (findText(root, "发言人 1") == null || findText(root, "发言人 2") == null)
                throw new AssertionError("Speaker dialogue is missing");
            if (findText(root, "片段 1") != null || findText(root, "片段 2") != null)
                throw new AssertionError("Upload boundaries leaked into dialogue");
        });
        capture("01-original-transcript");
        onMain(() -> {
            android.app.AlertDialog dialog = (android.app.AlertDialog) get("sourceDialog");
            TextView minutes = findText(dialog.getWindow().getDecorView(), "AI 纪要");
            if (minutes == null) throw new AssertionError("Missing original/minutes tabs");
            minutes.performClick();
        });
        capture("02-pending-minutes");
        onMain(() -> {
            JSONObject report = new JSONObject("{\"title\":\"新版设计稿评审\",\"notice\":\"AI 整理稿，请结合原文核对；未进行说话人身份识别。\",\"summary\":\"会议确认采用蓝色方案，提交时间由周三调整为周五，由小林整理交付。是否增加红色版本尚未确定。\",\"topics\":[{\"text\":\"讨论设计稿颜色及交付安排，等待客户反馈。\",\"source_ids\":[\"source-one\"]}],\"decisions\":[{\"text\":\"蓝色设计稿改为周五提交。\",\"source_ids\":[\"source-two\"]}],\"tasks\":[{\"content\":\"整理并交付蓝色设计稿\",\"owner\":\"小林\",\"due\":\"周五\",\"source_ids\":[\"source-two\"]}],\"open_questions\":[{\"text\":\"是否增加红色版本。\",\"source_ids\":[\"source-two\"]}]}");
            meeting.put("summary_status", "ready").put("summary_stage", "done").put("report", report);
            invoke("renderWorkMeetingDetail", new Class<?>[]{JSONObject.class}, meeting);
            android.app.AlertDialog dialog = (android.app.AlertDialog) get("sourceDialog");
            if (findText(dialog.getWindow().getDecorView(), "会议摘要") == null)
                throw new AssertionError("Completed minutes did not replace pending state");
        });
        capture("03-completed-minutes");
        onMain(() -> {
            android.app.AlertDialog dialog = (android.app.AlertDialog) get("sourceDialog");
            TextView evidence = findText(dialog.getWindow().getDecorView(), "查看原文依据");
            if (evidence == null) throw new AssertionError("Missing source disclosure");
            evidence.performClick();
            android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
            config.fontScale = 1.6f;
            activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
            dialog.getWindow().setLayout(Math.round(320 * activity.getResources().getDisplayMetrics().density), android.view.WindowManager.LayoutParams.MATCH_PARENT);
        });
        capture("04-large-font-evidence");
        onMain(() -> {
            meeting.put("summary_status", "fallback");
            meeting.getJSONObject("report").put("notice", "AI 整理未完成，下面仅为原文摘录；完整原文已保存，可以重试。");
            invoke("renderWorkMeetingDetail", new Class<?>[]{JSONObject.class}, meeting);
        });
        capture("05-retry-fallback");
    }
    private void reviewLifeConversation() throws Exception {
        onMain(() -> {
            set("state", new JSONObject("{\"settings\":{\"mode\":\"life\"},\"agent\":{\"mode\":\"life\"},\"transcripts\":[],\"capabilities\":{}}"));
            set("tab", 0); invoke("showApp", new Class<?>[]{});
        });
        capture("00-empty");
        onMain(() -> {
            if (findText(activity.getWindow().getDecorView(), "你的随身 AI 伙伴") != null)
                throw new AssertionError("Legacy hero still occupies the life transcript");
            invoke("newSession", new Class<?>[]{}); // Allocate only; no capture or provider request.
            invoke("showLifeCallSettings", new Class<?>[]{});
        });
        capture("01-settings");
        onMain(() -> {
            TextView hint = (TextView) get("voiceHint");
            invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject().put("text", "你好").put("response", "我在听。"));
            if (!hint.isShown()) throw new AssertionError("Ordinary reply dismissed the call settings dialog");
            // Source dialogs must still be invalidated without closing the settings behind them.
            invoke("showToolResults", new Class<?>[]{});
            android.app.AlertDialog sources = (android.app.AlertDialog) get("sourceDialog");
            invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject().put("text", "继续聊").put("response", "好的。"));
            if (sources.isShowing()) throw new AssertionError("Ordinary reply retained stale answer sources");
            if (!hint.isShown()) throw new AssertionError("Source invalidation dismissed the call settings");
            invoke("cancelSession", new Class<?>[]{String.class}, "");
        });
        capture("01b-settings-after-replies");
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        awaitSettingsDismissed("Back");
        onMain(() -> {
            invoke("showLifeCallSettings", new Class<?>[]{});
            android.app.AlertDialog settings = (android.app.AlertDialog) get("lifeCallSettingsDialog");
            invoke("showLifeCallSettings", new Class<?>[]{});
            if (settings != get("lifeCallSettingsDialog")) throw new AssertionError("Repeated opening stacked settings dialogs");
            settings.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        });
        awaitSettingsDismissed("Done");
        onMain(() -> {
            if (get("lifeCallSettingsDialog") != null) throw new AssertionError("Done retained the settings dialog");
            invoke("showLifeCallSettings", new Class<?>[]{});
            android.app.AlertDialog settings = (android.app.AlertDialog) get("lifeCallSettingsDialog");
            JSONObject fixture = (JSONObject) get("state");
            org.json.JSONArray history = new org.json.JSONArray();
            history.put(new JSONObject().put("speaker", "user").put("text", "今天想慢下来，去附近走走。"));
            history.put(new JSONObject().put("speaker", "assistant").put("text", "那就留一点时间给自己。想去公园散散步，还是找一家安静的咖啡馆？"));
            history.put(new JSONObject().put("speaker", "user").put("text", "去公园吧，想晒晒太阳。"));
            history.put(new JSONObject().put("speaker", "assistant").put("text", "好啊，走一走，也让脑子休息一下。"));
            fixture.put("transcripts", history); invoke("showApp", new Class<?>[]{});
            if (settings.isShowing()) throw new AssertionError("Rebuilding the account scaffold retained settings");
        });
        capture("02-conversation");
        onMain(() -> {
            TextView user = findText((View) get("content"), "今天想慢下来");
            TextView assistant = findText((View) get("content"), "那就留一点时间");
            if (screenX(user) <= screenX(assistant)) throw new AssertionError("User message is not aligned right");
            if (assistant.getBackground() != null) throw new AssertionError("Assistant should be plain text");
            if (((View) get("composer")).getParent() == get("page")) throw new AssertionError("Voice controls scroll with messages");
            ((EditText) get("chatInput")).setText("尚未发送的草稿");
            invoke("switchTab", new Class<?>[]{int.class}, 1);
            invoke("switchTab", new Class<?>[]{int.class}, 0);
            if (!"尚未发送的草稿".contentEquals(((EditText) get("chatInput")).getText())) throw new AssertionError("Tab switch lost draft");
            JSONObject fixture = (JSONObject) get("state");
            fixture.getJSONObject("agent").put("mode", "work"); fixture.getJSONObject("settings").put("mode", "work");
            invoke("renderModeSelection", new Class<?>[]{});
            if ((Boolean) get("lifeConversationLayout")) throw new AssertionError("Work inherited life layout");
            fixture.getJSONObject("agent").put("mode", "commute"); fixture.getJSONObject("settings").put("mode", "commute");
            invoke("renderModeSelection", new Class<?>[]{});
            if ((Boolean) get("lifeConversationLayout")) throw new AssertionError("Commute inherited life layout");
            fixture.getJSONObject("agent").put("mode", "life"); fixture.getJSONObject("settings").put("mode", "life");
            invoke("renderModeSelection", new Class<?>[]{});
            if (!"尚未发送的草稿".contentEquals(((EditText) get("chatInput")).getText())) throw new AssertionError("Mode switch lost draft");
            ((EditText) get("chatInput")).setText("");
            invoke("newSession", new Class<?>[]{}); // Allocate only: no capture, sockets or model call.
            if (!"正在准备…".contentEquals(((View) get("voiceButton")).getContentDescription())) throw new AssertionError("Missing accessible call state");
            ConversationFeed feed = (ConversationFeed) get("conversationFeed");
            feed.transcript("周末"); feed.reply("可以"); invoke("renderLiveTurn", new Class<?>[]{});
            Object userBubble = get("liveUserBubble"), assistantBubble = get("liveAssistantBubble");
            feed.transcript("周末想出去走走。"); feed.reply("可以去公园，或者沿河散步。"); invoke("renderLiveTurn", new Class<?>[]{});
            if (userBubble != get("liveUserBubble") || assistantBubble != get("liveAssistantBubble")) throw new AssertionError("Partial updates recreated bubbles");
            invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject().put("text", feed.user()).put("response", feed.reply()));
            if (get("liveTurn") != null) throw new AssertionError("Final result duplicated streaming turn");
        });
        capture("03-active-call");
        onMain(() -> {
            ((View) get("voiceButton")).performClick();
            if (get("session") != null) throw new AssertionError("End control did not cancel the session");
            JSONObject fixture = (JSONObject) get("state");
            fixture.getJSONObject("settings").put("retain_transcripts", false);
            invoke("acceptBusinessResult", new Class<?>[]{JSONObject.class}, new JSONObject().put("text", "这句不保存").put("response", "仅在本次显示。"));
            invoke("showApp", new Class<?>[]{});
            if (findText((View) get("content"), "这句不保存") == null) throw new AssertionError("Unsaved turn vanished after rebuilding");
            // Long history must scroll independently of the top selector and bottom controls.
            org.json.JSONArray history = fixture.getJSONArray("transcripts");
            for (int i = 0; i < 12; i++) history.put(new JSONObject().put("speaker", i % 2 == 0 ? "user" : "assistant").put("text", "公开测试对话 " + i + "，用于检查长对话滚动及底部操作。"));
            invoke("showApp", new Class<?>[]{});
        });
        capture("04-long-history");
        final int[] positions = new int[2];
        onMain(() -> {
            positions[0] = screenY((View) get("modeChoices")); positions[1] = screenY((View) get("orb"));
            ((android.widget.ScrollView) get("scroll")).scrollTo(0, 0);
        });
        waitForIdleSync();
        onMain(() -> {
            if (positions[0] != screenY((View) get("modeChoices")) || positions[1] != screenY((View) get("orb"))) throw new AssertionError("Scroll moved pinned controls");
            EditText draft = (EditText) get("chatInput"); draft.requestFocus(); draft.setText("键盘打开时也能发送");
            ((android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(draft, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        SystemClock.sleep(700); capture("05-keyboard");
        onMain(() -> {
            View voice = (View) get("voiceButton");
            android.graphics.Rect visible = new android.graphics.Rect(); activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            if (screenY(voice) + voice.getHeight() > visible.bottom) throw new AssertionError("Keyboard covers voice control");
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (!activity.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())) throw new AssertionError("Keyboard fixture did not open the IME");
                if (((View) get("orb")).isShown() || ((View) get("bottomNavigation")).isShown()) throw new AssertionError("Keyboard did not free transcript space");
            }
            invoke("hideKeyboard", new Class<?>[]{});
        });
        onMain(() -> invoke("showLifeCallSettings", new Class<?>[]{}));
        android.app.AlertDialog settings = (android.app.AlertDialog) get("lifeCallSettingsDialog");
        onMain(() -> activity.finish());
        awaitSettingsDismissed("Activity destruction");
        if (settings.isShowing()) throw new AssertionError("Activity destruction retained the settings window");
    }
    private int screenX(View view) { int[] p = new int[2]; view.getLocationOnScreen(p); return p[0]; }
    private int screenY(View view) { int[] p = new int[2]; view.getLocationOnScreen(p); return p[1]; }
    private void awaitSettingsDismissed(String action) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] dismissed = {false};
            onMain(() -> dismissed[0] = get("lifeCallSettingsDialog") == null);
            if (dismissed[0]) return;
            SystemClock.sleep(50); // Back animation and the dismiss listener run asynchronously.
        }
        throw new AssertionError(action + " retained the settings dialog");
    }

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
