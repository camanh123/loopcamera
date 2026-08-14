package com.loopcamera.loop20;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Discovers removable/USB volumes and DCIM folders at runtime.
 * Does not hard-code a UUID or a single mount path.
 */
public final class UsbLocator {

    public static final class Candidate {
        public final File directory;
        public final String label;
        public final boolean removable;
        public final boolean hasDcimName;

        Candidate(File directory, String label, boolean removable, boolean hasDcimName) {
            this.directory = directory;
            this.label = label;
            this.removable = removable;
            this.hasDcimName = hasDcimName;
        }

        public String display() {
            return label + "\n" + directory.getAbsolutePath();
        }
    }

    private UsbLocator() {
    }

    public static List<Candidate> findDcimCandidates(Context context) {
        LinkedHashMap<String, Candidate> out = new LinkedHashMap<>();
        List<File> roots = listVolumeRoots(context);
        List<String> rootLabels = new ArrayList<>();
        List<Boolean> removableFlags = new ArrayList<>();
        for (File root : roots) {
            rootLabels.add(root.getName());
            removableFlags.add(true);
        }

        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            if (root == null || !root.isDirectory() || isIgnoredRootName(root.getName())) {
                continue;
            }
            File dcim = new File(root, "DCIM");
            File camera = new File(dcim, "Camera");
            boolean removable = removableFlags.get(i);
            String label = rootLabels.get(i);
            if (camera.isDirectory() && canList(camera)) {
                put(out, camera, label + " / DCIM/Camera", removable, true);
            }
            if (dcim.isDirectory() && canList(dcim)) {
                put(out, dcim, label + " / DCIM", removable, true);
            } else if (isDcimName(root) && canList(root)) {
                File nestedCam = new File(root, "Camera");
                if (nestedCam.isDirectory() && canList(nestedCam)) {
                    put(out, nestedCam, label + " / Camera", removable, true);
                }
                put(out, root, rootLabels.get(i), removable, true);
            }
        }

        List<Candidate> list = new ArrayList<>(out.values());
        list.sort((a, b) -> {
            int as = score(a);
            int bs = score(b);
            if (as != bs) {
                return Integer.compare(bs, as);
            }
            return a.directory.getAbsolutePath().compareToIgnoreCase(b.directory.getAbsolutePath());
        });
        LoopLog.get().i("Tìm thấy " + list.size() + " thư mục DCIM/Camera ứng viên.");
        return list;
    }

    private static int score(Candidate c) {
        int s = 0;
        String p = c.directory.getAbsolutePath().toLowerCase(Locale.US);
        if (p.endsWith("/dcim/camera")) {
            s += 40;
        } else if (p.endsWith("/camera")) {
            s += 30;
        } else if (p.endsWith("/dcim")) {
            s += 20;
        }
        if (c.removable) {
            s += 10;
        }
        return s;
    }

    public static List<File> listVolumeRoots(Context context) {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume vol : sm.getStorageVolumes()) {
                File path = volumePath(vol);
                if (path == null) {
                    continue;
                }
                String state = vol.getState();
                if (state != null && !Environment.MEDIA_MOUNTED.equals(state)
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                    continue;
                }
                if (isIgnoredRootName(path.getName())) {
                    continue;
                }
                roots.put(canonical(path), path);
            }
        }
        addScannedRootMap(roots, new File("/storage"));
        addScannedRootMap(roots, new File("/mnt/media_rw"));
        addScannedRootMap(roots, new File("/mnt/usb_storage"));
        addScannedRootMap(roots, new File("/mnt/usb"));
        addScannedRootMap(roots, new File("/mnt/usbotg"));
        return new ArrayList<>(roots.values());
    }

    private static void addScannedRootMap(LinkedHashMap<String, File> roots, File parent) {
        if (!parent.isDirectory()) {
            return;
        }
        File[] kids = parent.listFiles();
        if (kids == null) {
            return;
        }
        for (File child : kids) {
            if (!child.isDirectory() || isIgnoredRootName(child.getName())) {
                continue;
            }
            roots.putIfAbsent(canonical(child), child);
        }
    }

    public static String relativeToNearestVolume(Context context, File dir) {
        String abs = canonical(dir);
        File best = null;
        for (File root : listVolumeRoots(context)) {
            String r = canonical(root);
            if (abs.equals(r) || abs.startsWith(r + "/")) {
                if (best == null || canonical(best).length() < r.length()) {
                    best = root;
                }
            }
        }
        if (best != null) {
            String r = canonical(best);
            if (abs.equals(r)) {
                return "";
            }
            return abs.substring(r.length() + 1);
        }
        String upper = abs.toUpperCase(Locale.US);
        int i = upper.indexOf("/DCIM/");
        if (i >= 0) {
            return abs.substring(i + 1);
        }
        if (upper.endsWith("/DCIM")) {
            return "DCIM";
        }
        return dir.getName();
    }

    private static void put(LinkedHashMap<String, Candidate> out, File dir, String label,
                            boolean removable, boolean hasDcim) {
        String key = canonical(dir);
        if (out.containsKey(key)) {
            return;
        }
        out.put(key, new Candidate(dir, label, removable, hasDcim));
        LoopLog.get().i("Ứng viên: " + dir.getAbsolutePath() + " (" + label + ")");
    }

    private static boolean canList(File dir) {
        String[] names = dir.list();
        return names != null;
    }

    private static boolean isDcimName(File dir) {
        return "DCIM".equalsIgnoreCase(dir.getName());
    }

    private static boolean isIgnoredRootName(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.US);
        return n.equals("emulated")
                || n.equals("self")
                || n.equals("oem")
                || n.equals("persist")
                || n.equals("sdcard0")
                || n.startsWith(".");
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    static File volumePath(StorageVolume volume) {
        try {
            Method m = StorageVolume.class.getMethod("getDirectory");
            Object dir = m.invoke(volume);
            if (dir instanceof File) {
                return (File) dir;
            }
        } catch (Exception ignored) {
        }
        try {
            Method m = StorageVolume.class.getMethod("getPathFile");
            Object dir = m.invoke(volume);
            if (dir instanceof File) {
                return (File) dir;
            }
        } catch (Exception ignored) {
        }
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object path = m.invoke(volume);
            if (path instanceof String && !((String) path).isEmpty()) {
                return new File((String) path);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
