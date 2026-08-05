package com.yupi.catchtranslator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 極簡 LLM 客戶端（DeepSeek 兼容格式：OpenAI chat/completions）。零第三方依賴。 */
public class DeepSeekClient {

    public static String chat(String baseUrl, String apiKey, String model, String system, String user) throws Exception {
        return chat(baseUrl, apiKey, model, system, user, 500, true);
    }

    public static String chat(String baseUrl, String apiKey, String model, String system, String user, int maxTokens) throws Exception {
        return chat(baseUrl, apiKey, model, system, user, maxTokens, true);
    }

    /**
     * 呼叫 chat/completions。
     * thinking=false 時傳 {"thinking":{"type":"disabled"}}——deepseek-v4 系支持非思考模式，快好多（用嚟生成按鈕）。
     * 帶思考時 reasoning_content 會食 token，content 空會自動加大重試（最多 2 次）。
     */
    public static String chat(String baseUrl, String apiKey, String model, String system, String user, int maxTokens, boolean thinking) throws Exception {
        String url = baseUrl;
        if (!url.endsWith("/")) url += "/";
        url += "chat/completions";

        DebugLog.add("DS", "POST " + model + " | user=" + truncate(user, 120) + " | max_tokens=" + maxTokens);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);
        if (thinking) {
            body.put("temperature", 1.1);
        } else {
            // 非思考模式：快、平，用嚟生成按鈕選項
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        body.put("max_tokens", maxTokens);

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        DebugLog.add("DS", "HTTP " + code + " | resp=" + truncate(resp, 400));
        if (code < 200 || code >= 300) {
            throw new Exception("API 錯誤 " + code + ": " + truncate(resp, 200));
        }
        JSONObject j = new JSONObject(resp);
        String content = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        if (content != null && content.trim().isEmpty() && maxTokens < 3000) {
            // 思考模型食晒 token：加大再試一次（避免無限遞歸，3000 封頂）
            DebugLog.add("DS", "content 空（reasoning 食晒 token），加大到 " + (maxTokens * 2) + " 重試");
            return chat(baseUrl, apiKey, model, system, user, Math.min(3000, maxTokens * 2));
        }
        return content == null ? "" : content;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
