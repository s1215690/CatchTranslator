package com.yupi.catchtranslator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日 00:01 總結 / 20:00 提醒 / 每週一 00:05 週回顧，全部經呢個 receiver 觸發。 */
public class DailySummaryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            DailySummary.schedule(ctx);
            final String action = intent.getAction();
            new Thread(() -> {
                try {
                    if (DailySummary.ACTION_REMINDER.equals(action)) {
                        DailySummary.remind(ctx);
                    } else if (DailySummary.ACTION_WEEKLY.equals(action)) {
                        DailySummary.generateWeeklyAndNotify(ctx);
                    } else {
                        DailySummary.generateAndNotify(ctx);
                    }
                } catch (Exception ignored) {
                }
            }).start();
        } catch (Exception ignored) {
        }
    }
}
