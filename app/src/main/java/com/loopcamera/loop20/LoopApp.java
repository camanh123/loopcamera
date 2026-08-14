package com.loopcamera.loop20;

import android.app.Application;

public class LoopApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashLog.write(getApplicationContext(), error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        LoopLog.get().i("App khởi động — sẽ đọc lại trạng thái từ DCIM khi đã chọn thư mục.");
        String last = CrashLog.readAndClear(this);
        if (last != null) {
            LoopLog.get().e("Crash lần trước:\n" + last);
        }
    }
}
