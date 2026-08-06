package com.yupi.catchtranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import java.util.Map;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 主頁：設定 API key + 開關懸浮按鈕 + 睇記錄。 */
public class MainActivity extends Activity {

    private EditText etKey, etModel, etBase, etDebugToken, etDebugChatId;
    private TextView tvStatus, tvRecords, tvSummary, tvStats, tvLog;
    private Button btnLogToggle, btnSendLog;
    private boolean logVisible = false;
    private Spinner spEdgeVoice, spEdgeStyle;
    private LinearLayout llEdge;
    private static final String[] EDGE_VOICE_VALUES = {"hk-f", "hk-m", "cn"};
    private static final String[] EDGE_STYLE_VALUES = {"friendly", "", "cheerful", "serious"};
    private Button btnStart;
    private RadioGroup rgSize, rgVoice, rgSpeed, rgBtnCount;
    private CheckBox cbSummary, cbNarration, cbThinking;
    private EditText etMiniMaxKey;
    private Spinner spMiniMaxVoice, spMiniMaxModel, spMiniMaxEmotion;
    private LinearLayout llMiniMax;
    private Button btnDesignVoice;
    private TextView tvVoiceDesignStatus;
    private final List<String> miniMaxVoiceIds = new ArrayList<>();
    private final List<String> miniMaxVoiceLabels = new ArrayList<>();
    private ArrayAdapter<String> miniMaxVoiceAdapter;
    private int emotionDemoIndex = 0;

    private static final String[] EMOTION_DEMO_IDS = {
            "calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "fluent"
    };
    private static final String[] EMOTION_DEMO_LABELS = {
            "平靜", "開心", "傷感", "堅定保護", "害怕", "厭惡", "驚訝", "自然敘述"
    };
    private static final String[] EMOTION_DEMO_TEXTS = {
            "唔使心急，我喺度陪住你，慢慢講就得。",
            "好嘢！你做得到，今次真係值得替自己開心！",
            "我知道你已經好攰、好辛苦，唔需要再硬撐。",
            "夠喇，翻譯官唔准再搶咪，呢句根本唔代表你。",
            "我知道你而家好驚，心口好實，但你唔係一個人。",
            "呢種剝奪你快樂嘅做法真係好離譜，唔值得你再跟。",
            "吓？原來你已經行咗咁遠，連自己都未發現！",
            "而家我哋先停一停，再慢慢睇清楚發生緊乜。"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        etBase = findViewById(R.id.etBaseUrl);
        etDebugToken = findViewById(R.id.etDebugToken);
        etDebugChatId = findViewById(R.id.etDebugChatId);
        tvStatus = findViewById(R.id.tvOverlayStatus);
        tvRecords = findViewById(R.id.tvRecords);
        tvSummary = findViewById(R.id.tvSummary);
        tvStats = findViewById(R.id.tvStats);
        btnStart = findViewById(R.id.btnStart);
        rgSize = findViewById(R.id.rgSize);
        rgVoice = findViewById(R.id.rgVoice);
        rgBtnCount = findViewById(R.id.rgBtnCount);
        rgSpeed = findViewById(R.id.rgSpeed);
        cbSummary = findViewById(R.id.cbSummary);
        cbNarration = findViewById(R.id.cbNarration);
        cbThinking = findViewById(R.id.cbThinking);
        etMiniMaxKey = findViewById(R.id.etMiniMaxKey);
        spMiniMaxVoice = findViewById(R.id.spMiniMaxVoice);
        spMiniMaxModel = findViewById(R.id.spMiniMaxModel);
        spMiniMaxEmotion = findViewById(R.id.spMiniMaxEmotion);
        llMiniMax = findViewById(R.id.llMiniMax);
        btnDesignVoice = findViewById(R.id.btnDesignVoice);
        tvVoiceDesignStatus = findViewById(R.id.tvVoiceDesignStatus);
        llEdge = findViewById(R.id.llEdge);
        spEdgeVoice = findViewById(R.id.spEdgeVoice);
        spEdgeStyle = findViewById(R.id.spEdgeStyle);
        miniMaxVoiceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, miniMaxVoiceLabels);
        miniMaxVoiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMiniMaxVoice.setAdapter(miniMaxVoiceAdapter);
        ArrayAdapter<String> mmModelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MiniMaxTts.MODEL_LABELS);
        mmModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMiniMaxModel.setAdapter(mmModelAdapter);
        ArrayAdapter<String> mmEmotionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MiniMaxTts.EMOTION_LABELS);
        mmEmotionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMiniMaxEmotion.setAdapter(mmEmotionAdapter);
        ArrayAdapter<String> evAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"女聲·曉佳（預設）", "男聲·雲龍", "普通話·曉曉"});
        evAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEdgeVoice.setAdapter(evAdapter);
        ArrayAdapter<String> esAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"友好（預設）", "自然", "開朗", "認真"});
        esAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEdgeStyle.setAdapter(esAdapter);

        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        etKey.setText(p.getString("api_key", ""));
        etModel.setText(p.getString("model", "deepseek-chat"));
        etBase.setText(p.getString("base_url", "https://api.deepseek.com"));
        etDebugToken.setText(p.getString("debug_token", ""));
        etDebugChatId.setText(p.getString("debug_chat_id", ""));

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        btnLogToggle = findViewById(R.id.btnLogToggle);
        btnSendLog = findViewById(R.id.btnSendLog);
        tvLog = findViewById(R.id.tvLog);
        btnLogToggle.setOnClickListener(v -> {
            logVisible = !logVisible;
            tvLog.setVisibility(logVisible ? View.VISIBLE : View.GONE);
            if (logVisible) tvLog.setText(DebugLog.dump());
            btnLogToggle.setText(logVisible ? "📋 收埋 Log" : "📋 睇 AI Log");
        });
        btnSendLog.setOnClickListener(v -> {
            if (etDebugToken.getText().toString().trim().isEmpty()
                    || etDebugChatId.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "請先喺設定填 TG Bot Token 同 Chat ID", Toast.LENGTH_LONG).show();
                return;
            }
            save();
            Toast.makeText(this, "發送緊 AI Log 去 Telegram…", Toast.LENGTH_SHORT).show();
            DebugLog.sendToTelegram(this, () -> runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "已發送 ✅", Toast.LENGTH_SHORT).show()));
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> test());
        btnDesignVoice.setOnClickListener(v -> showVoiceDesignDialog());
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putBoolean("floating_enabled", false).apply();
            stopService(new Intent(this, FloatingService.class));
        });
        btnStart.setOnClickListener(v -> startFloating());
        findViewById(R.id.btnPerm).setOnClickListener(v -> openOverlaySettings());

        rgSize.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rbSizeSmall ? "small" : id == R.id.rbSizeMedium ? "medium" : "large";
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("panel_size", v).apply();
        });
        rgVoice.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rbVoiceEdgeHk ? "edge-hk"
                    : id == R.id.rbVoiceEdgeCn ? "edge-cn"
                    : id == R.id.rbVoiceMiniMax ? "minimax" : "system";
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("voice_engine", v).apply();
            llMiniMax.setVisibility(id == R.id.rbVoiceMiniMax ? View.VISIBLE : View.GONE);
            boolean edge = id == R.id.rbVoiceEdgeHk || id == R.id.rbVoiceEdgeCn;
            llEdge.setVisibility(edge ? View.VISIBLE : View.GONE);
        });
        cbSummary.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE)
                        .edit().putBoolean("summary_enabled", checked).apply());
        cbNarration.setChecked(p.getBoolean("narration_enabled", false));
        cbNarration.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE)
                        .edit().putBoolean("narration_enabled", checked).apply());
        cbThinking.setChecked(p.getBoolean("thinking_enabled", true));
        cbThinking.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE)
                        .edit().putBoolean("thinking_enabled", checked).apply());
        findViewById(R.id.btnVoiceTest).setOnClickListener(v -> {
            save(); // 先儲存再試聽，唔使怕漏撳儲存掣
            if (rgVoice.getCheckedRadioButtonId() == R.id.rbVoiceMiniMax
                    && "auto".equals(selectedMiniMaxEmotionMode())) {
                int i = emotionDemoIndex++ % EMOTION_DEMO_IDS.length;
                Toast.makeText(this, "自動情感試聽：" + EMOTION_DEMO_LABELS[i], Toast.LENGTH_SHORT).show();
                VoicePlayer.speak(this, EMOTION_DEMO_TEXTS[i], EMOTION_DEMO_IDS[i], null);
            } else {
                VoicePlayer.speak(this, "你好，我係 YupiSaver。今日想試下呢把聲得唔得。");
            }
        });
        rgSpeed.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rbSpeedSlow ? "-10"
                    : id == R.id.rbSpeedFast ? "20"
                    : id == R.id.rbSpeedVeryFast ? "40" : "0";
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("voice_rate", v).apply();
        });

        DailySummary.schedule(this);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.RECORD_AUDIO}, 1);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean ok = Settings.canDrawOverlays(this);
        tvStatus.setText(ok ? "懸浮權限：已開啟 ✅" : "懸浮權限：未開啟 ❌（撳下面掣去開）");
        btnStart.setEnabled(ok);
        tvRecords.setText(new TranslatorDb(this).dump());
        renderStats();

        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        String size = p.getString("panel_size", "large");
        rgSize.check("small".equals(size) ? R.id.rbSizeSmall
                : "medium".equals(size) ? R.id.rbSizeMedium : R.id.rbSizeLarge);
        String voice = p.getString("voice_engine", "edge-hk");
        rgVoice.check("edge-hk".equals(voice) ? R.id.rbVoiceEdgeHk
                : "edge-cn".equals(voice) ? R.id.rbVoiceEdgeCn
                : "minimax".equals(voice) ? R.id.rbVoiceMiniMax : R.id.rbVoiceSystem);
        int btnCount = p.getInt("button_count", 4);
        rgBtnCount.check(btnCount == 8 ? R.id.rbBtn8 : btnCount == 10 ? R.id.rbBtn10 : R.id.rbBtn4);
        rgBtnCount.setOnCheckedChangeListener((g, id) -> {
            int n = id == R.id.rbBtn8 ? 8 : id == R.id.rbBtn10 ? 10 : 4;
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putInt("button_count", n).apply();
            Toast.makeText(MainActivity.this, "按鈕數量改為 " + n + " 個——下次打開面板自動生成", Toast.LENGTH_LONG).show();
        });
        llMiniMax.setVisibility("minimax".equals(voice) ? View.VISIBLE : View.GONE);
        boolean edge = "edge-hk".equals(voice) || "edge-cn".equals(voice);
        llEdge.setVisibility(edge ? View.VISIBLE : View.GONE);
        spEdgeVoice.setSelection(indexOf(EDGE_VOICE_VALUES, p.getString("edge_voice", "hk-f")));
        spEdgeStyle.setSelection(indexOf(EDGE_STYLE_VALUES, p.getString("edge_style", "")));
        etMiniMaxKey.setText(p.getString("minimax_key", ""));
        String mmVoice = p.getString("minimax_voice", MiniMaxTts.VOICE_IDS[0]);
        reloadMiniMaxVoices(p);
        spMiniMaxVoice.setSelection(Math.max(0, miniMaxVoiceIds.indexOf(mmVoice)));
        int mmModelPos = 0;
        String mmModel = p.getString("minimax_model", MiniMaxTts.MODEL_IDS[0]);
        for (int i = 0; i < MiniMaxTts.MODEL_IDS.length; i++) {
            if (MiniMaxTts.MODEL_IDS[i].equals(mmModel)) { mmModelPos = i; break; }
        }
        spMiniMaxModel.setSelection(mmModelPos);
        int mmEmoPos = 0;
        String mmEmo = p.contains("minimax_emotion_mode")
                ? p.getString("minimax_emotion_mode", "auto")
                : (p.getString("minimax_emotion", "").isEmpty()
                ? "auto" : p.getString("minimax_emotion", ""));
        for (int i = 0; i < MiniMaxTts.EMOTION_IDS.length; i++) {
            if (MiniMaxTts.EMOTION_IDS[i].equals(mmEmo)) { mmEmoPos = i; break; }
        }
        spMiniMaxEmotion.setSelection(mmEmoPos);
        String rate = p.getString("voice_rate", "0");
        rgSpeed.check("-10".equals(rate) ? R.id.rbSpeedSlow
                : "20".equals(rate) ? R.id.rbSpeedFast
                : "40".equals(rate) ? R.id.rbSpeedVeryFast : R.id.rbSpeedNormal);
        cbSummary.setChecked(p.getBoolean("summary_enabled", true));

        String summary = new TranslatorDb(this).latestSummary();
        tvSummary.setText(summary == null ? "（未有總結——聽日 00:01 自動出第一份）" : summary);
    }

    /** 儀錶盤：連續日數、今日/總計捕捉、近7日類型分佈、行動完成率。 */
    private void renderStats() {
        TranslatorDb db = new TranslatorDb(this);
        int today = db.countToday();
        int all = db.countAll();
        int streak = db.streakDays();
        Map<String, Integer> m = db.channelCounts(7);
        int done = m.getOrDefault("行動完成", 0) + m.getOrDefault("推動完成", 0);
        int skip = m.getOrDefault("行動未做", 0) + m.getOrDefault("推動取消", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 連續使用 ").append(streak).append(" 日　·　今日捕捉 ").append(today)
                .append(" 次　·　總共 ").append(all).append(" 次\n");
        sb.append("近7日：「翻譯官」").append(m.getOrDefault("翻譯官", 0))
                .append(" 次　「真我」").append(m.getOrDefault("真我", 0))
                .append(" 次　「行動」").append(m.getOrDefault("行動", 0))
                .append(" 次　反駁 ").append(m.getOrDefault("反駁", 0)).append(" 次\n");
        if (done + skip > 0) {
            sb.append("行動完成率：").append(Math.round(done * 100.0 / (done + skip)))
                    .append("%（完成 ").append(done).append(" / ").append(done + skip).append("）");
        }
        tvStats.setText(sb.toString());
    }

    private void save() {
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putString("api_key", etKey.getText().toString().trim())
                .putString("model", etModel.getText().toString().trim())
                .putString("base_url", etBase.getText().toString().trim())
                .putString("debug_token", etDebugToken.getText().toString().trim())
                .putString("debug_chat_id", etDebugChatId.getText().toString().trim())
                .putString("minimax_key", etMiniMaxKey.getText().toString().trim())
                .putString("minimax_voice",
                        selectedMiniMaxVoiceId())
                .putString("minimax_model",
                        MiniMaxTts.MODEL_IDS[Math.max(0, spMiniMaxModel.getSelectedItemPosition())])
                .putString("minimax_emotion_mode", selectedMiniMaxEmotionMode())
                .putString("minimax_emotion", legacyMiniMaxEmotionValue())
                .putString("edge_voice",
                        EDGE_VOICE_VALUES[Math.max(0, spEdgeVoice.getSelectedItemPosition())])
                .putString("edge_style",
                        EDGE_STYLE_VALUES[Math.max(0, spEdgeStyle.getSelectedItemPosition())])
                .putBoolean("narration_enabled", cbNarration.isChecked())
                .putBoolean("thinking_enabled", cbThinking.isChecked())
                .apply();
        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show();
    }

    private String selectedMiniMaxVoiceId() {
        int position = spMiniMaxVoice.getSelectedItemPosition();
        if (position >= 0 && position < miniMaxVoiceIds.size()) {
            return miniMaxVoiceIds.get(position);
        }
        return MiniMaxTts.VOICE_IDS[0];
    }

    private String selectedMiniMaxEmotionMode() {
        int position = spMiniMaxEmotion.getSelectedItemPosition();
        if (position >= 0 && position < MiniMaxTts.EMOTION_IDS.length) {
            return MiniMaxTts.EMOTION_IDS[position];
        }
        return "auto";
    }

    /** 保留舊 key 兼容其他已安裝版本；auto 對舊版等同自然。 */
    private String legacyMiniMaxEmotionValue() {
        String mode = selectedMiniMaxEmotionMode();
        return "auto".equals(mode) ? "" : mode;
    }

    private void reloadMiniMaxVoices(SharedPreferences p) {
        miniMaxVoiceIds.clear();
        miniMaxVoiceLabels.clear();
        for (int i = 0; i < MiniMaxTts.VOICE_IDS.length; i++) {
            miniMaxVoiceIds.add(MiniMaxTts.VOICE_IDS[i]);
            miniMaxVoiceLabels.add(MiniMaxTts.VOICE_LABELS[i]);
        }
        try {
            JSONArray saved = new JSONArray(p.getString("minimax_designed_voices", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                String name = item.optString("name", "").trim();
                if (id.isEmpty() || miniMaxVoiceIds.contains(id)) continue;
                miniMaxVoiceIds.add(id);
                miniMaxVoiceLabels.add(name.isEmpty() ? "自訂聲線" : "自訂 · " + name);
            }
        } catch (Exception ignored) {
            // 舊設定或損壞資料不影響四把內置聲線。
        }
        miniMaxVoiceAdapter.notifyDataSetChanged();
    }

    private void showVoiceDesignDialog() {
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);

        EditText prompt = new EditText(this);
        prompt.setHint("例如：香港年輕女生，聲音甜美自然、有親和力，不要播音腔");
        prompt.setMinLines(3);
        prompt.setMaxLines(5);
        box.addView(prompt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText preview = new EditText(this);
        preview.setHint("粵語試聽文字");
        preview.setText("你好呀，今日過得點？唔使心急，慢慢講畀我聽。");
        preview.setMinLines(2);
        preview.setMaxLines(4);
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("生成後會再用目前模型合成一次試聽，以正式啟用聲線。MiniMax 會收取音色設計及合成費用。");
        note.setTextSize(12);
        note.setTextColor(0xFF7FA88F);
        note.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("設計 MiniMax 粵語聲線")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("生成並試聽", (dialog, which) ->
                        startVoiceDesign(prompt.getText().toString(), preview.getText().toString()))
                .show();
    }

    private void startVoiceDesign(String prompt, String previewText) {
        final String key = etMiniMaxKey.getText().toString().trim();
        final String description = prompt == null ? "" : prompt.trim();
        final String sample = previewText == null ? "" : previewText.trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "請先填 MiniMax API Key", Toast.LENGTH_LONG).show();
            return;
        }
        if (description.isEmpty() || sample.isEmpty()) {
            Toast.makeText(this, "聲線描述和粵語試聽文字都要填", Toast.LENGTH_LONG).show();
            return;
        }
        if (description.length() > 500 || sample.length() > 500) {
            Toast.makeText(this, "描述和試聽文字最多各 500 字", Toast.LENGTH_LONG).show();
            return;
        }

        btnDesignVoice.setEnabled(false);
        tvVoiceDesignStatus.setText("正在設計聲線，通常需要十幾秒…");
        final String model = MiniMaxTts.MODEL_IDS[Math.max(0, spMiniMaxModel.getSelectedItemPosition())];
        final String emotion = AiEngine.resolveVoiceEmotion(
                sample, null, selectedMiniMaxEmotionMode());
        final String rate = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("voice_rate", "0");

        new Thread(() -> {
            File previewFile = null;
            String designedVoiceId = null;
            try {
                designedVoiceId = MiniMaxTts.designVoice(key, description, sample);
                rememberDesignedVoice(description, designedVoiceId);

                previewFile = File.createTempFile("voice_design_", ".mp3", getCacheDir());
                MiniMaxTts.synthesize(key, sample, designedVoiceId, model, rate,
                        emotion, null, previewFile);
                File finalPreviewFile = previewFile;
                String finalDesignedVoiceId = designedVoiceId;
                runOnUiThread(() -> {
                    selectDesignedVoice(finalDesignedVoiceId, key);
                    btnDesignVoice.setEnabled(true);
                    tvVoiceDesignStatus.setText("已生成並啟用 ✅ 正在播放試聽");
                    VoicePlayer.playTemporaryFile(this, finalPreviewFile, sample, rate);
                });
            } catch (Exception e) {
                if (previewFile != null && previewFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    previewFile.delete();
                }
                String finalDesignedVoiceId = designedVoiceId;
                runOnUiThread(() -> {
                    if (finalDesignedVoiceId != null) selectDesignedVoice(finalDesignedVoiceId, key);
                    btnDesignVoice.setEnabled(true);
                    tvVoiceDesignStatus.setText(finalDesignedVoiceId == null
                            ? "生成失敗 ❌" : "聲線已生成，但啟用試聽失敗 ⚠️");
                    Toast.makeText(this, "MiniMax：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void rememberDesignedVoice(String description, String voiceId) throws Exception {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        JSONArray saved;
        try {
            saved = new JSONArray(p.getString("minimax_designed_voices", "[]"));
        } catch (Exception e) {
            saved = new JSONArray();
        }
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item != null && voiceId.equals(item.optString("id"))) return;
        }
        JSONObject item = new JSONObject();
        item.put("id", voiceId);
        item.put("name", shortVoiceName(description));
        saved.put(item);
        p.edit().putString("minimax_designed_voices", saved.toString()).apply();
    }

    private void selectDesignedVoice(String voiceId, String key) {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        p.edit()
                .putString("minimax_key", key)
                .putString("minimax_voice", voiceId)
                .putString("voice_engine", "minimax")
                .apply();
        reloadMiniMaxVoices(p);
        spMiniMaxVoice.setSelection(Math.max(0, miniMaxVoiceIds.indexOf(voiceId)));
        rgVoice.check(R.id.rbVoiceMiniMax);
    }

    private static String shortVoiceName(String description) {
        String oneLine = description.replace('\n', ' ').trim();
        return oneLine.length() <= 18 ? oneLine : oneLine.substring(0, 18) + "…";
    }

    private void test() {
        save();
        final String key = etKey.getText().toString().trim();
        final String model = etModel.getText().toString().trim();
        final String base = etBase.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "未填 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "測試連線中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String out = DeepSeekClient.chat(base, key, model,
                        "你係連線測試。", "請只回應：OK");
                runOnUiThread(() -> Toast.makeText(this,
                        "連線成功 ✅ 模型回應：" + out.trim(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "連線失敗 ❌ " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putBoolean("floating_enabled", true).apply();
        Intent i = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        Toast.makeText(this, "懸浮按鈕已開啟 🎧", Toast.LENGTH_SHORT).show();
    }

    private void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }
}
