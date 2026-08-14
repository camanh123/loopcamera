package com.loopcamera.loop20;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LoopLog {

    public interface Listener {
        void onLogChanged(String fullText);
    }

    private static final String TAG = "CameraLoop20";
    private static final int MAX_LINES = 200;
    private static final LoopLog INSTANCE = new LoopLog();

    private final List<String> lines = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static LoopLog get() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onLogChanged(text());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized void i(String message) {
        append("I", message);
        Log.i(TAG, message);
    }

    public synchronized void w(String message) {
        append("W", message);
        Log.w(TAG, message);
    }

    public synchronized void e(String message) {
        append("E", message);
        Log.e(TAG, message);
    }

    public synchronized void e(String message, Throwable t) {
        append("E", message + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
        Log.e(TAG, message, t);
    }

    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clear() {
        lines.clear();
        notifyListeners();
    }

    private void append(String level, String message) {
        String stamp = time.format(new Date());
        lines.add(stamp + " [" + level + "] " + message);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        final String snapshot = text();
        main.post(() -> {
            for (Listener l : listeners) {
                l.onLogChanged(snapshot);
            }
        });
    }
}
