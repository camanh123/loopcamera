package com.loopcamera.loop20;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import java.io.File;

public final class AppPreferences {

    public static final String MODE_TEST = "TEST";
    public static final String MODE_LOOP = "LOOP";

    private static final String PREF = "camera_loop_30";
    private static final String KEY_SAF_TREE = "saf_tree_uri";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_FOLDER_PATH = "folder_path";
    private static final String KEY_RELATIVE_PATH = "relative_path";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FAILSAFE = "failsafe";
    private static final String KEY_FAILSAFE_REASON = "failsafe_reason";
    private static final String KEY_DELETE_FAIL_STREAK = "delete_fail_streak";
    private static final String KEY_STABLE_MS = "stable_ms";
    private static final String KEY_MIN_AGE_MS = "min_age_ms";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean hasSavedFolder() {
        return !TextUtils.isEmpty(getFolderPath()) || getTreeUri() != null;
    }

    public String getFolderPath() {
        return prefs.getString(KEY_FOLDER_PATH, null);
    }

    public String getRelativePath() {
        return prefs.getString(KEY_RELATIVE_PATH, null);
    }

    public void saveFileFolder(File dir, String relativePath) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_FOLDER_PATH, dir.getAbsolutePath());
        e.putString(KEY_RELATIVE_PATH, relativePath == null ? "" : relativePath);
        e.apply();
    }

    public Uri getTreeUri() {
        String s = prefs.getString(KEY_TREE, null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        return Uri.parse(s);
    }

    public void setTreeUri(Uri uri) {
        prefs.edit().putString(KEY_TREE, uri == null ? null : uri.toString()).apply();
    }

    /** Persistable SAF tree (content://), never a file:// path. */
    public Uri getSafTreeUri() {
        String s = prefs.getString(KEY_SAF_TREE, null);
        if (s == null || s.isEmpty()) {
            Uri legacy = getTreeUri();
            if (legacy != null && "content".equalsIgnoreCase(legacy.getScheme())) {
                return legacy;
            }
            return null;
        }
        Uri u = Uri.parse(s);
        if (u != null && "content".equalsIgnoreCase(u.getScheme())) {
            return u;
        }
        return null;
    }

    public void setSafTreeUri(Uri uri) {
        prefs.edit().putString(KEY_SAF_TREE, uri == null ? null : uri.toString()).apply();
        if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
            setTreeUri(uri);
        }
    }

    public String getMode() {
        return prefs.getString(KEY_MODE, MODE_TEST);
    }

    public boolean isLoopMode() {
        return MODE_LOOP.equals(getMode());
    }

    public void setMode(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    public boolean isFailsafe() {
        return prefs.getBoolean(KEY_FAILSAFE, false);
    }

    public String getFailsafeReason() {
        return prefs.getString(KEY_FAILSAFE_REASON, "");
    }

    public void setFailsafe(boolean on, String reason) {
        SharedPreferences.Editor e = prefs.edit()
                .putBoolean(KEY_FAILSAFE, on)
                .putString(KEY_FAILSAFE_REASON, reason == null ? "" : reason);
        if (on) {
            e.putInt(KEY_DELETE_FAIL_STREAK, Math.max(getDeleteFailStreak(), DeletePolicy.FAILSAFE_AFTER));
        }
        e.apply();
    }

    /**
     * "ĐÃ KIỂM TRA / XÓA FAILSAFE" — clears the flag, reason, and consecutive
     * delete-failure counter so LOOP MODE does not immediately re-trip.
     */
    public void clearFailsafe() {
        prefs.edit()
                .putBoolean(KEY_FAILSAFE, false)
                .putString(KEY_FAILSAFE_REASON, "")
                .putInt(KEY_DELETE_FAIL_STREAK, 0)
                .apply();
    }

    public int getDeleteFailStreak() {
        return prefs.getInt(KEY_DELETE_FAIL_STREAK, 0);
    }

    public void setDeleteFailStreak(int streak) {
        prefs.edit().putInt(KEY_DELETE_FAIL_STREAK, Math.max(0, streak)).apply();
    }

    public long getStableMs() {
        return prefs.getLong(KEY_STABLE_MS, LoopPlanner.DEFAULT_STABLE_MS);
    }

    public long getMinAgeMs() {
        return prefs.getLong(KEY_MIN_AGE_MS, LoopPlanner.DEFAULT_MIN_AGE_MS);
    }
}
