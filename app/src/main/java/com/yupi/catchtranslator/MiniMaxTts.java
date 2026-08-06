package com.yupi.catchtranslator;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * MiniMax T2A v2 神經語音（海螺 AI）——超自然語音，按字符計費。
 * 文檔：https://platform.minimaxi.com/docs/api-reference/speech-t2a-http
 * 認證：Authorization: Bearer API_key（唔使 JWT）。
 * 回應：data.audio = hex 編碼音訊（唔係 base64！）。
 *
 * 粵語要點（參考官方文檔）：
 * 1. 一定要用 Cantonese_ 開頭嘅聲線，普通話聲線（female-shaonv 等）只識讀普通話！
 * 2. language_boost = "Chinese,Yue" 強制粵語，唔填會用普通話讀粵語字。
 * 3. 推薦模型 speech-2.8-hd（最新、最自然）。
 */
public class MiniMaxTts {

    private static final String ENDPOINT = "https://api.minimaxi.com/v1/t2a_v2";
    private static final String VOICE_DESIGN_ENDPOINT = "https://api.minimaxi.com/v1/voice_design";

    /** 官方 API 粵語聲線（唔好用 APIXO preset_cantonese_xxx，官方 key 會報 voice id not exist）。 */
    public static final String[] VOICE_IDS = {
            "Cantonese_CuteGirl", "Cantonese_KindWoman", "Cantonese_GentleLady", "Cantonese_PlayfulMan"
    };
    public static final String[] VOICE_LABELS = {
            "粵語·可愛女孩", "粵語·善良女士", "粵語·溫柔女士", "粵語·頑皮男聲"
    };

    /** 模型選擇（官方推薦 2.8 系列；02 系列舊模型已移除）。 */
    public static final String[] MODEL_IDS = {
            "speech-2.8-hd", "speech-2.8-turbo"
    };
    public static final String[] MODEL_LABELS = {
            "2.8 HD（推薦·最自然）", "2.8 Turbo（快·慳）"
    };

    /** 語氣（voice_setting.emotion），空＝唔傳（自然）。實測：speech-2.8 支持 calm/happy/sad/surprised/fluent；whisper 唔支持（報 2013）。 */
    public static final String[] EMOTION_IDS = {"", "calm", "happy", "sad", "surprised", "fluent"};
    public static final String[] EMOTION_LABELS = {"自然（預設）", "平靜", "開心", "傷心·安慰", "驚訝", "流利自然"};

    /** 句內語氣標籤白名單（speech-2.8 專用，插喺 text 入面做即時語氣切換）。 */
    public static final String[] TAG_IDS = {"", "laughs", "chuckle", "sighs", "gasps", "breath", "emm"};
    public static final String[] TAG_LABELS = {"冇", "笑(laughs)", "輕笑(chuckle)", "嘆氣(sighs)", "倒吸氣(gasps)", "換氣(breath)", "猶豫(emm)"};

    public static void synthesize(String apiKey, String text, String voiceId, String modelId,
                                  String ratePct, String emotion, String tag, File out) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("冇 MiniMax API Key");

        String finalText = applyTag(text, tag);

        JSONObject body = new JSONObject();
        body.put("model", (modelId == null || modelId.isEmpty()) ? MODEL_IDS[0] : modelId);
        body.put("text", finalText);
        body.put("stream", false);
        body.put("language_boost", "Chinese,Yue"); // 強制粵語，唔填會變普通話腔！

        JSONObject vs = new JSONObject();
        vs.put("voice_id", (voiceId == null || voiceId.isEmpty()) ? VOICE_IDS[0] : voiceId);
        float speed = 1f;
        try { speed = 1f + Integer.parseInt(ratePct) / 100f; } catch (Exception ignored) {}
        speed = Math.max(0.5f, Math.min(2f, speed));
        vs.put("speed", speed);
        vs.put("vol", 1.0);
        vs.put("pitch", 0);
        if (emotion != null && !emotion.trim().isEmpty()) vs.put("emotion", emotion.trim());
        body.put("voice_setting", vs);

        JSONObject as = new JSONObject();
        as.put("sample_rate", 32000);
        as.put("bitrate", 128000); // 官方文檔示例值；bitrate 係枚舉，192000 會報 invalid params！
        as.put("format", "mp3");
        as.put("channel", 1);
        body.put("audio_setting", as);

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = readAll(is);
        if (code != 200) throw new Exception("MiniMax HTTP " + code + ": " + truncate(resp, 200));

        JSONObject j = new JSONObject(resp);
        JSONObject br = j.optJSONObject("base_resp");
        if (br == null || br.optInt("status_code", -1) != 0) {
            throw new Exception("MiniMax 錯誤: " + (br == null ? "base_resp 缺失" : br.optString("status_msg")));
        }
        JSONObject data = j.optJSONObject("data");
        if (data == null) throw new Exception("MiniMax 回應冇 data");
        byte[] audio = hexToBytes(data.optString("audio", ""));
        if (audio.length < 100) throw new Exception("MiniMax 音訊太細");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(audio);
        }
    }

    /**
     * 按文字描述設計新音色。接口會回傳一個 voice_id；之後以該 voice_id 呼叫 T2A，
     * 音色才會正式啟用並長期保留。
     */
    public static String designVoice(String apiKey, String prompt, String previewText) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("未填 MiniMax API Key");
        if (prompt == null || prompt.trim().isEmpty()) throw new Exception("請輸入聲線描述");
        if (previewText == null || previewText.trim().isEmpty()) throw new Exception("請輸入粵語試聽文字");
        if (prompt.length() > 500) throw new Exception("聲線描述最多 500 字");
        if (previewText.length() > 500) throw new Exception("試聽文字最多 500 字");

        JSONObject body = new JSONObject();
        body.put("prompt", prompt.trim());
        body.put("preview_text", previewText.trim());
        body.put("aigc_watermark", false);

        HttpURLConnection c = (HttpURLConnection) new URL(VOICE_DESIGN_ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = readAll(is);
        if (code != 200) {
            throw new Exception("MiniMax Voice Design HTTP " + code + ": " + truncate(resp, 240));
        }

        JSONObject j = new JSONObject(resp);
        JSONObject br = j.optJSONObject("base_resp");
        if (br == null || br.optInt("status_code", -1) != 0) {
            throw new Exception("MiniMax 音色設計失敗: "
                    + (br == null ? "缺少 base_resp" : br.optString("status_msg")));
        }
        String voiceId = j.optString("voice_id", "").trim();
        if (voiceId.isEmpty()) throw new Exception("MiniMax 沒有回傳 voice_id");
        return voiceId;
    }

    /**
     * 將語氣標籤插入句入面（speech-2.8 專用，第二層語氣控制）。
     * 規則：插喺第一個「！／？」後；冇標點就插句尾。白名單外嘅標籤忽略。
     */
    public static String applyTag(String text, String tag) {
        if (text == null || text.isEmpty()) return text;
        String t = (tag == null ? "" : tag.trim());
        boolean valid = false;
        for (String id : TAG_IDS) {
            if (id.equals(t)) { valid = true; break; }
        }
        if (!valid || t.isEmpty()) return text;
        String label = "(" + t + ")";
        if (text.contains(label)) return text; // 已經有就唔重複
        int idx = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '！' || c == '?' || c == '？' || c == '!') { idx = i + 1; break; }
        }
        if (idx < 0) return text + label;
        // 插喺標點之後（連埋後面嘅字一齊）
        return text.substring(0, idx) + label + text.substring(idx);
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
