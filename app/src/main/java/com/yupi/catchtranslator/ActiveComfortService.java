package com.yupi.catchtranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

    private static final int NOTIFICATION_ID = 8;
    private static final int DEFAULT_INTERVAL_MINUTES = 20;
    private static final int MIN_INTERVAL_MINUTES = 15;
    private static final int MAX_INTERVAL_MINUTES = 30;
    private static final long BUSY_RETRY_MS = 2 * 60 * 1000L;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String lastStatus = "";

    private final Runnable generateTask = this::generateComfort;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getIntervalMinutes(Context context) {
        int value = context.getSharedPreferences("settings", MODE_PRIVATE)
                .getInt(PREF_INTERVAL, DEFAULT_INTERVAL_MINUTES);
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, value));
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
        scheduleAfter(getIntervalMinutes(this) * 60_000L);
        updateStatus("已開啟 · 每 " + getIntervalMinutes(this) + " 分鐘陪伴一次");
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
        VoicePlayer.speak(this, response.reply, response.emotion, response.tag);
        updateStatus("剛剛已播放安慰 · 下次每 " + getIntervalMinutes(this) + " 分鐘");
        scheduleAfter(getIntervalMinutes(this) * 60_000L);
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
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YupiSaver · 主動安慰")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
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
