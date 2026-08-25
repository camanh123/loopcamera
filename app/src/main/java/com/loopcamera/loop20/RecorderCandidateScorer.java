package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure ranking/classification for PackageManager snapshots.
 * Does not prove DCIM write ownership.
 */
public final class RecorderCandidateScorer {

    static final String[] DVR_TOKENS = {
            "dvr", "dashcam", "dash_cam", "dash-cam", "carcam", "car_cam",
            "tachograph", "drivingrec", "driving_rec", "autorec", "autoaid"
    };
    static final String[] RECORD_TOKENS = {
            "recorder", "recording", "record", "videocapture", "video_capture"
    };
    static final String[] CAMERA_TOKENS = {
            "camera", "camcorder", "camapp", "rearcam", "frontcam", "reversing"
    };
    static final String[] PATH_TOKENS = {
            "path", "storage", "usb", "sdcard", "folder", "directory", "savepath", "output"
    };
    static final String[] LOOP_TOKENS = {
            "loop", "cycle", "overwrite", "recycle", "maxfile", "max_file", "duration", "limit"
    };
    static final String[] SETTINGS_TOKENS = {
            "setting", "preference", "config", "option"
    };

    private RecorderCandidateScorer() {
    }

    public static int score(RecorderCandidate c) {
        if (c == null || RecorderCandidate.looksLikeNoise(c.packageName)) {
            return 0;
        }
        String hay = c.searchable();
        int s = 0;
        if (containsAny(hay, DVR_TOKENS)) {
            s += 80;
        }
        if (containsAny(hay, RECORD_TOKENS)) {
            s += 40;
        }
        if (containsAny(hay, CAMERA_TOKENS)) {
            s += 25;
        }
        if (c.cameraPermission) {
            s += 50;
        }
        if (c.recordAudioPermission) {
            s += 10;
        }
        if (c.writeStoragePermission) {
            s += 15;
        }
        if (c.readStoragePermission) {
            s += 5;
        }
        if (c.handlesVideoCapture) {
            s += 20;
        }
        if (c.systemApp && (c.cameraPermission || containsAny(hay, DVR_TOKENS))) {
            s += 20;
        }
        if (!c.services.isEmpty()) {
            s += 15;
        }
        if (!c.exportedServices.isEmpty()) {
            s += 8;
        }
        if (!c.providers.isEmpty()) {
            s += 5;
        }
        classifyHints(c);
        if (!c.pathLike.isEmpty()) {
            s += 6;
        }
        if (!c.loopLike.isEmpty()) {
            s += 6;
        }
        return s;
    }

    public static void classifyHints(RecorderCandidate c) {
        c.settingsLike.clear();
        c.pathLike.clear();
        c.loopLike.clear();
        c.recordLikeComponents.clear();
        List<String> all = new ArrayList<>();
        all.addAll(c.activities);
        all.addAll(c.services);
        all.addAll(c.receivers);
        all.addAll(c.providers);
        for (String name : all) {
            String simple = RecorderCandidate.simpleName(name).toLowerCase(Locale.US);
            String full = name == null ? "" : name.toLowerCase(Locale.US);
            if (containsAny(simple + " " + full, SETTINGS_TOKENS)) {
                c.settingsLike.add(name);
            }
            if (containsAny(simple + " " + full, PATH_TOKENS)) {
                c.pathLike.add(name);
            }
            if (containsAny(simple + " " + full, LOOP_TOKENS)) {
                c.loopLike.add(name);
            }
            if (containsAny(simple + " " + full, DVR_TOKENS)
                    || containsAny(simple + " " + full, RECORD_TOKENS)
                    || containsAny(simple + " " + full, CAMERA_TOKENS)) {
                c.recordLikeComponents.add(name);
            }
        }
    }

    public static boolean isLikely(RecorderCandidate c) {
        return c != null && c.score >= 40;
    }

    public static List<RecorderCandidate> rank(List<RecorderCandidate> input) {
        List<RecorderCandidate> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        for (RecorderCandidate c : input) {
            c.score = score(c);
            if (isLikely(c)) {
                out.add(c);
            }
        }
        Collections.sort(out, Comparator.comparingInt((RecorderCandidate c) -> c.score).reversed()
                .thenComparing(c -> c.packageName));
        return out;
    }

    /**
     * PackageManager cannot prove A/B/C. Identified candidate without a known
     * callable vendor API is D. No likely candidate is E.
     */
    public static String verdict(RecorderCandidate strongest) {
        if (strongest == null || !isLikely(strongest)) {
            return RecorderCandidate.VERDICT_E;
        }
        return RecorderCandidate.VERDICT_D;
    }

    public static String verdictReason(RecorderCandidate strongest) {
        String v = verdict(strongest);
        if (RecorderCandidate.VERDICT_E.equals(v)) {
            return "E — Recorder still unknown (no likely camera/DVR package above threshold)";
        }
        return "D — Strongest candidate identified from PackageManager, but no proven "
                + "public control API (cannot change path, loop, or delete from this app)";
    }

    public static boolean containsAny(String haystack, String[] tokens) {
        if (haystack == null) {
            return false;
        }
        String h = haystack.toLowerCase(Locale.US);
        for (String t : tokens) {
            if (h.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
