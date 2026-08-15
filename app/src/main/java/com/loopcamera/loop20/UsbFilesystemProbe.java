package com.loopcamera.loop20;

import android.content.Context;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hardware filesystem probe for CARFU. Never touches camera MP4 files.
 * Never calls rm/mv/chmod/chown/mount. Never launches SAF.
 */
public final class UsbFilesystemProbe {

    public static final String USB_ROOT = "/storage/USB1";
    public static final String USB_DCIM = "/storage/USB1/DCIM";
    public static final String USB_CAMERA = "/storage/USB1/DCIM/Camera";
    public static final String PROBE_NAME = ".cameraloop_probe.tmp";
    public static final String PROBE_PATH = USB_CAMERA + "/" + PROBE_NAME;
    public static final String EXPORT_NAME = "CameraLoop30_probe_report.txt";

    private static final byte[] PROBE_BYTES =
            "CameraLoop30 probe file. Not a video.\n".getBytes(StandardCharsets.UTF_8);

    public static final class Report {
        public String usbPath = USB_ROOT;
        public boolean usbExists;
        public boolean usbCanRead;
        public boolean usbCanWrite;
        public boolean cameraExists;
        public boolean cameraCanRead;
        public boolean cameraCanWrite;
        public String fsType = "unknown";
        public String mountRwRo = "unknown";
        public String mountUid = "n/a";
        public String mountGid = "n/a";
        public String mountOptions = "n/a";
        public String mountLine = "n/a";
        public int processUid = -1;
        public String selinux = "n/a";
        public String packageName = "";
        public String appDirs = "n/a";
        public String probeCreate = "not attempted";
        public String probeDeleteReturned = "n/a";
        public String probeExistsAfterDelete = "n/a";
        public String exception = "none";
        public boolean success;
        public String reason = "";
        public String exportPath = "";
        public String shell = "";

        public String verdictLine() {
            return success ? "PROBE SUCCESS" : "PROBE FAILED";
        }

        public String screenText() {
            return verdictLine() + "\n" + reason + "\n\n" + body();
        }

        public String body() {
            StringBuilder sb = new StringBuilder();
            sb.append("1. USB path: ").append(usbPath).append('\n');
            sb.append("2. USB exists: ").append(usbExists).append('\n');
            sb.append("3. USB canRead: ").append(usbCanRead).append('\n');
            sb.append("4. USB canWrite: ").append(usbCanWrite).append('\n');
            sb.append("5. DCIM/Camera exists: ").append(cameraExists).append('\n');
            sb.append("6. DCIM/Camera canRead: ").append(cameraCanRead).append('\n');
            sb.append("7. DCIM/Camera canWrite: ").append(cameraCanWrite).append('\n');
            sb.append("8. filesystem type: ").append(fsType).append('\n');
            sb.append("9. mount rw/ro: ").append(mountRwRo).append('\n');
            sb.append("10. mount uid: ").append(mountUid).append('\n');
            sb.append("11. mount gid: ").append(mountGid).append('\n');
            sb.append("12. mount options: ").append(mountOptions).append('\n');
            sb.append("    mount line: ").append(mountLine).append('\n');
            sb.append("13. process UID: ").append(processUid).append('\n');
            sb.append("14. SELinux: ").append(selinux).append('\n');
            sb.append("15. getExternalFilesDirs:\n").append(appDirs).append('\n');
            sb.append("16. probe create: ").append(probeCreate).append('\n');
            sb.append("17. probe delete returned: ").append(probeDeleteReturned).append('\n');
            sb.append("18. probe existsAfterDelete: ").append(probeExistsAfterDelete).append('\n');
            sb.append("19. exception: ").append(exception).append('\n');
            sb.append("package: ").append(packageName).append('\n');
            sb.append("probe file: ").append(PROBE_PATH).append('\n');
            if (!shell.isEmpty()) {
                sb.append("shell:\n").append(shell).append('\n');
            }
            if (!exportPath.isEmpty()) {
                sb.append("exported: ").append(exportPath).append('\n');
            }
            return sb.toString();
        }
    }

    private UsbFilesystemProbe() {
    }

    public static boolean isProbePath(String path) {
        return PROBE_PATH.equals(path);
    }

    public static boolean isCameraMp4(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".mp4");
    }

    public static boolean skipIfProbeAlreadyExists(boolean exists) {
        return exists;
    }

    public static boolean probeDeleteSuccess(boolean existsAfterDelete) {
        return !existsAfterDelete;
    }

    public static boolean isAllowedShell(String[] argv) {
        if (argv == null || argv.length == 0) {
            return false;
        }
        String cmd = argv[0];
        if ("id".equals(cmd)) {
            return argv.length == 1;
        }
        if ("ls".equals(cmd)) {
            return argv.length == 3 && "-ld".equals(argv[1]) && isAllowedLsPath(argv[2]);
        }
        if ("stat".equals(cmd)) {
            return argv.length == 2 && USB_CAMERA.equals(argv[1]);
        }
        return false;
    }

    static boolean isAllowedLsPath(String path) {
        return USB_ROOT.equals(path) || USB_DCIM.equals(path) || USB_CAMERA.equals(path);
    }

    public static Report run(Context context) {
        Report r = new Report();
        UsbDeleteLog.i("PROBE_START", "CHỈ KIỂM TRA — KHÔNG XÓA VIDEO path=" + PROBE_PATH);
        fillPath(r, new File(USB_ROOT), true);
        logPath("USB_PATH", new File(USB_ROOT));
        logPath("USB_PATH", new File(USB_DCIM));
        fillPath(r, new File(USB_CAMERA), false);
        logPath("USB_PATH", new File(USB_CAMERA));
        fillProcess(context, r);
        fillMounts(r);
        fillExternalFilesDirs(context, r);
        r.shell = runAllowedShell();
        runDisposableProbe(r);
        r.exportPath = exportReport(context, r);
        UsbDeleteLog.i("PROBE_DONE", r.verdictLine() + " " + r.reason);
        return r;
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
        } catch (Exception e) {
            File fallback = new File(context.getFilesDir(), EXPORT_NAME);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(fallback, false),
                    StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (Exception e2) {
                report.exception = appendEx(report.exception, e2);
                return null;
            }
            return fallback;
        }
        return out;
    }

    static String fullExportText(Report r) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return "Camera Loop 30 USB filesystem probe\n"
                + stamp + "\n"
                + r.verdictLine() + "\n"
                + r.reason + "\n\n"
                + r.body();
    }

    private static String exportReport(Context context, Report r) {
        File f = exportFile(context, r);
        if (f == null) {
            return "";
        }
        r.exportPath = f.getAbsolutePath();
        UsbDeleteLog.i("PROBE_EXPORT", "path=" + r.exportPath);
        return r.exportPath;
    }

    private static void fillPath(Report r, File file, boolean usbRoot) {
        if (usbRoot) {
            r.usbPath = file.getAbsolutePath();
            r.usbExists = file.exists();
            r.usbCanRead = file.canRead();
            r.usbCanWrite = file.canWrite();
        } else {
            r.cameraExists = file.exists();
            r.cameraCanRead = file.canRead();
            r.cameraCanWrite = file.canWrite();
        }
    }

    private static void logPath(String event, File file) {
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (Exception e) {
            canonical = file.getAbsolutePath();
        }
        UsbDeleteLog.i(event,
                "path=" + file.getAbsolutePath()
                + " exists=" + file.exists()
                + " isDirectory=" + file.isDirectory()
                + " canRead=" + file.canRead()
                + " canWrite=" + file.canWrite()
                + " canonicalPath=" + canonical);
    }

    private static void fillProcess(Context context, Report r) {
        r.packageName = context.getPackageName();
        r.processUid = Process.myUid();
        String selinux = readTextFile(new File("/proc/self/attr/current")).trim();
        r.selinux = selinux.isEmpty() ? "(empty/unreadable)" : selinux;
        UsbDeleteLog.i("PROBE_PROCESS",
                "package=" + r.packageName
                + " uid=" + r.processUid
                + " pid=" + Process.myPid()
                + " selinux=" + r.selinux);
    }

    private static void fillMounts(Report r) {
        String mounts = readTextFile(new File("/proc/mounts"));
        if (mounts.isEmpty()) {
            r.mountLine = "could not read /proc/mounts";
            UsbDeleteLog.w("PROBE_MOUNT", r.mountLine);
            return;
        }
        String chosen = null;
        for (String line : mounts.split("\n")) {
            if (lineContainsUsb1(line)) {
                chosen = line;
                break;
            }
        }
        if (chosen == null) {
            for (String line : mounts.split("\n")) {
                if (line.contains("/storage/")) {
                    chosen = line;
                    break;
                }
            }
        }
        if (chosen == null) {
            r.mountLine = "no USB1 or /storage/ line in /proc/mounts";
            UsbDeleteLog.i("PROBE_MOUNT", r.mountLine);
            return;
        }
        applyMount(r, chosen);
        UsbDeleteLog.i("PROBE_MOUNT", parseMountLine(chosen));
    }

    static void applyMount(Report r, String line) {
        String[] parts = line.trim().split("\\s+");
        r.mountLine = line;
        r.fsType = parts.length > 2 ? parts[2] : "unknown";
        String options = parts.length > 3 ? parts[3] : "";
        r.mountOptions = options;
        boolean rw = hasMountOpt(options, "rw");
        boolean ro = hasMountOpt(options, "ro");
        r.mountRwRo = rw ? "rw" : (ro ? "ro" : "unknown");
        r.mountUid = mountOptValue(options, "uid");
        r.mountGid = mountOptValue(options, "gid");
    }

    static boolean lineContainsUsb1(String line) {
        return line != null && (line.contains("/storage/USB1") || line.contains(" USB1 ")
                || line.contains("/USB1 ") || line.contains("/USB1\t"));
    }

    static String parseMountLine(String line) {
        String[] parts = line.trim().split("\\s+");
        String device = parts.length > 0 ? parts[0] : "?";
        String mountPoint = parts.length > 1 ? parts[1] : "?";
        String fsType = parts.length > 2 ? parts[2] : "?";
        String options = parts.length > 3 ? parts[3] : "";
        boolean rw = hasMountOpt(options, "rw");
        boolean ro = hasMountOpt(options, "ro");
        String uid = mountOptValue(options, "uid");
        String gid = mountOptValue(options, "gid");
        return "line=" + line
                + " device=" + device
                + " mountPoint=" + mountPoint
                + " fsType=" + fsType
                + " rw=" + rw
                + " ro=" + ro
                + " uid=" + uid
                + " gid=" + gid
                + " options=" + options;
    }

    private static boolean hasMountOpt(String options, String key) {
        for (String part : options.split(",")) {
            if (key.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static String mountOptValue(String options, String key) {
        String prefix = key + "=";
        for (String part : options.split(",")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "n/a";
    }

    private static void fillExternalFilesDirs(Context context, Report r) {
        StringBuilder sb = new StringBuilder();
        File[] dirs;
        try {
            dirs = context.getExternalFilesDirs(null);
        } catch (Throwable t) {
            r.appDirs = "getExternalFilesDirs failed: " + t.getClass().getName() + ": " + t.getMessage();
            r.exception = appendEx(r.exception, t);
            UsbDeleteLog.e("PROBE_APP_DIR", "getExternalFilesDirs failed", t);
            return;
        }
        if (dirs == null || dirs.length == 0) {
            r.appDirs = "(empty)";
            UsbDeleteLog.i("PROBE_APP_DIR", "getExternalFilesDirs=empty");
            return;
        }
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) {
                sb.append("  [").append(i).append("] null\n");
                continue;
            }
            sb.append("  [").append(i).append("] ").append(dir.getAbsolutePath())
                    .append(" exists=").append(dir.exists())
                    .append(" canRead=").append(dir.canRead())
                    .append(" canWrite=").append(dir.canWrite())
                    .append('\n');
            UsbDeleteLog.i("PROBE_APP_DIR",
                    "index=" + i
                    + " path=" + dir.getAbsolutePath()
                    + " exists=" + dir.exists()
                    + " canRead=" + dir.canRead()
                    + " canWrite=" + dir.canWrite());
        }
        r.appDirs = sb.toString().trim();
    }

    private static String runAllowedShell() {
        StringBuilder sb = new StringBuilder();
        sb.append(execLogged(new String[]{"id"})).append('\n');
        sb.append(execLogged(new String[]{"ls", "-ld", USB_ROOT})).append('\n');
        sb.append(execLogged(new String[]{"ls", "-ld", USB_DCIM})).append('\n');
        sb.append(execLogged(new String[]{"ls", "-ld", USB_CAMERA})).append('\n');
        sb.append(execLogged(new String[]{"stat", USB_CAMERA}));
        return sb.toString().trim();
    }

    private static String execLogged(String[] argv) {
        if (!isAllowedShell(argv)) {
            UsbDeleteLog.e("PROBE_SHELL", "blocked argv=" + Arrays.toString(argv));
            return "blocked " + Arrays.toString(argv);
        }
        try {
            java.lang.Process p = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            String out = readStream(p.getInputStream());
            int code = p.waitFor();
            String line = Arrays.toString(argv) + " exit=" + code + " " + out.trim();
            UsbDeleteLog.i("PROBE_SHELL", line);
            return line;
        } catch (Throwable t) {
            UsbDeleteLog.e("PROBE_SHELL", "argv=" + Arrays.toString(argv), t);
            return Arrays.toString(argv) + " exception=" + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static void runDisposableProbe(Report r) {
        File probe = new File(PROBE_PATH);
        if (!isProbePath(probe.getAbsolutePath()) || isCameraMp4(probe.getName())) {
            r.success = false;
            r.reason = "refusing non-probe path";
            r.probeCreate = "refused";
            return;
        }
        if (skipIfProbeAlreadyExists(probe.exists())) {
            r.probeCreate = "PROBE_ALREADY_EXISTS — not touched";
            r.success = false;
            r.reason = "PROBE_ALREADY_EXISTS: " + PROBE_PATH + " already present, not modified";
            UsbDeleteLog.w("PROBE_ALREADY_EXISTS", r.reason);
            return;
        }
        File parent = probe.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            r.probeCreate = "skipped — DCIM/Camera missing";
            r.success = false;
            r.reason = "DCIM/Camera does not exist or is not a directory: " + USB_CAMERA;
            UsbDeleteLog.w("PROBE_CREATE", r.reason);
            return;
        }

        boolean created = false;
        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write(PROBE_BYTES);
            out.flush();
            created = true;
        } catch (Throwable t) {
            r.exception = appendEx(r.exception, t);
            r.probeCreate = "exception " + t.getClass().getName() + ": " + t.getMessage();
            UsbDeleteLog.e("PROBE_CREATE", r.probeCreate, t);
        }
        boolean existsAfterCreate = probe.exists();
        if (created && existsAfterCreate) {
            r.probeCreate = "existsAfterCreate=true";
        } else if (!existsAfterCreate) {
            r.probeCreate = "existsAfterCreate=false";
        }
        UsbDeleteLog.i("PROBE_CREATE",
                "path=" + PROBE_PATH
                + " created=" + created
                + " existsAfterCreate=" + existsAfterCreate);
        if (!existsAfterCreate) {
            r.success = false;
            r.reason = "PROBE FAILED: could not create " + PROBE_PATH
                    + (r.exception.equals("none") ? "" : " (" + r.exception + ")");
            r.probeDeleteReturned = "skipped";
            r.probeExistsAfterDelete = "n/a";
            return;
        }

        boolean existsBefore = probe.exists();
        UsbDeleteLog.i("PROBE_DELETE_ATTEMPT",
                "path=" + PROBE_PATH + " existsBefore=" + existsBefore + " api=File.delete");
        boolean returned = false;
        try {
            returned = probe.delete();
        } catch (Throwable t) {
            r.exception = appendEx(r.exception, t);
            UsbDeleteLog.e("PROBE_DELETE_RESULT", "exception", t);
        }
        boolean existsAfter = probe.exists();
        r.probeDeleteReturned = String.valueOf(returned);
        r.probeExistsAfterDelete = String.valueOf(existsAfter);
        r.success = probeDeleteSuccess(existsAfter);
        if (r.success) {
            r.reason = "created probe tmp then File.delete left existsAfterDelete=false";
        } else {
            r.reason = "PROBE FAILED: File.delete returned=" + returned
                    + " existsAfterDelete=" + existsAfter
                    + (r.exception.equals("none") ? "" : " exception=" + r.exception);
        }
        UsbDeleteLog.i("PROBE_DELETE_RESULT",
                "returned=" + returned
                + " existsBefore=" + existsBefore
                + " existsAfter=" + existsAfter
                + " success=" + r.success);
    }

    private static String appendEx(String current, Throwable t) {
        String next = t.getClass().getName() + ": " + t.getMessage();
        if (current == null || current.isEmpty() || "none".equals(current)) {
            return next;
        }
        return current + " | " + next;
    }

    private static String readTextFile(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (Exception ignored) {
            return "";
        }
        return sb.toString();
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
