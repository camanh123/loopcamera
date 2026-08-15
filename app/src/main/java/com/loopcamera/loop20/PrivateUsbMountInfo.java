package com.loopcamera.loop20;

import java.util.List;
import java.util.Locale;

/**
 * Read-only /proc/mounts parser. Never execs rm or mutates the filesystem.
 */
public final class PrivateUsbMountInfo {

    public String mountPoint = "n/a";
    public String fsType = "unknown";
    public String mountMode = "unknown";
    public String mountUid = "n/a";
    public String mountGid = "n/a";
    public String mountOptions = "n/a";
    public String mountLine = "n/a";

    public static PrivateUsbMountInfo parseBest(String targetPath, List<String> mountLines) {
        PrivateUsbMountInfo out = new PrivateUsbMountInfo();
        if (targetPath == null || mountLines == null) {
            return out;
        }
        String target = targetPath.replace('\\', '/');
        String bestPoint = "";
        String bestLine = null;
        for (String raw : mountLines) {
            if (raw == null || raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String[] parts = raw.split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String point = parts[1];
            if (target.equals(point) || target.startsWith(point + "/")) {
                if (point.length() > bestPoint.length()) {
                    bestPoint = point;
                    bestLine = raw;
                }
            }
        }
        if (bestLine != null) {
            fill(out, bestLine);
        }
        return out;
    }

    public static void fill(PrivateUsbMountInfo out, String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) {
            out.mountLine = line;
            return;
        }
        out.mountPoint = parts[1];
        out.fsType = parts[2];
        out.mountOptions = parts[3];
        out.mountLine = line.trim();
        String opts = parts[3].toLowerCase(Locale.US);
        if (opts.startsWith("rw") || opts.contains(",rw") || opts.contains("rw,")) {
            out.mountMode = "rw";
        } else if (opts.startsWith("ro") || opts.contains(",ro") || opts.contains("ro,")) {
            out.mountMode = "ro";
        } else {
            out.mountMode = "unknown";
        }
        out.mountUid = optionValue(parts[3], "uid");
        out.mountGid = optionValue(parts[3], "gid");
    }

    static String optionValue(String options, String key) {
        if (options == null) {
            return "n/a";
        }
        String[] bits = options.split(",");
        String prefix = key + "=";
        for (String bit : bits) {
            if (bit.startsWith(prefix)) {
                return bit.substring(prefix.length());
            }
        }
        return "n/a";
    }
}
