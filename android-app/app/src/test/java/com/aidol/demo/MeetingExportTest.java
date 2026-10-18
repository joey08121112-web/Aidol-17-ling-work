package com.aidol.demo;

import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingExportTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static JSONObject meeting() throws Exception {
        JSONArray utterances = new JSONArray()
            .put(new JSONObject().put("id", "t1.0").put("transcript_id", "t1").put("start_ms", 0).put("end_ms", 900)
                .put("text", "大家好。").put("speaker", "说话人1").put("wall_at", "2026-10-17T06:03:05+00:00"))
            .put(new JSONObject().put("id", "t2.0").put("transcript_id", "t2").put("start_ms", 4000).put("end_ms", 5000)
                .put("text", "我建议推迟两天。").put("speaker", "说话人2").put("wall_at", "2026-10-17T06:03:09+00:00"));
        JSONObject report = new JSONObject().put("title", "发布计划").put("summary", "决定推迟两天。")
            .put("chapters", new JSONArray().put(new JSONObject().put("title", "排期").put("summary", "讨论推迟").put("start_ms", 4000)))
            .put("decisions", new JSONArray().put(new JSONObject().put("text", "推迟两天").put("source_ids", new JSONArray().put("t2.0"))))
            .put("tasks", new JSONArray().put(new JSONObject().put("content", "通知测试").put("owner", "").put("due", "")
                .put("source_ids", new JSONArray().put("t2.0"))));
        return new JSONObject().put("started_at", "2026-10-17T06:03:00+00:00").put("audio_started_at", "2026-10-17T06:03:05+00:00")
            .put("ended_at", "2026-10-17T06:10:00+00:00").put("utterances", utterances).put("sources", new JSONArray())
            .put("events", new JSONArray().put(new JSONObject().put("id", "m").put("kind", "mark").put("offset_ms", 4500)))
            .put("report", report);
    }

    @Test public void documentCarriesDateTimesSpeakersMinutesAndTranscript() throws Exception {
        String text = MeetingExport.markdown(meeting(), SHANGHAI);
        assertTrue(text.startsWith("# 发布计划\n"));
        assertTrue(text.contains("- 日期：2026-10-17 星期六"));
        assertTrue(text.contains("- 时间：14:03:05 – 14:10:00"));
        assertTrue(text.contains("- 说话人：说话人 1、说话人 2（按声音自动区分，不代表真实身份）"));
        assertTrue(text.contains("## 摘要\n\n决定推迟两天。"));
        assertTrue(text.contains("- [14:03:09] 排期：讨论推迟"));
        assertTrue(text.contains("- 推迟两天（依据 14:03:09）"));
        assertTrue(text.contains("- [ ] 通知测试（负责人：未明确；截止：未提及）"));
        assertTrue(text.contains("- ★ 14:03:09 说话人 2：我建议推迟两天。"));
        assertTrue(text.contains("[14:03:05] 说话人 1：大家好。"));
        assertEquals("会议纪要-2026-10-17-140300.md", MeetingExport.fileName(meeting(), SHANGHAI));
    }

    @Test public void userAddedMinutesAreLabeledWithoutInventingRecordingTimes() throws Exception {
        JSONObject value = meeting(), report = value.getJSONObject("report");
        JSONArray ids = new JSONArray().put("user-edit:revision:new-item");
        report.put("chapters", new JSONArray().put(new JSONObject().put("title", "新增章节").put("summary", "会后补充")
            .put("start_ms", JSONObject.NULL).put("source_ids", ids)));
        report.getJSONArray("tasks").getJSONObject(0).put("source_ids", ids);
        String text = MeetingExport.markdown(value, SHANGHAI);
        assertTrue(text.contains("- 新增章节：会后补充（用户修正）"));
        assertTrue(text.contains("通知测试（负责人：未明确；截止：未提及）（用户修正）"));
        assertFalse(text.contains("[14:03:05] 新增章节"));
    }

    @Test public void speakerNamesReplaceLabelsInTranscriptAndMinutes() throws Exception {
        JSONObject value = meeting(), report = value.getJSONObject("report");
        value.put("speaker_names", new JSONObject().put("说话人2", "李四"));
        report.put("summary", "说话人 2建议推迟两天，说话人1同意。");
        report.getJSONArray("tasks").getJSONObject(0).put("owner", "说话人2");
        String text = MeetingExport.markdown(value, SHANGHAI);
        assertTrue(text.contains("- 说话人：说话人 1、李四（按声音自动区分，名称由你指定）"));
        assertTrue(text.contains("## 摘要\n\n李四建议推迟两天，说话人1同意。"));
        assertTrue(text.contains("- [ ] 通知测试（负责人：李四；截止：未提及）（依据 14:03:09）"));
        assertTrue(text.contains("[14:03:09] 李四：我建议推迟两天。"));
        assertTrue(text.contains("[14:03:05] 说话人 1：大家好。"));
        // The saved minutes keep the label; only the exported copy is named.
        assertEquals("说话人2", report.getJSONArray("tasks").getJSONObject(0).getString("owner"));
    }

    @Test public void meetingWithoutMinutesOrSpeakersStillExports() throws Exception {
        JSONObject plain = meeting(); plain.remove("report");
        plain.getJSONArray("utterances").getJSONObject(0).put("speaker", "").put("wall_at", "");
        String text = MeetingExport.markdown(plain, SHANGHAI);
        assertTrue(text.startsWith("# 会议记录\n"));
        assertTrue(text.contains("[0:00] 大家好。"));
        assertFalse(text.contains("## 摘要"));
    }

    @Test public void exportIncludesWholeMeetingAnalysisAlongsideTimedTranscript() throws Exception {
        JSONObject value = meeting(), report = value.getJSONObject("report");
        JSONArray ids = new JSONArray().put("t2.0");
        report.put("viewpoints", new JSONArray().put(new JSONObject().put("person", "说话人2").put("text", "建议推迟两天").put("source_ids", ids)))
            .put("conflicts", new JSONArray().put(new JSONObject().put("text", "排期尚有分歧").put("source_ids", ids)))
            .put("events", new JSONArray().put(new JSONObject().put("content", "安排评审").put("time", "周五").put("location", "北京").put("task", "").put("source_ids", ids)));
        String text = MeetingExport.markdown(value, SHANGHAI);
        assertTrue(text.contains("说话人2：建议推迟两天（依据 14:03:09）"));
        assertTrue(text.contains("## 内容冲突与澄清"));
        assertTrue(text.contains("时间：周五；地点：北京；相关任务：未明确"));
        assertTrue(text.contains("## 智能章节"));
        assertTrue(text.contains("[14:03:09] 说话人 2：我建议推迟两天。"));
    }
}
