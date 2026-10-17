package com.aidol.demo;

/** User intent, independent of life/work/commute context. */
final class VoiceInteraction {
    static final int QUESTION = 0, CALL = 1, SCHEDULED = 2, NOTES = 3;
    static boolean directed(int selection) { return selection == QUESTION || selection == CALL; }
    static String source(int selection) {
        return selection == CALL ? "continuous_call" : selection == QUESTION ? "direct_question" : "ambient_capture";
    }
    static boolean repeats(int selection) { return selection != QUESTION; }
    static boolean allowed(int selection, String mode) {
        return selection == NOTES ? "work".equals(mode) : selection != SCHEDULED || "life".equals(mode);
    }
    static boolean endpoint(int selection, boolean configured) { return directed(selection) || selection == SCHEDULED && configured; }
    static long nextDelay(int selection, int pollSeconds) { return selection == SCHEDULED ? pollSeconds * 1000L : 180; }
}
