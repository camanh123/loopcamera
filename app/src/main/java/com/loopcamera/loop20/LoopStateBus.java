package com.loopcamera.loop20;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

public final class LoopStateBus {

    public interface Listener {
        void onState(LoopUiState state);
    }

    private static final LoopStateBus INSTANCE = new LoopStateBus();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LoopUiState latest = LoopUiState.disconnected(AppPreferences.MODE_TEST, false, "");

    public static LoopStateBus get() {
        return INSTANCE;
    }

    public LoopUiState latest() {
        return latest;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        LoopUiState s = latest;
        main.post(() -> listener.onState(s));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void post(LoopUiState state) {
        latest = state;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onState(state);
            }
        });
    }
}
