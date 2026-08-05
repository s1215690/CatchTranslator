package com.yupi.catchtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * AI 引擎：集中所有 DeepSeek 呼叫（回應＋按鈕一次過、單行回應、按鈕生成）。
 * 離線／無 key 嗰陣 fallback 去本地模板池，唔會死機，仲有足夠變化。
 */
public class AiEngine {

    public static class Response {
        public final String type;          // critic / feeling / action / other
        public final String reply;         // 30-60 字回應
        public final List<String> buttons; // 4 個新按鈕；null = 唔換按鈕
        public Response(String type, String reply, List<String> buttons) {
            this.type = type;
            this.reply = reply;
            this.buttons = buttons;
        }
    }

    // ---------- 本地 fallback 模板池（有分類、隨機變化） ----------

    private static final String[] FB_CRITIC = {
            "呢句係翻譯官同你講嘅，唔係你自己同自己講。認得佢就夠喇。",
            "翻譯官又出嚟搶咪——佢把聲唔代表你，你係聽嗰個。",
            "你聽緊嘅係翻譯官嘅舊錄音帶，唔係你嘅心聲。",
            "佢又用呢招——但呢句唔係你講嘅，係佢講嘅。你唔使同佢辯。",
            "翻譯官最叻將小事講到好大。你認得佢，就唔使跟佢行。",
            "呢句係翻譯官嘅背景音樂，唔係你嘅劇本。你可以唔聽。",
            "翻譯官又開咪喇。佢講還佢講，你唔使舉手答辯。",
            "嗰句聽落好似好真，其實係翻譯官嘅老翻錄音。",
    };
    private static final String[] FB_FEELING = {
            "呢個感覺係真嘅，可以淨係深呼吸一下，感受下個身體。",
            "心口實係身體嘅訊號——唔使即刻處理，陪住佢一陣就得。",
            "有個感受喺度，已經係好重要嘅一步。慢慢唞。",
            "身體嘅感覺唔使解釋，淨係留意佢喺邊度就得。",
    };
    private static final String[] FB_ACTION = {
            "好，試下郁下小指頭，郁到就算贏。",
            "唔使做晒，淨係做頭一啖氣就得。",
            "試下起身行兩步，行完可以坐返低。",
            "將件事縮細啲：淨係做一分鐘。",
    };
    private static final String[] FB_OTHER = {
            "記低咗。呢句可以留返，唔使即刻回應。",
            "收到。呢句我哋照樣記低，遲啲再睇。",
            "好，記低咗。想傾多啲隨時講。",
    };

    private static final String[] FB_BUTTONS = {
            "佢又鬧我", "我動唔到", "心口好實", "乜都冇感覺",
            "佢話我唔配", "想郁但郁唔到", "啱啱諗起醜事", "佢叫我唔好出聲",
            "我其實想…", "佢又話我會衰", "身體好攰", "想同人傾偈",
    };

    /** 本地 fallback：按關鍵字粗略分類，再隨機揀回應。 */
    public static Response fallback(String text) {
        String type = "other";
        String[] pool = FB_OTHER;
        if (text.contains("廢") || text.contains("唔配") || text.contains("失敗")
                || text.contains("蠢") || text.contains("冇用") || text.contains("唔得")) {
            type = "critic";
            pool = FB_CRITIC;
        } else if (text.contains("心口") || text.contains("攰") || text.contains("痛")
                || text.contains("實") || text.contains("緊")) {
            type = "feeling";
            pool = FB_FEELING;
        } else if (text.contains("想") || text.contains("做") || text.contains("郁")
                || text.contains("去") || text.contains("起身")) {
            type = "action";
            pool = FB_ACTION;
        }
        String reply = pool[Math.abs(new Random().nextInt()) % pool.length];
        return new Response(type, reply, fallbackButtons());
    }

    public static List<String> fallbackButtons() {
        List<String> list = new ArrayList<>(Arrays.asList(FB_BUTTONS));
        Collections.shuffle(list, new Random(System.currentTimeMillis()));
        return new ArrayList<>(list.subList(0, 4));
    }

    /**
     * 一次過回應：AI 判斷類型＋生成回應＋生成 4 個新按鈕（一次網絡呼叫）。
     * 連唔到線／冇 key 就 fallback。
     */
    public static Response respond(Context ctx, String text) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係「YupiSaver」嘅即時回應引擎。用戶啱啱捕捉咗一句：「" + text + "」（可能係撳掣，亦可能係自己打字或者講出嚟）。\n"
                        + "背景：而家" + nowTime() + "。佢最近嘅記錄：\n" + recordsContext(ctx, 5)
                        + "\n做三件事：\n"
                        + "1. 判斷類型：critic = 內在批判聲音（例如「佢又話我唔配」「我覺得自己好懶」）；feeling = 真實感受/身體狀態（例如「心口好實」）；action = 想做但做唔到嘅小事（例如「想郁但郁唔到」）；other = 其他。\n"
                        + "2. 用廣東話寫30-60字嘅回應：\n"
                        + "critic（最常見）：幫用戶同呢句說話「抽離」——明確講「呢句係翻譯官同你講嘅，唔係你自己同自己講」，再點醒一句（例如「佢把聲唔代表你」「認得佢就夠，唔使同佢辯」）。\n"
                        + "feeling：共情＋身體錨點（例如「可以淨係深呼吸一下」）。action：極微小行動邀請（例如「試下郁下小指頭」）。\n"
                        + "3. 生成 4 個新按鈕文字，等用戶下次一撳就捕捉到新狀態（廣東話口語、4-10個字、具體、唔好用命令式）。\n"
                        + "硬性要求：\n"
                        + "- 千祈唔好以「收到」「已記低」「已確定」「OK」開頭，唔好做應答式確認。\n"
                        + "- 每次角度同措辭都要唔同（指認／比喻／反問都得），唔好重複自己。\n"
                        + "- 唔好用「你應該」，唔好問問題。\n"
                        + "- 唔好重複現有按鈕。\n"
                        + "只輸出JSON：{\"type\":\"critic\",\"reply\":\"...\",\"buttons\":[\"...\",\"...\",\"...\",\"...\"]}";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, "生成回應。", 500);
                JSONObject j = new JSONObject(extractJson(out));
                String type = j.optString("type", "other");
                String reply = j.optString("reply", "").trim();
                List<String> buttons = null;
                JSONArray arr = j.optJSONArray("buttons");
                if (arr != null) {
                    buttons = new ArrayList<>();
                    for (int i = 0; i < arr.length() && buttons.size() < 4; i++) {
                        String s = arr.getString(i).trim();
                        if (!s.isEmpty() && s.length() <= 20) buttons.add(s);
                    }
                    if (buttons.size() != 4) buttons = null;
                }
                if (!reply.isEmpty() && reply.length() <= 80) {
                    return new Response(type, reply, buttons);
                }
            } catch (Exception ignored) {}
        }
        return fallback(text);
    }

    /** 單行回應（反駁／問題等）。冇 key 或者連唔到線就畀一句兜底。 */
    public static String oneLine(Context ctx, String sys, String userMsg) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (key.isEmpty()) return "好，我哋遲啲再傾。";
        try {
            String out = DeepSeekClient.chat(
                    p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, userMsg).trim();
            if (!out.isEmpty() && out.length() <= 60) return out;
        } catch (Exception ignored) {}
        return "好，我哋遲啲再傾。";
    }

    /** 獨立按鈕生成（初始／手動刷新用）。 */
    public static List<String> generateButtons(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (key.isEmpty()) return fallbackButtons();

        String now = nowTime();
        String dow = new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date()).replace("星期", "");
        String ctxText = "現在時間：" + now + "，星期" + dow + "。"
                + "最近記錄：" + recordsContext(ctx, 5)
                + "。上次撳咗：" + p.getString("last_button", "（未有）")
                + "。常見翻譯官主題：" + p.getString("common_theme", "（未有）");

        String sys = "你係「捉翻譯官」心理輔助工具嘅按鈕生成器。用戶心裡面有一個內在批判聲音（佢叫「翻譯官」），"
                + "會貶低佢、令佢冇動力、偷走佢嘅快樂、引誘佢「坐喺度乜都唔郁就安全」。"
                + "你要根據用戶而家嘅情境，生成4個按鈕文字，等用戶一撳就記錄到佢而家嘅狀態。"
                + "按鈕可以係：捕捉翻譯官啱啱講嘅嘢、表達真實感受、或者一個極微小嘅行動邀請。"
                + "規則：廣東話口語、4-10個字、具體、唔好用「你應該」「你必須」、唔好命令式、唔好講教、唔好重複。"
                + "只輸出一個JSON陣列，唔好加任何其他文字，例如：[\"佢又話我唔配\",\"想郁但郁唔到\",\"啱啱心口好實\",\"想同狗仔講嘢\"]";

        try {
            String out = DeepSeekClient.chat(
                    p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, ctxText);
            JSONArray arr = parseArray(out);
            List<String> res = new ArrayList<>();
            for (int i = 0; i < arr.length() && res.size() < 4; i++) {
                String s = arr.getString(i).trim();
                if (!s.isEmpty() && s.length() <= 20) res.add(s);
            }
            if (res.size() == 4) return res;
        } catch (Exception ignored) {}
        return fallbackButtons();
    }

    public static String recordsContext(Context ctx, int n) {
        List<String> recs = new TranslatorDb(ctx).recent(n);
        return recs.isEmpty() ? "（暫無記錄）" : String.join("\n", recs);
    }

    private static String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    public static String extractJson(String raw) {
        int st = raw.indexOf('{');
        int en = raw.lastIndexOf('}');
        return (st >= 0 && en > st) ? raw.substring(st, en + 1) : raw;
    }

    private static JSONArray parseArray(String raw) throws Exception {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("```[a-zA-Z]*", "").replace("```", "").trim();
        }
        int st = s.indexOf('[');
        int en = s.lastIndexOf(']');
        if (st >= 0 && en > st) s = s.substring(st, en + 1);
        return new JSONArray(s);
    }
}
