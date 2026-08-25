package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ranked camera/DVR app snapshot. Scoring is PackageManager-only and
 * cannot prove who writes /storage/USB1/DCIM/Camera.
 */
public final class RecorderCandidate {

    public static final String SELF_PACKAGE = "com.loopcamera.loop20";

    /** A path-settings UI exists; not proof the path can be changed. */
    public static final String VERDICT_A = "A";
    public static final String VERDICT_B = "B";
    public static final String VERDICT_C = "C";
    /** Identified strongest candidate, no proven public control. */
    public static final String VERDICT_D = "D";
    /** No likely recorder found. */
    public static final String VERDICT_E = "E";

    public String packageName = "";
    public String label = "";
    public boolean systemApp;
    public boolean cameraPermission;
    public boolean recordAudioPermission;
    public boolean readStoragePermission;
    public boolean writeStoragePermission;
    public boolean handlesVideoCapture;
    public int score;
    public final List<String> activities = new ArrayList<>();
    public final List<String> services = new ArrayList<>();
    public final List<String> receivers = new ArrayList<>();
    public final List<String> providers = new ArrayList<>();
    public final List<String> exportedActivities = new ArrayList<>();
    public final List<String> exportedServices = new ArrayList<>();
    public final List<String> exportedReceivers = new ArrayList<>();
    public final List<String> exportedProviders = new ArrayList<>();
    public final List<String> settingsLike = new ArrayList<>();
    public final List<String> pathLike = new ArrayList<>();
    public final List<String> loopLike = new ArrayList<>();
    public final List<String> recordLikeComponents = new ArrayList<>();

    public String blob() {
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(packageName)).append('\n');
        sb.append(nullToEmpty(label)).append('\n');
        for (String s : activities) {
            sb.append(s).append('\n');
        }
        for (String s : services) {
            sb.append(s).append('\n');
        }
        for (String s : receivers) {
            sb.append(s).append('\n');
        }
        for (String s : providers) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public String searchable() {
        return (nullToEmpty(packageName) + " " + nullToEmpty(label) + " " + blob())
                .toLowerCase(Locale.US);
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    public static boolean isSelf(String packageName) {
        return SELF_PACKAGE.equals(packageName);
    }

    public static boolean looksLikeNoise(String packageName) {
        if (packageName == null) {
            return true;
        }
        String p = packageName.toLowerCase(Locale.US);
        if (isSelf(p)) {
            return true;
        }
        return p.startsWith("com.google.android.gms")
                || p.equals("com.android.vending")
                || p.equals("com.android.systemui")
                || p.equals("com.android.settings")
                || p.equals("com.android.documentsui")
                || p.equals("com.android.providers.media")
                || p.equals("com.android.providers.downloads");
    }
}
