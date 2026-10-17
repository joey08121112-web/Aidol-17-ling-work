package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;

/** Preserve the entire displayed answer within the speech API's per-request limit. */
final class SpeechText {
    static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = text.offsetByCodePoints(start, Math.min(1500, text.codePointCount(start, text.length())));
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
    private SpeechText() {}
}
