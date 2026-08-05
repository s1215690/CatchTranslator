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
 * 回應係按用戶嘅真實心理狀況定制：佢長期被「翻譯官」（冷淡化一切善意）、
 * 「温柔看守」（用安全做餌令佢冇動力）、「破壞者」（剝奪快樂）困住。
 * 回應原則：指認、唔辯論、唔命令、唔否定感受、廣東話、溫暖。
 */
public class AiEngine {

    public static class Response {
        public final String type;          // critic / guardian / stuck / feeling / worth / other
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
    private static final String[] FB_GUARDIAN = {
            "呢個聲音想保護你，但佢叫你坐定定乜都唔郁，你先至更難受。你認得佢就夠。",
            "佢話「唔好郁就安全」，但你跟住佢做，結果係空虛同冇力。呢個唔係安全，係囚禁。",
            "溫柔嘅看守講嘅嘢唔一定啱——佢想保護你，但佢嘅方案，令你更難受。",
            "「坐喺度就冇人傷害你」——但佢冇話你知，坐喺度都會慢慢冇晒自己。",
    };
    private static final String[] FB_STUCK = {
            "你而家只係冇電，唔係壞咗。唔使逼自己，淨係陪住自己一陣就得。",
            "郁唔到就郁唔到，冇所謂。你肯講出嚟，已經係一步。",
            "唔使做啲乜，唔使郁。你肯喺度，已經夠。",
            "而家唔想郁，好正常。你唔係懶，你係俾個看守睇住咗。認得佢就夠。",
    };
    private static final String[] FB_FEELING = {
            "呢個感覺係真嘅，可以淨係深呼吸一下，感受下個身體。",
            "心口實係身體嘅訊號——唔使即刻處理，陪住佢一陣就得。",
            "有個感受喺度，已經係好重要嘅一步。慢慢唞。",
            "身體嘅感覺唔使解釋，淨係留意佢喺邊度就得。",
    };
    private static final String[] FB_WORTH = {
            "呢個係破壞者嚟搶你嘅快樂——你啱啱先覺得好少少，佢就問你值唔值得。你認得佢就夠。",
            "你嘅翻譯官將所有「被在乎」嘅證據都貪污咗。唔係你唔重要，係你收唔到。",
            "「值唔值得」係翻譯官設嘅陷阱，答親都輸。你淨係認得佢，唔好接佢個辯題。",
            "佢問你「你配唔配」，但你唔使答——因為呢條問題本身就係佢嚟呃你嘅。",
    };
    private static final String[] FB_OTHER = {
            "我聽到你講緊呢樣嘢。唔使急，慢慢講。",
            "呢句說話有重量，我陪你停一停。",
            "唔使即刻回應，你肯講出嚟已經夠。",
            "呢樣嘢對你嚟講唔簡單，我聽住。",
            "你講嘅我收到，唔會輕飄飄咁帶過。",
            "呢一刻唔使諗點答，淨係知道自己有嘢想講，已經係一步。",
            "我喺度聽緊。你想講落去，定係想靜一靜，都得。",
            "呢句唔係小事——你肯講出嚟，我當佢係一回事。",
            "唔使理佢啱唔啱聽，你講嘅嘢先至緊要。",
            "我聽到喇。你唔使急住回應我，呢度冇人催你。",
    };

    private static final String[] FB_BUTTONS = {
            "佢又鬧我", "我動唔到", "心口好實", "坐喺度就安全",
            "佢話我唔配", "我唔想覆信息", "我對佢哋嚟講唔重要", "佢叫我唔好出聲",
            "啱啱諗起醜事", "我值唔值得", "身體好攰", "想郁但郁唔到",
    };

    /** 本地 fallback：按關鍵字粗略分類，再隨機揀回應（唔會用「收到／記低」式應答）。 */
    public static Response fallback(String text, boolean narration) {
        String type = "other";
        String[] pool = FB_OTHER;
        if (text.contains("廢") || text.contains("唔配") || text.contains("失敗")
                || text.contains("蠢") || text.contains("冇用") || text.contains("唔得")
                || text.contains("鬧") || text.contains("醜") || text.contains("懶")
                || text.contains("差") || text.contains("冇人鍾意") || text.contains("冇人理")) {
            type = "critic";
            pool = FB_CRITIC;
        } else if (text.contains("安全") || text.contains("坐喺度") || text.contains("唔好郁")
                || text.contains("摸下狗") || text.contains("唔好出聲") || text.contains("唔好試")
                || text.contains("唔好掂")) {
            type = "guardian";
            pool = FB_GUARDIAN;
        } else if (text.contains("動唔到") || text.contains("郁唔到") || text.contains("冇力")
                || text.contains("唔想郁") || text.contains("唔想覆") || text.contains("唔想起身")
                || text.contains("唔想見人") || text.contains("唔想出街") || text.contains("僵住")
                || text.contains("唔想食") || text.contains("起唔到身")) {
            type = "stuck";
            pool = FB_STUCK;
        } else if (text.contains("心口") || text.contains("攰") || text.contains("痛")
                || text.contains("實") || text.contains("緊") || text.contains("悶")
                || text.contains("空虛") || text.contains("寂寞") || text.contains("麻木")
                || text.contains("麻木") || text.contains("頹") || text.contains("灰")
                || text.contains("驚") || text.contains("嬲") || text.contains("煩")
                || text.contains("怕")) {
            type = "feeling";
            pool = FB_FEELING;
        } else if (text.contains("唔重要") || text.contains("值唔值得") || text.contains("配唔配")
                || text.contains("冇人喺乎") || text.contains("冇價值") || text.contains("冇人需要")) {
            type = "worth";
            pool = FB_WORTH;
        }
        String reply = pool[Math.abs(new Random().nextInt()) % pool.length];
        if (narration) reply = toNarration(reply);
        return new Response(type, reply, fallbackButtons());
    }

    /** 旁白模式：將「你」改做「佢」，拉開觀察距離。 */
    private static String toNarration(String s) {
        return s.replace("你哋", "佢哋").replace("你", "佢");
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
        boolean narration = p.getBoolean("narration_enabled", false);
        DebugLog.add("AI", "輸入: " + truncate(text, 100) + " | narration=" + narration + " | 有key=" + !key.isEmpty());
        if (!key.isEmpty()) {
            try {
                String sys = "你係「YupiSaver」嘅即時回應引擎。你要服務嘅用戶，長期被內在機制困住，你要按佢嘅情況回應：\n"
                        + "【佢嘅內在機制】\n"
                        + "1.「翻譯官」：24小時運作嘅內在批判廣播，雙重攔截——對內，將外界善意、成就、快樂全部冷淡化（「呢啲冇乜嘢」「佢只係客氣」「你唔配」），令佢感受唔到快樂、成就感同連結；對外，迫佢用「社交版本」迎合人，真實嘅自己從未俾人見過。\n"
                        + "2.「溫柔看守」：另一個聲音，唔鬧，係勸：「你就坐喺度，乜都唔好郁，先至最安全，冇人會傷害你，得閒摸下隻狗就得。」——用「安全」做餌，令佢冇動力（刷牙都做唔到、唔想覆信息、想郁但郁唔到）。\n"
                        + "3.「破壞者」：喺佢順利嘅時候出現：「你值唔值得？」「你咁就滿足喇？」——專登剝奪佢嘅快樂。\n"
                        + "4. 佢最痛：生活完全冇動力；覺得自己對人哋唔重要；感受唔到快樂同連結。\n"
                        + "【回應原則（硬性要求）】\n"
                        + "- 首要係「指認」：幫佢將聲音同自己分開，例如：「呢句係翻譯官同你講嘅，唔係你自己同自己講」「翻譯官又嚟搶咪喇——你認得佢就夠，唔使同佢辯」「你又嚟剝奪我嘅快樂喇」。\n"
                        + "- 千祈唔好否定佢嘅感受；唔好同翻譯官辯論（辯論會畀翻譯官力量）；唔好用「你應該」「你必須」；唔好命令、唔好講道理、唔好問問題。\n"
                        + "- 冇動力／僵住：唔好催佢郁，唔好畀行動建議；接納「你而家只係冇電，唔係壞咗」，最多輕聲邀請極微細動作（「試下郁下小指頭」「深呼吸一啖就夠」）——做唔到完全冇問題。\n"
                        + "- 「溫柔看守」出現：指認佢嘅好意但方案唔work：「佢想保護你，但跟住佢做，結果係更難受」——唔好鬧佢。\n"
                        + "- 佢覺得自己唔重要：指認「你嘅翻譯官將所有『被在乎』嘅證據都貪污咗」，唔好反過來猛咁讚佢，唔好同佢爭辯。\n"
                        + "- 廣東話口語、溫暖、30-80字（至多80字，唔好超過）、每次角度同措辭唔同、唔好重複自己、唔好以「收到」「已記低」「OK」開頭、唔好做應答式確認。\n"
                        + "【旁白模式已開啟】你嘅回應必須用旁白式：用第三人稱「佢」描述用戶嘅一刻，唔好用「你」；例如：「佢啱啱認出翻譯官又喺度講嘢——『你唔配』。佢冇同佢辯，淨係認得佢就夠。」語氣保持溫暖，好似紀錄片旁白。\n"
                        + "而家背景：" + nowTime() + "。佢最近嘅記錄：\n" + recordsContext(ctx, 5)
                        + "\n做三件事：\n"
                        + "1. 判斷類型：critic=翻譯官批判聲（「佢又話我唔配」「我覺得自己好懶」）；guardian=溫柔看守／安全陷阱（「坐喺度就安全」「唔好郁就冇事」）；stuck=冇動力／僵住（「我動唔到」「唔想覆信息」）；feeling=真實感受／身體狀態（「心口好實」）；worth=覺得自己唔重要／值唔值得（「我對佢哋嚟講唔重要」）；other=其他。\n"
                        + "2. 用廣東話寫30-80字嘅回應，跟足上面原則。\n"
                        + "3. 生成 4 個新按鈕文字（廣東話口語、4-10個字、具體、唔好命令式、唔好重複現有按鈕），捕捉佢下一個狀態。\n"
                        + "只輸出JSON：{\"type\":\"critic\",\"reply\":\"...\",\"buttons\":[\"...\",\"...\",\"...\",\"...\"]}";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, "生成回應。", 1200);
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
                if (!reply.isEmpty() && reply.length() <= 150) {
                    DebugLog.add("AI", "解析 OK: type=" + type + " | reply=" + truncate(reply, 80)
                            + " | buttons=" + (buttons == null ? "null(保持原按鈕)" : buttons.size()));
                    return new Response(type, reply, buttons);
                }
                DebugLog.add("AI", "解析失敗: reply=" + truncate(reply, 60) + "（超長或空）→ fallback");
            } catch (Exception e) {
                DebugLog.add("AI", "異常: " + e.getClass().getSimpleName() + " " + truncate(e.getMessage(), 100) + " → fallback");
            }
        }
        return fallback(text, narration);
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
            DebugLog.add("AI", "oneLine 返回: " + truncate(out, 80));
            if (!out.isEmpty() && out.length() <= 60) return out;
        } catch (Exception e) {
            DebugLog.add("AI", "oneLine 異常: " + e.getClass().getSimpleName());
        }
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

        String sys = "你係「捉翻譯官」心理輔助工具嘅按鈕生成器。用戶心裡面有幾個內在聲音："
                + "「翻譯官」=冷淡化一切善意嘅批判聲；「溫柔看守」=用安全做餌叫佢「坐喺度就唔會受傷」嘅聲音；「破壞者」=剝奪快樂嘅聲音。"
                + "你要根據用戶而家嘅情境，生成4個按鈕文字，等用戶一撳就記錄到佢而家嘅狀態。"
                + "按鈕可以係：捕捉翻譯官啱啱講嘅嘢、溫柔看守嘅勸誘、冇動力嘅感覺、真實感受、或者覺得自己唔重要嘅諗法。"
                + "規則：廣東話口語、4-10個字、具體、唔好用「你應該」「你必須」、唔好命令式、唔好講教、唔好重複。"
                + "只輸出一個JSON陣列，唔好加任何其他文字，例如：[\"佢又話我唔配\",\"坐喺度就安全\",\"我動唔到\",\"我對佢哋嚟講唔重要\"]";

        try {
            String out = DeepSeekClient.chat(
                    p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, ctxText, 800);
            JSONArray arr = parseArray(out);
            List<String> res = new ArrayList<>();
            for (int i = 0; i < arr.length() && res.size() < 4; i++) {
                String s = arr.getString(i).trim();
                if (!s.isEmpty() && s.length() <= 20) res.add(s);
            }
            if (res.size() == 4) {
                DebugLog.add("AI", "生成按鈕 OK: " + res);
                return res;
            }
            DebugLog.add("AI", "生成按鈕失敗(唔夠4個): " + res);
        } catch (Exception e) {
            DebugLog.add("AI", "生成按鈕異常: " + e.getClass().getSimpleName());
        }
        return fallbackButtons();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "…" : s;
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
