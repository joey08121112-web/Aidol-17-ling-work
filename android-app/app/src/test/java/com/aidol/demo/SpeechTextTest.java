package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechTextTest {
    @Test public void longAnswerIsPreservedIncludingEmojiAtBoundary() {
        String answer = "文".repeat(1499) + "🎧" + "完整结尾".repeat(400);
        java.util.List<String> chunks = SpeechText.chunks(answer);
        assertEquals(answer, String.join("", chunks));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(0).endsWith("🎧"));
        for (String chunk : chunks) assertTrue(chunk.codePointCount(0, chunk.length()) <= 1500);
    }
    @Test public void emptyTextNeverStartsSpeech() {
        assertTrue(SpeechText.chunks("").isEmpty());
    }
}
