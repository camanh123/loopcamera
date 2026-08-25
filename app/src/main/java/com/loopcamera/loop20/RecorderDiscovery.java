package com.loopcamera.loop20;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Diagnostic-only: list likely camera/DVR packages on the head unit.
 * Does not start recording, delete MP4s, or talk to a vendor API.
 */
public final class RecorderDiscovery {

    public static final String EXPORT_NAME = "CameraLoop30_recorder_discovery.txt";
    public static final String SELF = RecorderCandidate.SELF_PACKAGE;

    public static final class Report {
        public boolean scanPass;
        public int packageCount;
        public int likelyCount;
        public String verdict = RecorderCandidate.VERDICT_E;
        public String verdictReason = "not run";
        public String strongestPackage = "(none)";
        public String strongestLabel = "(none)";
        public String body = "";
        public String exception = "none";
        public String exportPath = "";

        public String screenText() {
            return body;
        }
    }

    private RecorderDiscovery() {
    }

    public static Report run(Context context) {
        Report r = new Report();
        LoopLog.get().i("RECORDER DISCOVERY — CHỈ QUÉT PACKAGE, không ghi/xóa MP4");
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> installed;
        try {
            installed = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_PERMISSIONS);
        } catch (Throwable t) {
            r.scanPass = false;
            r.verdict = RecorderCandidate.VERDICT_E;
            r.verdictReason = "getInstalledPackages failed: " + t.getClass().getName();
            r.exception = t.getClass().getName() + ": " + t.getMessage();
            r.body = header(r) + "Scan failed:\n" + r.exception + "\n";
            LoopLog.get().e(r.verdictReason, t);
            return r;
        }
        r.packageCount = installed == null ? 0 : installed.size();
        Set<String> videoCapture = videoCapturePackages(pm);

        List<RecorderCandidate> raw = new ArrayList<>();
        if (installed != null) {
            for (PackageInfo pi : installed) {
                if (pi == null || pi.packageName == null) {
                    continue;
                }
                if (RecorderCandidate.looksLikeNoise(pi.packageName)) {
                    continue;
                }
                raw.add(fromPackage(pm, pi, videoCapture.contains(pi.packageName)));
            }
        }
        List<RecorderCandidate> likely = RecorderCandidateScorer.rank(raw);
        r.likelyCount = likely.size();
        RecorderCandidate strongest = likely.isEmpty() ? null : likely.get(0);
        r.verdict = RecorderCandidateScorer.verdict(strongest);
        r.verdictReason = RecorderCandidateScorer.verdictReason(strongest);
        if (strongest != null) {
            r.strongestPackage = strongest.packageName;
            r.strongestLabel = strongest.label;
        }
        r.scanPass = true;
        r.body = buildBody(r, likely, strongest);
        r.exportPath = saveExport(context, r);
        LoopLog.get().i("RECORDER DISCOVERY verdict=" + r.verdict
                + " strongest=" + r.strongestPackage
                + " likely=" + r.likelyCount
                + "/" + r.packageCount);
        return r;
    }

    static RecorderCandidate fromPackage(PackageManager pm, PackageInfo pi, boolean videoCapture) {
        RecorderCandidate c = new RecorderCandidate();
        c.packageName = pi.packageName;
        try {
            CharSequence label = pm.getApplicationLabel(pi.applicationInfo);
            c.label = label == null ? pi.packageName : label.toString();
        } catch (Throwable t) {
            c.label = pi.packageName;
        }
        c.systemApp = pi.applicationInfo != null
                && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        c.handlesVideoCapture = videoCapture;
        String[] perms = pi.requestedPermissions;
        if (perms != null) {
            for (String p : perms) {
                if ("android.permission.CAMERA".equals(p)) {
                    c.cameraPermission = true;
                } else if ("android.permission.RECORD_AUDIO".equals(p)) {
                    c.recordAudioPermission = true;
                } else if ("android.permission.READ_EXTERNAL_STORAGE".equals(p)) {
                    c.readStoragePermission = true;
                } else if ("android.permission.WRITE_EXTERNAL_STORAGE".equals(p)) {
                    c.writeStoragePermission = true;
                }
            }
        }
        if (pi.activities != null) {
            for (ActivityInfo a : pi.activities) {
                addComponent(c.activities, c.exportedActivities, a.name, a.exported);
            }
        }
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) {
                addComponent(c.services, c.exportedServices, s.name, s.exported);
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo rec : pi.receivers) {
                addComponent(c.receivers, c.exportedReceivers, rec.name, rec.exported);
            }
        }
        if (pi.providers != null) {
            for (ProviderInfo p : pi.providers) {
                String shown = p.name + (p.authority == null ? "" : " auth=" + p.authority);
                addComponent(c.providers, c.exportedProviders, shown, p.exported);
            }
        }
        return c;
    }

    private static void addComponent(List<String> all, List<String> exported, String name, boolean isExported) {
        if (name == null || name.isEmpty()) {
            return;
        }
        all.add(name);
        if (isExported) {
            exported.add(name);
        }
    }

    static Set<String> videoCapturePackages(PackageManager pm) {
        Set<String> out = new HashSet<>();
        try {
            Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        out.add(ri.activityInfo.packageName);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String header(Report r) {
        return "--------------------------------\n"
                + "CARFU CAMERA / DVR DISCOVERY\n"
                + "--------------------------------\n\n"
                + "Camera Loop records MP4:\nNO\n\n"
                + "Camera Loop role:\nscan + loop-delete manager only\n\n"
                + "Target files:\n/storage/USB1/DCIM/Camera/*.mp4\n\n"
                + "Scan:\n" + (r.scanPass ? "PASS" : "FAIL") + "\n"
                + "Installed packages seen:\n" + r.packageCount + "\n"
                + "Likely camera/DVR apps:\n" + r.likelyCount + "\n\n"
                + "VERDICT:\n" + r.verdict + "\n"
                + r.verdictReason + "\n\n";
    }

    static String buildBody(Report r, List<RecorderCandidate> likely, RecorderCandidate strongest) {
        StringBuilder sb = new StringBuilder(header(r));
        sb.append("Strongest candidate:\n");
        if (strongest == null) {
            sb.append("(none)\n\n");
        } else {
            appendCandidate(sb, strongest, true);
        }
        sb.append("A. Recorder output path can be changed:\nNOT PROVEN\n");
        sb.append("B. Recorder has its own loop/overwrite:\nNOT PROVEN\n");
        sb.append("C. Recorder exposes a delete/control API:\nNOT PROVEN\n");
        sb.append("D. Identified but no public control:\n")
                .append(RecorderCandidate.VERDICT_D.equals(r.verdict) ? "YES" : "NO").append('\n');
        sb.append("E. Recorder still unknown:\n")
                .append(RecorderCandidate.VERDICT_E.equals(r.verdict) ? "YES" : "NO").append("\n\n");
        sb.append("Private USB (Camera Loop only; recorder may not use it):\n")
                .append("/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera\n\n");
        sb.append("All likely apps (score desc):\n");
        if (likely.isEmpty()) {
            sb.append("(none)\n");
        } else {
            int n = 0;
            for (RecorderCandidate c : likely) {
                n++;
                sb.append("\n#").append(n).append(" score=").append(c.score).append('\n');
                appendCandidate(sb, c, false);
            }
        }
        sb.append("\nNOTE: This scan cannot prove which process writes DCIM.\n");
        sb.append("Open the strongest candidate on the head unit and look for USB/path settings.\n");
        if (!r.exportPath.isEmpty()) {
            sb.append("exported:\n").append(r.exportPath).append('\n');
        }
        return sb.toString();
    }

    static void appendCandidate(StringBuilder sb, RecorderCandidate c, boolean detail) {
        sb.append("label: ").append(c.label).append('\n');
        sb.append("package: ").append(c.packageName).append('\n');
        sb.append("system/user: ").append(c.systemApp ? "SYSTEM" : "USER").append('\n');
        sb.append("CAMERA permission: ").append(yn(c.cameraPermission)).append('\n');
        sb.append("RECORD_AUDIO: ").append(yn(c.recordAudioPermission)).append('\n');
        sb.append("READ_EXTERNAL_STORAGE: ").append(yn(c.readStoragePermission)).append('\n');
        sb.append("WRITE_EXTERNAL_STORAGE: ").append(yn(c.writeStoragePermission)).append('\n');
        sb.append("VIDEO_CAPTURE handler: ").append(yn(c.handlesVideoCapture)).append('\n');
        sb.append("score: ").append(c.score).append('\n');
        sb.append("DVR/camera/record-related names:\n");
        appendList(sb, c.recordLikeComponents, 12);
        sb.append("settings-like components:\n");
        appendList(sb, c.settingsLike, 8);
        sb.append("path/storage-like components:\n");
        appendList(sb, c.pathLike, 8);
        sb.append("loop/cycle/overwrite-like components:\n");
        appendList(sb, c.loopLike, 8);
        sb.append("exported activities:\n");
        appendList(sb, c.exportedActivities, 8);
        sb.append("exported services:\n");
        appendList(sb, c.exportedServices, 8);
        sb.append("exported receivers:\n");
        appendList(sb, c.exportedReceivers, 8);
        sb.append("exported providers:\n");
        appendList(sb, c.exportedProviders, 8);
        if (detail) {
            sb.append("activities:\n");
            appendList(sb, c.activities, 20);
            sb.append("services:\n");
            appendList(sb, c.services, 20);
            sb.append("receivers:\n");
            appendList(sb, c.receivers, 12);
            sb.append("providers:\n");
            appendList(sb, c.providers, 12);
        }
        sb.append('\n');
    }

    private static void appendList(StringBuilder sb, List<String> items, int max) {
        if (items == null || items.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        int n = Math.min(max, items.size());
        for (int i = 0; i < n; i++) {
            sb.append("  - ").append(items.get(i)).append('\n');
        }
        if (items.size() > max) {
            sb.append("  … +").append(items.size() - max).append(" more\n");
        }
    }

    static String yn(boolean v) {
        return v ? "YES" : "NO";
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
        return "Camera Loop 30 recorder discovery\n" + stamp + "\n\n" + r.screenText();
    }

    private static String saveExport(Context context, Report r) {
        File f = exportFile(context, r);
        return f == null ? "" : f.getAbsolutePath();
    }
}
