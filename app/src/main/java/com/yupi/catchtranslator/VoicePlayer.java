package com.yupi.catchtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 語音播放：支援系統 TTS（離線）＋ Microsoft Edge 神經語音（真·廣東話/普通話，最自然）。
 * Edge 失敗（冇網絡等）會自動 fallback 去系統 TTS。
 */
public class VoicePlayer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static TextToSpeech tts;
    private static boolean ttsReady = false;
    private static boolean legacyCacheCleared = false;

    /** 語音引擎失敗回退系統聲時通知 UI（等用戶知道唔係設定冇用）。 */
    public interface FallbackListener {
        void onFallback(String engine, String reason);
    }

    private static FallbackListener fallbackListener;

    public static void setFallbackListener(FallbackListener l) {
        fallbackListener = l;
    }

    private static void notifyFallback(String engine, String reason) {
        final FallbackListener l = fallbackListener;
        if (l != null) {
            MAIN.post(() -> l.onFallback(engine, reason));
        }
    }

    public static void speak(final Context ctx, final String text) {
        speak(ctx, text, null);
    }

    public static void speak(final Context ctx, final String text, final String emotionOverride) {
        speak(ctx, text, emotionOverride, null);
    }

    /**
     * emotionOverride：AI 根據回應內容揀嘅語氣；自動模式會先採用有效 AI 情感，
     * 再用本地內容判斷兜底。手動模式則固定使用設定值。
     * tag：句內語氣標籤（laughs/sighs/gasps/emm…，speech-2.8 專用）。Edge／系統 TTS 唔支持，會自動忽略。
     */
    public static void speak(final Context ctx, final String text, final String emotionOverride, final String tag) {
        if (text == null || text.isEmpty()) return;
        clearLegacyCacheOnce(ctx);
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String engine = p.getString("voice_engine", "system");
        final String ratePct = p.getString("voice_rate", "0");
        DebugLog.add("TTS", "speak: engine=" + engine + " | len=" + text.length()
                + " | emotion=" + emotionOverride + " | tag=" + tag);
        if ("edge-hk".equals(engine) || "edge-cn".equals(engine)) {
            final String edgeVoice = p.getString("edge_voice", "hk-f");
            final String voice = "hk-m".equals(edgeVoice) ? EdgeTts.VOICE_HK_M
                    : "cn".equals(edgeVoice) ? EdgeTts.VOICE_CN : EdgeTts.VOICE_HK;
            final String pitch = edgePitch(p.getString("edge_style", ""));
            POOL.execute(() -> {
                File out = null;
                try {
                    out = freshAudioFile(ctx);
                    DebugLog.add("TTS", "Edge 重新合成中: " + truncate(text, 50));
                    EdgeTts.synthesize(text, voice, ratePct, pitch, out);
                    final File f = out;
                    MAIN.post(() -> playFile(ctx, f, text, ratePct));
                } catch (Exception e) {
                    deleteQuietly(out);
                    DebugLog.add("TTS", "Edge 失敗: " + truncate(e.getMessage(), 100));
                    if (tryMiniMax(ctx, p, text, emotionOverride, tag, ratePct)) return; // 回退鏈
                    notifyFallback(engine, e.getMessage());
                    playSystem(ctx, text, ratePct);
                }
            });
        } else if ("minimax".equals(engine)) {
            final String mmKey = p.getString("minimax_key", "");
            final String mmVoice = p.getString("minimax_voice", MiniMaxTts.VOICE_IDS[0]);
            final String mmModel = p.getString("minimax_model", MiniMaxTts.MODEL_IDS[0]);
            final String mmEmotionMode = emotionMode(p);
            final String mmEmotion = AiEngine.resolveVoiceEmotion(
                    text, emotionOverride, mmEmotionMode);
            DebugLog.add("TTS", "MiniMax 情感: mode=" + mmEmotionMode + " | resolved=" + mmEmotion);
            POOL.execute(() -> {
                String finalText = MiniMaxTts.applyTag(text, tag);
                File out = null;
                try {
                    out = freshAudioFile(ctx);
                    DebugLog.add("TTS", "MiniMax 重新合成中: " + truncate(finalText, 50));
                    MiniMaxTts.synthesize(mmKey, finalText, mmVoice, mmModel, ratePct, mmEmotion, null, out);
                    final File f = out;
                    MAIN.post(() -> playFile(ctx, f, finalText, ratePct));
                } catch (Exception e) {
                    deleteQuietly(out);
                    DebugLog.add("TTS", "MiniMax 失敗: " + truncate(e.getMessage(), 100));
                    notifyFallback(engine, e.getMessage());
                    playSystem(ctx, text, ratePct);
                }
            });
        } else {
            playSystem(ctx, text, ratePct);
        }
    }

    /** 免費 Edge 接口唔支援語氣風格，用音調微調模擬：友好高少少、開朗再高、認真低沉。 */
    private static String edgePitch(String style) {
        if ("cheerful".equals(style)) return "+10%";
        if ("friendly".equals(style)) return "+6%";
        if ("serious".equals(style)) return "-8%";
        return "+0%";
    }

    /**
     * 回退鏈：Edge 失敗嗰陣試 MiniMax（有 key 先用）。成功 return true。
     * 因為系統 TTS 好多時冇粵語／未就緒，靜音就係咁嚟——所以中間加多一層。
     */
    private static boolean tryMiniMax(Context ctx, SharedPreferences p, String text,
                                      String emotion, String tag, String ratePct) {
        String mmKey = p.getString("minimax_key", "");
        if (mmKey.isEmpty()) {
            DebugLog.add("TTS", "回退 MiniMax: 冇 key，跳過");
            return false;
        }
        File out = null;
        try {
            String mmVoice = p.getString("minimax_voice", MiniMaxTts.VOICE_IDS[0]);
            String mmModel = p.getString("minimax_model", MiniMaxTts.MODEL_IDS[0]);
            String mmEmotionMode = emotionMode(p);
            String mmEmotion = AiEngine.resolveVoiceEmotion(text, emotion, mmEmotionMode);
            DebugLog.add("TTS", "回退 MiniMax 情感: mode=" + mmEmotionMode + " | resolved=" + mmEmotion);
            String finalText = MiniMaxTts.applyTag(text, tag);
            out = freshAudioFile(ctx);
            DebugLog.add("TTS", "回退重新合成中（MiniMax）: " + truncate(finalText, 50));
            MiniMaxTts.synthesize(mmKey, finalText, mmVoice, mmModel, ratePct, mmEmotion, null, out);
            final File f = out;
            MAIN.post(() -> playFile(ctx, f, finalText, ratePct));
            DebugLog.add("TTS", "Edge→MiniMax 回退成功");
            return true;
        } catch (Exception e2) {
            deleteQuietly(out);
            DebugLog.add("TTS", "回退 MiniMax 失敗: " + truncate(e2.getMessage(), 100));
            return false;
        }
    }

    /** 舊版冇 mode key：舊值為空代表原本預設，升級為 auto；非空手動選擇照舊保留。 */
    private static String emotionMode(SharedPreferences p) {
        if (p.contains("minimax_emotion_mode")) {
            return p.getString("minimax_emotion_mode", "auto");
        }
        String legacy = p.getString("minimax_emotion", "");
        return legacy == null || legacy.isEmpty() ? "auto" : legacy;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 每次合成都用全新臨時檔，播放完即刪，唔會重用舊錄音。 */
    private static File freshAudioFile(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "tts_temp");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("無法建立語音臨時目錄");
        }
        return File.createTempFile("tts_", ".mp3", dir);
    }

    /** 升級後第一次播聲時清走舊版本留下嘅可重用錄音。 */
    private static synchronized void clearLegacyCacheOnce(Context ctx) {
        if (legacyCacheCleared) return;
        File dir = new File(ctx.getCacheDir(), "tts_cache");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) deleteQuietly(file);
        }
        if (dir.exists()) deleteQuietly(dir);
        legacyCacheCleared = true;
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** 播放一次性合成檔；完成或失敗後都會刪除。可供音色設計試聽使用。 */
    public static void playTemporaryFile(final Context ctx, final File file,
                                         final String fallbackText, final String ratePct) {
        if (file == null || !file.exists()) {
            playSystem(ctx, fallbackText, ratePct);
            return;
        }
        MAIN.post(() -> playFile(ctx, file, fallbackText, ratePct));
    }

    private static void playFile(final Context ctx, final File f, final String fallbackText, final String ratePct) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> {
                m.release();
                deleteQuietly(f);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                try { m.release(); } catch (Exception ignored) {}
                deleteQuietly(f);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            deleteQuietly(f);
            playSystem(ctx, fallbackText, ratePct);
        }
    }

    private static void playSystem(final Context ctx, final String text, final String ratePct) {
        MAIN.post(() -> {
            ensureTts(ctx);
            if (!ttsReady || tts == null) {
                DebugLog.add("TTS", "系統 TTS 未就緒，無法發聲（engine fallback 到尾都冇聲）");
                return;
            }
            try {
                float rate = 1f;
                try {
                    rate = 1f + Integer.parseInt(ratePct) / 100f;
                } catch (Exception ignored) {}
                rate = Math.max(0.5f, Math.min(2f, rate));
                tts.setSpeechRate(rate);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yupi_" + System.currentTimeMillis());
            } catch (Exception ignored) {}
        });
    }

    private static synchronized void ensureTts(Context ctx) {
        if (tts != null) return;
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale[] tries = {
                        new Locale("yue", "HK"),
                        Locale.TRADITIONAL_CHINESE,
                        Locale.SIMPLIFIED_CHINESE,
                        Locale.getDefault()
                };
                for (Locale l : tries) {
                    int r = tts.setLanguage(l);
                    if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsReady = true;
                        break;
                    }
                }
            }
        });
    }
}
