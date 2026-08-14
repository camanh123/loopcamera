package com.loopcamera.loop20;

import android.app.Application;

public class LoopApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LoopLog.get().i("App khởi động — sẽ đọc lại trạng thái từ DCIM khi đã chọn thư mục.");
    }
}
