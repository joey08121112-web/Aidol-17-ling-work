package com.aidol.demo;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskRemindersTest {
    private JSONObject task(String status, long due, int hours) throws Exception {
        return new JSONObject().put("status", status).put("due_at", java.time.Instant.ofEpochMilli(due).toString()).put("reminder_hours", hours);
    }
    @Test public void oneSixAndTwentyFourHoursUseTheDeadlineRatherThanCreationTime() throws Exception {
        long now = 1_000_000, due = now + 48 * 3600000L;
        for (int hours : new int[]{1,6,24}) assertEquals(due - hours * 3600000L, TaskReminders.reminderAt(task("open", due, hours), now));
    }
    @Test public void completedExpiredAndUnsupportedTasksHaveNoAlarm() throws Exception {
        assertEquals(-1, TaskReminders.reminderAt(task("done", 10_000, 1), 1000));
        assertEquals(-1, TaskReminders.reminderAt(task("open", 1000, 1), 1000));
        assertEquals(-1, TaskReminders.reminderAt(task("open", 10000, 2), 1000));
        assertEquals(-1, TaskReminders.reminderAt(new JSONObject(), 1000));
    }
    @Test public void pastReminderForFutureDeadlineIsDueSoonInsteadOfSilentlyLost() throws Exception {
        assertEquals(2000, TaskReminders.reminderAt(task("open", 10000, 1), 1000));
    }
    @Test public void changingDeadlineOrLeadRearmsButTextOnlyEditsKeepDeliveredState() throws Exception {
        JSONObject old = task("open", 200_000_000, 1).put("content", "before");
        assertTrue(TaskReminders.sameReminder(old, new JSONObject(old.toString()).put("content", "after").put("owner", "New")));
        assertFalse(TaskReminders.sameReminder(old, task("open", 200_000_000, 6)));
        assertFalse(TaskReminders.sameReminder(old, task("open", 300_000_000, 1)));
        assertFalse(TaskReminders.sameReminder(old, task("open", 200_000_000, 0)));
        assertFalse(TaskReminders.sameReminder(null, old));
    }
}
