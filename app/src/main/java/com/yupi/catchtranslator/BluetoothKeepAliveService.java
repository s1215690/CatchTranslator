package com.yupi.catchtranslator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * 以接近聽不到的非零高頻訊號保持 A2DP 音箱唔因為長時間無聲而休眠。
 * 真正語音播放時會自動停，避免保活訊號混入正常聲音。
 */
public class BluetoothKeepAliveService extends Service {

    public static final String CHANNEL_ID = "bluetooth_keep_alive";
    public static final String ACTION_START = "com.yupi.catchtranslator.START_BT_KEEP_ALIVE";
    public static final String ACTION_STOP = "com.yupi.catchtranslator.STOP_BT_KEEP_ALIVE";
    public static final String PREF_ENABLED = "bluetooth_keepalive_enabled";

    private static final int NOTIFICATION_ID = 7;
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int SIGNAL_FRAMES = SAMPLE_RATE / 5; // 200 ms；19 kHz 下剛好完整週期
    private static final long MONITOR_INTERVAL_MS = 2_000L;
    private static final double SIGNAL_FREQUENCY = 19_000.0;
    private static final double SIGNAL_AMPLITUDE = 800.0 / 32767.0;
    private static final float TRACK_VOLUME = 0.03f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioTrack audioTrack;
    private AudioDeviceInfo currentDevice;
    private Thread audioThread;
    private volatile boolean audioLoopRunning;
    private static volatile boolean keepAliveAudioActive;
    private String lastStatus = "";

    private final Runnable monitor = new Runnable() {
        @Override
        public void run() {
            refreshKeepAlive();
            if (shouldRemainAlive()) handler.postDelayed(this, MONITOR_INTERVAL_MS);
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refreshKeepAlive();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refreshKeepAlive();
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    refreshKeepAlive();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        if (Build.VERSION.SDK_INT >= 23 && audioManager != null) {
            audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        }
        if (audioManager != null) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        }
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(monitor);
        handler.post(monitor);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(monitor);
        stopAudio();
        if (Build.VERSION.SDK_INT >= 23 && audioManager != null) {
            try { audioManager.unregisterAudioDeviceCallback(deviceCallback); } catch (Exception ignored) {}
        }
        if (audioManager != null) {
            try { audioManager.unregisterAudioPlaybackCallback(playbackCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, true);
    }

    /** 主頁顯示目前是否有 A2DP 藍牙輸出，唔會主動掃描附近設備。 */
    public static String getConnectedDeviceName(Context context) {
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return "需要「附近的設備」權限";
        }
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo device = findBluetoothOutput(manager);
        return device == null ? "未找到已連接的藍牙音箱" : deviceName(device);
    }

    private static AudioDeviceInfo findBluetoothOutput(AudioManager manager) {
        if (manager == null) return null;
        try {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return device;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    private static String deviceName(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "已連接的藍牙音箱" : name.toString();
    }

    private boolean shouldRemainAlive() {
        return isEnabled(this);
    }

    private void refreshKeepAlive() {
        if (!shouldRemainAlive()) {
            stopAudio();
            stopSelfSafely();
            return;
        }
        if (VoicePlayer.isPlaybackActive()) {
            stopAudio();
            updateStatus("語音播放中，暫停保活");
            return;
        }

        if (hasExternalPlayback()) {
            stopAudio();
            updateStatus("偵測到其他音樂，暫停保活");
            return;
        }

        AudioDeviceInfo device = findBluetoothOutput(audioManager);
        if (device == null) {
            stopAudio();
            updateStatus(getConnectedDeviceName(this));
            return;
        }
        if (audioTrack == null || currentDevice == null || currentDevice.getId() != device.getId()) {
            startAudio(device);
        }
        updateStatus("保活中 · " + deviceName(device) + "（近乎靜音）");
    }

    /**
     * 自己的保活 AudioTrack 會佔一個播放配置；多出來的配置通常就是其他 App 的音樂。
     * 因此不搶 Audio Focus，只在偵測到外部播放時停下，音樂停止後由 monitor 自動恢復。
     */
    private boolean hasExternalPlayback() {
        return isExternalPlaybackActive(this);
    }

    /** 判斷目前是否有除本服務保活訊號以外的播放，供主動安慰讓路。 */
    public static boolean isExternalPlaybackActive(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return false;
        try {
            List<AudioPlaybackConfiguration> configs =
                    manager.getActivePlaybackConfigurations();
            if (configs == null || configs.isEmpty()) return false;
            return keepAliveAudioActive ? configs.size() > 1 : true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private void startAudio(AudioDeviceInfo device) {
        stopAudio();
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = SIGNAL_FRAMES * 4;
        AudioTrack candidate = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(CHANNEL_MASK)
                    .build();
            candidate = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, SIGNAL_FRAMES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (candidate.getState() != AudioTrack.STATE_INITIALIZED) {
                candidate.release();
                updateStatus("手機音頻輸出初始化失敗");
                return;
            }
            candidate.setPreferredDevice(device);
            candidate.setVolume(TRACK_VOLUME);
            candidate.play();
            keepAliveAudioActive = true;
            audioTrack = candidate;
            currentDevice = device;
            audioLoopRunning = true;
            final AudioTrack local = candidate;
            audioThread = new Thread(() -> writeSignal(local), "BluetoothKeepAlive");
            audioThread.start();
        } catch (Exception e) {
            if (candidate != null) {
                try { candidate.release(); } catch (Exception ignored) {}
            }
            updateStatus("保活音頻啟動失敗：" + e.getClass().getSimpleName());
        }
    }

    private void writeSignal(AudioTrack local) {
        short[] signal = createSignal();
        try {
            while (audioLoopRunning && audioTrack == local
                    && !VoicePlayer.isPlaybackActive()) {
                int written = local.write(signal, 0, signal.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) break;
            }
        } catch (Exception ignored) {
            // 路由切換或音箱斷線時由主線程重新建立／停止 AudioTrack。
        } finally {
            if (audioTrack == local) {
                handler.post(() -> {
                    if (audioTrack == local) stopAudio();
                });
            }
        }
    }

    private static short[] createSignal() {
        short[] signal = new short[SIGNAL_FRAMES * 2];
        double step = 2.0 * Math.PI * SIGNAL_FREQUENCY / SAMPLE_RATE;
        for (int frame = 0; frame < SIGNAL_FRAMES; frame++) {
            short sample = (short) Math.round(Math.sin(frame * step) * Short.MAX_VALUE * SIGNAL_AMPLITUDE);
            signal[frame * 2] = sample;
            signal[frame * 2 + 1] = sample;
        }
        return signal;
    }

    private synchronized void stopAudio() {
        audioLoopRunning = false;
        keepAliveAudioActive = false;
        AudioTrack local = audioTrack;
        audioTrack = null;
        currentDevice = null;
        if (local != null) {
            try { local.pause(); } catch (Exception ignored) {}
            try { local.flush(); } catch (Exception ignored) {}
            try { local.release(); } catch (Exception ignored) {}
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "藍牙音箱保活", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用近乎聽不到的訊號避免藍牙音箱自動休眠");
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = lastStatus.isEmpty() ? "等待藍牙音箱連接" : lastStatus;
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YupiSaver · 藍牙音箱保活")
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
        stopAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
