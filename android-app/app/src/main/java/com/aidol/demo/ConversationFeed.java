package com.aidol.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory streaming text and display-only turns when transcript saving is disabled. */
final class ConversationFeed {
    static final class Line {
        final boolean assistant;
        final String text, time;
        Line(boolean assistant, String text, String time) { this.assistant = assistant; this.text = text; this.time = time; }
    }
    private String user = "", reply = "";
    private boolean completed;
    private final List<Line> unsaved = new ArrayList<>();
    String user() { return user; }
    String reply() { return reply; }
    List<Line> unsaved() { return Collections.unmodifiableList(unsaved); }
    boolean transcript(String text) {
        if (completed) { if (text.isEmpty()) return false; resetPending(); }
        if (user.equals(text)) return false;
        user = text; return true;
    }
    boolean reply(String text) {
        // Non-streaming playback can repeat the reply after the result was accepted.
        if (completed || reply.equals(text)) return false;
        reply = text; return true;
    }
    List<Line> finish(String user, String reply, String time, boolean retain) {
        List<Line> lines = new ArrayList<>();
        if (!user.isEmpty()) lines.add(new Line(false, user, time));
        if (!reply.isEmpty()) lines.add(new Line(true, reply, time));
        if (!retain) {
            unsaved.addAll(lines);
            while (unsaved.size() > 30) unsaved.remove(0);
        }
        resetPending(); completed = true;
        return lines;
    }
    void resetPending() { user = ""; reply = ""; completed = false; }
    void clear() { resetPending(); unsaved.clear(); }
    static boolean shouldFollow(int scrollY, int contentHeight, int viewportHeight) {
        return viewportHeight > 0 && contentHeight - viewportHeight - scrollY <= viewportHeight;
    }
}
