package com.yupi.catchtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
     * emotionOverride：AI 根據回應內容揀嘅語氣（""=自然 / calm / happy / sad / surprised / fluent），空＝用設定值。
     * tag：句內語氣標籤（laughs/sighs/gasps/emm…，speech-2.8 專用）。Edge／系統 TTS 唔支持，會自動忽略。
     */
    public static void speak(final Context ctx, final String text, final String emotionOverride, final String tag) {
        if (text == null || text.isEmpty()) return;
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
                File out = cachedFile(ctx, text, voice + "|" + pitch, ratePct);
                try {
                    if (!out.exists()) {
                        DebugLog.add("TTS", "Edge 合成中: " + truncate(text, 50));
                        EdgeTts.synthesize(text, voice, ratePct, pitch, out);
                    }
                    final File f = out;
                    MAIN.post(() -> playFile(ctx, f, text, ratePct));
                } catch (Exception e) {
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
            final String mmEmotion = (emotionOverride != null && !emotionOverride.isEmpty())
                    ? emotionOverride : p.getString("minimax_emotion", "");
            POOL.execute(() -> {
                String finalText = MiniMaxTts.applyTag(text, tag); // 標籤先入 text，再入 cache key
                File out = cachedFile(ctx, finalText, mmVoice + "|" + mmModel + "|" + mmEmotion, ratePct);
                try {
                    if (!out.exists()) {
                        DebugLog.add("TTS", "MiniMax 合成中: " + truncate(finalText, 50));
                        MiniMaxTts.synthesize(mmKey, finalText, mmVoice, mmModel, ratePct, mmEmotion, null, out);
                    }
                    final File f = out;
                    MAIN.post(() -> playFile(ctx, f, finalText, ratePct));
                } catch (Exception e) {
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
        try {
            String mmVoice = p.getString("minimax_voice", MiniMaxTts.VOICE_IDS[0]);
            String mmModel = p.getString("minimax_model", MiniMaxTts.MODEL_IDS[0]);
            String mmEmotion = (emotion != null && !emotion.isEmpty())
                    ? emotion : p.getString("minimax_emotion", "");
            String finalText = MiniMaxTts.applyTag(text, tag);
            File out = cachedFile(ctx, finalText, "mm-fb|" + mmVoice + "|" + mmModel + "|" + mmEmotion, ratePct);
            if (!out.exists()) {
                DebugLog.add("TTS", "回退合成中（MiniMax）: " + truncate(finalText, 50));
                MiniMaxTts.synthesize(mmKey, finalText, mmVoice, mmModel, ratePct, mmEmotion, null, out);
            }
            final File f = out;
            MAIN.post(() -> playFile(ctx, f, finalText, ratePct));
            DebugLog.add("TTS", "Edge→MiniMax 回退成功");
            return true;
        } catch (Exception e2) {
            DebugLog.add("TTS", "回退 MiniMax 失敗: " + truncate(e2.getMessage(), 100));
            return false;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 按文字＋聲線＋語速做快取 key：同一句講過就唔使再合成。 */
    private static File cachedFile(Context ctx, String text, String voice, String ratePct) {
        File dir = new File(ctx.getCacheDir(), "tts_cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, md5(text + "|" + voice + "|" + ratePct) + ".mp3");
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static void playFile(final Context ctx, final File f, final String fallbackText, final String ratePct) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> m.release());
            mp.setOnErrorListener((m, what, extra) -> {
                try { m.release(); } catch (Exception ignored) {}
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
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
