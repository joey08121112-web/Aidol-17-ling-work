package com.aidol.demo;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeakerParagraphsTest {
    private static SpeakerParagraphs.Sentence s(String speaker, long at, String text) {
        return new SpeakerParagraphs.Sentence(speaker + at, speaker, at, text);
    }

    @Test public void consecutiveSentencesOfOneSpeakerShareAParagraphHeadedLikeTheReference() {
        List<SpeakerParagraphs.Paragraph> paragraphs = SpeakerParagraphs.build(List.of(
            s("说话人1", 0, "大家好。"), s("说话人1", 2000, "今天讨论发布。"), s("说话人2", 41_000, "我建议推迟。"),
            s("说话人2", 43_000, ""), s("说话人1", 3_725_000, "好的。")));
        assertEquals(3, paragraphs.size());
        assertEquals("说话人 1  00:00:00", paragraphs.get(0).header());
        assertEquals(2, paragraphs.get(0).sentences.size());
        assertEquals("说话人 2  00:00:41", paragraphs.get(1).header());
        assertEquals("说话人 1  01:02:05", paragraphs.get(2).header());
    }

    @Test public void longMonologuesBreakAndUnlabelledSpeechIsNamed() {
        String chunk = "很长的一段话".repeat(30);  // 180 chars
        List<SpeakerParagraphs.Paragraph> paragraphs = SpeakerParagraphs.build(List.of(s("", 0, chunk), s("", 9000, chunk)));
        assertEquals(2, paragraphs.size());
        assertEquals("发言  00:00:09", paragraphs.get(1).header());
        assertEquals("说话人 12", SpeakerParagraphs.name("说话人12"));
        assertEquals("张三", SpeakerParagraphs.name("张三"));
    }
}
