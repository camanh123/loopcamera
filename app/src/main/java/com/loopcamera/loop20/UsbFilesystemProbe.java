package com.loopcamera.loop20;

import android.content.Context;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private static final byte[] PROBE_BYTES =
            "CameraLoop30 probe file. Not a video.\n".getBytes(StandardCharsets.UTF_8);

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

    public static void run(Context context) {
        UsbDeleteLog.i("PROBE_START", "CHỈ KIỂM TRA — KHÔNG XÓA VIDEO path=" + PROBE_PATH);
        logPath("USB_PATH", new File(USB_ROOT));
        logPath("USB_PATH", new File(USB_DCIM));
        logPath("USB_PATH", new File(USB_CAMERA));
        logProcess(context);
        logMounts();
        logExternalFilesDirs(context);
        runAllowedShell();
        runDisposableProbe();
        UsbDeleteLog.i("PROBE_DONE", "production delete logic unchanged");
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

    private static void logProcess(Context context) {
        String selinux = readTextFile(new File("/proc/self/attr/current")).trim();
        UsbDeleteLog.i("PROBE_PROCESS",
                "package=" + context.getPackageName()
                + " uid=" + Process.myUid()
                + " pid=" + Process.myPid()
                + " selinux=/proc/self/attr/current value=" + (selinux.isEmpty() ? "(empty)" : selinux));
    }

    private static void logMounts() {
        String mounts = readTextFile(new File("/proc/mounts"));
        if (mounts.isEmpty()) {
            UsbDeleteLog.w("PROBE_MOUNT", "could not read /proc/mounts");
            return;
        }
        boolean found = false;
        for (String line : mounts.split("\n")) {
            if (lineContainsUsb1(line)) {
                found = true;
                UsbDeleteLog.i("PROBE_MOUNT", parseMountLine(line));
            }
        }
        if (!found) {
            UsbDeleteLog.i("PROBE_MOUNT", "no /proc/mounts line containing USB1; dumping lines with /storage/");
            for (String line : mounts.split("\n")) {
                if (line.contains("/storage/")) {
                    UsbDeleteLog.i("PROBE_MOUNT", parseMountLine(line));
                }
            }
        }
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

    private static void logExternalFilesDirs(Context context) {
        File[] dirs;
        try {
            dirs = context.getExternalFilesDirs(null);
        } catch (Throwable t) {
            UsbDeleteLog.e("PROBE_APP_DIR", "getExternalFilesDirs failed", t);
            return;
        }
        if (dirs == null || dirs.length == 0) {
            UsbDeleteLog.i("PROBE_APP_DIR", "getExternalFilesDirs=empty");
            return;
        }
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) {
                UsbDeleteLog.i("PROBE_APP_DIR", "index=" + i + " path=null");
                continue;
            }
            UsbDeleteLog.i("PROBE_APP_DIR",
                    "index=" + i
                    + " path=" + dir.getAbsolutePath()
                    + " exists=" + dir.exists()
                    + " canRead=" + dir.canRead()
                    + " canWrite=" + dir.canWrite());
        }
    }

    private static void runAllowedShell() {
        execLogged(new String[]{"id"});
        execLogged(new String[]{"ls", "-ld", USB_ROOT});
        execLogged(new String[]{"ls", "-ld", USB_DCIM});
        execLogged(new String[]{"ls", "-ld", USB_CAMERA});
        execLogged(new String[]{"stat", USB_CAMERA});
    }

    private static void execLogged(String[] argv) {
        if (!isAllowedShell(argv)) {
            UsbDeleteLog.e("PROBE_SHELL", "blocked argv=" + Arrays.toString(argv));
            return;
        }
        try {
            Process p = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            String out = readStream(p.getInputStream());
            int code = p.waitFor();
            UsbDeleteLog.i("PROBE_SHELL",
                    "argv=" + Arrays.toString(argv)
                    + " exit=" + code
                    + " out=" + out.trim());
        } catch (Throwable t) {
            UsbDeleteLog.e("PROBE_SHELL", "argv=" + Arrays.toString(argv), t);
        }
    }

    private static void runDisposableProbe() {
        File probe = new File(PROBE_PATH);
        if (!isProbePath(probe.getAbsolutePath())) {
            UsbDeleteLog.e("PROBE_CREATE", "refusing non-probe path=" + probe.getAbsolutePath());
            return;
        }
        if (isCameraMp4(probe.getName())) {
            UsbDeleteLog.e("PROBE_CREATE", "refusing mp4");
            return;
        }
        if (skipIfProbeAlreadyExists(probe.exists())) {
            UsbDeleteLog.w("PROBE_ALREADY_EXISTS",
                    "path=" + PROBE_PATH + " exists=true — DO NOT TOUCH");
            return;
        }
        File parent = probe.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            UsbDeleteLog.w("PROBE_CREATE",
                    "skipped parent missing path=" + USB_CAMERA
                    + " exists=" + (parent != null && parent.exists()));
            return;
        }

        boolean created = false;
        String createEx = null;
        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write(PROBE_BYTES);
            out.flush();
            created = true;
        } catch (Throwable t) {
            createEx = t.getClass().getName() + ": " + t.getMessage();
            UsbDeleteLog.e("PROBE_CREATE",
                    "path=" + PROBE_PATH
                    + " existsAfterCreate=" + probe.exists()
                    + " exception=" + createEx, t);
        }
        boolean existsAfterCreate = probe.exists();
        UsbDeleteLog.i("PROBE_CREATE",
                "path=" + PROBE_PATH
                + " created=" + created
                + " existsAfterCreate=" + existsAfterCreate
                + (createEx == null ? "" : " exception=" + createEx));
        if (!existsAfterCreate) {
            UsbDeleteLog.w("PROBE_DELETE_ATTEMPT", "skipped — probe file was not created");
            return;
        }
        if (!isProbePath(probe.getAbsolutePath())) {
            UsbDeleteLog.e("PROBE_DELETE_ATTEMPT", "refusing non-probe path");
            return;
        }

        boolean existsBefore = probe.exists();
        UsbDeleteLog.i("PROBE_DELETE_ATTEMPT",
                "path=" + PROBE_PATH
                + " existsBefore=" + existsBefore
                + " api=File.delete");
        boolean returned = false;
        String delEx = null;
        try {
            returned = probe.delete();
        } catch (Throwable t) {
            delEx = t.getClass().getName() + ": " + t.getMessage();
            UsbDeleteLog.e("PROBE_DELETE_RESULT", "exception", t);
        }
        boolean existsAfter = probe.exists();
        boolean success = probeDeleteSuccess(existsAfter);
        UsbDeleteLog.i("PROBE_DELETE_RESULT",
                "path=" + PROBE_PATH
                + " returned=" + returned
                + " existsBefore=" + existsBefore
                + " existsAfter=" + existsAfter
                + " success=" + success
                + (delEx == null ? "" : " exception=" + delEx));
    }

    private static String readTextFile(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
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
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static List<String> allowedShellArgv() {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, "id", "ls -ld " + USB_ROOT, "ls -ld " + USB_DCIM,
                "ls -ld " + USB_CAMERA, "stat " + USB_CAMERA);
        return out;
    }
}
