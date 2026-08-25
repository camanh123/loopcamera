package com.loopcamera.loop20;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * USB/FUSE path aliases for matching a process FD to the same DCIM/Camera MP4.
 * Matching does not require the literal /storage/USB1/DCIM/Camera string.
 */
public final class PathAliasResolver {

    private PathAliasResolver() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String t = path.trim();
        int deleted = t.indexOf(" (deleted)");
        if (deleted > 0) {
            t = t.substring(0, deleted);
        }
        t = t.replace('\\', '/');
        while (t.contains("//")) {
            t = t.replace("//", "/");
        }
        if (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    public static boolean looksLikeKernelFd(String target) {
        if (target == null) {
            return true;
        }
        String t = target.trim();
        return t.startsWith("pipe:")
                || t.startsWith("socket:")
                || t.startsWith("anon_inode:")
                || t.startsWith("dma:")
                || t.equals("/")
                || t.isEmpty();
    }

    /**
     * True when an FD symlink likely refers to the given MP4 under USB/DCIM/Camera
     * or any collected alias of that folder.
     */
    public static boolean matchesFdTarget(String fdTarget, String filename, Collection<String> cameraDirAliases) {
        if (looksLikeKernelFd(fdTarget) || filename == null || filename.isEmpty()) {
            return false;
        }
        String t = normalize(fdTarget);
        String fn = filename;
        if (cameraDirAliases != null) {
            for (String dir : cameraDirAliases) {
                String d = normalize(dir);
                if (d.isEmpty()) {
                    continue;
                }
                if (t.equals(d + "/" + fn) || t.equalsIgnoreCase(d + "/" + fn)) {
                    return true;
                }
            }
        }
        String lower = t.toLowerCase(Locale.US);
        String fnLower = fn.toLowerCase(Locale.US);
        if (!lower.endsWith("/" + fnLower)) {
            return false;
        }
        boolean dcimCamera = lower.contains("/dcim/camera/") || lower.endsWith("/dcim/camera/" + fnLower);
        if (!dcimCamera) {
            return false;
        }
        return looksLikeUsbVolumePath(lower);
    }

    public static boolean looksLikeUsbVolumePath(String lowerPath) {
        if (lowerPath == null) {
            return false;
        }
        String p = lowerPath.toLowerCase(Locale.US);
        if (p.contains("/emulated/")) {
            return false;
        }
        return p.contains("/usb")
                || p.contains("/media_rw/")
                || p.contains("/usb_storage")
                || p.contains("/usbotg")
                || p.contains("/mnt/runtime/");
    }

    public static boolean sameIdentity(long devA, long inoA, long devB, long inoB) {
        return inoA > 0L && inoB > 0L && inoA == inoB && devA == devB;
    }

    public static Set<String> collectCameraDirAliases(File targetDir, List<File> volumeRoots, String mountinfo) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        addDir(dirs, "/storage/USB1/DCIM/Camera");
        addDir(dirs, "/mnt/runtime/default/USB1/DCIM/Camera");
        addDir(dirs, "/mnt/runtime/read/USB1/DCIM/Camera");
        addDir(dirs, "/mnt/runtime/write/USB1/DCIM/Camera");
        addDir(dirs, "/mnt/usb_storage/DCIM/Camera");
        addDir(dirs, "/mnt/usbotg/DCIM/Camera");
        if (targetDir != null) {
            addDir(dirs, targetDir.getAbsolutePath());
            try {
                addDir(dirs, targetDir.getCanonicalPath());
            } catch (Exception ignored) {
            }
            File parent = targetDir.getParentFile();
            if (parent != null && "DCIM".equalsIgnoreCase(parent.getName()) && parent.getParentFile() != null) {
                File vol = parent.getParentFile();
                String name = vol.getName();
                addDir(dirs, "/mnt/runtime/default/" + name + "/DCIM/Camera");
                addDir(dirs, "/mnt/runtime/read/" + name + "/DCIM/Camera");
                addDir(dirs, "/mnt/runtime/write/" + name + "/DCIM/Camera");
            }
        }
        if (volumeRoots != null) {
            for (File root : volumeRoots) {
                if (root == null) {
                    continue;
                }
                addDir(dirs, new File(new File(root, "DCIM"), "Camera").getAbsolutePath());
            }
        }
        for (String mp : parseMountPoints(mountinfo)) {
            addDir(dirs, mp);
            if (!mp.toLowerCase(Locale.US).contains("/dcim")) {
                addDir(dirs, mp + "/DCIM/Camera");
            }
        }
        return dirs;
    }

    public static List<String> parseMountPoints(String mountinfo) {
        List<String> out = new ArrayList<>();
        if (mountinfo == null || mountinfo.isEmpty()) {
            return out;
        }
        String[] lines = mountinfo.split("\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            String mountPoint = parts[4];
            String lower = mountPoint.toLowerCase(Locale.US);
            if (looksLikeUsbVolumePath(lower) || lower.contains("/storage/usb") || lower.contains("/media_rw/")) {
                out.add(normalize(mountPoint));
            }
        }
        return out;
    }

    public static String describeAliases(Collection<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return "(none collected)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String a : aliases) {
            if (n >= 12) {
                sb.append(" ... (+").append(aliases.size() - 12).append(")");
                break;
            }
            if (n > 0) {
                sb.append("; ");
            }
            sb.append(a);
            n++;
        }
        return sb.toString();
    }

    private static void addDir(Set<String> dirs, String path) {
        String n = normalize(path);
        if (!n.isEmpty()) {
            dirs.add(n);
        }
    }
}
