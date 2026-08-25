package com.loopcamera.loop20;

/**
 * Compact on-device report. Pure formatting — no I/O.
 */
public final class WriterReportFormatter {

    private WriterReportFormatter() {
    }

    public static final class Model {
        public String targetDirectory = "";
        public String observedMp4 = "(none)";
        public String firstSeenTime = "(none)";
        public boolean growthObserved;
        public String fileObserverEvents = "(none)";
        public String pid = "(none)";
        public String uid = "(none)";
        public String process = "(none)";
        public String pkg = "(none)";
        public String fdEvidence = "(none)";
        public String mountAliases = "(none)";
        public String foreground = "(none)";
        public String logCorrelation = "(none)";
        public String unavailable = "(none)";
        public String confidence = WriterConfidence.UNKNOWN;
        public String confidenceReason = "";
        public String finalLine = WriterConfidence.evaluate(null, null, null).finalLine();
        public String state = Mp4WatchTracker.WAITING;
        public String timeline = "";
    }

    public static String livePanel(Model m) {
        StringBuilder sb = new StringBuilder();
        sb.append("LIVE MP4 WRITER DISCOVERY\n\n");
        sb.append("Target:\n").append(nz(m.targetDirectory)).append("/*.mp4\n\n");
        sb.append("Current file:\n").append(nz(m.observedMp4)).append("\n\n");
        sb.append("State:\n").append(nz(m.state)).append("\n\n");
        sb.append("Writer PID:\n").append(nz(m.pid)).append("\n\n");
        sb.append("Writer UID:\n").append(nz(m.uid)).append("\n\n");
        sb.append("Process:\n").append(nz(m.process)).append("\n\n");
        sb.append("Package:\n").append(nz(m.pkg)).append("\n\n");
        sb.append("Evidence:\n").append(nz(m.fdEvidence)).append("\n\n");
        sb.append("Confidence:\n").append(nz(m.confidence));
        return sb.toString();
    }

    public static String compactReport(Model m) {
        StringBuilder sb = new StringBuilder();
        sb.append("CARFU MP4 WRITER DISCOVERY\n\n");
        sb.append("Target directory\n").append(nz(m.targetDirectory)).append("\n\n");
        sb.append("Observed MP4\n").append(nz(m.observedMp4)).append("\n\n");
        sb.append("First-seen time\n").append(nz(m.firstSeenTime)).append("\n\n");
        sb.append("Growth observed\n").append(m.growthObserved ? "YES" : "NO").append("\n\n");
        sb.append("FileObserver events\n").append(nz(m.fileObserverEvents)).append("\n\n");
        sb.append("PID\n").append(nz(m.pid)).append("\n\n");
        sb.append("UID\n").append(nz(m.uid)).append("\n\n");
        sb.append("process\n").append(nz(m.process)).append("\n\n");
        sb.append("package\n").append(nz(m.pkg)).append("\n\n");
        sb.append("FD evidence\n").append(nz(m.fdEvidence)).append("\n\n");
        sb.append("mount/path alias\n").append(nz(m.mountAliases)).append("\n\n");
        sb.append("foreground correlation\n").append(nz(m.foreground)).append("\n\n");
        sb.append("log correlation\n").append(nz(m.logCorrelation)).append("\n\n");
        sb.append("unavailable probes + reason\n").append(nz(m.unavailable)).append("\n\n");
        sb.append("confidence\n").append(nz(m.confidence));
        if (m.confidenceReason != null && !m.confidenceReason.isEmpty()) {
            sb.append(" — ").append(m.confidenceReason);
        }
        sb.append("\n\n");
        if (m.timeline != null && !m.timeline.isEmpty()) {
            sb.append("timeline\n").append(m.timeline).append("\n\n");
        }
        sb.append(nz(m.finalLine));
        sb.append('\n');
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "(none)" : s;
    }
}
