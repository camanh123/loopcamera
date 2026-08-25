package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic-only MP4 presence/growth tracker. Stats names and sizes only.
 * Never opens, deletes, renames, or truncates files.
 */
public final class Mp4WatchTracker {

    public static final String WAITING = "WAITING";
    public static final String NEW = "NEW";
    public static final String GROWING = "GROWING";
    public static final String CLOSED = "CLOSED";

    public static final long STABLE_MS = 3000L;

    public static final class Track {
        public final String filename;
        public String absPath = "";
        public long firstSeenMs;
        public long firstSize;
        public long size;
        public long lastModified;
        public long lastSizeChangeMs;
        public boolean grew;
        public boolean preexisting;
        public String state = NEW;
        public final List<String> fsEvents = new ArrayList<>();
        public final List<String> sizeHistory = new ArrayList<>();

        Track(String filename) {
            this.filename = filename;
        }
    }

    public static final class Event {
        public final long atMs;
        public final String text;

        public Event(long atMs, String text) {
            this.atMs = atMs;
            this.text = text;
        }
    }

    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private boolean startSnapshotDone;

    public void reset() {
        tracks.clear();
        startSnapshotDone = false;
    }

    public void snapshotExisting(String filename, String absPath, long size, long lastModified, long nowMs) {
        if (!isMp4(filename)) {
            return;
        }
        Track t = tracks.get(filename);
        if (t == null) {
            t = new Track(filename);
            t.preexisting = true;
            t.state = CLOSED;
            t.firstSeenMs = nowMs;
            t.firstSize = size;
            t.size = size;
            t.lastModified = lastModified;
            t.lastSizeChangeMs = nowMs;
            t.absPath = absPath == null ? "" : absPath;
            t.sizeHistory.add(String.valueOf(size));
            tracks.put(filename, t);
        }
        startSnapshotDone = true;
    }

    public void markStartSnapshotDone() {
        startSnapshotDone = true;
    }

    public List<Event> observe(String filename, String absPath, long size, long lastModified, long nowMs) {
        List<Event> out = new ArrayList<>();
        if (!isMp4(filename)) {
            return out;
        }
        Track t = tracks.get(filename);
        if (t == null) {
            t = new Track(filename);
            t.firstSeenMs = nowMs;
            t.firstSize = size;
            t.size = size;
            t.lastModified = lastModified;
            t.lastSizeChangeMs = nowMs;
            t.absPath = absPath == null ? "" : absPath;
            t.state = NEW;
            t.sizeHistory.add(String.valueOf(size));
            tracks.put(filename, t);
            out.add(new Event(nowMs, "NEW FILE " + filename));
            out.add(new Event(nowMs, "size=" + size));
            return out;
        }
        if (absPath != null && !absPath.isEmpty()) {
            t.absPath = absPath;
        }
        t.lastModified = lastModified;
        if (size > t.size) {
            t.grew = true;
            t.lastSizeChangeMs = nowMs;
            t.size = size;
            t.state = GROWING;
            if (t.sizeHistory.size() < 24) {
                t.sizeHistory.add(String.valueOf(size));
            } else {
                t.sizeHistory.set(t.sizeHistory.size() - 1, String.valueOf(size));
            }
            String kind = t.preexisting ? "FILE GROWING (present at start) " : "FILE GROWING ";
            out.add(new Event(nowMs, kind + filename + " size=" + size));
        } else if (size < t.size) {
            t.size = size;
            out.add(new Event(nowMs, "size shrunk unexpectedly " + filename + " size=" + size));
        } else if (NEW.equals(t.state) || GROWING.equals(t.state)) {
            if (nowMs - t.lastSizeChangeMs >= STABLE_MS) {
                t.state = CLOSED;
                out.add(new Event(nowMs, "FILE CLOSED/STABLE " + filename + " size=" + t.size));
            }
        }
        return out;
    }

    public List<Event> onFsEvent(String filename, String eventName, long nowMs) {
        List<Event> out = new ArrayList<>();
        if (filename == null || filename.isEmpty()) {
            out.add(new Event(nowMs, "FileObserver " + eventName + " (dir)"));
            return out;
        }
        if (!isMp4(filename)) {
            return out;
        }
        Track t = tracks.get(filename);
        if (t == null && ("CREATE".equals(eventName) || "MOVED_TO".equals(eventName))) {
            t = new Track(filename);
            t.firstSeenMs = nowMs;
            t.lastSizeChangeMs = nowMs;
            t.state = NEW;
            tracks.put(filename, t);
            out.add(new Event(nowMs, "NEW FILE " + filename));
        }
        if (t != null) {
            t.fsEvents.add(eventName);
            out.add(new Event(nowMs, "FileObserver " + eventName + " " + filename));
            if ("CLOSE_WRITE".equals(eventName)
                    && !CLOSED.equals(t.state)
                    && nowMs - t.lastSizeChangeMs >= STABLE_MS) {
                t.state = CLOSED;
                out.add(new Event(nowMs, "FILE CLOSED/STABLE " + filename + " size=" + t.size));
            }
        }
        return out;
    }

    public Track current() {
        Track growing = null;
        Track neu = null;
        Track recent = null;
        for (Track t : tracks.values()) {
            if (GROWING.equals(t.state)) {
                if (growing == null || t.firstSeenMs >= growing.firstSeenMs) {
                    growing = t;
                }
            } else if (NEW.equals(t.state)) {
                if (neu == null || t.firstSeenMs >= neu.firstSeenMs) {
                    neu = t;
                }
            }
            if (recent == null || t.firstSeenMs >= recent.firstSeenMs) {
                recent = t;
            }
        }
        if (growing != null) {
            return growing;
        }
        if (neu != null) {
            return neu;
        }
        if (recent != null && !recent.preexisting) {
            return recent;
        }
        if (recent != null && recent.grew) {
            return recent;
        }
        return null;
    }

    public String sessionState() {
        Track t = current();
        if (t == null) {
            return WAITING;
        }
        return t.state;
    }

    public List<Track> all() {
        return new ArrayList<>(tracks.values());
    }

    public boolean startSnapshotDone() {
        return startSnapshotDone;
    }

    public static boolean isMp4(String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase(Locale.US).endsWith(".mp4");
    }
}
