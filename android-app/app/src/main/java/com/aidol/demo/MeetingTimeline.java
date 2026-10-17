package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Orders one work meeting's transcript sentences, pauses and marks on its audio timeline,
 * and resolves minutes citations back to the sentence they point at.
 */
final class MeetingTimeline {
    static final class Entry {
        enum Kind { SENTENCE, PAUSE, RESUME, MARK }
        final Kind kind;
        final String id;       // Sentence id (or legacy transcript id); event id otherwise.
        final long startMs;
        final long endMs;      // Sentence end on the timeline; 0 when unknown.
        final String text;
        final String wallTime; // Server time of pause/resume/mark events; clock time of a sentence's start.
        final String speaker;  // "说话人1" etc. from speaker clustering; "" when not separated.
        Entry(Kind kind, String id, long startMs, long endMs, String text, String wallTime) {
            this(kind, id, startMs, endMs, text, wallTime, "");
        }
        Entry(Kind kind, String id, long startMs, long endMs, String text, String wallTime, String speaker) {
            this.kind = kind; this.id = id; this.startMs = startMs; this.endMs = endMs; this.text = text;
            this.wallTime = wallTime; this.speaker = speaker;
        }
    }

    /** Meeting segments are 20 s; older sources carry no duration of their own. */
    static final long SEGMENT_MS = 20000L;

    final List<Entry> entries = new ArrayList<>();
    private final List<Entry> sentences = new ArrayList<>();
    private final java.util.HashMap<String, String> transcriptOfSentence = new java.util.HashMap<>();
    private long audioEndMs;

    MeetingTimeline(JSONObject meeting) {
        JSONArray utterances = meeting.optJSONArray("utterances");
        JSONArray sources = meeting.optJSONArray("sources");
        java.util.HashSet<String> timed = new java.util.HashSet<>();
        if (utterances != null) for (int i = 0; i < utterances.length(); i++) {
            JSONObject item = utterances.optJSONObject(i); if (item == null) continue;
            Entry entry = new Entry(Entry.Kind.SENTENCE, item.optString("id"), item.optLong("start_ms"), item.optLong("end_ms"),
                item.optString("text"), item.optString("wall_at"), item.optString("speaker"));
            sentences.add(entry);
            transcriptOfSentence.put(entry.id, item.optString("transcript_id"));
            timed.add(item.optString("transcript_id"));
        }
        // Segments saved before sentence timing existed still appear, at their segment offset.
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject item = sources.optJSONObject(i); if (item == null) continue;
            long offset = item.has("offset_ms") ? item.optLong("offset_ms") : item.optLong("segment_index") * SEGMENT_MS;
            long end = offset + (item.has("duration_ms") ? item.optLong("duration_ms") : SEGMENT_MS);
            audioEndMs = Math.max(audioEndMs, end);
            if (timed.contains(item.optString("id"))) continue;
            Entry entry = new Entry(Entry.Kind.SENTENCE, item.optString("id"), offset, end, item.optString("text"), "");
            sentences.add(entry);
            transcriptOfSentence.put(entry.id, entry.id);
        }
        java.util.Collections.sort(sentences, (a, b) -> Long.compare(a.startMs, b.startMs));
        entries.addAll(sentences);
        JSONArray events = meeting.optJSONArray("events");
        if (events != null) for (int i = 0; i < events.length(); i++) {
            JSONObject item = events.optJSONObject(i); if (item == null) continue;
            String kind = item.optString("kind");
            Entry.Kind type = "pause".equals(kind) ? Entry.Kind.PAUSE : "resume".equals(kind) ? Entry.Kind.RESUME
                : "mark".equals(kind) ? Entry.Kind.MARK : null;
            if (type != null) entries.add(new Entry(type, item.optString("id"), item.optLong("offset_ms"), 0, "", item.optString("created_at")));
        }
        // Stable: at equal times, events stay after the sentence that ends there.
        java.util.Collections.sort(entries, (a, b) -> Long.compare(a.startMs, b.startMs));
    }

    List<Entry> sentences() { return sentences; }

    /** Audio exists to be played only while the server still keeps it. */
    static boolean audioPlayable(JSONObject meeting) {
        JSONObject audio = meeting.optJSONObject("audio");
        String status = audio == null ? "" : audio.optString("status");
        return "saved".equals(status) || "partial".equals(status);
    }

    /** End of the last saved segment on the timeline; playback never asks past it. 0 when unknown. */
    long audioEndMs() {
        long end = audioEndMs;
        for (Entry entry : sentences) end = Math.max(end, entry.endMs);
        return end;
    }

    List<Entry> marks() {
        List<Entry> marks = new ArrayList<>();
        for (Entry entry : entries) if (entry.kind == Entry.Kind.MARK) marks.add(entry);
        return marks;
    }

    /** A sentence id, or a legacy transcript id (its first sentence), to the sentence shown on screen. */
    Entry sentence(String citation) {
        for (Entry entry : sentences) if (entry.id.equals(citation)) return entry;
        // Sentences are time-ordered, so this is the segment's earliest sentence.
        for (Entry entry : sentences) if (citation.equals(transcriptOfSentence.get(entry.id))) return entry;
        return null;
    }

    /** The sentence being spoken at a mark: the last one starting at or before it. */
    Entry sentenceAt(long offsetMs) {
        Entry found = sentences.isEmpty() ? null : sentences.get(0);
        for (Entry entry : sentences) if (entry.startMs <= offsetMs) found = entry;
        return found;
    }

    /** The sentence under the playback position; none before the first or after a known end. */
    Entry sentencePlaying(long positionMs) {
        Entry found = null;
        for (Entry entry : sentences) if (entry.startMs <= positionMs) found = entry;
        // Silence after a sentence's known end highlights nothing.
        return found == null || found.endMs > found.startMs && positionMs >= found.endMs ? null : found;
    }

    /** Distinct speaker labels in order of first appearance. */
    List<String> speakers() {
        List<String> labels = new ArrayList<>();
        for (Entry entry : sentences) if (!entry.speaker.isEmpty() && !labels.contains(entry.speaker)) labels.add(entry.speaker);
        return labels;
    }
}
