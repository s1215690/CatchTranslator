package com.yupi.catchtranslator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** 定時推動到點：先通知兜底，再叫醒懸浮服務開始 AI 拆步和逐步確認。 */
public class TimedNudgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TimedNudgeScheduler.ACTION_TRIGGER.equals(intent.getAction())) return;
        String id = intent.getStringExtra(TimedNudgeScheduler.EXTRA_ID);
        String task = intent.getStringExtra(TimedNudgeScheduler.EXTRA_TASK);
        if (task == null || task.trim().isEmpty()) return;
        if (id != null) TimedNudgeScheduler.remove(context, id);
        boolean canOverlay = Settings.canDrawOverlays(context);
        TimedNudgeScheduler.notifyDue(context, task.trim(), !canOverlay);
        if (!canOverlay) return;
        try {
            Intent service = new Intent(context, FloatingService.class)
                    .setAction(FloatingService.ACTION_START_TIMED_NUDGE)
                    .putExtra(TimedNudgeScheduler.EXTRA_TASK, task.trim());
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
            // 高優先級通知已經發出，服務被系統攔截時仍然會提醒用戶。
        }
    }
}
