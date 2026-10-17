package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingTimelineTest {
    private static JSONObject meeting() throws Exception {
        return new JSONObject()
            .put("sources", new JSONArray()
                .put(new JSONObject().put("id", "t1").put("segment_index", 0).put("offset_ms", 0).put("text", "先说预算。再定排期。"))
                .put(new JSONObject().put("id", "old").put("segment_index", 1).put("text", "旧片段没有句子时间")))
            .put("utterances", new JSONArray()
                .put(new JSONObject().put("id", "t1.1").put("transcript_id", "t1").put("start_ms", 1100).put("text", "再定排期。"))
                .put(new JSONObject().put("id", "t1.0").put("transcript_id", "t1").put("start_ms", 200).put("text", "先说预算。")))
            .put("events", new JSONArray()
                .put(new JSONObject().put("id", "p").put("kind", "pause").put("offset_ms", 2000).put("created_at", "2026-10-17T06:00:00+00:00"))
                .put(new JSONObject().put("id", "m").put("kind", "mark").put("offset_ms", 1500))
                .put(new JSONObject().put("id", "x").put("kind", "unknown").put("offset_ms", 1)));
    }

    @Test public void sentencesEventsAndLegacySegmentsShareOneOrderedTimeline() throws Exception {
        MeetingTimeline timeline = new MeetingTimeline(meeting());
        List<String> order = new ArrayList<>();
        for (MeetingTimeline.Entry entry : timeline.entries) order.add(entry.kind + ":" + entry.id);
        // The legacy segment 1 falls back to 20 s; unknown events are ignored.
        assertEquals(List.of("SENTENCE:t1.0", "SENTENCE:t1.1", "MARK:m", "PAUSE:p", "SENTENCE:old"), order);
        assertEquals(20000, timeline.sentence("old").startMs);
    }

    @Test public void citationsResolveSentenceAndLegacyTranscriptIds() throws Exception {
        MeetingTimeline timeline = new MeetingTimeline(meeting());
        assertEquals("t1.1", timeline.sentence("t1.1").id);
        assertEquals("t1.0", timeline.sentence("t1").id); // Old minutes cite whole segments.
        assertNull(timeline.sentence("other-meeting"));
    }

    @Test public void marksPointAtTheSentenceBeingSpoken() throws Exception {
        MeetingTimeline timeline = new MeetingTimeline(meeting());
        assertEquals(1, timeline.marks().size());
        assertEquals("t1.1", timeline.sentenceAt(timeline.marks().get(0).startMs).id);
        assertEquals("t1.0", timeline.sentenceAt(0).id);
        assertNull(new MeetingTimeline(new JSONObject()).sentenceAt(0));
    }

    @Test public void playbackHighlightsOnlyTheSentenceBeingHeard() throws Exception {
        JSONObject meeting = meeting();
        meeting.getJSONArray("utterances").getJSONObject(0).put("end_ms", 1900);
        meeting.getJSONArray("utterances").getJSONObject(1).put("end_ms", 1000);
        MeetingTimeline timeline = new MeetingTimeline(meeting);
        assertNull(timeline.sentencePlaying(100));           // Before anyone spoke.
        assertEquals("t1.0", timeline.sentencePlaying(200).id);
        assertNull(timeline.sentencePlaying(1050));          // Silence between sentences.
        assertEquals("t1.1", timeline.sentencePlaying(1100).id);
        assertNull(timeline.sentencePlaying(5000));
        // A legacy segment without sentence timing covers its whole 20 s segment.
        assertEquals("old", timeline.sentencePlaying(39_999).id);
        assertNull(timeline.sentencePlaying(40_000));
    }

    @Test public void audioEndAndPlayabilityComeFromTheSavedRecording() throws Exception {
        JSONObject meeting = meeting();
        meeting.getJSONArray("sources").getJSONObject(0).put("duration_ms", 20000);
        assertEquals(40_000, new MeetingTimeline(meeting).audioEndMs()); // Legacy segment 1 assumes 20 s.
        assertEquals(0, new MeetingTimeline(new JSONObject()).audioEndMs());
        assertTrue(MeetingTimeline.audioPlayable(new JSONObject().put("audio", new JSONObject().put("status", "partial"))));
        assertTrue(MeetingTimeline.audioPlayable(new JSONObject().put("audio", new JSONObject().put("status", "saved"))));
        assertFalse(MeetingTimeline.audioPlayable(new JSONObject().put("audio", new JSONObject().put("status", "expired"))));
        assertFalse(MeetingTimeline.audioPlayable(new JSONObject()));
    }
}
