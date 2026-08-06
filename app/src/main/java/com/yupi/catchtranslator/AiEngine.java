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
        public final String emotion;       // 段落語氣：七種核心情緒 / fluent / ""=自然
        public final String tag;           // 句內語氣標籤："" / laughs / sighs / gasps / emm…(speech-2.8 專用)
        public Response(String type, String reply, List<String> buttons) {
            this(type, reply, buttons, "", "");
        }
        public Response(String type, String reply, List<String> buttons, String emotion) {
            this(type, reply, buttons, emotion, "");
        }
        public Response(String type, String reply, List<String> buttons, String emotion, String tag) {
            this.type = type;
            this.reply = reply;
            this.buttons = buttons;
            this.emotion = emotion;
            this.tag = tag;
        }
    }

    /** MiniMax Speech 2.8 情感白名單；whisper 曾回報 2013，所以唔放入自動模式。 */
    public static final String[] EMOTIONS = {
            "", "calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "fluent"
    };

    /** 句內語氣標籤白名單（speech-2.8 專用；其他引擎會忽略）。 */
    public static final String[] TAGS = {"", "laughs", "chuckle", "sighs", "gasps", "breath", "emm"};

    /**
     * 根據回應內容＋語氣，App 端自動補一個語氣標籤（AI 冇俾／俾錯嗰陣兜底）。
     * 標籤係「稀有調味」：明確表情位先用，而且只 40% 概率落（唔會每句都帶聲）。
     * 規則：明確嘆氣詞 → sighs；驚訝位 → gasps；開心感嘆 → laughs；猶豫位 → emm。
     */
    private static final Random TAG_RND = new Random();

    public static String suggestTag(String reply, String emotion) {
        if (reply == null || reply.isEmpty()) return "";
        String tag = "";
        if (reply.contains("吓?") || reply.contains("喂?") || reply.contains("真㗎?")
                || reply.contains("吓？") || reply.contains("喂？") || reply.contains("真㗎？")) {
            tag = "gasps";
        } else if (reply.contains("唉") || reply.contains("算啦")) {
            tag = "sighs"; // 只有明確嘆息詞先嘆氣；「唔緊要」「唔好意思」唔算
        } else if (("happy".equals(emotion) && (reply.contains("！") || reply.contains("!")))
                || reply.contains("哈哈")) {
            tag = "laughs";
        } else if (reply.contains("等我諗下") || reply.contains("等我唞")) {
            tag = "emm";
        }
        if (tag.isEmpty()) return "";
        return TAG_RND.nextInt(100) < 20 ? tag : ""; // 節制：得 20% 先落標籤（稀有調味）
    }

    /** 全局限流：60 秒內用過標籤就唔准再用，保證唔會連續兩句都帶聲。 */
    private static long lastTagAt = 0;

    public static String throttleTag(String tag) {
        long now = System.currentTimeMillis();
        if (tag == null || tag.isEmpty()) {
            lastTagAt = 0;
            return "";
        }
        if (now - lastTagAt < 60_000) return ""; // 太密，放棄
        lastTagAt = now;
        return tag;
    }

    /**
     * AI 俾嘅 tag 都要過內容關：標籤同句子內容唔夾就丟（AI 亂加都冇用）。
     * sighs 一定要句入面有嘆氣詞；laughs 要有開心詞；gasps 要有疑問；emm 要有猶豫。
     */
    public static boolean contentMatch(String reply, String tag) {
        if (reply == null || tag == null || tag.isEmpty()) return false;
        switch (tag) {
            case "sighs":
                return reply.contains("唉") || reply.contains("算啦");
            case "laughs":
            case "chuckle":
                return reply.contains("！") || reply.contains("!")
                        || reply.contains("哈哈") || reply.contains("好笑");
            case "gasps":
                return reply.contains("吓") || reply.contains("喂")
                        || reply.contains("？") || reply.contains("?");
            case "emm":
                return reply.contains("嗯") || reply.contains("等我諗下") || reply.contains("等我唞");
            case "breath":
                return reply.contains("唞") || reply.contains("深呼吸");
            default:
                return false;
        }
    }

    /**
     * emotion 都要同內容夾：各種強情緒一定要有句子內容支持。
     * 唔夾就降返自然，避免無緣無故嬲／驚／傷心。
     */
    public static String emotionForContent(String reply, String aiEmotion) {
        String e = safeEmotion(aiEmotion);
        if (e == null || e.isEmpty() || reply == null) return e;
        switch (e) {
            case "sad":
                return (reply.contains("攰") || reply.contains("痛") || reply.contains("唉")
                        || reply.contains("算啦") || reply.contains("冇力") || reply.contains("唔想")
                        || reply.contains("灰") || reply.contains("頹") || reply.contains("麻木")
                        || reply.contains("辛苦") || reply.contains("難受") || reply.contains("寂寞")) ? e : "";
            case "happy":
                return (reply.contains("！") || reply.contains("!")
                        || reply.contains("開心") || reply.contains("好嘢") || reply.contains("正")) ? e : "";
            case "angry":
                return (reply.contains("翻譯官") || reply.contains("破壞者")
                        || reply.contains("搶咪") || reply.contains("搶走") || reply.contains("剝奪")
                        || reply.contains("唔係你") || reply.contains("唔代表你")
                        || reply.contains("夠喇") || reply.contains("唔准") || reply.contains("過分")) ? e : "";
            case "fearful":
                return (reply.contains("驚") || reply.contains("怕") || reply.contains("危險")
                        || reply.contains("唔安全") || reply.contains("心口實")
                        || reply.contains("緊張") || reply.contains("縮")) ? e : "";
            case "disgusted":
                return (reply.contains("討厭") || reply.contains("厭惡") || reply.contains("噁心")
                        || reply.contains("反感") || reply.contains("離譜")) ? e : "";
            case "surprised":
                return (reply.contains("？") || reply.contains("?") || reply.contains("吓")
                        || reply.contains("竟然") || reply.contains("原來")) ? e : "";
            default:
                return e; // calm / fluent / 空 唔使校
        }
    }

    /**
     * 自動情感總入口：AI 有提供而且內容吻合就採用；否則由 App 按句子內容兜底。
     * mode 唔係 auto 時視為手動固定模式，AI 唔會覆蓋用戶選擇。
     */
    public static String resolveVoiceEmotion(String text, String aiEmotion, String mode) {
        String selected = mode == null ? "auto" : mode.trim();
        if (!"auto".equals(selected)) return safeEmotion(selected);
        String fromAi = emotionForContent(text, aiEmotion);
        return fromAi == null || fromAi.isEmpty() ? inferEmotion(text) : fromAi;
    }

    /** 冇 AI emotion 時，以明確關鍵詞判斷；寧願自然流利，唔會隨機亂變情緒。 */
    public static String inferEmotion(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String t = text.trim();
        if (containsAny(t, "討厭", "厭惡", "噁心", "反感", "離譜")) return "disgusted";
        if (containsAny(t, "驚", "害怕", "怕", "危險", "唔安全", "心口實", "緊張")) return "fearful";
        if (containsAny(t, "攰", "痛", "灰", "頹", "麻木", "冇力", "辛苦", "難受", "寂寞")) return "sad";
        if (containsAny(t, "翻譯官", "破壞者", "搶咪", "搶走", "剝奪", "唔代表你",
                "唔係你", "夠喇", "唔准", "過分")) return "angry";
        if (containsAny(t, "好嘢", "做到", "贏", "成功", "開心", "恭喜", "里程碑",
                "話到做到", "多謝", "值得", "正呀")) return "happy";
        if (containsAny(t, "吓", "竟然", "原來", "真㗎") || t.contains("？") || t.contains("?")) {
            return "surprised";
        }
        if (containsAny(t, "慢慢", "唔使心急", "陪住你", "唞下", "呼吸", "唔緊要",
                "冇問題", "安心", "溫柔")) return "calm";
        return "fluent";
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** 校驗 emotion 喺咪白名單內，唔喺就返回空（自然）。 */
    public static String safeEmotion(String e) {
        if (e == null) return "";
        for (String id : EMOTIONS) {
            if (id.equals(e.trim())) return id;
        }
        return "";
    }

    /** 校驗 tag 喺咪白名單內，唔喺就返回空。 */
    public static String safeTag(String t) {
        if (t == null) return "";
        for (String id : TAGS) {
            if (id.equals(t.trim())) return id;
        }
        return "";
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

    /** 整體思考模式開關：設定入面揀（預設開）。生成按鈕唔受影響（永遠非思考，要快）。 */
    public static boolean thinking(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("thinking_enabled", true);
    }

    /** 按鈕數量：設定入面揀 4/8/10。 */
    public static int buttonCount(Context ctx) {
        int n = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("button_count", 4);
        return (n == 8 || n == 10) ? n : 4;
    }

    /** 本地 fallback：按關鍵字粗略分類，再隨機揀回應（唔會用「收到／記低」式應答）。 */
    public static Response fallback(String text, boolean narration, int count) {
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
        // 本地 fallback 都有情感變化：批判用保護性堅定、看守平靜、僵住／自我價值傷感。
        String emo = "stuck".equals(type) || "worth".equals(type) ? "sad"
                : "critic".equals(type) ? "angry"
                : "guardian".equals(type) ? "calm" : inferEmotion(reply);
        String tag = throttleTag(suggestTag(reply, emo));
        return new Response(type, reply, fallbackButtons(count), emo, tag);
    }

    /** 旁白模式：將「你」改做「佢」，拉開觀察距離。 */
    private static String toNarration(String s) {
        return s.replace("你哋", "佢哋").replace("你", "佢");
    }

    public static List<String> fallbackButtons(int count) {
        List<String> list = new ArrayList<>(Arrays.asList(FB_BUTTONS));
        Collections.shuffle(list, new Random(System.currentTimeMillis()));
        int n = Math.min(count, list.size());
        return new ArrayList<>(list.subList(0, n));
    }

    /**
     * 一次過回應：AI 判斷類型＋生成回應＋生成 4 個新按鈕（一次網絡呼叫）。
     * 連唔到線／冇 key 就 fallback。
     */
    public static Response respond(Context ctx, String text) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        boolean narration = p.getBoolean("narration_enabled", false);
        int btnCount = buttonCount(ctx);
        DebugLog.add("AI", "輸入: " + truncate(text, 100) + " | narration=" + narration + " | 有key=" + !key.isEmpty() + " | 按鈕數=" + btnCount);
        if (!key.isEmpty()) {
            try {
                String sys = "你係「YupiSaver」嘅即時回應引擎。用戶啱啱捕捉咗一句：「" + text + "」——呢句就係今次要回應嘅嘢，其他記錄只係背景。\n"
                        + "你要服務嘅用戶，長期被內在機制困住，你要按佢嘅情況回應：\n"
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
                        + "3. 生成 " + btnCount + " 個新按鈕文字（廣東話口語、4-10個字、具體、唔好命令式、唔好重複現有按鈕；最少 4 個，最多 " + btnCount + " 個），捕捉佢下一個狀態。\n"
                        + "4. 判斷段落語氣 emotion（成段話嘅基調）：\n"
                        + "- \"calm\"：平靜、抽離、專業（指認翻譯官、安慰、沉重嘅嘢）\n"
                        + "- \"sad\"：傷心、柔軟——極少用！只有佢明言好攰／好痛／好灰嗰陣先用；普通安慰唔係 sad（聽落會似嘆氣）\n"
                        + "- \"happy\"：開朗、輕快（鼓勵、微小勝利、輕鬆嘅嘢）\n"
                        + "- \"angry\"：保護性、堅定、有力量（駁翻譯官／破壞者；唔可以對用戶發火）\n"
                        + "- \"fearful\"：緊張、害怕（只在句子本身描述驚／不安全時輕量使用）\n"
                        + "- \"disgusted\"：厭惡、反感（極少用，只限明確厭惡／離譜內容，唔可以針對用戶）\n"
                        + "- \"surprised\"：驚訝（好少用，得啲「喂？咁都得？」嘅位先用）\n"
                        + "- \"fluent\"：流利自然（普通敘述）\n"
                        + "- \"\"：中性自然（唔知用邊個就留空）\n"
                        + "唔好每次都揀 calm；按 reply 真正語氣揀最貼切一種，但強情緒一定要有內容依據。\n"
                        + "5. 判斷句內語氣標籤 tag（第二層，句中即時語氣）——重要：呢個係「稀有調味」，十句最多一兩句先用！大部分情況留空。\n"
                        + "- 只有真係有明顯表情嘅位先用一個：laughs（好笑）、chuckle（輕笑）、sighs（唉…嘆氣）、gasps（吓？）、breath（換氣）、emm（嗯…猶豫）\n"
                        + "- 冇明確表情位就留空字串；唔好夾硬加；只可以揀一個，一定要係上面其中一個或者空字串。\n"
                        + "只輸出JSON：{\"type\":\"critic\",\"emotion\":\"calm\",\"tag\":\"sighs\",\"reply\":\"...\",\"buttons\":[\"...\",\"...\"]}（buttons 最少 4 個，最多 " + btnCount + " 個）";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, "生成回應。", 1200, thinking(ctx));
                JSONObject j = new JSONObject(extractJson(out));
                String type = j.optString("type", "other");
                String reply = j.optString("reply", "").trim();
                // emotion 要同內容夾（AI 亂揀 sad 會似嘆氣腔）；tag 一定要內容匹配先用
                String emotion = emotionForContent(reply, j.optString("emotion", ""));
                String aiTag = safeTag(j.optString("tag", ""));
                String tag = contentMatch(reply, aiTag) ? aiTag : "";
                if (tag.isEmpty()) tag = suggestTag(reply, emotion); // App 補（得 20%）
                tag = throttleTag(tag);
                List<String> buttons = null;
                JSONArray arr = j.optJSONArray("buttons");
                if (arr != null) {
                    buttons = new ArrayList<>();
                    for (int i = 0; i < arr.length() && buttons.size() < btnCount; i++) {
                        String s = arr.getString(i).trim();
                        if (!s.isEmpty() && s.length() <= 20) buttons.add(s);
                    }
                    // 4~N 個都接受；唔夠 4 個就唔換（保持原按鈕）
                    if (buttons.size() < 4) buttons = null;
                }
                if (!reply.isEmpty() && reply.length() <= 150) {
                    DebugLog.add("AI", "解析 OK: type=" + type + " | emotion=" + emotion + " | tag=" + tag
                            + " | reply=" + truncate(reply, 80)
                            + " | buttons=" + (buttons == null ? "null(保持原按鈕)" : buttons.size()));
                    return new Response(type, reply, buttons, emotion, tag);
                }
                DebugLog.add("AI", "解析失敗: reply=" + truncate(reply, 60) + "（超長或空）→ fallback");
            } catch (Exception e) {
                DebugLog.add("AI", "異常: " + e.getClass().getSimpleName() + " " + truncate(e.getMessage(), 100) + " → fallback");
            }
        }
        return fallback(text, narration, btnCount);
    }

    // ---------- 再安慰多啲（同一主題繼續） ----------

    private static final String[] MORE_COMFORT = {
            "翻譯官把聲又細咗少少——你而家聽到嘅，開始係你自己。",
            "唔使一次過信晒佢，淨係知佢又講緊嘢，已經夠。",
            "你唔使即刻好返，可以慢慢唞——呢一刻你喺度，就係證據。",
            "嗰句嘢已經講完咗，而家唔使跟佢行，跟住自己嘅呼吸就得。",
            "翻譯官講嘅係舊錄音，你而家嘅一刻係新嘅。",
            "佢想你覺得自己冇價值，但你仲喺度揀聽唔聽——呢樣嘢佢搶唔走。",
            "唔使答佢。你淨係望住佢，佢就會縮。",
            "你已經識得叫佢做翻譯官——呢一步好大，好多人都未到。",
    };
    private static int moreIdx = -1;

    private static final String[] PROACTIVE_COMFORT = {
            "唔使急住變得更好，你而家已經值得被溫柔對待，我會安靜陪你一陣。",
            "呢一刻可能唔完美，但你仍然喺度照顧緊自己，呢件事本身已經好珍貴。",
            "如果今日好攰，就容許自己慢一點；你唔需要證明任何嘢先配得到休息。",
            "你可以暫時放低外面啲聲，留返一點空間畀自己，慢慢呼吸就已經足夠。",
            "就算你而家未感覺到力量，佢都冇消失，只係先陪你安靜休息喺身邊。",
            "我唔會催你振作；你行到邊一步，就算邊一步，呢度一直有一盞燈留住。",
    };
    private static int proactiveIdx = -1;

    /** 同一主題繼續安慰：傳埋之前講過嘅嘢，AI 會換角度、唔重複。 */
    public static Response respondMore(Context ctx, String topic, List<String> history) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String hist = "";
                if (history != null && !history.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String h : history) sb.append("「").append(h).append("」\n");
                    hist = sb.toString();
                }
                String sys = "你係「YupiSaver」嘅安慰延續引擎。用戶撳咗「再安慰多啲」，想繼續傾同一個主題：「" + truncate(topic, 80) + "」。\n"
                        + "背景：" + nowTime() + "。佢最近嘅記錄：\n" + recordsContext(ctx, 5)
                        + "\n你之前已經安慰過佢，講過呢啲：\n" + (hist.isEmpty() ? "（未有）" : hist)
                        + "\n而家要做：\n"
                        + "1. 同一主題，繼續安慰——但一定要換角度，千祈唔好重複上面已經講過嘅嘢。\n"
                        + "2. 角度可以輪住嚟：抽離指認 → 身體錨點 → 陪伴比喻 → 收窄落地；越講越貼身、越溫柔。\n"
                        + "3. 用廣東話寫30-60字。\n"
                        + "4. 唔好問問題、唔好用「你應該」「你必須」、唔好以「收到」「OK」開頭、唔好做應答式確認。\n"
                        + "5. emotion：按今次 reply 揀 calm／sad／happy／angry／fearful／disgusted／surprised／fluent／空字串；普通安慰多數 calm 或 fluent，強情緒一定要同內容吻合，唔好永遠同一種。\n"
                        + "6. tag 係稀有調味：冇明確表情位就留空；sighs 要句入面有「唉／算啦」先可以用。\n"
                        + "只輸出JSON：{\"emotion\":\"calm\",\"tag\":\"\",\"reply\":\"...\"}";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, "繼續安慰。", 800, thinking(ctx));
                JSONObject j = new JSONObject(extractJson(out));
                String reply = j.optString("reply", "").trim();
                String emotion = emotionForContent(reply, j.optString("emotion", ""));
                String aiTag = safeTag(j.optString("tag", ""));
                String tag = contentMatch(reply, aiTag) ? aiTag : "";
                if (tag.isEmpty()) tag = suggestTag(reply, emotion);
                tag = throttleTag(tag);
                if (!reply.isEmpty() && reply.length() <= 150) {
                    DebugLog.add("AI", "再安慰 OK: emotion=" + emotion + " | tag=" + tag
                            + " | reply=" + truncate(reply, 80));
                    return new Response("other", reply, null, emotion, tag);
                }
                DebugLog.add("AI", "再安慰解析失敗 → fallback 池");
            } catch (Exception e) {
                DebugLog.add("AI", "再安慰異常: " + e.getClass().getSimpleName());
            }
        }
        // 本地兜底：避開上次用過嗰句
        int idx;
        do {
            idx = Math.abs(new Random().nextInt()) % MORE_COMFORT.length;
        } while (idx == moreIdx && MORE_COMFORT.length > 1);
        moreIdx = idx;
        String reply = MORE_COMFORT[idx];
        String tag = throttleTag(suggestTag(reply, ""));
        return new Response("other", reply, null, "calm", tag);
    }

    /** 背景主動安慰：唔等用戶撳掣，按間隔生成一句並交畀語音引擎播放。 */
    public static Response proactiveComfort(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係 YupiSaver 嘅主動陪伴引擎。用戶冇主動提問，而家只需要收到一句自然、溫柔、唔打擾嘅廣東話安慰。\n"
                        + "最近記錄（只作為理解氣氛，唔好直接洩露敏感內容）：\n" + recordsContext(ctx, 5) + "\n"
                        + "規則：\n"
                        + "1. 只寫一句 30-60 字嘅廣東話安慰，具體但唔好太戲劇化。\n"
                        + "2. 唔好問問題、唔好叫用戶做任何事、唔好用『你應該』『你必須』，唔好以『收到』『OK』開頭。\n"
                        + "3. 唔好提 AI、定時、生成、通知或呢個提示本身；唔好假裝知道用戶一定發生咗乜。\n"
                        + "4. 避免重複最近記錄和常見句式，語氣要像安靜陪伴；可以按內容揀 calm／sad／happy／fluent，但一般以 calm 為主。\n"
                        + "5. tag 只在句子明確有嘆氣、笑聲或呼吸字眼時使用，否則留空。\n"
                        + "只輸出 JSON：{\"emotion\":\"calm\",\"tag\":\"\",\"reply\":\"...\"}";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys,
                        "請給我一句此刻適合收到的安慰。", 700, thinking(ctx)).trim();
                String reply = "";
                String emotion = "";
                String aiTag = "";
                try {
                    JSONObject j = new JSONObject(extractJson(out));
                    reply = j.optString("reply", "").trim();
                    emotion = emotionForContent(reply, j.optString("emotion", ""));
                    aiTag = safeTag(j.optString("tag", ""));
                } catch (Exception ignored) {
                    // 某些兼容接口會忽略 JSON 要求；純文字仍可安全使用。
                    reply = out.replace("\"", "").trim();
                    emotion = emotionForContent(reply, "");
                }
                String tag = contentMatch(reply, aiTag) ? aiTag : "";
                if (tag.isEmpty()) tag = suggestTag(reply, emotion);
                tag = throttleTag(tag);
                if (!reply.isEmpty() && reply.length() >= 8 && reply.length() <= 150) {
                    DebugLog.add("AI", "主動安慰 OK: emotion=" + emotion + " | tag=" + tag
                            + " | reply=" + truncate(reply, 80));
                    return new Response("other", reply, null,
                            emotion.isEmpty() ? "calm" : emotion, tag);
                }
                DebugLog.add("AI", "主動安慰解析失敗 → fallback 池");
            } catch (Exception e) {
                DebugLog.add("AI", "主動安慰異常: " + e.getClass().getSimpleName());
            }
        }
        int idx;
        do {
            idx = Math.abs(new Random().nextInt()) % PROACTIVE_COMFORT.length;
        } while (idx == proactiveIdx && PROACTIVE_COMFORT.length > 1);
        proactiveIdx = idx;
        String reply = PROACTIVE_COMFORT[idx];
        return new Response("other", reply, null, "calm", throttleTag(suggestTag(reply, "calm")));
    }

    /** 感恩練習引導句：每次唔同，引導佢諗「而家擁有／已經得到咗」啲乜。 */
    public static String gratitudePrompt(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係感恩練習嘅引導者。用戶啱啱撳咗感恩按鈕。"
                        + "用廣東話寫一句25-40字嘅引導句，引導佢諗下「而家擁有咩／已經得到咗咩」："
                        + "要具體、溫柔、唔好問「點解」、唔好用「你應該」、每次用詞都要唔同、唔好講大道理。"
                        + "直接輸出嗰一句，唔好加引號。";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys,
                        "畀我一句感恩引導。", 400, thinking(ctx)).trim();
                if (!out.isEmpty() && out.length() <= 60) return out;
            } catch (Exception e) {
                DebugLog.add("AI", "感恩引導異常: " + e.getClass().getSimpleName());
            }
        }
        return GRATITUDE_PROMPTS[Math.abs(new Random().nextInt()) % GRATITUDE_PROMPTS.length];
    }

    /** 感恩回應：用戶講咗佢擁有／得到嘅嘢，AI 溫暖回應。 */
    public static String gratitudeReply(Context ctx, String text) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係感恩練習嘅回應者。用戶講咗佢擁有／已經得到咗嘅嘢：「" + truncate(text, 150) + "」。"
                        + "用廣東話寫30-60字嘅溫暖回應：肯定佢擁有嘅嘢、幫佢記住呢一刻係真嘅、"
                        + "唔好講道理、唔好用「你應該」、唔好問問題、唔好以「收到」開頭。"
                        + "直接輸出回應，唔好加引號。";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys,
                        "回應我擁有嘅嘢。", 500, thinking(ctx)).trim();
                if (!out.isEmpty() && out.length() <= 100) return out;
            } catch (Exception e) {
                DebugLog.add("AI", "感恩回應異常: " + e.getClass().getSimpleName());
            }
        }
        return GRATITUDE_REPLIES[Math.abs(new Random().nextInt()) % GRATITUDE_REPLIES.length];
    }

    private static final String[] GRATITUDE_PROMPTS = {
            "試下諗下：你而家擁有啲乜嘢？床？熱水？有個狗仔等你？",
            "唔使諗大嘅嘢——淨係諗下你今日用到嘅：一杯水、一盞燈、一張被。",
            "你已經得到咗啲乜嘢？可以係好細嘅嘢：啱啱嗰啖氣、個枕頭、一部電話。",
            "諗下邊樣嘢係你而家有、但以前冇嘅？",
            "唔使急，靜靜諗下：你擁有嘅嘢入面，邊樣最細、但最實在？",
            "今日有咩嘢係企咗喺你身邊？唔使大，係真嘅就得。",
            "你已經有嘅嘢，邊樣係你平時唔會特別記得、但冇咗會好唔慣嘅？",
    };

    private static final String[] GRATITUDE_REPLIES = {
            "好，你講得出嚟，就係真嘅擁有。",
            "呢啲就係你嘅——記住佢哋喺度。",
            "你擁有嘅嘢，唔使好大，係真嘅就夠。",
            "呢一刻你講得出嘅，都係你袋袋平安嘅。",
            "好，收好呢樣嘢——佢係你嘅。",
    };

    /** 單行回應（反駁／問題等）。冇 key 或者連唔到線就畀一句兜底。 */
    public static String oneLine(Context ctx, String sys, String userMsg) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (key.isEmpty()) return "好，我哋遲啲再傾。";
        try {
            String out = DeepSeekClient.chat(
                    p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, userMsg, 500, thinking(ctx)).trim();
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
        int btnCount = buttonCount(ctx);
        if (key.isEmpty()) return fallbackButtons(btnCount);

        String now = nowTime();
        String dow = new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date()).replace("星期", "");
        String ctxText = "現在時間：" + now + "，星期" + dow + "。"
                + "最近記錄：" + recordsContext(ctx, 5)
                + "。上次撳咗：" + p.getString("last_button", "（未有）")
                + "。常見翻譯官主題：" + p.getString("common_theme", "（未有）");

        String sys = "你係「捉翻譯官」心理輔助工具嘅按鈕生成器。用戶心裡面有幾個內在聲音："
                + "「翻譯官」=冷淡化一切善意嘅批判聲；「溫柔看守」=用安全做餌叫佢「坐喺度就唔會受傷」嘅聲音；「破壞者」=剝奪快樂嘅聲音。"
                + "你要根據用戶而家嘅情境，生成" + btnCount + "個按鈕文字，等用戶一撳就記錄到佢而家嘅狀態。"
                + "按鈕可以係：捕捉翻譯官啱啱講嘅嘢、溫柔看守嘅勸誘、冇動力嘅感覺、真實感受、或者覺得自己唔重要嘅諗法。"
                + "規則：廣東話口語、4-10個字、具體、唔好用「你應該」「你必須」、唔好命令式、唔好講教、唔好重複。"
                + "只輸出一個JSON陣列（一定要有 " + btnCount + " 項），唔好加任何其他文字，例如：[\"佢又話我唔配\",\"坐喺度就安全\",\"我動唔到\",\"我對佢哋嚟講唔重要\"]";

        try {
            String out = DeepSeekClient.chat(
                    p.getString("base_url", "https://api.deepseek.com"),
                    key, p.getString("model", "deepseek-chat"), sys, ctxText, 800, false); // 非思考模式：生成按鈕要快
            JSONArray arr = parseArray(out);
            List<String> res = new ArrayList<>();
            for (int i = 0; i < arr.length() && res.size() < btnCount; i++) {
                String s = arr.getString(i).trim();
                if (!s.isEmpty() && s.length() <= 20) res.add(s);
            }
            if (res.size() == btnCount) {
                DebugLog.add("AI", "生成按鈕 OK: " + res);
                return res;
            }
            DebugLog.add("AI", "生成按鈕失敗(唔夠" + btnCount + "個): " + res);
        } catch (Exception e) {
            DebugLog.add("AI", "生成按鈕異常: " + e.getClass().getSimpleName());
        }
        return fallbackButtons(btnCount);
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
