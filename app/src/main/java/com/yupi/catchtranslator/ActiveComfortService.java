package com.yupi.catchtranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 背景主動安慰：按用戶選擇嘅間隔生成一句安慰，再交畀現有語音引擎播放。
 * 使用前台服務維持計時；偵測到語音或其他媒體播放時會稍後重試，唔搶聲。
 */
public class ActiveComfortService extends Service {

    public static final String CHANNEL_ID = "active_comfort";
    public static final String ACTION_START = "com.yupi.catchtranslator.START_ACTIVE_COMFORT";
    public static final String ACTION_STOP = "com.yupi.catchtranslator.STOP_ACTIVE_COMFORT";
    public static final String PREF_ENABLED = "active_comfort_enabled";
    public static final String PREF_INTERVAL = "active_comfort_interval_minutes";
    public static final String PREF_LAUNCH_ASSISTANT = "active_comfort_launch_assistant";
    private static final String PREF_ASSISTANT_STATUS = "active_comfort_assistant_status";
    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final String CHATGPT_VOICE_ACTIVITY =
            "com.openai.voice.assistant.AssistantActivity";

    private static final int NOTIFICATION_ID = 8;
    public static final int DEFAULT_INTERVAL_MINUTES = 20;
    public static final int MIN_INTERVAL_MINUTES = 0;
    public static final int MAX_INTERVAL_MINUTES = 300;
    private static final long BUSY_RETRY_MS = 2 * 60 * 1000L;
    private static final long ZERO_INTERVAL_SAFE_DELAY_MS = 60 * 1000L;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String lastStatus = "";

    private final Runnable generateTask = this::generateComfort;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    /** 供主頁及懸浮面板共用，確保兩邊切換的是同一個主動安慰服務。 */
    public static boolean setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, enabled).apply();
        if (!enabled) {
            context.stopService(new Intent(context, ActiveComfortService.class));
            return true;
        }
        try {
            Intent intent = new Intent(context, ActiveComfortService.class)
                    .setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (Exception e) {
            context.getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean(PREF_ENABLED, false).apply();
            DebugLog.add("Comfort", "懸浮面板啟動主動安慰失敗: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getIntervalMinutes(Context context) {
        int value = context.getSharedPreferences("settings", MODE_PRIVATE)
                .getInt(PREF_INTERVAL, DEFAULT_INTERVAL_MINUTES);
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, value));
    }

    public static String getAssistantStatus(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE)
                .getString(PREF_ASSISTANT_STATUS, "尚未測試 ChatGPT 語音");
    }

    /**
     * 優先呼叫 ChatGPT 的語音 Activity；找不到時才嘗試系統助理入口。
     * 普通 ChatGPT 首頁只會供通知按鈕使用，不會被當成語音已啟動。
     */
    public static boolean tryLaunchAssistant(Context context) {
        Context appContext = context.getApplicationContext();
        Intent launch = createVoiceLaunchIntent(appContext);
        if (launch == null) {
            String status = "找不到 ChatGPT 語音 Activity；請更新 ChatGPT，或先在系統設定啟用 ChatGPT 助理";
            saveAssistantStatus(appContext, status);
            DebugLog.add("Comfort", status);
            return false;
        }
        String target = describeIntent(appContext, launch);
        try {
            appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            String status = "已呼叫 " + target + "；若沒有畫面，請點通知內的 ChatGPT 語音按鈕"
                    + "（懸浮窗權限=" + (hasOverlayPermission(appContext) ? "已開" : "未開") + "）";
            saveAssistantStatus(appContext, status);
            DebugLog.add("Comfort", status);
            return true;
        } catch (Exception e) {
            String status = "呼叫 " + target + " 失敗：" + e.getClass().getSimpleName()
                    + "；背景啟動可能被 Android 阻止"
                    + "（懸浮窗權限=" + (hasOverlayPermission(appContext) ? "已開" : "未開") + "）";
            saveAssistantStatus(appContext, status);
            DebugLog.add("Comfort", status);
            return false;
        }
    }

    private static void saveAssistantStatus(Context context, String status) {
        context.getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString(PREF_ASSISTANT_STATUS, status).apply();
    }

    private static boolean hasOverlayPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
    }

    private static Intent createVoiceLaunchIntent(Context context) {
        Intent direct = createChatGptVoiceIntent(context);
        if (direct != null) return direct;

        PackageManager manager = context.getPackageManager();
        Intent assist = new Intent(Intent.ACTION_ASSIST);
        ResolveInfo resolved = manager.resolveActivity(assist, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null
                && CHATGPT_PACKAGE.equals(resolved.activityInfo.packageName)) {
            return assist;
        }
        return null;
    }

    private static Intent createAssistantLaunchIntent(Context context) {
        Intent voice = createVoiceLaunchIntent(context);
        if (voice != null) return voice;

        PackageManager manager = context.getPackageManager();
        return manager.getLaunchIntentForPackage(CHATGPT_PACKAGE);
    }

    private static Intent createChatGptVoiceIntent(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent direct = new Intent().setComponent(new ComponentName(
                CHATGPT_PACKAGE, CHATGPT_VOICE_ACTIVITY));
        ResolveInfo resolved = manager.resolveActivity(direct, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved == null ? null : direct;
    }

    private static String describeIntent(Context context, Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null) return component.flattenToShortString();
        ResolveInfo resolved = context.getPackageManager().resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null) {
            return intent.getAction() + " -> " + resolved.activityInfo.packageName;
        }
        return intent.getAction() == null ? "未知入口" : intent.getAction();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action) || !isEnabled(this)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        scheduleAfter(intervalDelayMs());
        updateStatus("已開啟 · 每 " + intervalDescription() + " 陪伴一次");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(generateTask);
        worker.shutdownNow();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleAfter(long delayMs) {
        handler.removeCallbacks(generateTask);
        if (isEnabled(this)) handler.postDelayed(generateTask, Math.max(1_000L, delayMs));
    }

    private void generateComfort() {
        if (!isEnabled(this)) {
            stopSelfSafely();
            return;
        }
        if (VoicePlayer.isPlaybackActive()
                || BluetoothKeepAliveService.isExternalPlaybackActive(this)) {
            updateStatus("偵測到其他播放 · 稍後再試");
            scheduleAfter(BUSY_RETRY_MS);
            return;
        }

        updateStatus("正在準備一段安慰…");
        worker.execute(() -> {
            try {
                AiEngine.Response response = AiEngine.proactiveComfort(this);
                handler.post(() -> deliverComfort(response));
            } catch (Exception e) {
                DebugLog.add("Comfort", "生成主動安慰失敗: " + e.getClass().getSimpleName());
                handler.post(() -> {
                    updateStatus("今次生成失敗 · 稍後再試");
                    scheduleAfter(BUSY_RETRY_MS);
                });
            }
        });
    }

    private void deliverComfort(AiEngine.Response response) {
        if (!isEnabled(this)) return;
        if (response == null || response.reply == null || response.reply.trim().isEmpty()) {
            updateStatus("今次未有合適句子 · 稍後再試");
            scheduleAfter(BUSY_RETRY_MS);
            return;
        }
        if (VoicePlayer.isPlaybackActive()
                || BluetoothKeepAliveService.isExternalPlaybackActive(this)) {
            updateStatus("偵測到其他播放 · 稍後再試");
            scheduleAfter(BUSY_RETRY_MS);
            return;
        }

        try {
            new TranslatorDb(this).insert("主動安慰", response.reply, "proactive_comfort");
        } catch (Exception e) {
            DebugLog.add("Comfort", "儲存主動安慰記錄失敗: " + e.getClass().getSimpleName());
        }
        boolean launchAssistant = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean(PREF_LAUNCH_ASSISTANT, false);
        if (launchAssistant) {
            VoicePlayer.speak(this, response.reply, response.emotion, response.tag, () -> {
                if (!isEnabled(this)
                        || !getSharedPreferences("settings", MODE_PRIVATE)
                        .getBoolean(PREF_LAUNCH_ASSISTANT, false)) {
                    return;
                }
                if (tryLaunchAssistant(this)) {
                    updateStatus("安慰已播放完，已打開 ChatGPT 語音 · 下次每 "
                            + intervalDescription());
                } else {
                    updateStatus("安慰已播放完，但 ChatGPT 未能打開（"
                            + getAssistantStatus(this) + "）· 下次每 " + intervalDescription());
                }
            });
            updateStatus("正在播放安慰，完成後打開 ChatGPT 語音 · 下次每 "
                    + intervalDescription());
        } else {
            VoicePlayer.speak(this, response.reply, response.emotion, response.tag);
            updateStatus("剛剛已播放安慰 · 下次每 " + intervalDescription());
        }
        scheduleAfter(intervalDelayMs());
    }

    private long intervalDelayMs() {
        int minutes = getIntervalMinutes(this);
        return minutes == 0 ? ZERO_INTERVAL_SAFE_DELAY_MS : minutes * 60_000L;
    }

    private String intervalDescription() {
        int minutes = getIntervalMinutes(this);
        return minutes == 0 ? "0 分鐘（安全最短 1 分鐘）" : minutes + " 分鐘";
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "主動安慰", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("按設定間隔生成並播放溫柔安慰");
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = lastStatus.isEmpty() ? "等待下一段溫柔安慰" : lastStatus;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YupiSaver · 主動安慰")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pending)
                .setOngoing(true);
        Intent assistant = createAssistantLaunchIntent(this);
        if (assistant != null) {
            String actionTitle = createVoiceLaunchIntent(this) == null
                    ? "打開 ChatGPT" : "ChatGPT 語音";
            PendingIntent assistantPending = PendingIntent.getActivity(this, 9,
                    assistant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif),
                    actionTitle, assistantPending).build());
        }
        return builder.build();
    }

    private void updateStatus(String status) {
        if (status == null || status.equals(lastStatus)) return;
        lastStatus = status;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void stopSelfSafely() {
        handler.removeCallbacks(generateTask);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
