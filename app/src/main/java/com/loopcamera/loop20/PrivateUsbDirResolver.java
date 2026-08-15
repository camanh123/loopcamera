package com.loopcamera.loop20;

import java.io.File;
import java.util.Locale;

/**
 * Chooses an app-private USB files dir from getExternalFilesDirs() results.
 * Does not assume array index 1. Does not hard-code USB1 as the resolver.
 */
public final class PrivateUsbDirResolver {

    public static final String PROBE_NAME = ".cameraloop_probe.tmp";

    public static final class VolumeHint {
        public final String path;
        public final boolean removable;
        public final boolean primary;
        public final String description;
        public final String state;

        public VolumeHint(String path, boolean removable, boolean primary,
                          String description, String state) {
            this.path = path == null ? "" : path;
            this.removable = removable;
            this.primary = primary;
            this.description = description == null ? "" : description;
            this.state = state == null ? "" : state;
        }
    }

    public static final class DirInfo {
        public final String path;
        public final String canonicalPath;
        public final boolean exists;
        public final boolean directory;
        public final boolean canRead;
        public final boolean canWrite;
        public final boolean internal;
        public final boolean removableUsb;
        public final String volumePath;
        public final String volumeKind;

        public DirInfo(String path, boolean exists, boolean directory, boolean canRead, boolean canWrite) {
            this(path, path, exists, directory, canRead, canWrite, null, false);
        }

        public DirInfo(String path, String canonicalPath, boolean exists, boolean directory,
                       boolean canRead, boolean canWrite, String volumePath, boolean volumeRemovable) {
            this.path = path;
            this.canonicalPath = canonicalPath == null || canonicalPath.isEmpty() ? path : canonicalPath;
            this.exists = exists;
            this.directory = directory;
            this.canRead = canRead;
            this.canWrite = canWrite;
            this.internal = isEmulatedInternal(path);
            this.volumePath = volumePath == null ? "" : volumePath;
            this.removableUsb = !this.internal
                    && (volumeRemovable || looksLikeRemovableUsb(path) || looksLikeRemovableUsb(this.volumePath));
            if (this.internal) {
                this.volumeKind = "internal";
            } else if (this.removableUsb) {
                this.volumeKind = "removable-usb";
            } else {
                this.volumeKind = "unknown";
            }
        }
    }

    private PrivateUsbDirResolver() {
    }

    public static boolean isEmulatedInternal(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String p = path.replace('\\', '/').toLowerCase(Locale.US);
        return p.contains("/emulated/");
    }

    public static boolean looksLikeRemovableUsb(String path) {
        if (path == null || path.isEmpty() || isEmulatedInternal(path)) {
            return false;
        }
        String p = path.replace('\\', '/').toLowerCase(Locale.US);
        return p.contains("/usb")
                || p.contains("/media_rw")
                || p.contains("/usbotg")
                || p.contains("/usb_storage");
    }

    public static boolean filesDirBelongsToVolume(String filesDir, String volumePath) {
        if (filesDir == null || volumePath == null || volumePath.isEmpty()) {
            return false;
        }
        String f = filesDir.replace('\\', '/');
        String v = volumePath.replace('\\', '/');
        if (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return f.equals(v) || f.startsWith(v + "/");
    }

    public static VolumeHint bestVolume(String filesDir, VolumeHint[] volumes) {
        if (filesDir == null || volumes == null) {
            return null;
        }
        VolumeHint best = null;
        int bestLen = -1;
        for (VolumeHint h : volumes) {
            if (h == null || h.path.isEmpty()) {
                continue;
            }
            if (filesDirBelongsToVolume(filesDir, h.path) && h.path.length() > bestLen) {
                best = h;
                bestLen = h.path.length();
            }
        }
        return best;
    }

    public static DirInfo annotate(DirInfo raw, VolumeHint[] volumes) {
        if (raw == null) {
            return null;
        }
        VolumeHint vol = bestVolume(raw.path, volumes);
        if (vol == null) {
            return raw;
        }
        return new DirInfo(raw.path, raw.canonicalPath, raw.exists, raw.directory,
                raw.canRead, raw.canWrite, vol.path, vol.removable && !vol.primary);
    }

    /**
     * First writable removable-USB candidate. Skips nulls. Never uses a fixed index.
     */
    public static DirInfo selectWritableUsb(DirInfo[] candidates) {
        return selectWritableUsb(candidates, null);
    }

    public static DirInfo selectWritableUsb(DirInfo[] candidates, VolumeHint[] volumes) {
        if (candidates == null) {
            return null;
        }
        DirInfo firstUsbLike = null;
        DirInfo firstVolumeRemovable = null;
        DirInfo firstNonEmulated = null;
        for (DirInfo raw : candidates) {
            DirInfo d = annotate(raw, volumes);
            if (d == null || d.path == null || d.path.isEmpty()) {
                continue;
            }
            if (!d.canWrite) {
                continue;
            }
            if (d.internal) {
                continue;
            }
            if (looksLikeRemovableUsb(d.path) || looksLikeRemovableUsb(d.volumePath)) {
                if (firstUsbLike == null) {
                    firstUsbLike = d;
                }
            } else if (d.removableUsb) {
                if (firstVolumeRemovable == null) {
                    firstVolumeRemovable = d;
                }
            } else if (firstNonEmulated == null) {
                firstNonEmulated = d;
            }
        }
        if (firstUsbLike != null) {
            return firstUsbLike;
        }
        if (firstVolumeRemovable != null) {
            return firstVolumeRemovable;
        }
        return firstNonEmulated;
    }

    public static boolean ensureDirectory(File dir) {
        return dir != null && (dir.mkdirs() || dir.isDirectory());
    }

    public static String cameraSubdir(String privateUsbDir) {
        if (privateUsbDir == null || privateUsbDir.isEmpty()) {
            return null;
        }
        if (privateUsbDir.endsWith("/")) {
            return privateUsbDir + "Camera";
        }
        return privateUsbDir + "/Camera";
    }

    public static String probeFile(String cameraDir) {
        if (cameraDir == null) {
            return null;
        }
        return cameraDir + "/" + PROBE_NAME;
    }

    public static boolean abortIfProbeAlreadyExists(boolean probeExists) {
        return probeExists;
    }

    /**
     * Only the disposable hidden tmp in the resolved Camera dir may be deleted.
     * Production timestamp MP4s and DCIM/Camera videos are never allowed.
     */
    public static boolean isAllowedProbeDeletePath(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return false;
        }
        String norm = absolutePath.replace('\\', '/');
        File f = new File(norm);
        if (!PROBE_NAME.equals(f.getName())) {
            return false;
        }
        if (LoopPlanner.isManagedVideo(f.getName())) {
            return false;
        }
        String lower = norm.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) {
            return false;
        }
        if (lower.contains("/dcim/camera/") && !lower.endsWith("/" + PROBE_NAME)) {
            return false;
        }
        return lower.endsWith("/" + PROBE_NAME);
    }

    /** Definitive hardware proof: file was there, then it is gone. */
    public static boolean probeFilesystemSuccess(boolean existsBefore, boolean existsAfterDelete) {
        return existsBefore && !existsAfterDelete;
    }

    public static boolean falsePositiveDelete(boolean deleteReturned, boolean existsAfterDelete) {
        return deleteReturned && existsAfterDelete;
    }

    public static boolean unusualDeleteReturnedFalseButGone(boolean deleteReturned,
                                                            boolean existsAfterDelete) {
        return !deleteReturned && !existsAfterDelete;
    }
}
