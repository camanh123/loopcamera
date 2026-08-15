package com.loopcamera.loop20;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only SAF/DocumentsUI probes for CARFU diagnostics.
 * Does not grant permissions or change folder-picker behavior.
 */
public final class SafDiagnostics {

    public static final String EVENT_PICKER_LAUNCH = "SAF_PICKER_LAUNCH";
    public static final String EVENT_PICKER_RESULT = "SAF_PICKER_RESULT";
    public static final String EVENT_TREE_URI = "SAF_TREE_URI";
    public static final String EVENT_PERSISTABLE_FLAGS = "SAF_PERSISTABLE_FLAGS";
    public static final String EVENT_TAKE_PERMISSION = "SAF_TAKE_PERMISSION";
    public static final String EVENT_PERSISTED_URI = "SAF_PERSISTED_URI";
    public static final String EVENT_PERMISSION_CHECK = "SAF_PERMISSION_CHECK";
    public static final String EVENT_DOCUMENTFILE_RESOLVE = "SAF_DOCUMENTFILE_RESOLVE";

    private static final String[] DOCUMENTS_UI_PACKAGES = {
            "com.android.documentsui",
            "com.google.android.documentsui"
    };

    private SafDiagnostics() {
    }

    public static boolean openDocumentTreeResolvable(Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        return intent.resolveActivity(context.getPackageManager()) != null;
    }

    public static String pickerComponent(Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        ResolveInfo info = context.getPackageManager()
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) {
            return "none";
        }
        return info.activityInfo.packageName + "/" + info.activityInfo.name;
    }

    public static String documentsUiPackages(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> found = new ArrayList<>();
        for (String pkg : DOCUMENTS_UI_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info != null) {
                    found.add(pkg);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return found.isEmpty() ? "none" : found.toString();
    }

    public static String persistedPermissionDump(Context context) {
        List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
        if (perms == null || perms.isEmpty()) {
            return "count=0";
        }
        StringBuilder sb = new StringBuilder("count=").append(perms.size());
        for (UriPermission p : perms) {
            sb.append(" [uri=").append(p.getUri())
                    .append(" read=").append(p.isReadPermission())
                    .append(" write=").append(p.isWritePermission())
                    .append("]");
        }
        return sb.toString();
    }

    public static void logPermissionCheck(Context context, AppPreferences prefs, String reason) {
        Uri saved = prefs.getSafTreeUri();
        boolean resolvable = openDocumentTreeResolvable(context);
        UsbDeleteLog.i(EVENT_PERMISSION_CHECK,
                "reason=" + reason
                + " openDocumentTreeResolvable=" + resolvable
                + " pickerComponent=" + pickerComponent(context)
                + " documentsUiPackages=" + documentsUiPackages(context)
                + " savedSafTree=" + (saved == null ? "none" : saved)
                + " persistedWriteForSaved=" + UsbDeleter.hasPersistedTree(context, saved)
                + " " + persistedPermissionDump(context)
                + " note=WRITE_EXTERNAL_STORAGE_does_not_grant_USB1_delete");
    }

    public static void logDocumentFileResolve(Context context, Uri treeUri, String name) {
        if (treeUri == null) {
            UsbDeleteLog.i(EVENT_DOCUMENTFILE_RESOLVE, "treeUri=none name=" + name);
            return;
        }
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            UsbDeleteLog.i(EVENT_DOCUMENTFILE_RESOLVE,
                    "treeUri=" + treeUri
                    + " root=" + (root == null ? "null" : root.getUri())
                    + " rootExists=" + (root != null && root.exists())
                    + " rootCanWrite=" + (root != null && root.canWrite())
                    + " name=" + name);
        } catch (Throwable t) {
            UsbDeleteLog.e(EVENT_DOCUMENTFILE_RESOLVE, "treeUri=" + treeUri + " name=" + name, t);
        }
    }
}
