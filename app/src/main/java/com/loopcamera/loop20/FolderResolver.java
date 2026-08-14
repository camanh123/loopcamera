package com.loopcamera.loop20;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Persists the user-selected camera folder as a volume-relative path
 * (e.g. DCIM/Camera) so USB1 vs USB2 remounts can be resolved without
 * hard-coding a mount point.
 */
public final class FolderResolver {

    private FolderResolver() {
    }

    public static void save(Context context, AppPreferences prefs, File dir) {
        String relative = UsbLocator.relativeToNearestVolume(context, dir);
        prefs.saveFileFolder(dir, relative);
        LoopLog.get().i("Đã lưu thư mục: " + dir.getAbsolutePath()
                + " | relative=" + relative);
    }

    public static File resolve(Context context, AppPreferences prefs) {
        String savedAbs = prefs.getFolderPath();
        String relative = prefs.getRelativePath();

        if (!TextUtilsEmpty(savedAbs)) {
            File abs = new File(savedAbs);
            if (isUsableDir(abs)) {
                return abs;
            }
        }

        Uri tree = prefs.getTreeUri();
        if (tree != null && "file".equalsIgnoreCase(tree.getScheme()) && tree.getPath() != null) {
            File fromUri = new File(tree.getPath());
            if (isUsableDir(fromUri)) {
                return fromUri;
            }
        }

        List<File> roots = UsbLocator.listVolumeRoots(context);
        if (!TextUtilsEmpty(relative)) {
            for (File root : roots) {
                File candidate = new File(root, relative);
                if (isUsableDir(candidate)) {
                    LoopLog.get().i("Reconnect USB: " + savedAbs + " → " + candidate.getAbsolutePath());
                    prefs.saveFileFolder(candidate, relative);
                    return candidate;
                }
            }
        }

        String rel = !TextUtilsEmpty(relative) ? relative : "DCIM/Camera";
        String leaf = rel.replace('\\', '/');
        for (File root : roots) {
            File byRelative = walkRelative(root, leaf);
            if (isUsableDir(byRelative)) {
                LoopLog.get().i("Reconnect USB (search): " + byRelative.getAbsolutePath());
                prefs.saveFileFolder(byRelative, UsbLocator.relativeToNearestVolume(context, byRelative));
                return byRelative;
            }
        }

        File camera = findFirstDcimCamera(roots);
        if (camera != null) {
            LoopLog.get().i("Reconnect USB (DCIM/Camera): " + camera.getAbsolutePath());
            prefs.saveFileFolder(camera, UsbLocator.relativeToNearestVolume(context, camera));
            return camera;
        }
        return null;
    }

    private static File walkRelative(File root, String relative) {
        File cur = root;
        for (String part : relative.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            cur = new File(cur, part);
        }
        return cur;
    }

    private static File findFirstDcimCamera(List<File> roots) {
        for (File root : roots) {
            File camera = new File(new File(root, "DCIM"), "Camera");
            if (isUsableDir(camera)) {
                return camera;
            }
        }
        return null;
    }

    static boolean isUsableDir(File dir) {
        return dir != null && dir.isDirectory() && dir.list() != null;
    }

    private static boolean TextUtilsEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String describe(File dir) {
        if (dir == null) {
            return "(chưa chọn)";
        }
        return dir.getAbsolutePath();
    }

    public static boolean looksLikeCameraFolder(File dir) {
        if (dir == null) {
            return false;
        }
        String path = dir.getAbsolutePath().toLowerCase(Locale.US);
        return path.endsWith("/dcim/camera") || path.endsWith("/dcim");
    }
}
