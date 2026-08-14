package com.loopcamera.loop20;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppPreferences prefs = new AppPreferences(context);
        if (prefs.getTreeUri() == null) {
            return;
        }
        LoopLog.get().i("Thiết bị/app khởi động lại — khôi phục theo dõi DCIM (TEST MODE mặc định an toàn).");
        if (prefs.isLoopMode() && prefs.isFailsafe()) {
            prefs.setMode(AppPreferences.MODE_TEST);
        }
        LoopMonitorService.start(context);
    }
}
