package com.yupi.catchtranslator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 開機後：重排每日總結 + 如果之前開咗懸浮按鈕就自動開返。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        DailySummary.schedule(ctx);
        TimedNudgeScheduler.rescheduleAll(ctx);
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (p.getBoolean("floating_enabled", false)) {
            try {
                Intent i = new Intent(ctx, FloatingService.class);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Exception ignored) {}
        }
    }
}
