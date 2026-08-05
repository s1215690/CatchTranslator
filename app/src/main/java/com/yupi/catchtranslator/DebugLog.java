package com.yupi.catchtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 調試 Log：記錄所有 DeepSeek／TTS 調用嘅輸入輸出，方便睇 AI 點樣返回。
 * 主頁可以一鍵發送到 Telegram bot。
 */
public class DebugLog {

    private static final int MAX_ENTRIES = 200;
    private static final List<String> BUFFER = new ArrayList<>();

    public static synchronized void add(String tag, String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        BUFFER.add("[" + ts + "][" + tag + "] " + msg);
        while (BUFFER.size() > MAX_ENTRIES) BUFFER.remove(0);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : BUFFER) sb.append(s).append('\n');
        return sb.length() == 0 ? "（未有 log——試下捕捉一次先）" : sb.toString().trim();
    }

    /** 發送 log 到 Telegram bot（設定頁填咗調試 token 先用得）。 */
    public static void sendToTelegram(final Context ctx, final Runnable onDone) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        final String token = p.getString("debug_token", "").trim();
        final String chatId = p.getString("debug_chat_id", "").trim();
        if (token.isEmpty() || chatId.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        final String text = dump();
        new Thread(() -> {
            try {
                // 分塊發送（Telegram 上限 4096 字符）
                int CHUNK = 3800;
                for (int i = 0; i < text.length(); i += CHUNK) {
                    String part = "📋 AI Log (" + (i / CHUNK + 1) + ")\n"
                            + text.substring(i, Math.min(text.length(), i + CHUNK));
                    sendMessage(token, chatId, part);
                }
            } catch (Exception ignored) {
            }
            if (onDone != null) onDone.run();
        }).start();
    }

    private static void sendMessage(String token, String chatId, String text) throws Exception {
        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        String body = "chat_id=" + urlEncode(chatId) + "&text=" + urlEncode(text);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        is.close();
        if (code != 200) {
            DebugLog.add("TG", "sendMessage 失敗 " + code + ": " + new String(b.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
