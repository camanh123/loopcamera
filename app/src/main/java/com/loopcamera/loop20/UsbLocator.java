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
        List<File> roots = new ArrayList<>();
        List<String> rootLabels = new ArrayList<>();
        List<Boolean> removableFlags = new ArrayList<>();

        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume vol : sm.getStorageVolumes()) {
                File path = volumePath(vol);
                if (path == null) {
                    LoopLog.get().w("Volume không lấy được path: " + vol);
                    continue;
                }
                boolean removable = vol.isRemovable() || !vol.isPrimary();
                String desc = vol.getDescription(context);
                if (desc == null || desc.trim().isEmpty()) {
                    desc = path.getName();
                }
                String state = vol.getState();
                LoopLog.get().i("Volume: " + desc + " path=" + path.getAbsolutePath()
                        + " removable=" + vol.isRemovable()
                        + " primary=" + vol.isPrimary()
                        + " state=" + state);
                if (state != null && !Environment.MEDIA_MOUNTED.equals(state)
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                    continue;
                }
                roots.add(path);
                rootLabels.add(desc);
                removableFlags.add(removable);
            }
        }

        addScannedRoots(roots, rootLabels, removableFlags, new File("/storage"), true);
        addScannedRoots(roots, rootLabels, removableFlags, new File("/mnt/media_rw"), true);
        addScannedRoots(roots, rootLabels, removableFlags, new File("/mnt/usb_storage"), true);
        addScannedRoots(roots, rootLabels, removableFlags, new File("/mnt/usb"), true);
        addScannedRoots(roots, rootLabels, removableFlags, new File("/mnt/usbotg"), true);

        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            if (root == null || !root.isDirectory()) {
                continue;
            }
            String key = canonical(root);
            if (isIgnoredRootName(root.getName())) {
                continue;
            }
            File dcim = new File(root, "DCIM");
            if (dcim.isDirectory() && canList(dcim)) {
                put(out, dcim, rootLabels.get(i) + " / DCIM", removableFlags.get(i), true);
            } else if (isDcimName(root) && canList(root)) {
                put(out, root, rootLabels.get(i), removableFlags.get(i), true);
            }
        }

        List<Candidate> list = new ArrayList<>(out.values());
        list.sort((a, b) -> {
            if (a.removable != b.removable) {
                return a.removable ? -1 : 1;
            }
            if (a.hasDcimName != b.hasDcimName) {
                return a.hasDcimName ? -1 : 1;
            }
            return a.directory.getAbsolutePath().compareToIgnoreCase(b.directory.getAbsolutePath());
        });
        LoopLog.get().i("Tìm thấy " + list.size() + " thư mục DCIM ứng viên.");
        return list;
    }

    private static void addScannedRoots(List<File> roots, List<String> labels, List<Boolean> removable,
                                        File parent, boolean childrenAreVolumes) {
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
            if (childrenAreVolumes) {
                roots.add(child);
                labels.add(parent.getName() + "/" + child.getName());
                removable.add(true);
            }
        }
    }

    private static void put(LinkedHashMap<String, Candidate> out, File dir, String label,
                            boolean removable, boolean hasDcim) {
        String key = canonical(dir);
        if (out.containsKey(key)) {
            return;
        }
        out.put(key, new Candidate(dir, label, removable, hasDcim));
        LoopLog.get().i("Ứng viên DCIM: " + dir.getAbsolutePath() + " (" + label + ")");
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
