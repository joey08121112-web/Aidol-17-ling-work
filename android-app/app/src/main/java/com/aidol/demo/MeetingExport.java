package com.aidol.demo;

import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** One meeting as a Markdown document: details, AI minutes and the timed, speaker-labelled transcript. */
final class MeetingExport {
    private MeetingExport() {}

    static String fileName(JSONObject meeting, ZoneId zone) {
        String day = TimeFormat.dayOf(meeting.optString("started_at"), zone);
        String clock = TimeFormat.clockOf(meeting.optString("started_at"), zone).replace(":", "");
        return "会议纪要-" + (day.isEmpty() ? "未知日期" : day.substring(0, 10)) + (clock.isEmpty() ? "" : "-" + clock) + ".md";
    }

    static String markdown(JSONObject meeting, ZoneId zone) {
        MeetingTimeline timeline = new MeetingTimeline(meeting);
        JSONObject report = meeting.optJSONObject("report");
        StringBuilder out = new StringBuilder();
        String title = report == null ? "" : report.optString("title");
        out.append("# ").append(title.isEmpty() ? "会议记录" : title).append("\n\n");
        String started = meeting.optString("audio_started_at", meeting.optString("started_at"));
        out.append("- 日期：").append(fallback(TimeFormat.dayOf(started, zone), "未知")).append('\n');
        String end = TimeFormat.clockOf(meeting.optString("ended_at"), zone);
        out.append("- 时间：").append(fallback(TimeFormat.clockOf(started, zone), "未知"))
            .append(end.isEmpty() ? "" : " – " + end)
            .append("（录音时长 ").append(TimeFormat.clock(timeline.audioEndMs())).append("）\n");
        java.util.List<String> speakers = new java.util.ArrayList<>();
        for (String label : timeline.speakers()) speakers.add(SpeakerParagraphs.name(label));
        out.append("- 说话人：").append(speakers.isEmpty() ? "未区分" : String.join("、", speakers) + "（按声音自动区分，不代表真实身份）").append('\n');
        out.append("- ").append(MeetingPlace.label(meeting, zone)).append('\n');
        out.append("- 原文句数：").append(timeline.sentences().size()).append("\n\n");
        if (report != null) {
            out.append("## 摘要\n\n").append(report.optString("summary")).append("\n\n");
            section(out, "智能章节", array(report, "chapters"), item -> {
                MeetingTimeline.Entry at = item.has("start_ms") && !item.isNull("start_ms") ? timeline.sentenceAt(item.optLong("start_ms")) : null;
                return "- " + (at == null ? "" : "[" + clock(at, zone) + "] ") + item.optString("title")
                    + (item.optString("summary").isEmpty() ? "" : "：" + item.optString("summary")) + (userEdited(item) ? "（用户修正）" : "");
            });
            section(out, "关键决策", array(report, "decisions"), item -> "- " + item.optString("text") + evidence(item, timeline, zone));
            section(out, "各人物观点", array(report, "viewpoints"), item -> "- " + fallback(item.optString("person"), "身份未明确的发言")
                + "：" + item.optString("text") + evidence(item, timeline, zone));
            section(out, "内容冲突与澄清", array(report, "conflicts"), item -> "- " + item.optString("text") + evidence(item, timeline, zone));
            section(out, "重要事件", array(report, "events"), item -> "- " + item.optString("content")
                + "（时间：" + fallback(item.optString("time"), "未明确") + "；地点：" + fallback(item.optString("location"), "未明确")
                + "；相关任务：" + fallback(item.optString("task"), "未明确") + "）" + evidence(item, timeline, zone));
            section(out, "议题与重要信息", array(report, "topics"), item -> "- " + item.optString("text") + evidence(item, timeline, zone));
            section(out, "行动待办", array(report, "tasks"), item -> "- [ ] " + item.optString("content")
                + "（负责人：" + fallback(item.optString("owner"), "未明确") + "；截止：" + fallback(item.optString("due"), "未提及") + "）"
                + evidence(item, timeline, zone));
            section(out, "待确认事项", array(report, "open_questions"), item -> "- " + item.optString("text") + evidence(item, timeline, zone));
            section(out, "重点原话", array(report, "highlights"), item -> "- “" + item.optString("text") + "”" + evidence(item, timeline, zone));
        }
        if (!timeline.marks().isEmpty()) {
            out.append("## 我的标记\n\n");
            for (MeetingTimeline.Entry mark : timeline.marks()) {
                MeetingTimeline.Entry at = timeline.sentenceAt(mark.startMs);
                out.append("- ★ ").append(at == null ? TimeFormat.clock(mark.startMs) : clock(at, zone)).append(at == null ? "" : " " + line(at)).append('\n');
            }
            out.append('\n');
        }
        out.append("## 原文\n\n");
        for (MeetingTimeline.Entry entry : timeline.sentences())
            out.append("[").append(clock(entry, zone)).append("] ").append(line(entry)).append("  \n");
        out.append("\n> AI 整理稿请结合原文核对；时间为录音时的本地时间。\n");
        return out.toString();
    }

    private interface Item { String format(JSONObject item); }
    private static void section(StringBuilder out, String title, JSONArray items, Item format) {
        if (items.length() == 0) return;
        out.append("## ").append(title).append("\n\n");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) out.append(format.format(item)).append('\n');
        }
        out.append('\n');
    }
    private static boolean userEdited(JSONObject item) {
        JSONArray ids = array(item, "source_ids");
        for (int i = 0; i < ids.length(); i++) if (ids.optString(i).startsWith("user-edit:")) return true;
        return false;
    }
    private static String evidence(JSONObject item, MeetingTimeline timeline, ZoneId zone) {
        if (userEdited(item)) return "（用户修正）";
        JSONArray ids = array(item, "source_ids");
        for (int i = 0; i < ids.length(); i++) {
            MeetingTimeline.Entry cited = timeline.sentence(ids.optString(i));
            if (cited != null) return "（依据 " + clock(cited, zone) + "）";
        }
        return "";
    }
    /** Clock time when the server knows it, otherwise the position in the recording. */
    private static String clock(MeetingTimeline.Entry entry, ZoneId zone) {
        String wall = TimeFormat.clockOf(entry.wallTime, zone);
        return wall.isEmpty() ? TimeFormat.clock(entry.startMs) : wall;
    }
    private static String line(MeetingTimeline.Entry entry) {
        return (entry.speaker.isEmpty() ? "" : SpeakerParagraphs.name(entry.speaker) + "：") + entry.text;
    }
    private static JSONArray array(JSONObject value, String key) {
        JSONArray items = value.optJSONArray(key); return items == null ? new JSONArray() : items;
    }
    private static String fallback(String value, String other) { return value == null || value.isEmpty() ? other : value; }
}
