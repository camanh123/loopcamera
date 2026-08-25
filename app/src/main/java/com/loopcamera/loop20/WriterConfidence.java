package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Correlated-evidence verdicts. Never guesses a package from PackageManager scores.
 */
public final class WriterConfidence {

    public static final String PROVEN = "PROVEN";
    public static final String VERY_STRONG = "VERY STRONG";
    public static final String CANDIDATE = "CANDIDATE";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String TYPE_FD = "FD";
    public static final String TYPE_LSOF = "LSOF";
    public static final String TYPE_MEDIASTORE = "MEDIASTORE_OWNER";
    public static final String TYPE_FOREGROUND = "FOREGROUND";
    public static final String TYPE_LOGCAT = "LOGCAT";
    public static final String TYPE_RUNNING = "RUNNING_PROCESS";

    public static final class Signal {
        public final String type;
        public final String identity;
        public final String detail;

        public Signal(String type, String identity, String detail) {
            this.type = type;
            this.identity = identity;
            this.detail = detail;
        }
    }

    public static final class Verdict {
        public final String level;
        public final String identity;
        public final String reason;

        public Verdict(String level, String identity, String reason) {
            this.level = level;
            this.identity = identity;
            this.reason = reason;
        }

        public String finalLine() {
            if (PROVEN.equals(level) && identity != null && !identity.isEmpty()) {
                return "WRITER PROVEN:\n" + identity;
            }
            if (VERY_STRONG.equals(level) && identity != null && !identity.isEmpty()) {
                return "WRITER VERY STRONGLY INDICATED:\n" + identity;
            }
            if (CANDIDATE.equals(level) && identity != null && !identity.isEmpty()) {
                return "WRITER CANDIDATE:\n" + identity;
            }
            return "WRITER UNKNOWN:\nAndroid/ROM restrictions prevented process attribution.";
        }
    }

    private WriterConfidence() {
    }

    /**
     * @param fdIdentity  process/package from an FD pointing at the growing MP4 (null if none)
     * @param lsofIdentity process/package from lsof/fuser on that MP4 (null if none)
     * @param indirect     supporting signals captured during CREATE/growth only
     */
    public static Verdict evaluate(String fdIdentity, String lsofIdentity, List<Signal> indirect) {
        if (notEmpty(fdIdentity)) {
            return new Verdict(PROVEN, fdIdentity, "process FD referenced the growing MP4");
        }
        if (notEmpty(lsofIdentity)) {
            return new Verdict(PROVEN, lsofIdentity, "lsof/fuser referenced the growing MP4");
        }
        Map<String, List<Signal>> byId = new LinkedHashMap<>();
        if (indirect != null) {
            for (Signal s : indirect) {
                if (s == null || !notEmpty(s.identity) || !notEmpty(s.type)) {
                    continue;
                }
                if (TYPE_FD.equals(s.type) || TYPE_LSOF.equals(s.type)) {
                    continue;
                }
                if (isNoiseIdentity(s.identity)) {
                    continue;
                }
                String key = normalizeIdentity(s.identity);
                List<Signal> list = byId.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    byId.put(key, list);
                }
                list.add(s);
            }
        }
        String bestId = null;
        int bestTypes = 0;
        List<Signal> best = null;
        for (Map.Entry<String, List<Signal>> e : byId.entrySet()) {
            int types = distinctTypes(e.getValue());
            if (types > bestTypes) {
                bestTypes = types;
                bestId = e.getValue().get(0).identity;
                best = e.getValue();
            }
        }
        if (bestTypes >= 2 && bestId != null) {
            return new Verdict(VERY_STRONG, bestId,
                    "independent runtime signals during CREATE/growth: " + typeSummary(best));
        }
        if (bestTypes == 1 && bestId != null) {
            return new Verdict(CANDIDATE, bestId,
                    "only indirect correlation: " + typeSummary(best));
        }
        return new Verdict(UNKNOWN, null, "no process FD or agreeing runtime owner signal");
    }

    static String normalizeIdentity(String identity) {
        return identity.trim().toLowerCase(Locale.US);
    }

    /** Home/system processes that can be foreground during recording without writing the MP4. */
    public static boolean isNoiseIdentity(String identity) {
        if (identity == null || identity.trim().isEmpty()) {
            return true;
        }
        String s = identity.toLowerCase(Locale.US);
        return s.contains("com.loopcamera.loop20")
                || s.contains("launcher")
                || s.contains("systemui")
                || s.contains("inputmethod")
                || s.equals("android")
                || s.startsWith("com.android.settings");
    }

    private static int distinctTypes(List<Signal> signals) {
        List<String> types = new ArrayList<>();
        for (Signal s : signals) {
            if (!types.contains(s.type)) {
                types.add(s.type);
            }
        }
        return types.size();
    }

    private static String typeSummary(List<Signal> signals) {
        StringBuilder sb = new StringBuilder();
        for (Signal s : signals) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.type);
        }
        return sb.toString();
    }

    static String extractPackageToken(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("[\\s,;:()\\[\\]]+");
        for (String p : parts) {
            if (p.startsWith("com.") && p.contains(".") && p.length() > 6 && !p.contains("loopcamera")) {
                return p;
            }
        }
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
