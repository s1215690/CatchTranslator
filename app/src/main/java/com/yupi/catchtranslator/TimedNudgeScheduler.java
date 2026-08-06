package com.yupi.catchtranslator;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 自然語言延遲解析、持久化、AlarmManager 排程和到點通知。 */
public final class TimedNudgeScheduler {

    public static final String ACTION_TRIGGER = "com.yupi.catchtranslator.TIMED_NUDGE";
    public static final String EXTRA_ID = "timed_nudge_id";
    public static final String EXTRA_TASK = "timed_nudge_task";
    public static final String EXTRA_TRIGGER_AT = "timed_nudge_at";
    public static final String CHANNEL_ID = "timed_nudge";

    private static final String PREFS = "settings";
    private static final String KEY_ITEMS = "timed_nudges";
    private static final long MIN_DELAY_MS = 5_000L;
    private static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;

    private static final Pattern DELAY_PATTERN = Pattern.compile(
            "(\\d{1,4}|半)\\s*(?:個|个)?\\s*"
                    + "(秒鐘|秒钟|秒|分鐘|分钟|小時|小时|鐘頭|钟头|個鐘|个钟|日|天)"
                    + "\\s*(?:之)?[後后]",
            Pattern.CASE_INSENSITIVE);

    private TimedNudgeScheduler() {}

    public static final class ParseResult {
        public final String task;
        public final long delayMs;

        ParseResult(String task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    public static final class ScheduleResult {
        public final String id;
        public final long triggerAt;
        public final boolean exact;

        ScheduleResult(String id, long triggerAt, boolean exact) {
            this.id = id;
            this.triggerAt = triggerAt;
            this.exact = exact;
        }
    }

    /** 支援「30分鐘後提醒我食飯」「半個鐘後叫我落街」「10秒後測試」。 */
    public static ParseResult parse(String raw) {
        if (raw == null) return null;
        Matcher matcher = DELAY_PATTERN.matcher(raw.trim());
        if (!matcher.find()) return null;

        String number = matcher.group(1);
        String unit = matcher.group(2);
        double amount = "半".equals(number) ? 0.5 : Double.parseDouble(number);
        long unitMs;
        if (unit.startsWith("秒")) {
            unitMs = 1000L;
        } else if (unit.startsWith("分")) {
            unitMs = 60_000L;
        } else if (unit.equals("日") || unit.equals("天")) {
            unitMs = 24L * 60 * 60 * 1000;
        } else {
            unitMs = 60L * 60 * 1000;
        }
        long delay = Math.round(amount * unitMs);
        if (delay < MIN_DELAY_MS || delay > MAX_DELAY_MS) return null;

        String task = raw.substring(matcher.end()).trim();
        task = task.replaceFirst("^[，,。\\.、\\s呃嗯啊哦]*(?:到時|到时)?(?:記得|记得)?"
                + "(?:提醒我|提我|叫我|通知我|幫我|帮我)?[，,。\\.、\\s呃嗯啊哦]*", "").trim();
        if (task.isEmpty()) return null;
        return new ParseResult(task, delay);
    }

    public static ScheduleResult schedule(Context context, String task, long delayMs) throws Exception {
        String cleanTask = task == null ? "" : task.trim();
        if (cleanTask.isEmpty()) throw new Exception("定時任務內容係空");
        long safeDelay = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
        long triggerAt = System.currentTimeMillis() + safeDelay;
        String id = Long.toString(System.currentTimeMillis()) + "-"
                + Integer.toHexString((cleanTask + triggerAt).hashCode());

        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("task", cleanTask);
        item.put("trigger_at", triggerAt);
        item.put("created_at", System.currentTimeMillis());
        saveItem(context, item);
        boolean exact;
        try {
            exact = scheduleAlarm(context, id, cleanTask, triggerAt);
        } catch (Exception e) {
            remove(context, id);
            throw e;
        }
        return new ScheduleResult(id, triggerAt, exact);
    }

    private static boolean scheduleAlarm(Context context, String id, String task,
                                         long triggerAt) throws Exception {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) throw new Exception("手機冇提供鬧鐘服務");
        PendingIntent pi = alarmIntent(context, id, task, triggerAt);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                return true;
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            return false;
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            return false;
        }
    }

    private static PendingIntent alarmIntent(Context context, String id, String task,
                                             long triggerAt) {
        Intent intent = new Intent(context, TimedNudgeReceiver.class)
                .setAction(ACTION_TRIGGER)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static synchronized void saveItem(Context context, JSONObject item) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray items;
        try {
            items = new JSONArray(p.getString(KEY_ITEMS, "[]"));
        } catch (Exception e) {
            items = new JSONArray();
        }
        items.put(item);
        p.edit().putString(KEY_ITEMS, items.toString()).apply();
    }

    public static synchronized void remove(Context context, String id) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray out = new JSONArray();
        try {
            JSONArray items = new JSONArray(p.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && !id.equals(item.optString("id"))) out.put(item);
            }
        } catch (Exception ignored) {}
        p.edit().putString(KEY_ITEMS, out.toString()).apply();
    }

    /** 開機或重新開 App 後恢復未到期提醒；過期少於一天就盡快補發。 */
    public static synchronized void rescheduleAll(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray kept = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONArray items = new JSONArray(p.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String task = item.optString("task", "");
                long at = item.optLong("trigger_at", 0);
                if (id.isEmpty() || task.isEmpty() || at <= 0 || at < now - 24L * 60 * 60 * 1000) {
                    continue;
                }
                long scheduledAt = Math.max(at, now + MIN_DELAY_MS);
                try {
                    scheduleAlarm(context, id, task, scheduledAt);
                    kept.put(item);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        p.edit().putString(KEY_ITEMS, kept.toString()).apply();
    }

    public static boolean canScheduleExact(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());
    }

    public static String describeDelay(long delayMs) {
        if (delayMs < 60_000L) return Math.max(1, Math.round(delayMs / 1000f)) + "秒後";
        if (delayMs % (60L * 60 * 1000) == 0) {
            return (delayMs / (60L * 60 * 1000)) + "小時後";
        }
        return Math.max(1, Math.round(delayMs / 60_000f)) + "分鐘後";
    }

    /** 即使懸浮權限／服務啟動失敗，仍用高優先級通知兜底提醒。 */
    public static void notifyDue(Context context, String task, boolean openMustStartTask) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "定時推動提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("定時任務到點提醒");
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
        Intent open = new Intent(context, MainActivity.class);
        if (openMustStartTask) open.putExtra(EXTRA_TASK, task);
        PendingIntent content = PendingIntent.getActivity(context, task.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("⏰ 時間到")
                .setContentText(task)
                .setStyle(new Notification.BigTextStyle().bigText("時間到：「" + task + "」\n已開始分步推動，請逐步確認。"))
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        nm.notify(20_000 + Math.abs(task.hashCode() % 10_000), notification);
    }
}
