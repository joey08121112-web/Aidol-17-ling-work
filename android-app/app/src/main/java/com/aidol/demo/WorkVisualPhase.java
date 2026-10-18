package com.aidol.demo;

/** Saved audio/text and AI minutes have independent completion states. */
final class WorkVisualPhase {
    static CompanionPhase afterRecording(String capture, String transcription, String summary) {
        if ("recording".equals(capture)) return null;
        if ("partial".equals(capture) || "empty".equals(capture)) return CompanionPhase.ERROR;
        if (!"saved".equals(capture)) return null;
        if ("failed".equals(summary) || "fallback".equals(summary) || "unavailable".equals(summary))
            return CompanionPhase.ERROR;
        if ("pending".equals(transcription) || "pending".equals(summary)) return CompanionPhase.SUMMARIZING;
        return CompanionPhase.COMPLETE;
    }
    private WorkVisualPhase() {}
}
