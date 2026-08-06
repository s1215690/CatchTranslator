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
import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 每日總結＋每週回顧＋每日提醒：
 * - 每日 00:01：總結「尋日」記錄，AI 分析＋建議，存低＋通知；建議任務會變成今日嘅🚀推動目標。
 * - 每日 20:00：提醒捕捉（今日未捕捉過就溫柔叫一聲）。
 * - 每週一 00:05：週回顧，歸納翻譯官最常講嘅主題，等按鈕生成更貼身。
 */
public class DailySummary {
    public static final String CHANNEL_ID = "daily_summary";
    public static final String ACTION_DAILY = "com.yupi.catchtranslator.DAILY_SUMMARY";
    public static final String ACTION_REMINDER = "com.yupi.catchtranslator.REMINDER";
    public static final String ACTION_WEEKLY = "com.yupi.catchtranslator.WEEKLY_SUMMARY";

    public static void schedule(Context ctx) {
        scheduleAt(ctx, ACTION_DAILY, 0, 1);
        scheduleAt(ctx, ACTION_REMINDER, 20, 0);
        scheduleWeekly(ctx);
    }

    private static void scheduleAt(Context ctx, String action, int hour, int minute) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, DailySummaryReceiver.class).setAction(action);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, action.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                // Android 12+ 有精確鬧鐘權限先用精確（總結準時啲）；冇權限就 setWindow 兜底
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            } else if (Build.VERSION.SDK_INT < 31) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), 10 * 60 * 1000L, pi);
            }
        } catch (SecurityException se) {
            // 精確鬧鐘權限被拒：用 setWindow 兜底，唔可以閃退
            try {
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                am.setWindow(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60 * 1000L, 10 * 60 * 1000L,
                        PendingIntent.getBroadcast(ctx, action.hashCode(),
                                new Intent(ctx, DailySummaryReceiver.class).setAction(action),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } catch (Exception ignored2) {
            }
        } catch (Exception ignored) {
            // 鬧鐘排唔到都唔可以閃退
        }
    }

    private static void scheduleWeekly(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, DailySummaryReceiver.class).setAction(ACTION_WEEKLY);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, ACTION_WEEKLY.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 5);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.before(Calendar.getInstance())) c.add(Calendar.DAY_OF_YEAR, 7);
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY * 7, pi);
        } catch (Exception ignored) {
        }
    }

    // ---------- 每日總結 ----------

    public static void generateAndNotify(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("summary_enabled", true)) return;

        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_YEAR, -1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);

        TranslatorDb db = new TranslatorDb(ctx);
        List<String> recs = db.between(start.getTimeInMillis(), end.getTimeInMillis());
        if (recs.isEmpty()) return; // 冇記錄就唔打擾

        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(start.getTimeInMillis()));
        String[] res = buildSummary(ctx, p, date, recs);
        db.insertSummary(date, res[0]);
        if (!res[1].isEmpty()) {
            // 建議任務 → 今日嘅推動目標（FloatingService 撳🚀直接用）
            p.edit().putString("next_task", res[1])
                    .putString("next_task_date", new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date())).apply();
        }
        notify(ctx, "尋日總結｜" + date, res[0], 1001);
    }

    /** 返回 {總結內容, 今日建議任務}；AI 失敗就用本地統計。 */
    private static String[] buildSummary(Context ctx, SharedPreferences p, String date, List<String> recs) {
        String fallback = localDaily(recs);
        String key = p.getString("api_key", "");
        if (key.isEmpty()) return new String[]{fallback, ""};
        try {
            String sys = "你係「YupiSaver」嘅每日總結助手。用戶尋日記錄咗一啲內在聲音捕捉："
                    + "「翻譯官」=佢嘅內在批判聲音；「真我」=真實感受；「按鈕」=佢撳嘅狀態掣；「推動完成/取消」=佢想完成嘅小事；"
                    + "「反駁」=佢駁返翻譯官嘅一句話；「行動完成/未做」=跟進按鈕嘅結果。\n"
                    + "請用廣東話寫一份150-250字嘅總結：溫柔、唔judge、唔好用「你應該」「你必須」；"
                    + "第一部分講尋日大概發生咗咩（引用具體記錄），第二部分講觀察到嘅模式，第三部分俾1-2個好細、好具體、容易做到嘅建議。\n"
                    + "另外諗一個「今日建議」：一個好細、好具體、容易做到嘅行動任務（4-12字，例如：刷牙、落樓行一圈、沖杯茶），作為今日嘅推動目標。\n"
                    + "只輸出JSON：{\"summary\":\"...\",\"next_task\":\"...\"}";
            String user = "日期：" + date + "\n記錄：\n"
                    + TextUtils.join("\n", recs.subList(0, Math.min(recs.size(), 40)));
            String out = DeepSeekClient.chat(p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, user, 1000,
                        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .getBoolean("thinking_enabled", true)).trim();
            JSONObject j = new JSONObject(AiEngine.extractJson(out));
            String summary = j.optString("summary", "").trim();
            String nextTask = j.optString("next_task", "").trim();
            if (summary.isEmpty()) return new String[]{fallback, ""};
            if (summary.length() > 900) summary = summary.substring(0, 900);
            if (nextTask.length() > 12) nextTask = "";
            return new String[]{summary, nextTask};
        } catch (Exception ignored) {
            return new String[]{fallback, ""};
        }
    }

    private static String localDaily(List<String> recs) {
        int translator = 0, self = 0, button = 0, done = 0, cancel = 0, rebut = 0, taskDone = 0, taskSkip = 0;
        for (String r : recs) {
            if (r.startsWith("[翻譯官]")) translator++;
            else if (r.startsWith("[真我]")) self++;
            else if (r.startsWith("[按鈕]")) button++;
            else if (r.startsWith("[推動完成]")) done++;
            else if (r.startsWith("[推動取消]")) cancel++;
            else if (r.startsWith("[反駁]")) rebut++;
            else if (r.startsWith("[行動完成]")) taskDone++;
            else if (r.startsWith("[行動未做]")) taskSkip++;
        }
        return "尋日你捉到「翻譯官」" + translator + " 次，記低咗 " + self + " 句真我感受，撳咗 " + button
                + " 次狀態掣，反駁咗 " + rebut + " 次，完成咗 " + (done + taskDone)
                + " 件小事（未完成 " + (cancel + taskSkip) + " 件）。"
                + "唔使理個數字係大定細——有記錄，就代表尋日你有喺度。";
    }

    // ---------- 每週回顧 ----------

    public static void generateWeeklyAndNotify(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("summary_enabled", true)) return;

        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_YEAR, -7);

        TranslatorDb db = new TranslatorDb(ctx);
        List<String> recs = db.between(start.getTimeInMillis(), end.getTimeInMillis());
        if (recs.isEmpty()) return;

        String content = localDaily(recs);
        String theme = "";
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係「YupiSaver」嘅每週回顧助手。用戶過去一星期記錄咗啲內在聲音捕捉："
                        + "「翻譯官」=內在批判聲音；「真我」=真實感受；「反駁」=佢駁返翻譯官嘅話；「推動完成/行動完成」=完成嘅小事。\n"
                        + "請用廣東話寫一份200-300字嘅週回顧：1) 翻譯官最常講嘅主題（歸納成2-3個模式，引用具體例子）2) 真我／行動方面有咩進展 3) 行動完成率大概幾多 4) 下星期一個最細嘅目標。\n"
                        + "溫柔、唔judge、唔好用「你應該」。\n"
                        + "另外用一句（4-12字）歸納翻譯官最常講嘅主題，例如「成日話自己廢」。\n"
                        + "只輸出JSON：{\"summary\":\"...\",\"theme\":\"...\"}";
                String user = "過去7日記錄：\n" + TextUtils.join("\n", recs.subList(0, Math.min(recs.size(), 60)));
                String out = DeepSeekClient.chat(p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, user, 1200,
                        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .getBoolean("thinking_enabled", true)).trim();
                JSONObject j = new JSONObject(AiEngine.extractJson(out));
                String s = j.optString("summary", "").trim();
                if (!s.isEmpty()) {
                    content = s.length() > 1100 ? s.substring(0, 1100) : s;
                    theme = j.optString("theme", "").trim();
                    if (theme.length() > 12) theme = "";
                }
            } catch (Exception ignored) {}
        }
        String weekDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start.getTime());
        db.insertSummary("週" + weekDate, content);
        if (!theme.isEmpty()) p.edit().putString("common_theme", theme).apply();
        notify(ctx, "每週回顧｜" + weekDate, content, 1003);
    }

    // ---------- 每日提醒 ----------

    public static void remind(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("summary_enabled", true)) return;
        int n = new TranslatorDb(ctx).countToday();
        String msg = n == 0
                ? "今日仲未捕捉過——翻譯官今日有冇出聲？撳個🎧捉住佢。"
                : "今日已經捕捉咗 " + n + " 次，好犀利。而家翻譯官有冇出聲？";
        notify(ctx, "YupiSaver", msg, 1002);
    }

    // ---------- 通知 ----------

    private static void notify(Context ctx, String title, String content, int id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "每日總結", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("每日總結／週回顧／提醒");
        nm.createNotificationChannel(ch);
        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE);
        String preview = content.length() > 80 ? content.substring(0, 80) + "…" : content;
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
