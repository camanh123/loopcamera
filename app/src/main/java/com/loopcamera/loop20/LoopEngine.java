package com.loopcamera.loop20;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors timestamp-named camera clips. Never renames or rewrites video bytes.
 * LOOP MODE may delete only the oldest COMPLETE file when count &gt; 30.
 */
public final class LoopEngine {

    public static final long POLL_MS = LoopPlanner.POLL_MS;

    private final Context app;
    private final AppPreferences prefs;
    private final DcimStore store;

    private final Map<String, FileTrack> tracks = new HashMap<>();
    private final Set<String> loggedDetected = new HashSet<>();
    private final Set<String> loggedComplete = new HashSet<>();
    private final Set<String> loggedPlan = new HashSet<>();

    private volatile boolean processing;
    private int deleteFailStreak;
    private long lastWaitLogMs;
    private String lastResolvedPath = "";

    public LoopEngine(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new AppPreferences(context);
        this.store = new DcimStore(context);
    }

    public synchronized void resetTracking() {
        tracks.clear();
        loggedDetected.clear();
        loggedComplete.clear();
        loggedPlan.clear();
        lastResolvedPath = "";
    }

    public synchronized void onUsbChanged() {
        LoopLog.get().i("USB mount thay đổi — sẽ quét lại DCIM/Camera.");
        lastResolvedPath = "";
    }

    public synchronized void tick() {
        deleteFailStreak = prefs.getDeleteFailStreak();
        String mode = prefs.getMode();
        if (!prefs.hasSavedFolder()) {
            LoopStateBus.get().post(LoopUiState.idle(mode, prefs.isFailsafe(), prefs.getFailsafeReason(),
                    LoopUiState.PHASE_IDLE, "Chưa chọn thư mục USB/DCIM/Camera"));
            return;
        }

        File dir = FolderResolver.resolve(app, prefs);
        if (dir == null || !FolderResolver.isUsableDir(dir)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastWaitLogMs > 15_000L) {
                LoopLog.get().i("Waiting for USB — chưa thấy " + prefs.getRelativePath()
                        + " (đã lưu " + prefs.getFolderPath() + "). Retry, không dừng.");
                lastWaitLogMs = now;
            }
            LoopStateBus.get().post(LoopUiState.idle(mode, prefs.isFailsafe(), prefs.getFailsafeReason(),
                    LoopUiState.PHASE_WAITING_USB,
                    "Waiting for USB — sẽ tự reconnect khi USB mount (không hard-code USB1)"));
            return;
        }

        if (!dir.getAbsolutePath().equals(lastResolvedPath)) {
            LoopLog.get().i("Monitoring: " + dir.getAbsolutePath());
            lastResolvedPath = dir.getAbsolutePath();
            resetTrackingKeepResolved(dir.getAbsolutePath());
        }

        DcimStore.Folder folder = store.openFile(dir);
        if (folder == null) {
            LoopStateBus.get().post(LoopUiState.idle(mode, prefs.isFailsafe(), prefs.getFailsafeReason(),
                    LoopUiState.PHASE_WAITING_USB, "Waiting for USB — thư mục chưa đọc được"));
            return;
        }

        List<DcimEntry> children = store.listChildren(folder);
        if (children == null) {
            LoopStateBus.get().post(LoopUiState.idle(mode, prefs.isFailsafe(), prefs.getFailsafeReason(),
                    LoopUiState.PHASE_WAITING_USB, "Waiting for USB — listFiles=null"));
            return;
        }

        pruneTracks(children);
        List<Managed> managed = new ArrayList<>();
        for (DcimEntry e : children) {
            if (e.directory) {
                continue;
            }
            if (!LoopPlanner.isManagedVideo(e.displayName)) {
                continue;
            }
            FileTrack t = updateTrack(e);
            managed.add(new Managed(e, t));
        }
        managed.sort(Comparator.comparingLong((Managed m) -> LoopPlanner.timestampSortKey(m.entry.displayName)));

        List<Managed> complete = new ArrayList<>();
        for (Managed m : managed) {
            if (m.track.state == LoopPlanner.FileState.COMPLETE) {
                complete.add(m);
            }
        }

        Managed newest = managed.isEmpty() ? null : managed.get(managed.size() - 1);
        Managed oldestComplete = complete.isEmpty() ? null : complete.get(0);

        List<String> planned = new ArrayList<>();
        if (complete.size() > LoopPlanner.MAX_VIDEOS && oldestComplete != null) {
            planned.add("DELETE " + oldestComplete.entry.displayName);
        }

        String newestText = newest == null ? "—"
                : newest.entry.displayName + " (" + DcimStore.formatSize(newest.entry.size) + ")";
        String oldestText = oldestComplete == null ? "—"
                : oldestComplete.entry.displayName + " (" + DcimStore.formatSize(oldestComplete.entry.size) + ")";
        String newestState = newest == null ? "—" : newest.track.state.name();
        String oldestState = oldestComplete == null ? "—" : oldestComplete.track.state.name();

        String detail = LoopUiState.PHASE_CONNECTED + " / " + LoopUiState.PHASE_MONITORING
                + "\nCOMPLETE " + complete.size() + " | WAITING/NOT_READY "
                + (managed.size() - complete.size())
                + " | pattern " + LoopPlanner.FILENAME_EXAMPLE;
        if (prefs.isFailsafe()) {
            detail += "\nFAILSAFE — không xóa";
        } else if (!prefs.isLoopMode()) {
            detail += "\nTEST MODE — không xóa/rename/modify";
        }

        LoopStateBus.get().post(new LoopUiState(
                true,
                folder.label,
                complete.size(),
                mode,
                prefs.isFailsafe(),
                prefs.getFailsafeReason(),
                newestText,
                oldestText,
                newestState,
                oldestState,
                planned,
                detail,
                LoopUiState.PHASE_CONNECTED,
                processing));

        if (complete.size() > LoopPlanner.MAX_VIDEOS && oldestComplete != null) {
            maybeLogPlan(oldestComplete, complete.size());
        }

        if (processing) {
            return;
        }
        if (!DeletePolicy.allowRealDelete(mode, prefs.isFailsafe(), complete.size())) {
            return;
        }
        deleteOldestIfSafe(folder, oldestComplete);
    }

    private void resetTrackingKeepResolved(String path) {
        tracks.clear();
        loggedDetected.clear();
        loggedComplete.clear();
        loggedPlan.clear();
        lastResolvedPath = path;
    }

    private FileTrack updateTrack(DcimEntry e) {
        long now = SystemClock.elapsedRealtime();
        String key = e.documentId;
        FileTrack t = tracks.get(key);
        if (t == null) {
            t = new FileTrack();
            t.firstSeenMs = now;
            t.lastSize = -1L;
            tracks.put(key, t);
            if (loggedDetected.add(key)) {
                LoopLog.get().i("Phát hiện file timestamp: " + e.displayName
                        + " size=" + DcimStore.formatSize(e.size) + " — chưa COMPLETE, đang chờ ổn định.");
            }
        }
        t.name = e.displayName;

        if (t.lastSize != e.size) {
            t.lastSize = e.size;
            t.lastChangeMs = now;
            t.mmrDurationMs = -1L;
            t.state = LoopPlanner.FileState.WAITING;
            loggedComplete.remove(key);
        } else if (t.state == LoopPlanner.FileState.COMPLETE) {
            return t;
        }

        long stableFor = now - t.lastChangeMs;
        long age = now - t.firstSeenMs;
        boolean sizeOk = e.size >= LoopPlanner.MIN_STABLE_BYTES;
        boolean stable = sizeOk && stableFor >= prefs.getStableMs() && age >= prefs.getMinAgeMs();

        if (!sizeOk || t.lastChangeMs == now && t.lastSize == e.size && age < prefs.getMinAgeMs()) {
            t.state = LoopPlanner.FileState.WAITING;
            return t;
        }
        if (!stable) {
            t.state = LoopPlanner.FileState.WAITING;
            return t;
        }

        long duration = probeDurationMs(e);
        if (duration > 0) {
            t.mmrDurationMs = duration;
            t.state = LoopPlanner.FileState.COMPLETE;
            if (loggedComplete.add(key)) {
                LoopLog.get().i("COMPLETE: " + e.displayName
                        + " size=" + DcimStore.formatSize(e.size)
                        + " stable=" + (stableFor / 1000) + "s"
                        + " duration=" + (duration / 1000) + "s");
            }
            return t;
        }

        if (stableFor >= LoopPlanner.INVALID_AFTER_STABLE_MS) {
            t.state = LoopPlanner.FileState.INVALID;
            LoopLog.get().w("INVALID (không xóa): " + e.displayName
                    + " — MMR không đọc được sau khi size đã ổn định lâu. Không convert/repair.");
            return t;
        }
        t.state = LoopPlanner.FileState.NOT_READY;
        return t;
    }

    /**
     * Read-only probe. Never writes. If the file is still being recorded, MMR typically
     * fails — that is treated as NOT_READY, never as a reason to delete.
     */
    private long probeDurationMs(DcimEntry e) {
        if (!DcimStore.isFileUri(e.uri)) {
            return -1L;
        }
        String path = e.documentId;
        if (path == null) {
            return -1L;
        }
        File f = new File(path);
        if (!f.isFile()) {
            return -1L;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur == null) {
                return -1L;
            }
            return Long.parseLong(dur);
        } catch (Throwable t) {
            return -1L;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void maybeLogPlan(Managed oldest, int completeCount) {
        if (!loggedPlan.add(oldest.entry.documentId + ":" + completeCount)) {
            return;
        }
        if (prefs.isLoopMode() && !prefs.isFailsafe()) {
            LoopLog.get().i("LOOP: " + completeCount + " COMPLETE > " + LoopPlanner.MAX_VIDEOS
                    + " → sẽ xóa oldest COMPLETE: " + oldest.entry.displayName);
        } else {
            LoopLog.get().i("TEST MODE predicted: DELETE " + oldest.entry.displayName
                    + " (" + completeCount + "/" + LoopPlanner.MAX_VIDEOS + ") — không thực hiện.");
        }
    }

    private void deleteOldestIfSafe(DcimStore.Folder folder, Managed oldest) {
        processing = true;
        try {
            DcimEntry live = store.findByName(folder, oldest.entry.displayName);
            if (live == null) {
                LoopLog.get().w("Oldest biến mất trước khi xóa: " + oldest.entry.displayName);
                return;
            }
            if (!LoopPlanner.isManagedVideo(live.displayName)) {
                LoopLog.get().e("Từ chối xóa — không khớp pattern timestamp: " + live.displayName);
                return;
            }
            FileTrack t = tracks.get(live.documentId);
            if (t == null || t.state != LoopPlanner.FileState.COMPLETE) {
                LoopLog.get().i("Bỏ xóa — file không còn COMPLETE: " + live.displayName);
                return;
            }
            if (live.size != t.lastSize) {
                LoopLog.get().i("Bỏ xóa — size vừa đổi (có thể camera đang ghi): " + live.displayName);
                t.state = LoopPlanner.FileState.WAITING;
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            DcimEntry again = store.findByName(folder, live.displayName);
            if (again == null || again.size != live.size) {
                LoopLog.get().i("Bỏ xóa — size đổi lúc recheck: " + live.displayName);
                return;
            }
            long dur = probeDurationMs(again);
            if (dur <= 0) {
                LoopLog.get().i("Bỏ xóa — MMR không đọc được lúc recheck (NOT_READY), không xóa: "
                        + again.displayName);
                return;
            }
            // probeDurationMs always release()/close() in finally. Brief pause so FUSE
            // on /storage/USB1 can drop the handle before POSIX/SAF delete.
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!DeletePolicy.readyToDelete(true, t.state == LoopPlanner.FileState.COMPLETE, again.size == live.size)) {
                LoopLog.get().i("Bỏ xóa — preconditions không đủ (retriever/complete/size).");
                return;
            }
            UsbDeleteLog.i("DELETE_ATTEMPT",
                    "oldest=" + again.displayName
                    + " size=" + DcimStore.formatSize(again.size)
                    + " mmrReleased=true delayMs=200");
            if (!store.delete(again)) {
                deleteFailStreak++;
                prefs.setDeleteFailStreak(deleteFailStreak);
                UsbDeleteLog.e("DELETE_RESULT",
                        "failed name=" + again.displayName
                        + " streak=" + deleteFailStreak
                        + " existsAfterDelete=true");
                if (DeletePolicy.enterFailsafe(deleteFailStreak)) {
                    enterFailsafe("Xóa oldest thất bại 3 lần. Dừng LOOP để tránh xóa hàng loạt.");
                }
                return;
            }
            deleteFailStreak = 0;
            prefs.setDeleteFailStreak(0);
            tracks.remove(again.documentId);
            UsbDeleteLog.i("DELETE_RESULT",
                    "success name=" + again.displayName
                    + " existsAfterDelete=false");
        } finally {
            processing = false;
        }
    }

    private void pruneTracks(List<DcimEntry> children) {
        Set<String> live = new HashSet<>();
        for (DcimEntry e : children) {
            live.add(e.documentId);
        }
        tracks.keySet().removeIf(k -> !live.contains(k));
        loggedDetected.removeIf(k -> !live.contains(k));
        loggedComplete.removeIf(k -> !live.contains(k));
    }

    private void enterFailsafe(String reason) {
        prefs.setFailsafe(true, reason);
        prefs.setDeleteFailStreak(deleteFailStreak);
        if (prefs.isLoopMode()) {
            prefs.setMode(AppPreferences.MODE_TEST);
        }
        UsbDeleteLog.e("FAILSAFE", reason + " streak=" + deleteFailStreak);
    }

    private static final class FileTrack {
        long firstSeenMs;
        long lastSize = -1L;
        long lastChangeMs;
        long mmrDurationMs = -1L;
        LoopPlanner.FileState state = LoopPlanner.FileState.WAITING;
        String name;
    }

    private static final class Managed {
        final DcimEntry entry;
        final FileTrack track;

        Managed(DcimEntry entry, FileTrack track) {
            this.entry = entry;
            this.track = track;
        }
    }
}
