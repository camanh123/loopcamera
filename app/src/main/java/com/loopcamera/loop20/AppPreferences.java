package com.loopcamera.loop20;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class AppPreferences {

    public static final String MODE_TEST = "TEST";
    public static final String MODE_LOOP = "LOOP";

    private static final String PREF = "camera_loop_20";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_MODE = "mode";
    private static final String KEY_EXTS = "video_extensions";
    private static final String KEY_FAILSAFE = "failsafe";
    private static final String KEY_FAILSAFE_REASON = "failsafe_reason";
    private static final String KEY_TXN = "txn_active";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
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

    public String getMode() {
        return prefs.getString(KEY_MODE, MODE_TEST);
    }

    public boolean isLoopMode() {
        return MODE_LOOP.equals(getMode());
    }

    public void setMode(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    public String getVideoExtensions() {
        return prefs.getString(KEY_EXTS, LoopPlanner.DEFAULT_EXTENSIONS);
    }

    public boolean isFailsafe() {
        return prefs.getBoolean(KEY_FAILSAFE, false);
    }

    public String getFailsafeReason() {
        return prefs.getString(KEY_FAILSAFE_REASON, "");
    }

    public void setFailsafe(boolean on, String reason) {
        prefs.edit()
                .putBoolean(KEY_FAILSAFE, on)
                .putString(KEY_FAILSAFE_REASON, reason == null ? "" : reason)
                .apply();
    }

    public void clearFailsafe() {
        setFailsafe(false, "");
        setTxnActive(false);
    }

    public boolean isTxnActive() {
        return prefs.getBoolean(KEY_TXN, false);
    }

    public boolean setTxnActive(boolean on) {
        return prefs.edit().putBoolean(KEY_TXN, on).commit();
    }
}
