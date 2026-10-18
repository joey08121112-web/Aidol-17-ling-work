package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import org.json.*;
import java.time.Instant;

/** Local account-scoped reminders. No microphone, foreground service or provider requests. */
public final class TaskReminders extends android.content.BroadcastReceiver {
    private static final String PREF = "task_reminders", CHANNEL = "action_tasks";
    static long reminderAt(JSONObject task, long now) {
        try {
            long due = Instant.parse(task.getString("due_at")).toEpochMilli();
            int hours = task.optInt("reminder_hours");
            if (!"open".equals(task.optString("status")) || due <= now || (hours != 1 && hours != 6 && hours != 24)) return -1;
            return Math.max(now + 1000, due - hours * 3600000L);
        } catch (Exception ignored) { return -1; }
    }
    static boolean sameReminder(JSONObject previous, JSONObject current) {
        return previous != null && previous.optString("due_at").equals(current.optString("due_at"))
            && previous.optInt("reminder_hours") == current.optInt("reminder_hours");
    }
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    private static JSONObject saved(Context c) {
        try { return new JSONObject(prefs(c).getString("tasks", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }
    private static PendingIntent alarm(Context c, String id) {
        Intent intent = new Intent(c, TaskReminders.class).setAction("com.aidol.TASK_REMINDER").setData(Uri.parse("aidol-task:" + id));
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void schedule(Context c, JSONObject task) {
        String id = task.optString("id");
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent pending = alarm(c, id);
        alarms.cancel(pending);
        long at = reminderAt(task, System.currentTimeMillis());
        if (at < 0) { c.getSystemService(NotificationManager.class).cancel(id, 1); return; }
        if (task.optBoolean("notified")) return;
        // Inexact alarms work without an additional special-access permission.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    }
    public static void sync(Context c, String account, JSONArray tasks, boolean complete) {
        if (!account.equals(prefs(c).getString("account", ""))) clear(c);
        JSONObject old = saved(c), next = complete ? new JSONObject() : old;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String id = task.optString("id");
            try {
                JSONObject copy = new JSONObject(task.toString()), previous = old.optJSONObject(id);
                if (sameReminder(previous, copy)) copy.put("notified", previous.optBoolean("notified"));
                if (previous != null && (!sameReminder(previous, copy) || !previous.optString("content").equals(copy.optString("content"))
                        || !previous.optString("owner").equals(copy.optString("owner")))) c.getSystemService(NotificationManager.class).cancel(id, 1);
                if (copy.optInt("reminder_hours") > 0) next.put(id, copy);
                else next.remove(id);
                schedule(c, copy);
            } catch (JSONException ignored) {}
        }
        if (complete) for (java.util.Iterator<String> it = old.keys(); it.hasNext();) {
            String id = it.next(); if (!next.has(id)) c.getSystemService(AlarmManager.class).cancel(alarm(c, id));
        }
        prefs(c).edit().putString("account", account).putString("tasks", next.toString()).apply();
    }
    public static void cancel(Context c, String id) {
        c.getSystemService(AlarmManager.class).cancel(alarm(c, id));
        c.getSystemService(NotificationManager.class).cancel(id, 1);
        JSONObject tasks = saved(c); tasks.remove(id); prefs(c).edit().putString("tasks", tasks.toString()).apply();
    }
    public static void clear(Context c) {
        JSONObject tasks = saved(c);
        for (java.util.Iterator<String> it = tasks.keys(); it.hasNext();) {
            String id = it.next(); c.getSystemService(AlarmManager.class).cancel(alarm(c, id));
            c.getSystemService(NotificationManager.class).cancel(id, 1);
        }
        prefs(c).edit().clear().apply();
    }
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject tasks = saved(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction()) || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
            for (java.util.Iterator<String> it = tasks.keys(); it.hasNext();) schedule(context, tasks.optJSONObject(it.next()));
            return;
        }
        String id = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
        JSONObject task = tasks.optJSONObject(id);
        if (task == null || task.optBoolean("notified") || reminderAt(task, System.currentTimeMillis()) < 0) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "行动清单提醒", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, MainActivity.class).putExtra("open_work_tasks", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 801, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String message = task.optString("content");
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("行动清单提醒").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message
                + "\n负责人：" + task.optString("owner", "待确认") + "\n截止：" + TimeFormat.format(task.optString("due_at"))))
            .setContentIntent(content).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        manager.notify(id, 1, notification);
        try { task.put("notified", true); } catch (JSONException ignored) {}
        prefs(context).edit().putString("tasks", tasks.toString()).apply();
    }
}
