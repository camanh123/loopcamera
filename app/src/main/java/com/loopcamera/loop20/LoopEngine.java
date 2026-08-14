package com.loopcamera.loop20;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches the granted DCIM tree, waits for files to stop growing, then either
 * simulates (TEST MODE) or rotates 10 loop slots (LOOP MODE).
 */
public final class LoopEngine {

    public static final long POLL_MS = 2000L;
    public static final int STABLE_POLLS = 3;
    public static final long MIN_STABLE_BYTES = 1024L;

    private final AppPreferences prefs;
    private final DcimStore store;

    private final Map<String, SizeTrack> tracks = new HashMap<>();
    private final Set<String> loggedDetected = new HashSet<>();
    private final Set<String> loggedStable = new HashSet<>();
    private final Set<String> loggedTestPlan = new HashSet<>();

    private volatile boolean processing;
    private String lastDetail = "";

    public LoopEngine(Context context) {
        this.prefs = new AppPreferences(context);
        this.store = new DcimStore(context);
    }

    public synchronized void resetTracking() {
        tracks.clear();
        loggedDetected.clear();
        loggedStable.clear();
        loggedTestPlan.clear();
    }

    public synchronized void tick() {
        Uri selected = prefs.getTreeUri();
        String mode = prefs.getMode();
        if (selected == null) {
            LoopStateBus.get().post(LoopUiState.disconnected(mode, prefs.isFailsafe(), prefs.getFailsafeReason()));
            return;
        }

        DcimStore.Folder folder = store.open(selected);
        if (folder == null || !store.isAccessible(folder)) {
            lastDetail = "USB/DCIM: Not connected";
            LoopStateBus.get().post(new LoopUiState(
                    false, "(không đọc được)", 0, mode, prefs.isFailsafe(),
                    prefs.getFailsafeReason(), "—", "—", Collections.emptyList(),
                    lastDetail, processing));
            return;
        }

        if ((store.hasTxn(folder) || prefs.isTxnActive()) && !prefs.isFailsafe()) {
            enterFailsafe("Phát hiện giao dịch dở dang sau khi app/máy khởi động lại. Không tiếp tục xóa/đổi tên.");
        }

        List<DcimEntry> children = store.listChildren(folder);
        if (children == null) {
            lastDetail = "USB/DCIM: Not connected";
            LoopStateBus.get().post(new LoopUiState(
                    false, folder.label, 0, mode, prefs.isFailsafe(),
                    prefs.getFailsafeReason(), "—", "—", Collections.emptyList(),
                    lastDetail, processing));
            return;
        }

        Set<String> exts = LoopPlanner.parseExtensions(prefs.getVideoExtensions());
        Map<Integer, DcimEntry> slots = new HashMap<>();
        List<DcimEntry> cameraVideos = new ArrayList<>();
        pruneTracks(children);

        for (DcimEntry e : children) {
            if (e.directory) {
                continue;
            }
            if (!LoopPlanner.isVideoFile(e.displayName, exts)) {
                continue;
            }
            Integer slot = LoopPlanner.slotNumber(e.displayName);
            if (slot != null) {
                slots.put(slot, e);
            } else {
                cameraVideos.add(e);
                watchForStability(e);
            }
        }

        List<DcimEntry> stableNew = new ArrayList<>();
        for (DcimEntry e : cameraVideos) {
            if (isStable(e)) {
                stableNew.add(e);
            }
        }
        stableNew.sort(Comparator.comparingLong((DcimEntry e) -> e.lastModified)
                .thenComparing(e -> e.displayName));

        String newest = describeNewest(slots, stableNew, cameraVideos);
        String oldest = describeOldest(slots, cameraVideos);
        List<String> planned = new ArrayList<>();
        DcimEntry next = stableNew.isEmpty() ? null : stableNew.get(0);
        if (next != null) {
            Set<Integer> occupied = new HashSet<>(slots.keySet());
            List<LoopPlanner.Action> actions = LoopPlanner.planForNewVideo(occupied, next.displayName);
            for (LoopPlanner.Action a : actions) {
                planned.add(LoopPlanner.describe(a));
            }
        }

        lastDetail = "USB/DCIM: Connected";
        if (folder.isFileMode()) {
            lastDetail += " (File/USB)";
        } else {
            lastDetail += " (SAF)";
        }
        if (prefs.isFailsafe()) {
            lastDetail += " | FAILSAFE — không xóa/đổi tên";
        } else if (processing) {
            lastDetail += " | đang xử lý";
        } else if (next != null && !prefs.isLoopMode()) {
            lastDetail += " | TEST MODE — chỉ mô phỏng";
        }

        LoopStateBus.get().post(new LoopUiState(
                true,
                folder.label,
                slots.size(),
                mode,
                prefs.isFailsafe(),
                prefs.getFailsafeReason(),
                newest,
                oldest,
                planned,
                lastDetail,
                processing));

        if (next == null || processing) {
            return;
        }

        if (!prefs.isLoopMode() || prefs.isFailsafe()) {
            maybeLogTestPlan(next, planned);
            return;
        }

        runLoopRotation(folder, next, slots);
    }

    private void watchForStability(DcimEntry e) {
        String key = e.documentId;
        SizeTrack t = tracks.get(key);
        if (t == null) {
            t = new SizeTrack();
            tracks.put(key, t);
            if (loggedDetected.add(key)) {
                LoopLog.get().i("Phát hiện video mới: " + e.displayName
                        + " (" + DcimStore.formatSize(e.size) + ")");
            }
        }
        if (t.lastSize == e.size && e.size >= MIN_STABLE_BYTES) {
            t.stableCount++;
        } else {
            t.stableCount = 0;
        }
        t.lastSize = e.size;
        t.name = e.displayName;
        if (t.stableCount > 0 && t.stableCount < STABLE_POLLS) {
            LoopLog.get().i("Chờ file ổn định: " + e.displayName
                    + " size=" + DcimStore.formatSize(e.size)
                    + " (" + t.stableCount + "/" + STABLE_POLLS + ")");
        }
        if (t.stableCount >= STABLE_POLLS && loggedStable.add(key)) {
            LoopLog.get().i("File đã ổn định: " + e.displayName
                    + " size=" + DcimStore.formatSize(e.size));
        }
    }

    private boolean isStable(DcimEntry e) {
        SizeTrack t = tracks.get(e.documentId);
        return t != null && t.stableCount >= STABLE_POLLS && e.size >= MIN_STABLE_BYTES;
    }

    private void pruneTracks(List<DcimEntry> children) {
        Set<String> live = new HashSet<>();
        for (DcimEntry e : children) {
            live.add(e.documentId);
        }
        tracks.keySet().removeIf(k -> !live.contains(k));
        loggedDetected.removeIf(k -> !live.contains(k));
        loggedStable.removeIf(k -> !live.contains(k));
        loggedTestPlan.removeIf(k -> !live.contains(k));
    }

    private void maybeLogTestPlan(DcimEntry next, List<String> planned) {
        if (!loggedTestPlan.add(next.documentId)) {
            return;
        }
        LoopLog.get().i("TEST MODE — không xóa/đổi tên file");
        LoopLog.get().i("Video mới nhất (ứng viên): " + next.displayName);
        LoopLog.get().i("Thao tác dự kiến:");
        for (String line : planned) {
            LoopLog.get().i("  • " + line);
        }
    }

    private void runLoopRotation(DcimStore.Folder folder, DcimEntry newVideo, Map<Integer, DcimEntry> slots) {
        processing = true;
        Set<Integer> occupied = new HashSet<>(slots.keySet());
        List<LoopPlanner.Action> actions = LoopPlanner.planForNewVideo(occupied, newVideo.displayName);
        LoopLog.get().i("LOOP MODE — bắt đầu xoay vòng cho " + newVideo.displayName);

        StringBuilder txn = new StringBuilder();
        txn.append("new=").append(newVideo.displayName).append('\n');
        for (LoopPlanner.Action a : actions) {
            txn.append(LoopPlanner.describe(a)).append('\n');
        }
        prefs.setTxnActive(true);
        if (!store.writeTxn(folder, txn.toString())) {
            LoopLog.get().w("Không ghi được " + LoopPlanner.TXN_NAME + " trên USB — dùng mốc nội bộ.");
        }

        for (LoopPlanner.Action action : actions) {
            boolean ok = executeAndVerify(folder, action);
            if (!ok) {
                enterFailsafe("Thao tác thất bại: " + LoopPlanner.describe(action)
                        + ". Đã dừng, không xóa hàng loạt.");
                processing = false;
                return;
            }
        }

        prefs.setTxnActive(false);
        if (!store.deleteTxn(folder)) {
            LoopLog.get().w("Xoay vòng xong nhưng không xóa được " + LoopPlanner.TXN_NAME
                    + " trên USB — hãy xóa tay nếu thấy file này.");
        }
        LoopLog.get().i("Hoàn tất: video mới đã vào 01.mp4 (" + newVideo.displayName + ")");
        tracks.remove(newVideo.documentId);
        processing = false;
    }

    private boolean executeAndVerify(DcimStore.Folder folder, LoopPlanner.Action action) {
        if (action.type == LoopPlanner.Action.Type.DELETE) {
            DcimEntry target = store.findByName(folder, action.fromName);
            if (target == null) {
                LoopLog.get().w("Không thấy " + action.fromName + " để xóa — bỏ qua (đã trống)");
                return true;
            }
            LoopLog.get().i("Xóa video " + LoopPlanner.MAX_VIDEOS + ": " + action.fromName);
            if (!store.delete(target)) {
                LoopLog.get().e("Xóa thất bại: " + action.fromName);
                return false;
            }
            DcimEntry still = store.findByName(folder, action.fromName);
            if (still != null) {
                LoopLog.get().e("Sau khi xóa, file vẫn còn: " + action.fromName);
                return false;
            }
            LoopLog.get().i("Đã xóa: " + action.fromName);
            return true;
        }

        DcimEntry from = store.findByName(folder, action.fromName);
        if (from == null) {
            LoopLog.get().e("Không thấy file nguồn để đổi tên: " + action.fromName);
            return false;
        }
        if ("01.mp4".equalsIgnoreCase(action.toName)) {
            LoopLog.get().i("Đưa video mới vào 01: " + action.fromName + " → " + action.toName);
        } else {
            LoopLog.get().i("Rename: " + action.fromName + " → " + action.toName);
        }
        if (!store.rename(from, action.toName)) {
            LoopLog.get().e("Đổi tên thất bại: " + action.fromName + " → " + action.toName);
            return false;
        }
        DcimEntry dest = store.findByName(folder, action.toName);
        DcimEntry old = store.findByName(folder, action.fromName);
        if (dest == null) {
            LoopLog.get().e("Sau rename không thấy " + action.toName);
            return false;
        }
        if (old != null && old.documentId.equals(from.documentId)) {
            LoopLog.get().e("Sau rename file cũ vẫn còn tên " + action.fromName);
            return false;
        }
        return true;
    }

    private void enterFailsafe(String reason) {
        prefs.setFailsafe(true, reason);
        if (prefs.isLoopMode()) {
            prefs.setMode(AppPreferences.MODE_TEST);
        }
        LoopLog.get().e("FAILSAFE: " + reason);
    }

    private String describeNewest(Map<Integer, DcimEntry> slots, List<DcimEntry> stableNew,
                                  List<DcimEntry> cameraVideos) {
        if (!stableNew.isEmpty()) {
            DcimEntry newestStable = stableNew.get(stableNew.size() - 1);
            return newestStable.displayName + " (" + DcimStore.formatSize(newestStable.size) + ")";
        }
        if (slots.containsKey(1)) {
            DcimEntry s = slots.get(1);
            return s.displayName + " (" + DcimStore.formatSize(s.size) + ")";
        }
        if (!cameraVideos.isEmpty()) {
            List<DcimEntry> copy = new ArrayList<>(cameraVideos);
            copy.sort(Comparator.comparingLong((DcimEntry e) -> e.lastModified).reversed());
            DcimEntry n = copy.get(0);
            return n.displayName + " (đang ghi/chưa ổn định, " + DcimStore.formatSize(n.size) + ")";
        }
        return "—";
    }

    private String describeOldest(Map<Integer, DcimEntry> slots, List<DcimEntry> cameraVideos) {
        for (int i = LoopPlanner.MAX_VIDEOS; i >= 1; i--) {
            if (slots.containsKey(i)) {
                DcimEntry s = slots.get(i);
                return s.displayName + " (" + DcimStore.formatSize(s.size) + ")";
            }
        }
        if (!cameraVideos.isEmpty()) {
            List<DcimEntry> copy = new ArrayList<>(cameraVideos);
            copy.sort(Comparator.comparingLong(e -> e.lastModified));
            DcimEntry o = copy.get(0);
            return o.displayName + " (" + DcimStore.formatSize(o.size) + ")";
        }
        return "—";
    }

    private static final class SizeTrack {
        long lastSize = -1L;
        int stableCount;
        String name;
    }
}
