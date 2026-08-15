package com.loopcamera.loop20;

import android.content.Context;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hardware verification only: CREATE → WRITE → CLOSE → File.delete()
 * of .cameraloop_probe.tmp in the app-private USB files dir.
 * Never touches MP4s, DCIM/Camera videos, or production loop delete.
 */
public final class PrivateUsbProbe {

    public static final String PROBE_NAME = PrivateUsbDirResolver.PROBE_NAME;
    public static final String EXPORT_NAME = "CameraLoop30_private_usb_probe.txt";
    static final byte[] PROBE_BYTES =
            "CameraLoop30 private USB probe. Not a video.\n".getBytes(StandardCharsets.UTF_8);

    public static final class Report {
        public boolean resolverPass;
        public String candidates = "";
        public String selectedPrivateDir = "NOT FOUND";
        public String cameraDir = "NOT FOUND";
        public String privateCanonical = "n/a";
        public String cameraCanonical = "n/a";
        public boolean privateExists;
        public boolean privateReadable;
        public boolean privateWritable;
        public boolean privateIsDirectory;
        public boolean dirExists;
        public boolean dirReadable;
        public boolean dirWritable;
        public boolean dirIsDirectory;
        public boolean createPass;
        public boolean writePass;
        public boolean existsBefore;
        public boolean probeCanRead;
        public boolean probeCanWrite;
        public long probeLength;
        public boolean deleteAttempted;
        public boolean deleteReturned;
        public boolean existsAfterDelete = true;
        public boolean abortedExisting;
        public boolean success;
        public String reason = "not run";
        public String exception = "none";
        public String exportPath = "";
        public int processUid = -1;
        public String fsType = "unknown";
        public String mountMode = "unknown";
        public String mountUid = "n/a";
        public String mountGid = "n/a";
        public String mountOptions = "n/a";
        public String mountPoint = "n/a";
        public String volumes = "";

        public String screenText() {
            return "--------------------------------\n"
                    + "USB PRIVATE STORAGE PROBE\n"
                    + "--------------------------------\n\n"
                    + "Resolver:\n" + pf(resolverPass) + "\n\n"
                    + "External files directories:\n" + (candidates.isEmpty() ? "(none)\n" : candidates + "\n")
                    + "\nSelected USB private directory:\n" + selectedPrivateDir + "\n"
                    + "canonical:\n" + privateCanonical + "\n"
                    + "privateDir.exists: " + yn(privateExists) + "\n"
                    + "privateDir.canRead: " + yn(privateReadable) + "\n"
                    + "privateDir.canWrite: " + yn(privateWritable) + "\n"
                    + "privateDir.isDirectory: " + yn(privateIsDirectory) + "\n\n"
                    + "Camera directory:\n" + cameraDir + "\n"
                    + "canonical:\n" + cameraCanonical + "\n\n"
                    + "Directory exists:\n" + yn(dirExists) + "\n\n"
                    + "Directory canRead:\n" + yn(dirReadable) + "\n\n"
                    + "Directory canWrite:\n" + yn(dirWritable) + "\n\n"
                    + "CameraDir.isDirectory:\n" + yn(dirIsDirectory) + "\n\n"
                    + "Probe create:\n" + pf(createPass) + "\n\n"
                    + "Probe write:\n" + pf(writePass) + "\n\n"
                    + "Probe exists before delete:\n" + yn(existsBefore) + "\n\n"
                    + "File.delete() returned:\n" + (deleteAttempted ? String.valueOf(deleteReturned) : "NOT RUN") + "\n\n"
                    + "Probe exists after delete:\n" + (deleteAttempted ? yn(existsAfterDelete) : "NOT RUN") + "\n\n"
                    + "FINAL PROBE RESULT:\n" + pf(success) + "\n\n"
                    + "Reason:\n" + reason + "\n\n"
                    + "Process UID:\n" + processUid + "\n\n"
                    + "Filesystem:\n" + fsType + "\n\n"
                    + "Mount mode:\n" + mountMode + "\n\n"
                    + "Mount UID:\n" + mountUid + "\n\n"
                    + "Mount GID:\n" + mountGid + "\n\n"
                    + "Mount options:\n" + mountOptions + "\n"
                    + "Mount point:\n" + mountPoint + "\n\n"
                    + "Storage volumes:\n" + (volumes.isEmpty() ? "(none)\n" : volumes + "\n")
                    + "probe length: " + probeLength + "\n"
                    + "probe canRead: " + yn(probeCanRead) + "\n"
                    + "probe canWrite: " + yn(probeCanWrite) + "\n"
                    + "exception: " + exception + "\n"
                    + (exportPath.isEmpty() ? "" : "exported:\n" + exportPath + "\n");
        }
    }

    static String yn(boolean v) {
        return v ? "YES" : "NO";
    }

    static String pf(boolean v) {
        return v ? "PASS" : "FAIL";
    }

    private PrivateUsbProbe() {
    }

    public static Report run(Context context) {
        Report r = new Report();
        r.processUid = Process.myUid();
        LoopLog.get().i("USB PRIVATE STORAGE PROBE — CHỈ KIỂM TRA, không đụng MP4 / DCIM/Camera");

        File[] dirs;
        try {
            dirs = context.getExternalFilesDirs(null);
        } catch (Throwable t) {
            r.success = false;
            r.resolverPass = false;
            r.reason = "getExternalFilesDirs failed: " + t.getClass().getName() + ": " + t.getMessage();
            r.exception = t.getClass().getName() + ": " + t.getMessage();
            LoopLog.get().e(r.reason, t);
            return r;
        }

        PrivateUsbDirResolver.VolumeHint[] hints = collectVolumeHints(context, r);
        int n = dirs == null ? 0 : dirs.length;
        PrivateUsbDirResolver.DirInfo[] infos = new PrivateUsbDirResolver.DirInfo[n];
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < n; i++) {
            File d = dirs[i];
            if (d == null) {
                cand.append("[").append(i).append("] null\n");
                infos[i] = null;
                continue;
            }
            String canon;
            try {
                canon = d.getCanonicalPath();
            } catch (Exception e) {
                canon = d.getAbsolutePath();
            }
            PrivateUsbDirResolver.VolumeHint vol =
                    PrivateUsbDirResolver.bestVolume(d.getAbsolutePath(), hints);
            infos[i] = new PrivateUsbDirResolver.DirInfo(
                    d.getAbsolutePath(), canon, d.exists(), d.isDirectory(),
                    d.canRead(), d.canWrite(),
                    vol == null ? "" : vol.path,
                    vol != null && vol.removable && !vol.primary);
            cand.append("[").append(i).append("] ").append(infos[i].path)
                    .append("\n    canonical=").append(infos[i].canonicalPath)
                    .append(" exists=").append(infos[i].exists)
                    .append(" isDirectory=").append(infos[i].directory)
                    .append(" canRead=").append(infos[i].canRead)
                    .append(" canWrite=").append(infos[i].canWrite)
                    .append(" kind=").append(infos[i].volumeKind)
                    .append(" volume=").append(infos[i].volumePath.isEmpty() ? "(unmatched)" : infos[i].volumePath)
                    .append('\n');
        }
        r.candidates = cand.toString().trim();

        PrivateUsbDirResolver.DirInfo chosen = PrivateUsbDirResolver.selectWritableUsb(infos, hints);
        r.resolverPass = chosen != null;
        if (chosen == null) {
            r.success = false;
            r.reason = "no writable removable USB private dir (resolver does not use index[1])";
            LoopLog.get().w(r.reason);
            return r;
        }

        File privateRoot = new File(chosen.path);
        r.selectedPrivateDir = chosen.path;
        r.privateCanonical = chosen.canonicalPath;
        r.privateExists = privateRoot.exists();
        r.privateReadable = privateRoot.canRead();
        r.privateWritable = privateRoot.canWrite();
        r.privateIsDirectory = privateRoot.isDirectory();

        File cameraDir = new File(privateRoot, "Camera");
        boolean mk = PrivateUsbDirResolver.ensureDirectory(cameraDir);
        r.cameraDir = cameraDir.getAbsolutePath();
        try {
            r.cameraCanonical = cameraDir.getCanonicalPath();
        } catch (Exception e) {
            r.cameraCanonical = cameraDir.getAbsolutePath();
        }
        r.dirExists = cameraDir.exists();
        r.dirReadable = cameraDir.canRead();
        r.dirWritable = cameraDir.canWrite();
        r.dirIsDirectory = cameraDir.isDirectory();
        fillMount(r, cameraDir);

        LoopLog.get().i("PRIVATE USB dir=" + r.selectedPrivateDir
                + " Camera=" + r.cameraDir
                + " mkdirs=" + mk
                + " exists=" + r.dirExists
                + " canRead=" + r.dirReadable
                + " canWrite=" + r.dirWritable);

        if (!r.dirExists || !r.dirIsDirectory || !r.dirReadable || !r.dirWritable) {
            r.success = false;
            r.reason = "Camera subdir not usable exists=" + r.dirExists
                    + " isDirectory=" + r.dirIsDirectory
                    + " canRead=" + r.dirReadable + " canWrite=" + r.dirWritable;
            return r;
        }

        File probe = new File(cameraDir, PROBE_NAME);
        String expected = PrivateUsbDirResolver.probeFile(cameraDir.getAbsolutePath());
        if (!PrivateUsbDirResolver.isAllowedProbeDeletePath(probe.getAbsolutePath())
                || !probe.getAbsolutePath().equals(expected)) {
            r.success = false;
            r.reason = "refusing non-probe path " + probe.getAbsolutePath();
            return r;
        }

        if (PrivateUsbDirResolver.abortIfProbeAlreadyExists(probe.exists())) {
            r.abortedExisting = true;
            r.createPass = false;
            r.writePass = false;
            r.existsBefore = true;
            r.success = false;
            r.reason = "PROBE_FILE_ALREADY_EXISTS";
            LoopLog.get().w("PROBE_FILE_ALREADY_EXISTS — abort, no delete");
            r.exportPath = saveExport(context, r);
            return r;
        }

        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write(PROBE_BYTES);
            out.flush();
            try {
                out.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            r.exception = t.getClass().getName() + ": " + t.getMessage();
            r.createPass = false;
            r.writePass = false;
            r.success = false;
            r.reason = "create/write failed: " + r.exception;
            LoopLog.get().e(r.reason, t);
            r.exportPath = saveExport(context, r);
            return r;
        }

        r.existsBefore = probe.exists();
        r.probeLength = r.existsBefore ? probe.length() : 0L;
        r.probeCanRead = probe.canRead();
        r.probeCanWrite = probe.canWrite();
        r.createPass = r.existsBefore;
        r.writePass = r.existsBefore && r.probeLength > 0 && r.probeCanRead && r.probeCanWrite;
        LoopLog.get().i("PRIVATE USB create existsBefore=" + r.existsBefore
                + " length=" + r.probeLength
                + " canRead=" + r.probeCanRead
                + " canWrite=" + r.probeCanWrite);
        if (!r.createPass || !r.writePass) {
            r.success = false;
            r.reason = "probe create/write incomplete exists=" + r.existsBefore
                    + " length=" + r.probeLength
                    + " canRead=" + r.probeCanRead
                    + " canWrite=" + r.probeCanWrite;
            r.exportPath = saveExport(context, r);
            return r;
        }

        boolean returned = false;
        r.deleteAttempted = true;
        try {
            returned = probe.delete();
        } catch (Throwable t) {
            r.exception = t.getClass().getName() + ": " + t.getMessage();
            LoopLog.get().e("File.delete exception", t);
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        r.deleteReturned = returned;
        r.existsAfterDelete = probe.exists();
        if (PrivateUsbDirResolver.falsePositiveDelete(r.deleteReturned, r.existsAfterDelete)) {
            r.success = false;
            r.reason = "FAIL delete() returned true but existsAfterDelete=true";
        } else if (PrivateUsbDirResolver.probeFilesystemSuccess(r.existsBefore, r.existsAfterDelete)) {
            r.success = true;
            if (PrivateUsbDirResolver.unusualDeleteReturnedFalseButGone(r.deleteReturned, r.existsAfterDelete)) {
                r.reason = "unusual: File.delete() returned false but existsAfterDelete=false"
                        + " (filesystem state SUCCESS)";
            } else {
                r.reason = "existsBefore=true AND existsAfterDelete=false";
            }
        } else {
            r.success = false;
            r.reason = "PROBE FAIL existsBefore=" + r.existsBefore
                    + " deleteReturned=" + r.deleteReturned
                    + " existsAfterDelete=" + r.existsAfterDelete
                    + (r.exception.equals("none") ? "" : " exception=" + r.exception);
        }
        LoopLog.get().i("PRIVATE USB delete returned=" + r.deleteReturned
                + " existsAfterDelete=" + r.existsAfterDelete
                + " success=" + r.success);
        r.exportPath = saveExport(context, r);
        return r;
    }

    static PrivateUsbDirResolver.VolumeHint[] collectVolumeHints(Context context, Report r) {
        List<PrivateUsbDirResolver.VolumeHint> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume vol : sm.getStorageVolumes()) {
                    File path = UsbLocator.volumePath(vol);
                    String abs = path == null ? "" : path.getAbsolutePath();
                    PrivateUsbDirResolver.VolumeHint h = new PrivateUsbDirResolver.VolumeHint(
                            abs, vol.isRemovable(), vol.isPrimary(),
                            vol.getDescription(context), vol.getState());
                    list.add(h);
                    sb.append("path=").append(abs.isEmpty() ? "(none)" : abs)
                            .append(" removable=").append(vol.isRemovable())
                            .append(" primary=").append(vol.isPrimary())
                            .append(" state=").append(vol.getState())
                            .append(" desc=").append(h.description)
                            .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("StorageManager error: ").append(t.getClass().getName())
                    .append(": ").append(t.getMessage()).append('\n');
        }
        r.volumes = sb.toString().trim();
        return list.toArray(new PrivateUsbDirResolver.VolumeHint[0]);
    }

    static void fillMount(Report r, File target) {
        List<String> lines = readMountLines();
        PrivateUsbMountInfo info = PrivateUsbMountInfo.parseBest(target.getAbsolutePath(), lines);
        r.fsType = info.fsType;
        r.mountMode = info.mountMode;
        r.mountUid = info.mountUid;
        r.mountGid = info.mountGid;
        r.mountOptions = info.mountOptions;
        r.mountPoint = info.mountPoint;
    }

    static List<String> readMountLines() {
        List<String> lines = new ArrayList<>();
        File mounts = new File("/proc/mounts");
        if (!mounts.canRead()) {
            return lines;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(mounts))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    public static File exportFile(Context context, Report report) {
        String text = fullExportText(report);
        File dir = context.getExternalFilesDir(null);
        if (dir == null || (!dir.exists() && !dir.mkdirs()) || !dir.canWrite()) {
            dir = context.getFilesDir();
        }
        File out = new File(dir, EXPORT_NAME);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out, false),
                StandardCharsets.UTF_8)) {
            w.write(text);
            return out;
        } catch (Exception e) {
            File fallback = new File(context.getFilesDir(), EXPORT_NAME);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(fallback, false),
                    StandardCharsets.UTF_8)) {
                w.write(text);
                return fallback;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    static String fullExportText(Report r) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return "Camera Loop 30 private USB storage probe\n" + stamp + "\n\n" + r.screenText();
    }

    private static String saveExport(Context context, Report r) {
        File f = exportFile(context, r);
        return f == null ? "" : f.getAbsolutePath();
    }
}
