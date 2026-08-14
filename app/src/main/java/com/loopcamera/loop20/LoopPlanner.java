package com.loopcamera.loop20;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Camera Loop 30: keep original timestamp filenames, never rename.
 * Managed files: YYYYMMDD_HHhMMmSSs.mp4
 */
public final class LoopPlanner {

    public static final int MAX_VIDEOS = 30;
    public static final String FILENAME_EXAMPLE = "20260814_10h56m24s.mp4";

    /** Size must be unchanged for this long before a file can be COMPLETE. */
    public static final long DEFAULT_STABLE_MS = 12_000L;
    /** File must have been observed at least this long. */
    public static final long DEFAULT_MIN_AGE_MS = 12_000L;
    public static final long POLL_MS = 2_000L;
    public static final long MIN_STABLE_BYTES = 32_768L;
    /** After this much size-stable time, MMR still failing → INVALID (never delete). */
    public static final long INVALID_AFTER_STABLE_MS = 60_000L;

    private static final Pattern TIMESTAMP_NAME = Pattern.compile(
            "^(\\d{8})_(\\d{2})h(\\d{2})m(\\d{2})s\\.mp4$",
            Pattern.CASE_INSENSITIVE);

    private LoopPlanner() {
    }

    public static boolean isManagedVideo(String displayName) {
        return displayName != null && TIMESTAMP_NAME.matcher(displayName.trim()).matches();
    }

    /**
     * Sort key from filename only (not mtime). YYYYMMDDHHMMSS as long, or -1 if not managed.
     */
    public static long timestampSortKey(String displayName) {
        if (displayName == null) {
            return -1L;
        }
        Matcher m = TIMESTAMP_NAME.matcher(displayName.trim());
        if (!m.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(m.group(1) + m.group(2) + m.group(3) + m.group(4));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static String extensionOf(String displayName) {
        if (displayName == null) {
            return "";
        }
        int dot = displayName.lastIndexOf('.');
        if (dot < 0 || dot == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(dot + 1).toLowerCase(Locale.US);
    }

    public enum FileState {
        WAITING,
        NOT_READY,
        INVALID,
        COMPLETE
    }
}
