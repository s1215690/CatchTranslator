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
        return chat(baseUrl, apiKey, model, system, user, 250);
    }

    public static String chat(String baseUrl, String apiKey, String model, String system, String user, int maxTokens) throws Exception {
        String url = baseUrl;
        if (!url.endsWith("/")) url += "/";
        url += "chat/completions";

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
        body.put("temperature", 1.1);
        body.put("max_tokens", maxTokens);

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        if (code < 200 || code >= 300) {
            throw new Exception("API 錯誤 " + code + ": " + truncate(resp, 200));
        }
        JSONObject j = new JSONObject(resp);
        return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
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
