package com.loopcamera.loop20;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

public final class WriterDiscoveryBus {

    public interface Listener {
        void onWriterSnapshot(Snapshot snapshot);
    }

    public static final class Snapshot {
        public boolean running;
        public WriterReportFormatter.Model model = new WriterReportFormatter.Model();
        public String liveText = "";
        public String reportText = "";
    }

    private static final WriterDiscoveryBus INSTANCE = new WriterDiscoveryBus();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Snapshot latest = idle("STOPPED — bấm START WRITER DISCOVERY, rồi để OEM DVR ghi MP4.");

    public static WriterDiscoveryBus get() {
        return INSTANCE;
    }

    public Snapshot latest() {
        return latest;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        Snapshot s = latest;
        main.post(() -> listener.onWriterSnapshot(s));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void post(Snapshot snapshot) {
        latest = snapshot;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onWriterSnapshot(snapshot);
            }
        });
    }

    public static Snapshot idle(String note) {
        Snapshot s = new Snapshot();
        s.running = false;
        s.model.targetDirectory = "/storage/USB1/DCIM/Camera";
        s.model.state = Mp4WatchTracker.WAITING;
        s.model.fdEvidence = note;
        s.model.confidence = WriterConfidence.UNKNOWN;
        s.model.finalLine = WriterConfidence.evaluate(null, null, null).finalLine();
        s.liveText = WriterReportFormatter.livePanel(s.model);
        s.reportText = WriterReportFormatter.compactReport(s.model);
        return s;
    }
}
