package com.loopcamera.loop20;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppPreferences prefs = new AppPreferences(context);
        if (!prefs.hasSavedFolder()) {
            LoopLog.get().i("Boot: chưa có thư mục đã lưu — không auto-start.");
            return;
        }
        String action = intent != null ? intent.getAction() : "";
        LoopLog.get().i("BOOT (" + action + ") → start foreground service, chờ USB mount, "
                + "tìm lại " + prefs.getRelativePath() + " (đã lưu " + prefs.getFolderPath() + ")");
        try {
            LoopMonitorService.start(context);
        } catch (Throwable t) {
            LoopLog.get().e("Không start được service lúc boot", t);
        }
    }
}
