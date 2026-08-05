package com.yupi.catchtranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 懸浮按鈕服務：一個永遠喺螢幕上嘅圓掣，撳開個快捷面板——
 * 4 個 AI 生成按鈕 + 語音捕捉 + 打字捕捉。記錄全部存本地。
 * 交互：單撳開面板、雙撳／長撳直接語音捕捉；捕捉成功有音效＋振動＋圓掣✓反饋。
 */
public class FloatingService extends Service {

    public static final String CHANNEL_ID = "floating_channel";
    private static final String[] LOCALES = {"yue-Hant-HK", "zh-HK", "zh-CN", "zh"};

    private static final long DOUBLE_TAP_MS = 300;
    private static final long LONG_PRESS_MS = 550;
    private static final int[] MILESTONES = {10, 25, 50, 100, 200, 500};

    private WindowManager wm;
    private TextView circle;
    private View panel;
    private WindowManager.LayoutParams circleParams;
    private WindowManager.LayoutParams panelParams;
    private boolean panelOpen = false;

    private SpeechRecognizer sr;
    private boolean listening = false;
    private int srLocaleIdx = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView statusText;
    private EditText inputBox;
    private Button nudgeBtn;
    private LinearLayout llButtons;

    private NudgeManager nudge;
    private boolean pendingNudge = false;
    private LinearLayout llFollowup;
    private Button btnFollow1, btnFollow2;

    private float downX, downY, rawX, rawY;
    private boolean dragging = false;

    // 手勢狀態
    private long lastTapAt = 0;
    private boolean longPressFired = false;
    private final Runnable longPressRunnable = this::onLongPress;
    private final Runnable singleTapRunnable = this::togglePanel;

    private ToneGenerator tone;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(1, buildNotification());
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70); } catch (Exception ignored) {}
        refreshCircleFace();
        addCircle();
        regenerate();
        nudge = new NudgeManager(this, wm, nudgeCallback);
        ensureButtons();
        // 語音引擎失敗時話畀用戶知，唔好靜雞雞用系統聲
        VoicePlayer.setFallbackListener((engine, reason) ->
                setStatus(("edge-hk".equals(engine) || "edge-cn".equals(engine) ? "Edge" : "MiniMax")
                        + " 語音連唔到（" + (reason == null ? "網絡問題" : reason) + "），已用系統聲"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        closePanel();
        if (circle != null) {
            try { wm.removeView(circle); } catch (Exception ignored) {}
            circle = null;
        }
        stopListening();
        if (nudge != null) nudge.destroy();
        if (tone != null) { try { tone.release(); } catch (Exception ignored) {} }
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 通知 ----------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "捉翻譯官助手", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("懸浮按鈕背景服務");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YupiSaver")
                .setContentText("懸浮按鈕運行中——翻譯官一出聲就撳佢")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------- 懸浮圓掣 ----------

    private void addCircle() {
        circle = new TextView(this);
        circle.setText(normalFace());
        circle.setTextSize(16);
        circle.setGravity(Gravity.CENTER);
        circle.setTextColor(0xFFFFFFFF);
        circle.setBackgroundResource(R.drawable.circle_bg);
        circleParams = new WindowManager.LayoutParams(
                dp(42), dp(42),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        circleParams.gravity = Gravity.TOP | Gravity.START;
        circleParams.x = dp(16);
        circleParams.y = dp(400);
        circle.setOnTouchListener(circleTouch);
        wm.addView(circle, circleParams);
    }

    private String normalFace() {
        return todayHint() != null ? "🚩" : "🎧";
    }

    private void refreshCircleFace() {
        ui.post(() -> {
            if (circle != null) circle.setText(normalFace());
        });
    }

    /** 捕捉成功：音效＋振動＋圓掣彈出 ✓。 */
    private void feedbackOk() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90);
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(30);
                }
            }
        } catch (Exception ignored) {}
        flashCircle("✓");
    }

    private void flashCircle(String face) {
        ui.post(() -> {
            if (circle == null) return;
            circle.setText(face);
            circle.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
                    .withEndAction(() -> {
                        circle.setText(normalFace());
                        circle.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    }).start();
        });
    }

    private final View.OnTouchListener circleTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    rawX = circleParams.x;
                    rawY = circleParams.y;
                    dragging = false;
                    longPressFired = false;
                    ui.removeCallbacks(singleTapRunnable);
                    ui.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragging = true;
                        ui.removeCallbacks(longPressRunnable);
                        ui.removeCallbacks(singleTapRunnable);
                    }
                    if (dragging) {
                        circleParams.x = (int) (rawX + dx);
                        circleParams.y = (int) (rawY + dy);
                        try { wm.updateViewLayout(circle, circleParams); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(longPressRunnable);
                    if (longPressFired) {
                        longPressFired = false;
                        return true;
                    }
                    if (!dragging) {
                        long now = System.currentTimeMillis();
                        if (now - lastTapAt < DOUBLE_TAP_MS) {
                            // 雙撳 → 直接語音捕捉
                            ui.removeCallbacks(singleTapRunnable);
                            lastTapAt = 0;
                            startVoice();
                        } else {
                            // 單撳 → 延遲少少等雙撳，冇雙撳就開面板
                            lastTapAt = now;
                            ui.postDelayed(singleTapRunnable, DOUBLE_TAP_MS);
                        }
                    }
                    return true;
            }
            return false;
        }
    };

    /** 長撳 → 直接語音捕捉。 */
    private void onLongPress() {
        longPressFired = true;
        ui.removeCallbacks(singleTapRunnable);
        startVoice();
    }

    // ---------- 面板 ----------

    private void togglePanel() {
        if (panelOpen) closePanel();
        else openPanel();
    }

    private void openPanel() {
        LayoutInflater inf = LayoutInflater.from(this);
        panel = inf.inflate(R.layout.overlay_panel, null);
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        String size = getSharedPreferences("settings", MODE_PRIVATE).getString("panel_size", "large");
        panelParams.width = "small".equals(size) ? dp(280) : "medium".equals(size) ? dp(340) : dp(380);
        panelParams.y = dp(80);
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        wm.addView(panel, panelParams);
        panelOpen = true;
        ensureButtons();
        boolean needRegen = false;
        for (String b : currentButtons) {
            if ("…".equals(b)) { needRegen = true; break; }
        }
        wirePanel();
        renderButtons();
        if (needRegen) regenerate(); // 佔位未就緒（啱改數量／服務啱開）就自動生成
    }

    private void closePanel() {
        stopListening();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        panelOpen = false;
    }

    private void wirePanel() {
        llButtons = panel.findViewById(R.id.llButtons);
        statusText = panel.findViewById(R.id.tvStatus);
        inputBox = panel.findViewById(R.id.etInput);
        View close = panel.findViewById(R.id.btnClose);
        View refresh = panel.findViewById(R.id.btnRefresh);
        View mic = panel.findViewById(R.id.btnMic);
        View send = panel.findViewById(R.id.btnSend);

        close.setOnClickListener(v -> closePanel());
        refresh.setOnClickListener(v -> regenerate());
        mic.setOnClickListener(v -> startVoice());
        send.setOnClickListener(v -> {
            String t = inputBox.getText().toString().trim();
            if (!t.isEmpty()) {
                inputBox.setText("");
                record(t, "text");
            }
        });
        nudgeBtn = panel.findViewById(R.id.btnNudge);
        String hint = todayHint();
        if (hint != null) {
            nudgeBtn.setText("🚀 今日建議：「" + hint + "」");
        } else {
            nudgeBtn.setText("🚀 推動力：想做咩？講出嚟");
        }
        nudgeBtn.setOnClickListener(v -> onNudgeTap());
        llFollowup = panel.findViewById(R.id.llFollowup);
        btnFollow1 = panel.findViewById(R.id.btnFollow1);
        btnFollow2 = panel.findViewById(R.id.btnFollow2);
        hideFollowup();
        String base = counterLine() + (todayHint() != null
                ? "今日建議：「" + todayHint() + "」——撳🚀開始"
                : "翻譯官一出聲，就撳個掣捉住佢");
        setStatus(base);
    }

    /** 動態重建按鈕：兩粒一行，數量跟設定（4/8/10）。 */
    private void renderButtons() {
        if (panel == null || llButtons == null) return;
        llButtons.removeAllViews();
        int n = currentButtons.length;
        for (int i = 0; i < n; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            int end = Math.min(i + 2, n);
            for (int j = i; j < end; j++) {
                final String label = currentButtons[j];
                Button b = new Button(this);
                b.setText(label);
                b.setBackgroundResource(R.drawable.btn_bg);
                b.setTextSize(13);
                b.setTextColor(0xFF2E7D5B);
                b.setMinHeight(dp(46));
                b.setPadding(dp(4), 0, dp(4), 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (j > i) lp.setMarginStart(dp(6));
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> {
                    animateTap(v);
                    record(((TextView) v).getText().toString(), "button");
                });
                row.addView(b);
            }
            llButtons.addView(row);
        }
    }

    /** 按鈕數量：設定入面揀 4/8/10。 */
    private int buttonCount() {
        int n = getSharedPreferences("settings", MODE_PRIVATE).getInt("button_count", 4);
        return (n == 8 || n == 10) ? n : 4;
    }

    private void ensureButtons() {
        int n = buttonCount();
        if (currentButtons.length != n) {
            currentButtons = new String[n];
            for (int i = 0; i < n; i++) currentButtons[i] = "…";
        }
    }

    private String[] currentButtons = new String[4];

    private void setStatus(final String s) {
        ui.post(() -> {
            if (statusText != null) statusText.setText(s);
        });
    }

    // ---------- 捕捉記錄 ----------

    private void record(final String text, final String source) {
        if (pendingNudge && !source.equals("button")) {
            pendingNudge = false;
            startNudge(text);
            return;
        }
        feedbackOk();
        if (source.equals("button")) {
            new TranslatorDb(this).insert("按鈕", text, source);
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("last_button", text).apply();
            checkMilestone();
            setStatus(counterLine() + "已記低：「" + text + "」——跟住換新按鈕");
            pool.execute(() -> {
                final AiEngine.Response r = AiEngine.respond(this, text);
                ui.post(() -> {
                    VoicePlayer.speak(this, r.reply, r.emotion);
                    setStatus(counterLine() + "「" + text + "」｜" + r.reply);
                    applyButtons(r.buttons);
                    showFollowup(r.type, text);
                });
            });
        } else {
            // 文字/語音輸入：一次 AI 呼叫完成分類＋回應＋新按鈕
            setStatus("諗緊點回應…");
            pool.execute(() -> {
                final AiEngine.Response r = AiEngine.respond(this, text);
                final String ch = "critic".equals(r.type) ? "翻譯官"
                        : "guardian".equals(r.type) ? "看守"
                        : "stuck".equals(r.type) ? "冇力"
                        : "feeling".equals(r.type) ? "真我"
                        : "worth".equals(r.type) ? "自我懷疑"
                        : "文字";
                new TranslatorDb(this).insert(ch, text, source);
                checkMilestone();
                ui.post(() -> {
                    VoicePlayer.speak(this, r.reply, r.emotion);
                    setStatus(counterLine() + "已記低（" + ch + "）：「" + text + "」｜" + r.reply);
                    applyButtons(r.buttons);
                    showFollowup(r.type, text);
                });
            });
        }
    }

    /** 用 AI 一次過生成嘅新按鈕換走舊按鈕。 */
    private void applyButtons(List<String> buttons) {
        if (buttons == null || buttons.size() != buttonCount()) return;
        currentButtons = buttons.toArray(new String[0]);
        renderButtons();
    }

    /** 「今日第 N 次 · 連續 X 日」前綴，等用戶感受到累積。 */
    private String counterLine() {
        TranslatorDb db = new TranslatorDb(this);
        return "今日第 " + db.countToday() + " 次 · 連續 " + db.streakDays() + " 日 · ";
    }

    /** 里程碑：總捕捉次數到 10/25/50/100… 就慶祝一次。 */
    private void checkMilestone() {
        int total = new TranslatorDb(this).countAll();
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        int done = p.getInt("milestone", 0);
        for (int m : MILESTONES) {
            if (total >= m && done < m) {
                p.edit().putInt("milestone", m).apply();
                final String msg = pick(new String[]{
                        "里程碑！你已經捕捉咗 " + m + " 次——你越嚟越認得翻譯官喇。",
                        "第 " + m + " 次捕捉！呢個習慣開始成形喇。",
                        "已經記低咗 " + m + " 次。你唔係冇感覺，你係開始睇得清。"});
                VoicePlayer.speak(this, msg);
                setStatus("🎉 " + msg);
                break;
            }
        }
    }

    private void animateTap(final View v) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    // ---------- 跟進按鈕 ----------

    private void showFollowup(final String type, final String buttonText) {
        if (llFollowup == null) return;
        if ("critic".equals(type)) {
            btnFollow1.setText("🔨 駁返佢");
            btnFollow2.setText("📋 記低就算");
            btnFollow1.setOnClickListener(v -> counterArgument(buttonText));
            btnFollow2.setOnClickListener(v -> recordOnly(buttonText));
        } else if ("stuck".equals(type)) {
            btnFollow1.setText("✅ 郁咗一下");
            btnFollow2.setText("💤 唔郁都得");
            btnFollow1.setOnClickListener(v -> actionDone(buttonText));
            btnFollow2.setOnClickListener(v -> actionLater(buttonText));
        } else if ("feeling".equals(type)) {
            btnFollow1.setText("💬 講多啲");
            btnFollow2.setText("📋 記低就算");
            btnFollow1.setOnClickListener(v -> exploreMore(buttonText));
            btnFollow2.setOnClickListener(v -> recordOnly(buttonText));
        } else if ("guardian".equals(type) || "worth".equals(type)) {
            btnFollow1.setText("💬 講多啲");
            btnFollow2.setText("📋 記低就算");
            btnFollow1.setOnClickListener(v -> exploreMore(buttonText));
            btnFollow2.setOnClickListener(v -> recordOnly(buttonText));
        } else {
            hideFollowup();
            return;
        }
        llFollowup.setVisibility(View.VISIBLE);
    }

    private void hideFollowup() {
        if (llFollowup != null) llFollowup.setVisibility(View.GONE);
    }

    /** 🔨 駁返佢：AI 用真實記錄做證據，幫佢諗一句反駁。 */
    private void counterArgument(final String buttonText) {
        hideFollowup();
        setStatus("諗緊點駁…");
        pool.execute(() -> {
            String reply = AiEngine.oneLine(this,
                    "你係「YupiSaver」。用戶想駁返內在批判聲音一句：「" + buttonText + "」。\n"
                    + "參考佢嘅真實記錄（可能係證據）：\n" + AiEngine.recordsContext(this, 8)
                    + "\n用廣東話講一句15-30字嘅反駁：溫柔但有力、用真實記錄做證據、唔好攻擊自己、唔好用「你應該」。只輸出嗰一句。",
                    "幫我諗一句反駁。");
            final String r = reply;
            ui.post(() -> {
                new TranslatorDb(this).insert("反駁", buttonText + " → " + r, "followup");
                VoicePlayer.speak(this, r);
                setStatus("反駁：「" + r + "」");
            });
        });
    }

    /** ✅ 郁咗一下：記低，講句讚（唔逼，郁到就贏）。 */
    private void actionDone(final String buttonText) {
        hideFollowup();
        new TranslatorDb(this).insert("行動完成", buttonText, "followup");
        VoicePlayer.speak(this, pick(new String[]{
                "好，郁咗一下，話到做到！", "郁到就係贏！", "好，今次郁咗，下次都會郁到嘅。"}));
        setStatus("郁咗一下：「" + buttonText + "」");
    }

    /** 💤 唔郁都得：記低，唔逼。 */
    private void actionLater(final String buttonText) {
        hideFollowup();
        new TranslatorDb(this).insert("行動未做", buttonText, "followup");
        VoicePlayer.speak(this, pick(new String[]{
                "唔郁都得，想郁先郁。", "冇所謂，你肯認得嗰個看守就夠喇。"}));
        setStatus("記低咗：「" + buttonText + "」——唔郁都得");
    }

    /** 💬 講多啲：AI 問一條溫柔、具體嘅問題。 */
    private void exploreMore(final String buttonText) {
        hideFollowup();
        setStatus("諗緊問題…");
        pool.execute(() -> {
            String q = AiEngine.oneLine(this,
                    "你係「YupiSaver」。用戶撳咗「" + buttonText + "」，表示想探索呢個感受。\n"
                    + "用廣東話問一個溫柔、具體、容易答嘅問題（15-25字），關於身體感受或嗰刻嘅情況，唔好問「點解」。只輸出嗰條問題。",
                    "問我一條問題。");
            final String qq = q;
            ui.post(() -> {
                VoicePlayer.speak(this, qq);
                setStatus(qq + "（用🎤或者打字答我）");
            });
        });
    }

    /** 📋 記低就算：簡單確認。 */
    private void recordOnly(final String buttonText) {
        hideFollowup();
        VoicePlayer.speak(this, pick(new String[]{"好，記低咗。", "收到，我哋照樣記低。"}));
        setStatus("已記低：「" + buttonText + "」");
    }

    private static String pick(String[] arr) {
        return arr[(int) (System.currentTimeMillis() / 1000 % arr.length)];
    }

    // ---------- 今日建議（每日總結出嘅任務，撳🚀直接開始） ----------

    private String todayHint() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (!todayStr().equals(p.getString("next_task_date", ""))) return null;
        String t = p.getString("next_task", "");
        return t.isEmpty() ? null : t;
    }

    private void consumeHint() {
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit().remove("next_task").remove("next_task_date").apply();
        refreshCircleFace();
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void onNudgeTap() {
        String t = inputBox != null ? inputBox.getText().toString().trim() : "";
        if (!t.isEmpty()) {
            inputBox.setText("");
            startNudge(t);
        } else {
            String hint = todayHint();
            if (hint != null) {
                consumeHint();
                startNudge(hint);
            } else {
                pendingNudge = true;
                setStatus("🚀 講出你想做嘅嘢…（用🎤或者打字後撳送出）");
            }
        }
    }

    private void startNudge(String text) {
        if (nudge == null) nudge = new NudgeManager(this, wm, nudgeCallback);
        nudge.start(text);
    }

    private final NudgeManager.Callback nudgeCallback = new NudgeManager.Callback() {
        @Override
        public void onNudgeEnd(String task, boolean done) {
            new TranslatorDb(FloatingService.this).insert(done ? "推動完成" : "推動取消", task, "nudge");
            setStatus(done ? "好嘢！完成咗「" + task + "」🎉" : "冇所謂，想嘅時候再嚟：「" + task + "」");
        }

        @Override
        public void onStatus(String message) {
            setStatus(message);
        }
    };

    private void regenerate() {
        setStatus("諗緊新按鈕…");
        pool.execute(() -> {
            final List<String> btns2 = AiEngine.generateButtons(this);
            ui.post(() -> {
                currentButtons = btns2.toArray(new String[0]);
                renderButtons();
                setStatus(counterLine() + "按鈕已更新——翻譯官一出聲就撳");
            });
        });
    }

    // ---------- 語音捕捉 ----------

    private void startVoice() {
        if (listening) {
            stopListening();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("未授權錄音——請喺 App 主頁撳「允許錄音」");
            return;
        }
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(listener);
            srLocaleIdx = 0;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALES[0]);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LOCALES[0]);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sr.startListening(intent);
            listening = true;
            setStatus("🎤 聽緊…（講完自動停，再撳一次取消）");
        } catch (Exception e) {
            setStatus("語音用唔到（" + e.getMessage() + "）——用打字啦");
        }
    }

    private void stopListening() {
        listening = false;
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) {}
            try { sr.destroy(); } catch (Exception ignored) {}
            sr = null;
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            listening = false;
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && srLocaleIdx + 1 < LOCALES.length) {
                srLocaleIdx++;
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALES[srLocaleIdx]);
                    sr.startListening(intent);
                    listening = true;
                    return;
                } catch (Exception ignored) {}
            }
            setStatus("聽唔到（" + errName(error) + "）——可以打字");
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            List<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) {
                record(r.get(0), "voice");
            } else {
                setStatus("聽唔到內容——可以打字");
            }
        }

        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    private static String errName(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "聽唔清";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "冇出聲";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "辨識器忙緊";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "冇錄音權限";
            default: return "錯誤 " + e;
        }
    }

    // ---------- 工具 ----------

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
