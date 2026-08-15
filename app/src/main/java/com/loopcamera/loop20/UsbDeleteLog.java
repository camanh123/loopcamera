package com.loopcamera.loop20;

import android.util.Log;

/**
 * Unified logcat channel for USB delete diagnostics on CARFU.
 * Filter: adb logcat -s CameraLoopUSB
 */
public final class UsbDeleteLog {

    public static final String TAG = "CameraLoopUSB";

    private UsbDeleteLog() {
    }

    public static void i(String event, String details) {
        String line = details == null || details.isEmpty() ? event : event + " " + details;
        Log.i(TAG, line);
        LoopLog.get().i(line);
    }

    public static void w(String event, String details) {
        String line = details == null || details.isEmpty() ? event : event + " " + details;
        Log.w(TAG, line);
        LoopLog.get().w(line);
    }

    public static void e(String event, String details) {
        String line = details == null || details.isEmpty() ? event : event + " " + details;
        Log.e(TAG, line);
        LoopLog.get().e(line);
    }

    public static void e(String event, String details, Throwable t) {
        String extra = details == null ? "" : details;
        if (t != null) {
            extra = extra
                    + (extra.isEmpty() ? "" : " ")
                    + "exception=" + t.getClass().getName() + ": " + t.getMessage();
        }
        String line = extra.isEmpty() ? event : event + " " + extra;
        Log.e(TAG, line, t);
        LoopLog.get().e(line);
    }
}
