package com.loopcamera.loop20;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort process probes from an ordinary app sandbox. Never assumes root,
 * adb, readable /proc fds, or that lsof exists.
 */
public final class ProcFdProbe {

    public static final class Capabilities {
        public boolean procDirListable;
        public int pidDirsVisible;
        public boolean selfFdReadable;
        public int fdDirsReadable;
        public int fdDirsDenied;
        public int cmdlineReadable;
        public int statusReadable;
        public boolean mountinfoReadable;
        public String lsof = "not probed";
        public String toyboxLsof = "not probed";
        public String fuser = "not probed";
        public String logcat = "not probed";
        public boolean readLogsGranted;
        public int runningAppProcessCount = -1;
        public String runningAppProcessNote = "";
        public String mediaStore = "not probed";
        public String usageStats = "not probed";
        public String exception = "none";

        public String unavailableSummary() {
            StringBuilder sb = new StringBuilder();
            if (!procDirListable) {
                line(sb, "/proc: not listable");
            } else {
                line(sb, "/proc pids visible=" + pidDirsVisible
                        + " fd-readable=" + fdDirsReadable
                        + " fd-denied=" + fdDirsDenied
                        + " cmdline-readable=" + cmdlineReadable
                        + " status-readable=" + statusReadable);
            }
            if (!selfFdReadable) {
                line(sb, "/proc/self/fd: not readable");
            }
            if (!mountinfoReadable) {
                line(sb, "/proc/self/mountinfo: not readable");
            }
            if (fdDirsReadable <= 1 && fdDirsDenied > 0) {
                line(sb, "/proc/[pid]/fd for other UIDs: EACCES/null (ordinary app / hidepid)");
            }
            line(sb, "lsof: " + lsof);
            line(sb, "toybox lsof: " + toyboxLsof);
            line(sb, "fuser: " + fuser);
            line(sb, "logcat: " + logcat);
            line(sb, "READ_LOGS granted: " + readLogsGranted);
            line(sb, "ActivityManager.getRunningAppProcesses: count="
                    + runningAppProcessCount
                    + (runningAppProcessNote.isEmpty() ? "" : " (" + runningAppProcessNote + ")"));
            line(sb, "MediaStore: " + mediaStore);
            line(sb, "UsageStats: " + usageStats);
            return sb.toString();
        }

        private static void line(StringBuilder sb, String s) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(s);
        }
    }

    public static final class FdHit {
        public int pid;
        public int fd = -1;
        public int uid = -1;
        public String cmdline = "";
        public String statusName = "";
        public String fdTarget = "";
        public String[] packages = new String[0];
        public boolean inodeMatch;

        public String identity() {
            String pkg = firstPackage();
            String proc = !cmdline.isEmpty() ? cmdline : statusName;
            StringBuilder sb = new StringBuilder();
            if (pkg != null) {
                sb.append(pkg);
            } else if (!proc.isEmpty()) {
                sb.append(proc);
            } else {
                sb.append("pid ").append(pid);
            }
            sb.append(" (pid ").append(pid);
            if (uid >= 0) {
                sb.append(" uid ").append(uid);
            }
            if (!proc.isEmpty() && pkg != null) {
                sb.append(" cmdline=").append(trimCmd(proc));
            }
            sb.append(')');
            return sb.toString();
        }

        public String firstPackage() {
            if (packages == null) {
                return null;
            }
            for (String p : packages) {
                if (p != null && !p.isEmpty()) {
                    return p;
                }
            }
            return null;
        }

        public String packagesJoined() {
            if (packages == null || packages.length == 0) {
                return "(none)";
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String p : packages) {
                if (n >= 8) {
                    sb.append(" ...");
                    break;
                }
                if (n > 0) {
                    sb.append(", ");
                }
                sb.append(p);
                n++;
            }
            return sb.toString();
        }
    }

    public static final class RunningProc {
        public int pid;
        public int uid;
        public String processName = "";
        public String[] pkgList = new String[0];
        public int importance;
    }

    private ProcFdProbe() {
    }

    public static Capabilities probeCapabilities(Context context) {
        Capabilities c = new Capabilities();
        try {
            c.readLogsGranted = context.checkSelfPermission("android.permission.READ_LOGS")
                    == PackageManager.PERMISSION_GRANTED;
            File proc = new File("/proc");
            File[] kids = proc.listFiles();
            c.procDirListable = kids != null;
            if (kids != null) {
                for (File k : kids) {
                    if (isPidDir(k)) {
                        c.pidDirsVisible++;
                    }
                }
            }
            File selfFd = new File("/proc/self/fd");
            File[] selfFds = selfFd.listFiles();
            c.selfFdReadable = selfFds != null;
            String mount = readSmall(new File("/proc/self/mountinfo"), 64 * 1024);
            c.mountinfoReadable = mount != null && !mount.isEmpty();

            int scanned = 0;
            if (kids != null) {
                for (File k : kids) {
                    if (!isPidDir(k)) {
                        continue;
                    }
                    scanned++;
                    if (scanned > 80) {
                        break;
                    }
                    File fdDir = new File(k, "fd");
                    File[] fds = fdDir.listFiles();
                    if (fds != null) {
                        c.fdDirsReadable++;
                    } else {
                        c.fdDirsDenied++;
                    }
                    if (readSmall(new File(k, "cmdline"), 4096) != null) {
                        c.cmdlineReadable++;
                    }
                    if (readSmall(new File(k, "status"), 8192) != null) {
                        c.statusReadable++;
                    }
                }
            }

            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                c.runningAppProcessCount = list == null ? 0 : list.size();
                if (list == null) {
                    c.runningAppProcessNote = "returned null";
                } else if (list.size() <= 1) {
                    c.runningAppProcessNote = "Android 10 typically hides other apps; not proof";
                }
            } else {
                c.runningAppProcessNote = "ActivityManager null";
            }

            c.lsof = classifyExec(runCmd(1500, "which", "lsof"));
            String toyHelp = runCmd(1500, "toybox", "lsof", "-h");
            if (unavailable(toyHelp) || toyHelp.toLowerCase(Locale.US).contains("not found")
                    || toyHelp.toLowerCase(Locale.US).contains("unknown")) {
                toyHelp = runCmd(1500, "/system/bin/toybox", "lsof", "-h");
            }
            c.toyboxLsof = classifyExec(toyHelp);
            c.fuser = classifyExec(runCmd(1500, "which", "fuser"));
            String logOut = runCmd(2000, "logcat", "-d", "-t", "8", "-v", "brief");
            c.logcat = classifyLogcat(logOut, c.readLogsGranted);
        } catch (Throwable t) {
            c.exception = t.getClass().getName() + ": " + t.getMessage();
        }
        return c;
    }

    public static String readMountinfo() {
        String s = readSmall(new File("/proc/self/mountinfo"), 96 * 1024);
        return s == null ? "" : s;
    }

    public static List<RunningProc> runningAppProcesses(Context context) {
        List<RunningProc> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return out;
            }
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) {
                return out;
            }
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info == null) {
                    continue;
                }
                RunningProc p = new RunningProc();
                p.pid = info.pid;
                p.uid = info.uid;
                p.processName = info.processName == null ? "" : info.processName;
                p.pkgList = info.pkgList == null ? new String[0] : info.pkgList;
                p.importance = info.importance;
                out.add(p);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String foregroundSummary(List<RunningProc> running) {
        if (running == null || running.isEmpty()) {
            return "ActivityManager.getRunningAppProcesses empty/unavailable (not proof)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (RunningProc p : running) {
            if (p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                continue;
            }
            if (n >= 6) {
                sb.append(" ...");
                break;
            }
            if (n > 0) {
                sb.append("; ");
            }
            sb.append(p.processName).append(" pid=").append(p.pid).append(" uid=").append(p.uid);
            n++;
        }
        if (n == 0) {
            return "no IMPORTANCE_FOREGROUND process visible (count=" + running.size() + ")";
        }
        return sb.toString();
    }

    public static FdHit scanForWriter(Context context, File mp4, Collection<String> cameraDirAliases, long budgetMs) {
        if (mp4 == null) {
            return null;
        }
        long start = SystemClock.elapsedRealtime();
        String filename = mp4.getName();
        long[] mp4Id = statDevIno(mp4.getAbsolutePath());
        PackageManager pm = context.getPackageManager();
        Set<Integer> scanned = new LinkedHashSet<>();
        List<RunningProc> running = runningAppProcesses(context);
        for (RunningProc p : running) {
            if (SystemClock.elapsedRealtime() - start > budgetMs) {
                break;
            }
            FdHit hit = scanPid(p.pid, filename, cameraDirAliases, mp4Id, pm);
            scanned.add(p.pid);
            if (hit != null) {
                return hit;
            }
        }
        File proc = new File("/proc");
        File[] kids;
        try {
            kids = proc.listFiles();
        } catch (Throwable t) {
            kids = null;
        }
        if (kids == null) {
            return null;
        }
        int self = Process.myPid();
        for (File k : kids) {
            if (SystemClock.elapsedRealtime() - start > budgetMs) {
                break;
            }
            if (!isPidDir(k)) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(k.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self || scanned.contains(pid)) {
                continue;
            }
            FdHit hit = scanPid(pid, filename, cameraDirAliases, mp4Id, pm);
            if (hit != null) {
                return hit;
            }
        }
        FdHit selfHit = scanPid(self, filename, cameraDirAliases, mp4Id, pm);
        if (selfHit != null) {
            return selfHit;
        }
        return null;
    }

    private static FdHit scanPid(int pid, String filename, Collection<String> aliases, long[] mp4Id,
                                 PackageManager pm) {
        File fdDir = new File("/proc/" + pid + "/fd");
        File[] fds;
        try {
            fds = fdDir.listFiles();
        } catch (Throwable t) {
            return null;
        }
        if (fds == null) {
            return null;
        }
        for (File fd : fds) {
            String target;
            try {
                target = Os.readlink(fd.getAbsolutePath());
            } catch (Throwable t) {
                continue;
            }
            if (target == null) {
                continue;
            }
            boolean pathMatch = PathAliasResolver.matchesFdTarget(target, filename, aliases);
            boolean inodeMatch = false;
            if (!pathMatch && mp4Id != null) {
                long[] other = statDevIno(PathAliasResolver.normalize(target));
                if (other != null) {
                    inodeMatch = PathAliasResolver.sameIdentity(mp4Id[0], mp4Id[1], other[0], other[1]);
                }
            }
            if (!pathMatch && !inodeMatch) {
                continue;
            }
            FdHit hit = new FdHit();
            hit.pid = pid;
            try {
                hit.fd = Integer.parseInt(fd.getName());
            } catch (NumberFormatException e) {
                hit.fd = -1;
            }
            hit.fdTarget = target;
            hit.inodeMatch = inodeMatch;
            String status = readSmall(new File("/proc/" + pid + "/status"), 8192);
            hit.uid = parseUid(status);
            hit.statusName = parseStatusName(status);
            String cmd = readSmall(new File("/proc/" + pid + "/cmdline"), 4096);
            hit.cmdline = cmd == null ? "" : cmd.replace('\0', ' ').trim();
            if (hit.uid >= 0 && pm != null) {
                try {
                    String[] pkgs = pm.getPackagesForUid(hit.uid);
                    hit.packages = pkgs == null ? new String[0] : pkgs;
                } catch (Throwable ignored) {
                }
            }
            return hit;
        }
        return null;
    }

    public static String runAgainstFile(String tool, File mp4) {
        if (mp4 == null) {
            return "no current file";
        }
        String path = mp4.getAbsolutePath();
        if ("lsof".equals(tool)) {
            return runCmd(2500, "lsof", path);
        }
        if ("toybox-lsof".equals(tool)) {
            String a = runCmd(2500, "toybox", "lsof", path);
            if (unavailable(a)) {
                return runCmd(2500, "/system/bin/toybox", "lsof", path);
            }
            return a;
        }
        if ("fuser".equals(tool)) {
            return runCmd(2500, "fuser", path);
        }
        return "unknown tool";
    }

    public static String dumpLogcat() {
        return runCmd(3000, "logcat", "-d", "-t", "160", "-v", "threadtime");
    }

    public static List<String> logHits(String logcat, String filename, long aroundEpochMs) {
        List<String> hits = new ArrayList<>();
        if (logcat == null || logcat.isEmpty() || unavailable(logcat)) {
            return hits;
        }
        String[] keys = new String[]{
                filename == null ? "" : filename,
                "DCIM/Camera",
                "MediaRecorder",
                "recorder",
                "DVR",
                "camera",
                "muxer",
                "MPEG4",
                "storage"
        };
        String[] lines = logcat.split("\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (line.contains("CameraLoop30") || line.contains("com.loopcamera.loop20")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.US);
            boolean match = false;
            for (String k : keys) {
                if (k != null && !k.isEmpty() && lower.contains(k.toLowerCase(Locale.US))) {
                    match = true;
                    break;
                }
            }
            if (match) {
                hits.add(line.trim());
                if (hits.size() >= 12) {
                    break;
                }
            }
        }
        return hits;
    }

    static long[] statDevIno(String path) {
        if (path == null || path.isEmpty() || looksLikeKernel(path)) {
            return null;
        }
        try {
            android.system.StructStat st = Os.lstat(path);
            if (st == null) {
                return null;
            }
            return new long[]{st.st_dev, st.st_ino};
        } catch (Throwable t) {
            return null;
        }
    }

    static int parseUid(String status) {
        if (status == null) {
            return -1;
        }
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] p = line.substring(4).trim().split("\\s+");
                if (p.length > 0) {
                    try {
                        return Integer.parseInt(p[0]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    static String parseStatusName(String status) {
        if (status == null) {
            return "";
        }
        for (String line : status.split("\n")) {
            if (line.startsWith("Name:")) {
                return line.substring(5).trim();
            }
        }
        return "";
    }

    static String readSmall(File file, int max) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[Math.min(max, 96 * 1024)];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    static String runCmd(int timeoutMs, String... cmd) {
        java.lang.Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = drain(p.getInputStream(), bos);
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
                return "TIMEOUT running " + join(cmd);
            }
            try {
                reader.join(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            int code = p.exitValue();
            String out = bos.toString("UTF-8").trim();
            if (out.length() > 4000) {
                out = out.substring(0, 4000) + "\n...truncated...";
            }
            if (code != 0 && out.isEmpty()) {
                return "exit=" + code + " (no output) cmd=" + join(cmd);
            }
            if (out.isEmpty()) {
                return "exit=" + code + " empty output";
            }
            return out;
        } catch (Throwable t) {
            return "UNAVAILABLE: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream bos) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 64 * 1024) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "proc-cmd-drain");
        t.start();
        return t;
    }

    private static String classifyExec(String out) {
        if (out == null) {
            return "not found";
        }
        String l = out.toLowerCase(Locale.US);
        if (l.contains("unavailable") && (l.contains("fnf") || l.contains("no such file")
                || l.contains("ioexception") || l.contains("error=2"))) {
            return "not found (" + trimCmd(out) + ")";
        }
        if (l.startsWith("unavailable:")) {
            return out;
        }
        if (l.contains("permission denied")) {
            return "permission denied: " + trimCmd(out);
        }
        if (l.contains("not found") || l.contains("no such file")) {
            return "not found";
        }
        return "present: " + trimCmd(out);
    }

    private static String classifyLogcat(String out, boolean readLogs) {
        if (unavailable(out)) {
            return out;
        }
        if (!readLogs && (out == null || out.isEmpty() || onlyOwnLogs(out))) {
            return "READ_LOGS unavailable; only own UID logs visible (not a system/root app)";
        }
        return "readable (" + (out == null ? 0 : out.length()) + " chars)";
    }

    private static boolean onlyOwnLogs(String out) {
        if (out == null || out.isEmpty()) {
            return true;
        }
        int others = 0;
        for (String line : out.split("\n")) {
            if (line.contains("CameraLoop30") || line.contains("loopcamera") || line.contains("loop20")) {
                continue;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            others++;
        }
        return others < 2;
    }

    private static boolean unavailable(String s) {
        if (s == null) {
            return true;
        }
        String l = s.toLowerCase(Locale.US);
        return l.startsWith("unavailable") || l.contains("not found") || l.startsWith("timeout");
    }

    private static boolean isPidDir(File f) {
        if (f == null || !f.isDirectory()) {
            return false;
        }
        String n = f.getName();
        if (n.isEmpty()) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            if (!Character.isDigit(n.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeKernel(String path) {
        return PathAliasResolver.looksLikeKernelFd(path);
    }

    private static String join(String[] cmd) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmd) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String trimCmd(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        if (t.length() > 180) {
            return t.substring(0, 180) + "...";
        }
        return t;
    }
}
