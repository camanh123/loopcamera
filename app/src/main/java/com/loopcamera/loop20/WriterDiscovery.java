package com.loopcamera.loop20;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runtime diagnostic: watch USB DCIM/Camera MP4 create/growth and correlate
 * whichever process evidence this ROM actually exposes.
 * Read/stat/watch only — never delete, rename, move, truncate, or open MP4 for write.
 */
public final class WriterDiscovery {

    public static final long POLL_MS = 750L;
    private static final int FO_MASK = FileObserver.CREATE
            | FileObserver.OPEN
            | FileObserver.MODIFY
            | FileObserver.CLOSE_WRITE
            | FileObserver.MOVED_TO
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.CLOSE_NOWRITE
            | FileObserver.ATTRIB;

    private final Context app;
    private final AppPreferences prefs;
    private final DcimStore store;
    private final Mp4WatchTracker tracker = new Mp4WatchTracker();
    private final List<String> timeline = new ArrayList<>();
    private final Object lock = new Object();

    private volatile boolean running;
    private FileObserver observer;
    private ContentObserver mediaObserver;
    private File targetDir;
    private Set<String> aliases = new LinkedHashSet<>();
    private ProcFdProbe.Capabilities caps;
    private ProcFdProbe.FdHit lastHit;
    private String lastHitFile = "";
    private String mediaStoreNote = "not queried yet";
    private String mediaStoreOwner = "";
    private String foregroundNote = "not captured yet";
    private String foregroundIdentity = "";
    private String logcatNote = "not probed yet";
    private String logcatIdentity = "";
    private String lsofNote = "not run yet";
    private String lsofIdentity = "";
    private String lastShellFile = "";
    private long lastShellMs;
    private boolean fileObserverStarted;
    private boolean fileObserverEventSeen;
    private String observerNote = "not started";
    private String usageNote = "not probed";
    private String usageIdentity = "";
    private WriterConfidence.Verdict verdict =
            WriterConfidence.evaluate(null, null, null);

    public WriterDiscovery(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new AppPreferences(this.app);
        this.store = new DcimStore(this.app);
    }

    public void requestStop() {
        running = false;
    }

    public void runLoop() {
        running = true;
        try {
            startSession();
            while (running) {
                try {
                    tick();
                } catch (Throwable t) {
                    LoopLog.get().e("Writer discovery tick failed (continuing)", t);
                    note(System.currentTimeMillis(), "tick error " + t.getClass().getSimpleName());
                    publish();
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            stopWatches();
            running = false;
            publish();
            LoopLog.get().i("WRITER DISCOVERY stopped — no files were modified");
        }
    }

    private void startSession() {
        synchronized (lock) {
            tracker.reset();
            timeline.clear();
            lastHit = null;
            lastHitFile = "";
            mediaStoreOwner = "";
            logcatIdentity = "";
            lsofIdentity = "";
            usageIdentity = "";
            fileObserverEventSeen = false;
        }
        LoopLog.get().i("WRITER DISCOVERY — READ/STAT/WATCH ONLY. No delete/rename/write.");
        note(System.currentTimeMillis(), "START watching USB DCIM/Camera for MP4 writer");
        caps = ProcFdProbe.probeCapabilities(app);
        caps.mediaStore = "pending first MP4";
        caps.usageStats = usageNote;
        targetDir = resolveTargetDir();
        aliases = PathAliasResolver.collectCameraDirAliases(
                targetDir, UsbLocator.listVolumeRoots(app), ProcFdProbe.readMountinfo());
        note(System.currentTimeMillis(), "target=" + describeDir(targetDir));
        note(System.currentTimeMillis(), "aliases=" + PathAliasResolver.describeAliases(aliases));
        snapshotExisting();
        startFileObserver();
        startMediaObserver();
        probeUsageStats();
        publish();
    }

    private File resolveTargetDir() {
        File resolved = FolderResolver.resolve(app, prefs);
        if (FolderResolver.isUsableDir(resolved)) {
            return resolved;
        }
        File usb1 = new File("/storage/USB1/DCIM/Camera");
        if (FolderResolver.isUsableDir(usb1)) {
            return usb1;
        }
        List<UsbLocator.Candidate> found = UsbLocator.findDcimCandidates(app);
        for (UsbLocator.Candidate c : found) {
            String p = c.directory.getAbsolutePath().toLowerCase(Locale.US);
            if (p.endsWith("/dcim/camera")) {
                return c.directory;
            }
        }
        if (!found.isEmpty()) {
            return found.get(0).directory;
        }
        return usb1;
    }

    private void snapshotExisting() {
        long now = System.currentTimeMillis();
        if (!FolderResolver.isUsableDir(targetDir)) {
            tracker.markStartSnapshotDone();
            observerNote = "target not listable yet: " + describeDir(targetDir);
            return;
        }
        DcimStore.Folder folder = store.openFile(targetDir);
        List<DcimEntry> kids = store.listChildren(folder);
        if (kids == null) {
            tracker.markStartSnapshotDone();
            return;
        }
        int mp4 = 0;
        for (DcimEntry e : kids) {
            if (e.directory || !Mp4WatchTracker.isMp4(e.displayName)) {
                continue;
            }
            mp4++;
            String path = e.documentId != null ? e.documentId : new File(targetDir, e.displayName).getAbsolutePath();
            tracker.snapshotExisting(e.displayName, path, e.size, e.lastModified, now);
        }
        tracker.markStartSnapshotDone();
        note(now, "start snapshot mp4Count=" + mp4 + " (pre-existing not treated as NEW unless they grow)");
    }

    private void startFileObserver() {
        stopObserverOnly();
        if (targetDir == null) {
            observerNote = "no target dir";
            return;
        }
        try {
            observer = new FileObserver(targetDir, FO_MASK) {
                @Override
                public void onEvent(int event, String path) {
                    handleFsEvent(event, path);
                }
            };
            observer.startWatching();
            fileObserverStarted = true;
            observerNote = "started on " + targetDir.getAbsolutePath()
                    + " (USB/FUSE may not deliver events; poll is the fallback)";
            note(System.currentTimeMillis(), "FileObserver started");
        } catch (Throwable t) {
            fileObserverStarted = false;
            observerNote = "FileObserver failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            observer = null;
            note(System.currentTimeMillis(), observerNote);
        }
    }

    private void startMediaObserver() {
        try {
            mediaObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    note(System.currentTimeMillis(), "MediaStore onChange " + uri);
                }
            };
            app.getContentResolver().registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
        } catch (Throwable t) {
            mediaObserver = null;
            note(System.currentTimeMillis(), "MediaStore observer unavailable: " + t.getMessage());
        }
    }

    private void handleFsEvent(int event, String path) {
        int e = event & FileObserver.ALL_EVENTS;
        String name = foName(e);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            fileObserverEventSeen = true;
            List<Mp4WatchTracker.Event> ev = tracker.onFsEvent(path, name, now);
            for (Mp4WatchTracker.Event x : ev) {
                noteLocked(x.atMs, x.text);
            }
        }
        publish();
    }

    private void tick() {
        File dir = resolveTargetDir();
        boolean remount = false;
        synchronized (lock) {
            if (targetDir == null || !dir.getAbsolutePath().equals(targetDir.getAbsolutePath())) {
                targetDir = dir;
                aliases = PathAliasResolver.collectCameraDirAliases(
                        targetDir, UsbLocator.listVolumeRoots(app), ProcFdProbe.readMountinfo());
                remount = true;
            }
        }
        if (remount) {
            startFileObserver();
            snapshotExisting();
        }
        pollDirectory();
        Mp4WatchTracker.Track current;
        synchronized (lock) {
            current = tracker.current();
        }
        boolean hot = current != null
                && (Mp4WatchTracker.NEW.equals(current.state) || Mp4WatchTracker.GROWING.equals(current.state));
        if (hot) {
            File mp4 = currentFile(current);
            captureForeground(current);
            if (Mp4WatchTracker.NEW.equals(current.state)) {
                probeUsageStats();
            }
            scanFd(mp4, current);
            maybeShell(mp4, current);
            maybeLogcat(current);
            probeMediaStore(mp4, current);
        }
        evaluate();
        publish();
    }

    private void pollDirectory() {
        if (!FolderResolver.isUsableDir(targetDir)) {
            return;
        }
        DcimStore.Folder folder = store.openFile(targetDir);
        List<DcimEntry> kids = store.listChildren(folder);
        if (kids == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            for (DcimEntry e : kids) {
                if (e.directory || !Mp4WatchTracker.isMp4(e.displayName)) {
                    continue;
                }
                String path = e.documentId != null
                        ? e.documentId
                        : new File(targetDir, e.displayName).getAbsolutePath();
                List<Mp4WatchTracker.Event> ev = tracker.observe(e.displayName, path, e.size, e.lastModified, now);
                for (Mp4WatchTracker.Event x : ev) {
                    noteLocked(x.atMs, x.text);
                }
            }
        }
    }

    private void scanFd(File mp4, Mp4WatchTracker.Track current) {
        try {
            ProcFdProbe.FdHit hit = ProcFdProbe.scanForWriter(app, mp4, aliases, 400L);
            if (hit != null) {
                synchronized (lock) {
                    lastHit = hit;
                    lastHitFile = current.filename;
                    noteLocked(System.currentTimeMillis(),
                            "PID " + hit.pid + " FD " + hit.fd + " -> " + hit.fdTarget);
                    noteLocked(System.currentTimeMillis(),
                            "PID " + hit.pid + " cmdline=" + ProcFdProbe.trimCmd(hit.cmdline)
                                    + " UID " + hit.uid + " packages " + hit.packagesJoined());
                }
                LoopLog.get().i("WRITER DISCOVERY FD hit pid=" + hit.pid + " -> " + hit.fdTarget);
            }
        } catch (Throwable t) {
            note(System.currentTimeMillis(), "fd scan error " + t.getClass().getSimpleName());
        }
    }

    private void maybeShell(File mp4, Mp4WatchTracker.Track current) {
        long now = SystemClock.elapsedRealtime();
        if (current.filename.equals(lastShellFile) && now - lastShellMs < 3000L) {
            return;
        }
        lastShellFile = current.filename;
        lastShellMs = now;
        String lsof = ProcFdProbe.runAgainstFile("lsof", mp4);
        String toy = ProcFdProbe.runAgainstFile("toybox-lsof", mp4);
        String fuser = ProcFdProbe.runAgainstFile("fuser", mp4);
        StringBuilder sb = new StringBuilder();
        sb.append("lsof: ").append(ProcFdProbe.trimCmd(lsof)).append('\n');
        sb.append("toybox lsof: ").append(ProcFdProbe.trimCmd(toy)).append('\n');
        sb.append("fuser: ").append(ProcFdProbe.trimCmd(fuser));
        lsofNote = sb.toString();
        String ident = identityFromShell(lsof, mp4.getName());
        if (ident == null) {
            ident = identityFromShell(toy, mp4.getName());
        }
        if (ident == null) {
            ident = identityFromShell(fuser, mp4.getName());
        }
        if (ident != null) {
            lsofIdentity = ident;
            note(System.currentTimeMillis(), "shell writer " + ident);
        }
        if (caps != null) {
            caps.lsof = ProcFdProbe.trimCmd(lsof);
            caps.toyboxLsof = ProcFdProbe.trimCmd(toy);
            caps.fuser = ProcFdProbe.trimCmd(fuser);
        }
    }

    private String identityFromShell(String out, String filename) {
        if (out == null) {
            return null;
        }
        String l = out.toLowerCase(Locale.US);
        if (l.startsWith("unavailable") || l.contains("not found") || l.startsWith("timeout")
                || l.contains("permission denied") || l.contains("empty output")) {
            return null;
        }
        if (filename != null && !l.contains(filename.toLowerCase(Locale.US))
                && !l.matches("(?s).*\\b\\d+\\b.*")) {
            return null;
        }
        Integer pid = firstForeignPid(out);
        if (pid == null) {
            return null;
        }
        String cmd = ProcFdProbe.readSmall(new File("/proc/" + pid + "/cmdline"), 4096);
        String status = ProcFdProbe.readSmall(new File("/proc/" + pid + "/status"), 8192);
        int uid = ProcFdProbe.parseUid(status);
        String name = cmd != null ? cmd.replace('\0', ' ').trim() : ProcFdProbe.parseStatusName(status);
        String pkg = null;
        if (uid >= 0) {
            try {
                String[] pkgs = app.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    pkg = pkgs[0];
                }
            } catch (Throwable ignored) {
            }
        }
        if (pkg != null) {
            return pkg + " (pid " + pid + " uid " + uid + " cmdline=" + ProcFdProbe.trimCmd(name) + ")";
        }
        if (name != null && !name.isEmpty()) {
            return name + " (pid " + pid + " uid " + uid + ")";
        }
        return "pid " + pid + " (uid " + uid + ")";
    }

    private Integer firstForeignPid(String out) {
        int self = android.os.Process.myPid();
        String[] parts = out.split("[^0-9]+");
        for (String p : parts) {
            if (p.length() < 2 || p.length() > 7) {
                continue;
            }
            try {
                int pid = Integer.parseInt(p);
                if (pid != self && pid > 1 && new File("/proc/" + pid).isDirectory()) {
                    return pid;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void maybeLogcat(Mp4WatchTracker.Track current) {
        if (current == null) {
            return;
        }
        if (logcatNote != null && logcatNote.startsWith("hits for ") && logcatNote.contains(current.filename)) {
            return;
        }
        if (!Mp4WatchTracker.NEW.equals(current.state) && lastHit != null) {
            return;
        }
        String dump = ProcFdProbe.dumpLogcat();
        if (caps != null) {
            caps.logcat = dump != null && dump.toLowerCase(Locale.US).contains("unavailable")
                    ? dump
                    : (caps.readLogsGranted ? "dump ok" : caps.logcat);
        }
        List<String> hits = ProcFdProbe.logHits(dump, current.filename, current.firstSeenMs);
        if (hits.isEmpty()) {
            logcatNote = caps != null && !caps.readLogsGranted
                    ? "READ_LOGS unavailable; no foreign log lines matched filename/DCIM/MediaRecorder"
                    : "no matching log lines around create";
            return;
        }
        StringBuilder sb = new StringBuilder("hits for ");
        sb.append(current.filename).append(':');
        for (String h : hits) {
            sb.append('\n').append(ProcFdProbe.trimCmd(h));
            String maybePkg = WriterConfidence.extractPackageToken(h);
            if (maybePkg != null && logcatIdentity.isEmpty()) {
                logcatIdentity = maybePkg;
            }
        }
        logcatNote = sb.toString();
        note(System.currentTimeMillis(), "logcat hits=" + hits.size());
    }

    private void probeMediaStore(File mp4, Mp4WatchTracker.Track current) {
        if (mp4 == null) {
            return;
        }
        if (mediaStoreNote != null && mediaStoreNote.contains(current.filename)
                && mediaStoreNote.startsWith("row ")) {
            return;
        }
        ContentResolver cr = app.getContentResolver();
        List<Uri> uris = new ArrayList<>();
        uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        try {
            Set<String> vols = MediaStore.getExternalVolumeNames(app);
            for (String v : vols) {
                uris.add(MediaStore.Video.Media.getContentUri(v));
            }
        } catch (Throwable ignored) {
        }
        String[] wide = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING
        };
        String[] narrow = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE
        };
        boolean found = false;
        StringBuilder detail = new StringBuilder();
        for (Uri uri : uris) {
            Cursor c = null;
            try {
                c = cr.query(uri, wide, MediaStore.Video.Media.DISPLAY_NAME + "=?",
                        new String[]{mp4.getName()}, null);
            } catch (IllegalArgumentException iae) {
                try {
                    c = cr.query(uri, narrow, MediaStore.Video.Media.DISPLAY_NAME + "=?",
                            new String[]{mp4.getName()}, null);
                } catch (Throwable t) {
                    detail.append("query failed ").append(uri).append(" ").append(t.getMessage()).append('\n');
                    continue;
                }
            } catch (Throwable t) {
                detail.append("query failed ").append(uri).append(" ").append(t.getMessage()).append('\n');
                continue;
            }
            try {
                if (c == null) {
                    continue;
                }
                if (c.moveToFirst()) {
                    found = true;
                    String owner = col(c, MediaStore.MediaColumns.OWNER_PACKAGE_NAME);
                    String data = col(c, MediaStore.Video.Media.DATA);
                    String pending = col(c, MediaStore.MediaColumns.IS_PENDING);
                    mediaStoreNote = "row uri=" + uri
                            + " owner_package_name=" + (owner == null ? "(column missing/null)" : owner)
                            + " data=" + data
                            + " is_pending=" + pending;
                    if (owner != null && !owner.isEmpty()) {
                        mediaStoreOwner = owner;
                    }
                    if (caps != null) {
                        caps.mediaStore = mediaStoreNote;
                    }
                    note(System.currentTimeMillis(), "MediaStore " + mediaStoreNote);
                    break;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        if (!found) {
            mediaStoreNote = "no MediaStore row for " + mp4.getName()
                    + " (USB DCIM often unindexed on this ROM)";
            if (caps != null) {
                caps.mediaStore = mediaStoreNote;
            }
        }
    }

    private static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        if (i < 0 || c.isNull(i)) {
            return null;
        }
        try {
            return c.getString(i);
        } catch (Exception e) {
            try {
                return String.valueOf(c.getLong(i));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private void captureForeground(Mp4WatchTracker.Track current) {
        List<ProcFdProbe.RunningProc> running = ProcFdProbe.runningAppProcesses(app);
        foregroundNote = ProcFdProbe.foregroundSummary(running);
        for (ProcFdProbe.RunningProc p : running) {
            if (p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                if (p.pkgList != null && p.pkgList.length > 0
                        && !"com.loopcamera.loop20".equals(p.pkgList[0])) {
                    foregroundIdentity = p.pkgList[0];
                    break;
                }
                if (!p.processName.isEmpty() && !p.processName.contains("loopcamera")) {
                    foregroundIdentity = p.processName;
                    break;
                }
            }
        }
        if (Mp4WatchTracker.NEW.equals(current.state)) {
            note(System.currentTimeMillis(), "foreground " + foregroundNote);
        }
    }

    private void probeUsageStats() {
        try {
            UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                usageNote = "UsageStatsManager null";
                return;
            }
            long end = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(end - 15_000L, end);
            if (events == null) {
                usageNote = "queryEvents returned null (PACKAGE_USAGE_STATS not granted)";
                return;
            }
            UsageEvents.Event ev = new UsageEvents.Event();
            int n = 0;
            String lastMove = "";
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                n++;
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        && ev.getPackageName() != null
                        && !"com.loopcamera.loop20".equals(ev.getPackageName())) {
                    lastMove = ev.getPackageName();
                }
            }
            if (n == 0) {
                usageNote = "UsageEvents empty (PACKAGE_USAGE_STATS not granted / AppOps denied)";
            } else {
                usageNote = "UsageEvents readable count=" + n + " lastFg=" + lastMove;
                usageIdentity = lastMove;
            }
        } catch (SecurityException se) {
            usageNote = "SecurityException (PACKAGE_USAGE_STATS / AppOps denied)";
        } catch (Throwable t) {
            usageNote = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (caps != null) {
            caps.usageStats = usageNote;
        }
    }

    private void evaluate() {
        Mp4WatchTracker.Track current;
        ProcFdProbe.FdHit hit;
        synchronized (lock) {
            current = tracker.current();
            hit = lastHit;
        }
        boolean hot = current != null
                && (Mp4WatchTracker.NEW.equals(current.state) || Mp4WatchTracker.GROWING.equals(current.state)
                || (current.grew && lastHitFile.equals(current.filename)));
        String fdId = null;
        if (hit != null && current != null && current.filename.equals(lastHitFile) && (hot || current.grew)) {
            fdId = hit.identity();
        }
        String lsofId = (current != null && current.filename.equals(lastShellFile)) ? lsofIdentity : null;
        List<WriterConfidence.Signal> indirect = new ArrayList<>();
        if (hot || (current != null && current.grew)) {
            if (mediaStoreOwner != null && !mediaStoreOwner.isEmpty()) {
                indirect.add(new WriterConfidence.Signal(
                        WriterConfidence.TYPE_MEDIASTORE, mediaStoreOwner, mediaStoreNote));
            }
            if (foregroundIdentity != null && !foregroundIdentity.isEmpty()
                    && !"com.loopcamera.loop20".equals(foregroundIdentity)) {
                indirect.add(new WriterConfidence.Signal(
                        WriterConfidence.TYPE_FOREGROUND, foregroundIdentity, foregroundNote));
            }
            if (logcatIdentity != null && !logcatIdentity.isEmpty()) {
                indirect.add(new WriterConfidence.Signal(
                        WriterConfidence.TYPE_LOGCAT, logcatIdentity, logcatNote));
            }
            if (usageIdentity != null && !usageIdentity.isEmpty()) {
                indirect.add(new WriterConfidence.Signal(
                        WriterConfidence.TYPE_RUNNING, usageIdentity, usageNote));
            }
        }
        verdict = WriterConfidence.evaluate(fdId, lsofId, indirect);
    }

    private void publish() {
        WriterDiscoveryBus.Snapshot snap = new WriterDiscoveryBus.Snapshot();
        snap.running = running;
        WriterReportFormatter.Model m = new WriterReportFormatter.Model();
        Mp4WatchTracker.Track current;
        ProcFdProbe.FdHit hit;
        synchronized (lock) {
            current = tracker.current();
            hit = lastHit;
        }
        m.targetDirectory = describeDir(targetDir);
        m.state = current == null ? Mp4WatchTracker.WAITING : current.state;
        if (!FolderResolver.isUsableDir(targetDir) && current == null) {
            m.state = Mp4WatchTracker.WAITING;
        }
        m.observedMp4 = current == null ? "(none)" : current.filename;
        m.firstSeenTime = current == null ? "(none)" : stamp(current.firstSeenMs);
        m.growthObserved = current != null && current.grew;
        m.fileObserverEvents = current == null || current.fsEvents.isEmpty()
                ? (fileObserverStarted
                ? (fileObserverEventSeen ? observerNote : "started but no events yet — " + observerNote)
                : observerNote)
                : join(current.fsEvents);
        if (hit != null && current != null && current.filename.equals(lastHitFile)) {
            m.pid = String.valueOf(hit.pid);
            m.uid = String.valueOf(hit.uid);
            m.process = hit.cmdline.isEmpty() ? hit.statusName : hit.cmdline;
            m.pkg = hit.packagesJoined();
            m.fdEvidence = "FD " + hit.fd + " -> " + hit.fdTarget
                    + (hit.inodeMatch ? " (inode match)" : "");
        } else {
            m.pid = "(none)";
            m.uid = "(none)";
            m.process = "(none)";
            m.pkg = "(none)";
            m.fdEvidence = fdUnavailableReason();
        }
        m.mountAliases = PathAliasResolver.describeAliases(aliases);
        m.foreground = foregroundNote + (usageNote.isEmpty() ? "" : "\n" + usageNote);
        m.logCorrelation = logcatNote;
        m.unavailable = (caps == null ? "capabilities not probed" : caps.unavailableSummary())
                + "\n- FileObserver: " + observerNote
                + "\n- shell:\n" + lsofNote;
        m.confidence = verdict.level;
        m.confidenceReason = verdict.reason;
        m.finalLine = verdict.finalLine();
        m.timeline = timelineText();
        snap.model = m;
        snap.liveText = WriterReportFormatter.livePanel(m);
        snap.reportText = WriterReportFormatter.compactReport(m);
        WriterDiscoveryBus.get().post(snap);
    }

    private String fdUnavailableReason() {
        StringBuilder sb = new StringBuilder();
        if (caps != null && caps.fdDirsReadable <= 1 && caps.fdDirsDenied > 0) {
            sb.append("/proc/[pid]/fd not readable for other UIDs (EACCES/hidepid). ");
        } else if (lastHit == null) {
            sb.append("No process FD currently points at the watched MP4. ");
        }
        if (lsofNote != null && lsofNote.toLowerCase(Locale.US).contains("not found")) {
            sb.append("lsof/fuser not usable from app sandbox. ");
        }
        if (sb.length() == 0) {
            sb.append("Direct FD evidence not obtained on this pass. ");
        }
        sb.append("Stat/watch only — files were not opened for write.");
        return sb.toString();
    }

    private File currentFile(Mp4WatchTracker.Track t) {
        if (t == null) {
            return null;
        }
        if (t.absPath != null && !t.absPath.isEmpty()) {
            return new File(t.absPath);
        }
        if (targetDir != null) {
            return new File(targetDir, t.filename);
        }
        return new File("/storage/USB1/DCIM/Camera", t.filename);
    }

    private void stopWatches() {
        stopObserverOnly();
        if (mediaObserver != null) {
            try {
                app.getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (Throwable ignored) {
            }
            mediaObserver = null;
        }
    }

    private void stopObserverOnly() {
        if (observer != null) {
            try {
                observer.stopWatching();
            } catch (Throwable ignored) {
            }
            observer = null;
        }
        fileObserverStarted = false;
    }

    private void note(long atMs, String text) {
        synchronized (lock) {
            noteLocked(atMs, text);
        }
    }

    private void noteLocked(long atMs, String text) {
        timeline.add(stamp(atMs) + " " + text);
        while (timeline.size() > 120) {
            timeline.remove(0);
        }
    }

    private String timelineText() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, timeline.size() - 40);
            for (int i = from; i < timeline.size(); i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(timeline.get(i));
            }
            return sb.toString();
        }
    }

    private static String stamp(long ms) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(ms));
    }

    private static String describeDir(File dir) {
        return dir == null ? "(unresolved)" : dir.getAbsolutePath();
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    static String foName(int event) {
        List<String> names = new ArrayList<>();
        if ((event & FileObserver.CREATE) != 0) {
            names.add("CREATE");
        }
        if ((event & FileObserver.OPEN) != 0) {
            names.add("OPEN");
        }
        if ((event & FileObserver.MODIFY) != 0) {
            names.add("MODIFY");
        }
        if ((event & FileObserver.CLOSE_WRITE) != 0) {
            names.add("CLOSE_WRITE");
        }
        if ((event & FileObserver.CLOSE_NOWRITE) != 0) {
            names.add("CLOSE_NOWRITE");
        }
        if ((event & FileObserver.MOVED_TO) != 0) {
            names.add("MOVED_TO");
        }
        if ((event & FileObserver.MOVED_FROM) != 0) {
            names.add("MOVED_FROM");
        }
        if ((event & FileObserver.DELETE) != 0) {
            names.add("DELETE");
        }
        if ((event & FileObserver.ATTRIB) != 0) {
            names.add("ATTRIB");
        }
        if (names.isEmpty()) {
            return "0x" + Integer.toHexString(event);
        }
        return join(names);
    }
}
