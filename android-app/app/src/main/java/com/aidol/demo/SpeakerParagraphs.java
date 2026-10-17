package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * Feishu-style transcript layout: consecutive sentences of one speaker form a paragraph headed
 * "说话人 1  00:00:41". A paragraph also breaks when it grows long, so headers keep recurring.
 */
final class SpeakerParagraphs {
    static final int MAX_CHARS = 320;

    static final class Sentence {
        final String id, speaker, text;
        final long startMs;
        Sentence(String id, String speaker, long startMs, String text) {
            this.id = id == null ? "" : id; this.speaker = speaker == null ? "" : speaker;
            this.startMs = startMs; this.text = text == null ? "" : text;
        }
    }

    static final class Paragraph {
        final String speaker;
        final long startMs;
        final List<Sentence> sentences = new ArrayList<>();
        Paragraph(String speaker, long startMs) { this.speaker = speaker; this.startMs = startMs; }
        int length() { int n = 0; for (Sentence s : sentences) n += s.text.length(); return n; }
        String header() { return name(speaker) + "  " + TimeFormat.hms(startMs); }
    }

    private SpeakerParagraphs() {}

    static List<Paragraph> build(List<Sentence> sentences) {
        List<Paragraph> paragraphs = new ArrayList<>();
        Paragraph current = null;
        for (Sentence sentence : sentences) {
            if (sentence.text.isEmpty()) continue;
            if (current == null || !current.speaker.equals(sentence.speaker) || current.length() + sentence.text.length() > MAX_CHARS) {
                current = new Paragraph(sentence.speaker, sentence.startMs);
                paragraphs.add(current);
            }
            current.sentences.add(sentence);
        }
        return paragraphs;
    }

    /** "说话人1" -> "说话人 1" as in the reference layout; unlabelled speech is "发言". */
    static String name(String label) {
        if (label == null || label.isEmpty()) return "发言";
        return label.startsWith("说话人") && label.length() > 3 ? "说话人 " + label.substring(3) : label;
    }
}
