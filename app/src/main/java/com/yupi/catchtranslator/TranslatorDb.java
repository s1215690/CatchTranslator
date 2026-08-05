package com.yupi.catchtranslator;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 本地記錄庫：所有捕捉記錄同每日總結只存喺手機，唔會上傳。 */
public class TranslatorDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "translator.db";
    private static final int DB_VERSION = 2;
    public static final String TABLE = "records";
    public static final String COL_TS = "ts";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_TEXT = "text";
    public static final String COL_SOURCE = "source";
    private static final String TABLE_SUMMARY = "summaries";

    public TranslatorDb(Context c) {
        super(c.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TS + " INTEGER, " + COL_CHANNEL + " TEXT, " + COL_TEXT + " TEXT, " + COL_SOURCE + " TEXT)");
        db.execSQL("CREATE TABLE " + TABLE_SUMMARY + " (date TEXT PRIMARY KEY, content TEXT, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SUMMARY
                    + " (date TEXT PRIMARY KEY, content TEXT, ts INTEGER)");
        }
    }

    public long insert(String channel, String text, String source) {
        ContentValues v = new ContentValues();
        v.put(COL_TS, System.currentTimeMillis());
        v.put(COL_CHANNEL, channel);
        v.put(COL_TEXT, text);
        v.put(COL_SOURCE, source);
        return getWritableDatabase().insert(TABLE, null, v);
    }

    /** 某段時間內嘅記錄，格式："[翻譯官] xxx"。 */
    public List<String> between(long from, long to) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null,
                COL_TS + " >= ? AND " + COL_TS + " < ?",
                new String[]{String.valueOf(from), String.valueOf(to)},
                null, null, COL_TS + " ASC");
        while (c.moveToNext()) {
            String ch = c.getString(c.getColumnIndexOrThrow(COL_CHANNEL));
            String tx = c.getString(c.getColumnIndexOrThrow(COL_TEXT));
            out.add("[" + ch + "] " + tx);
        }
        c.close();
        return out;
    }

    /** 最近 N 條記錄，格式："[翻譯官] xxx"。 */
    public List<String> recent(int n) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null, COL_TS + " DESC", String.valueOf(n));
        while (c.moveToNext()) {
            String ch = c.getString(c.getColumnIndexOrThrow(COL_CHANNEL));
            String tx = c.getString(c.getColumnIndexOrThrow(COL_TEXT));
            out.add("[" + ch + "] " + tx);
        }
        c.close();
        return out;
    }

    /** 主頁顯示用，最近 50 條。 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        List<String> list = recent(50);
        if (list.isEmpty()) return "（未有記錄）";
        for (String s : list) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    // ---------- 統計 ----------

    /** 今日記錄數。 */
    public int countToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return countSince(c.getTimeInMillis());
    }

    public int countAll() {
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"COUNT(*)"}, null, null, null, null, null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    private int countSince(long from) {
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"COUNT(*)"},
                COL_TS + " >= ?", new String[]{String.valueOf(from)}, null, null, null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** 近 N 日每個 channel 嘅數量（用嚟顯示類型分佈）。 */
    public Map<String, Integer> channelCounts(int days) {
        Map<String, Integer> m = new HashMap<>();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        Cursor cur = getReadableDatabase().query(TABLE, new String[]{COL_CHANNEL},
                COL_TS + " >= ?", new String[]{String.valueOf(c.getTimeInMillis())},
                null, null, null);
        while (cur.moveToNext()) {
            String ch = cur.getString(0);
            m.put(ch, m.getOrDefault(ch, 0) + 1);
        }
        cur.close();
        return m;
    }

    /** 連續使用天數：今日有記錄就由今日數起，冇就由尋日數起。 */
    public int streakDays() {
        Set<String> days = distinctDays(120);
        if (days.isEmpty()) return 0;
        int streak = 0;
        Calendar cal = Calendar.getInstance();
        if (!days.contains(fmt(cal))) cal.add(Calendar.DAY_OF_YEAR, -1);
        while (days.contains(fmt(cal))) {
            streak++;
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return streak;
    }

    /** 行動完成率：完成／（完成＋未做），冇行動就 -1。 */
    public double actionRate() {
        Map<String, Integer> m = channelCounts(90);
        int done = m.getOrDefault("行動完成", 0) + m.getOrDefault("推動完成", 0);
        int skip = m.getOrDefault("行動未做", 0) + m.getOrDefault("推動取消", 0);
        if (done + skip == 0) return -1;
        return (double) done / (done + skip);
    }

    private Set<String> distinctDays(int limit) {
        Set<String> days = new HashSet<>();
        Cursor c = getReadableDatabase().query(TABLE, new String[]{COL_TS}, null, null, null, null,
                COL_TS + " DESC", String.valueOf(limit));
        Calendar cal = Calendar.getInstance();
        while (c.moveToNext()) {
            cal.setTimeInMillis(c.getLong(0));
            days.add(fmt(cal));
        }
        c.close();
        return days;
    }

    private static String fmt(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    public long insertSummary(String date, String content) {
        ContentValues v = new ContentValues();
        v.put("date", date);
        v.put("content", content);
        v.put("ts", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(TABLE_SUMMARY, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 最新嘅總結，冇就 null。 */
    public String latestSummary() {
        Cursor c = getReadableDatabase().query(TABLE_SUMMARY, null, null, null, null, null, "ts DESC", "1");
        if (c.moveToFirst()) {
            String s = c.getString(c.getColumnIndexOrThrow("content"));
            c.close();
            return s;
        }
        c.close();
        return null;
    }
}
