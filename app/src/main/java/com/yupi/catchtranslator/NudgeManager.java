package com.yupi.catchtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推動力模式：用戶講出想做嘅事（例如「我想去刷牙」），
 * AI 將任務拆成 3-5 個極微細步驟，一步一推：彈窗＋讀出嚟，
 * 每 60 秒用唔同嘅句子提一次；完成一步即刻讚＋推下一步，
 * 最後一步完成就慶祝。做唔到都唔逼，每步超時 3 次就自動推下一步。
 * 語音由 VoicePlayer 負責。
 */
public class NudgeManager {

    public interface Callback {
        void onNudgeEnd(String task, boolean done);
        void onStatus(String message);
    }

    private static final long INTERVAL_MS = 60_000;
    private static final int MAX_PER_STEP = 3;
    private static final String[] FALLBACK = {
            "而家去做「%s」啦，好快㗎",
            "準備做「%s」未？一分鐘就夠",
            "做咗「%s」，個感覺會好好多㗎",
            "「%s」其實好簡單㗎，試下啦",
            "記得「%s」呀，我等你",
            "同我一齊去做「%s」好冇？",
            "「%s」做完就可以鬆返，去吧",
    };
    private static final String[] STEP_PRAISE = {
            "好，呢步搞掂！", "得咗！繼續下一步。", "好叻，郁到喇！", "正！一步一步嚟。",
    };

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Callback callback;
    private final List<String> usedPhrases = new ArrayList<>();

    private View popup;
    private TextView tvPhrase;
    private Button btnDone, btnCancel;
    private String task = "";
    private List<String> steps = new ArrayList<>();
    private int stepIdx = 0;
    private int stepAttempt = 0;
    private boolean running = false;
    private Runnable ticker;

    public NudgeManager(Context ctx, WindowManager wm, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.wm = wm;
        this.callback = cb;
    }

    /** 開始推動：先拆步，再彈窗＋讀出第一步。 */
    public void start(String raw) {
        stop();
        running = true;
        stepIdx = 0;
        stepAttempt = 0;
        usedPhrases.clear();
        callback.onStatus("分析緊你想做咩…");
        pool.execute(() -> {
            final List<String> s = analyzeSteps(raw);
            ui.post(() -> {
                if (!running) return;
                steps = s;
                task = s.isEmpty() ? raw.trim() : s.get(0);
                showPopup();
                nextNudge();
            });
        });
    }

    /** AI 拆步：2-5 個極微細步驟（按任務複雜度）；失敗／單步就用本地通用模板，最少 2 步。 */
    private List<String> analyzeSteps(String raw) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係任務拆解器。用戶想做一件事：「" + raw.trim() + "」。"
                        + "將佢拆成2-5個極微細、具體、一步一步嚟嘅動作步驟（每步2-12個字，例如：起身、行去洗手間、開水喉、刷牙、抹嘴）。"
                        + "步驟數視任務而定：簡單任務拆2-3步，複雜任務先拆3-5步；唔好硬湊，但**最少要有2步，唔可以1步講完**。"
                        + "步驟要細到「冇動力嘅人都做到第一步」。"
                        + "只輸出JSON陣列，例如：[\"起身\",\"行去洗手間\",\"開水喉\",\"刷牙\"]";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"), sys, "幫我拆步。").trim();
                List<String> res = new ArrayList<>();
                String s = out.trim();
                if (s.startsWith("```")) {
                    s = s.replaceAll("```[a-zA-Z]*", "").replace("```", "").trim();
                }
                int st = s.indexOf('[');
                int en = s.lastIndexOf(']');
                if (st >= 0 && en > st) s = s.substring(st, en + 1);
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length() && res.size() < 5; i++) {
                    String step = arr.getString(i).trim();
                    if (!step.isEmpty() && step.length() <= 15) res.add(step);
                }
                if (res.size() >= 2) return res;
            } catch (Exception ignored) {}
        }
        return localSteps(raw);
    }

    /** 本地兜底拆步：AI 連唔到線／只出 1 步時用，確保唔會一句講完。 */
    private List<String> localSteps(String raw) {
        String t = raw.trim();
        if (t.isEmpty()) t = "呢件事";
        List<String> s = new ArrayList<>();
        s.add("望住「" + t + "」，吸一啖氣");
        s.add("開始做「" + t + "」嘅頭一步");
        s.add("完成「" + t + "」");
        return s;
    }

    private void showPopup() {
        if (popup != null) return;
        popup = LayoutInflater.from(ctx).inflate(R.layout.nudge_popup, null);
        tvPhrase = popup.findViewById(R.id.tvNudgePhrase);
        btnDone = popup.findViewById(R.id.btnNudgeDone);
        btnCancel = popup.findViewById(R.id.btnNudgeCancel);
        btnDone.setText("✅ 做咗呢步");
        btnDone.setOnClickListener(v -> onStepDone());
        btnCancel.setOnClickListener(v -> end(false));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(160);
        wm.addView(popup, lp);
    }

    private void hidePopup() {
        if (popup != null) {
            try { wm.removeView(popup); } catch (Exception ignored) {}
            popup = null;
            tvPhrase = null;
            btnDone = null;
            btnCancel = null;
        }
    }

    private void nextNudge() {
        if (!running) return;
        stepAttempt++;
        if (stepAttempt > MAX_PER_STEP) {
            advanceStep(); // 呢步推咗三次都冇郁，唔逼，自動去下一步
            return;
        }
        callback.onStatus("諗緊點提你…");
        pool.execute(() -> {
            final String phrase = genPhrase();
            ui.post(() -> {
                if (!running) return;
                showProgress(phrase);
                VoicePlayer.speak(ctx, phrase);
                ticker = NudgeManager.this::nextNudge;
                ui.postDelayed(ticker, INTERVAL_MS);
            });
        });
    }

    private void showProgress(String phrase) {
        if (tvPhrase == null) return;
        String cur = steps.isEmpty() ? task : steps.get(stepIdx);
        if (steps.size() > 1) {
            tvPhrase.setText("第 " + (stepIdx + 1) + "/" + steps.size() + " 步：「" + cur + "」\n" + phrase);
        } else {
            tvPhrase.setText("「" + cur + "」：" + phrase);
        }
    }

    /** ✅ 做咗呢步：讚一句，推下一步；最後一步完成就慶祝。 */
    private void onStepDone() {
        if (!running) return;
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        String cur = steps.isEmpty() ? task : steps.get(stepIdx);
        if (stepIdx >= steps.size() - 1) {
            end(true);
            return;
        }
        VoicePlayer.speak(ctx, STEP_PRAISE[stepIdx % STEP_PRAISE.length]);
        stepIdx++;
        stepAttempt = 0;
        callback.onStatus("下一步：「" + (steps.isEmpty() ? task : steps.get(stepIdx)) + "」");
        nextNudge();
    }

    /** 推咗幾次都冇郁：自動去下一步（最後一步就溫和結束）。 */
    private void advanceStep() {
        if (!running) return;
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        if (stepIdx >= steps.size() - 1) {
            end(false);
            return;
        }
        stepIdx++;
        stepAttempt = 0;
        callback.onStatus("冇所謂，想嘅時候再嚟——而家試下：「" + (steps.isEmpty() ? task : steps.get(stepIdx)) + "」");
        nextNudge();
    }

    private String genPhrase() {
        String cur = steps.isEmpty() ? task : steps.get(stepIdx);
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("api_key", "");
        if (!key.isEmpty()) {
            try {
                String sys = "你係「推動力助手」。用戶而家喺度做緊：「" + cur + "」（成個任務係「" + task + "」）。"
                        + "生成一句廣東話催促語：15個字以內、溫暖、有變化、具體、可以幽默；"
                        + "唔好重複以下已用過嘅句子：" + (usedPhrases.isEmpty() ? "（未有）" : String.join("；", usedPhrases))
                        + "；唔好嚴厲、唔好鬧、唔好加引號、唔好用「你應該」。只輸出嗰一句。";
                String out = DeepSeekClient.chat(
                        p.getString("base_url", "https://api.deepseek.com"),
                        key, p.getString("model", "deepseek-chat"),
                        sys, "請生成第" + (stepIdx + 1) + "步第" + stepAttempt + "句。").trim();
                if (!out.isEmpty() && out.length() <= 30) {
                    usedPhrases.add(out);
                    return out;
                }
            } catch (Exception ignored) {}
        }
        String fallback = String.format(FALLBACK[(stepAttempt - 1) % FALLBACK.length], cur);
        usedPhrases.add(fallback);
        return fallback;
    }

    private void end(boolean done) {
        running = false;
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        hidePopup();
        if (done) VoicePlayer.speak(ctx, "好嘢！做咗「" + task + "」，你話到做到！");
        callback.onNudgeEnd(task, done);
    }

    public void stop() {
        running = false;
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        hidePopup();
    }

    public void destroy() {
        stop();
        pool.shutdownNow();
    }

    private int dp(float v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v);
    }
}
