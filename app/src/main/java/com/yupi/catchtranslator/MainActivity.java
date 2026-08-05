package com.yupi.catchtranslator;

import android.app.Activity;
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

/** 主頁：設定 API key + 開關懸浮按鈕 + 睇記錄。 */
public class MainActivity extends Activity {

    private EditText etKey, etModel, etBase;
    private TextView tvStatus, tvRecords, tvSummary, tvStats;
    private Spinner spEdgeVoice, spEdgeStyle;
    private LinearLayout llEdge;
    private static final String[] EDGE_VOICE_VALUES = {"hk-f", "hk-m", "cn"};
    private static final String[] EDGE_STYLE_VALUES = {"friendly", "", "cheerful", "serious"};
    private Button btnStart;
    private RadioGroup rgSize, rgVoice, rgSpeed;
    private CheckBox cbSummary, cbNarration;
    private EditText etMiniMaxKey;
    private Spinner spMiniMaxVoice, spMiniMaxModel, spMiniMaxEmotion;
    private LinearLayout llMiniMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        etBase = findViewById(R.id.etBaseUrl);
        tvStatus = findViewById(R.id.tvOverlayStatus);
        tvRecords = findViewById(R.id.tvRecords);
        tvSummary = findViewById(R.id.tvSummary);
        tvStats = findViewById(R.id.tvStats);
        btnStart = findViewById(R.id.btnStart);
        rgSize = findViewById(R.id.rgSize);
        rgVoice = findViewById(R.id.rgVoice);
        rgSpeed = findViewById(R.id.rgSpeed);
        cbSummary = findViewById(R.id.cbSummary);
        cbNarration = findViewById(R.id.cbNarration);
        etMiniMaxKey = findViewById(R.id.etMiniMaxKey);
        spMiniMaxVoice = findViewById(R.id.spMiniMaxVoice);
        spMiniMaxModel = findViewById(R.id.spMiniMaxModel);
        spMiniMaxEmotion = findViewById(R.id.spMiniMaxEmotion);
        llMiniMax = findViewById(R.id.llMiniMax);
        llEdge = findViewById(R.id.llEdge);
        spEdgeVoice = findViewById(R.id.spEdgeVoice);
        spEdgeStyle = findViewById(R.id.spEdgeStyle);
        ArrayAdapter<String> mmAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MiniMaxTts.VOICE_LABELS);
        mmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMiniMaxVoice.setAdapter(mmAdapter);
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

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnTest).setOnClickListener(v -> test());
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
        findViewById(R.id.btnVoiceTest).setOnClickListener(v -> {
            save(); // 先儲存再試聽，唔使怕漏撳儲存掣
            VoicePlayer.speak(this, "你好，我係 YupiSaver。今日想試下呢把聲得唔得。");
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
        llMiniMax.setVisibility("minimax".equals(voice) ? View.VISIBLE : View.GONE);
        boolean edge = "edge-hk".equals(voice) || "edge-cn".equals(voice);
        llEdge.setVisibility(edge ? View.VISIBLE : View.GONE);
        spEdgeVoice.setSelection(indexOf(EDGE_VOICE_VALUES, p.getString("edge_voice", "hk-f")));
        spEdgeStyle.setSelection(indexOf(EDGE_STYLE_VALUES, p.getString("edge_style", "")));
        etMiniMaxKey.setText(p.getString("minimax_key", ""));
        int mmPos = 0;
        String mmVoice = p.getString("minimax_voice", MiniMaxTts.VOICE_IDS[0]);
        for (int i = 0; i < MiniMaxTts.VOICE_IDS.length; i++) {
            if (MiniMaxTts.VOICE_IDS[i].equals(mmVoice)) { mmPos = i; break; }
        }
        spMiniMaxVoice.setSelection(mmPos);
        int mmModelPos = 0;
        String mmModel = p.getString("minimax_model", MiniMaxTts.MODEL_IDS[0]);
        for (int i = 0; i < MiniMaxTts.MODEL_IDS.length; i++) {
            if (MiniMaxTts.MODEL_IDS[i].equals(mmModel)) { mmModelPos = i; break; }
        }
        spMiniMaxModel.setSelection(mmModelPos);
        int mmEmoPos = 0;
        String mmEmo = p.getString("minimax_emotion", "");
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
                .putString("minimax_key", etMiniMaxKey.getText().toString().trim())
                .putString("minimax_voice",
                        MiniMaxTts.VOICE_IDS[Math.max(0, spMiniMaxVoice.getSelectedItemPosition())])
                .putString("minimax_model",
                        MiniMaxTts.MODEL_IDS[Math.max(0, spMiniMaxModel.getSelectedItemPosition())])
                .putString("minimax_emotion",
                        MiniMaxTts.EMOTION_IDS[Math.max(0, spMiniMaxEmotion.getSelectedItemPosition())])
                .putString("edge_voice",
                        EDGE_VOICE_VALUES[Math.max(0, spEdgeVoice.getSelectedItemPosition())])
                .putString("edge_style",
                        EDGE_STYLE_VALUES[Math.max(0, spEdgeStyle.getSelectedItemPosition())])
                .putBoolean("narration_enabled", cbNarration.isChecked())
                .apply();
        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show();
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
