package com.loopcamera.loop20;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Android 10 USB-safe delete. File.delete() on /storage/USB1 is often a no-op
 * (scoped storage / FUSE). Falls back to MediaStore then SAF DocumentFile.
 */
public final class UsbDeleter {

    private static final String EXT_STORAGE = "com.android.externalstorage.documents";

    private final Context context;
    private final ContentResolver resolver;

    public UsbDeleter(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    public UsbDeleteResult deleteVideo(File file, Uri persistedTreeUri) {
        String path = file.getAbsolutePath();
        boolean existsBefore = file.exists();
        long length = existsBefore ? file.length() : -1L;
        boolean readable = file.canRead();
        boolean writable = file.canWrite();
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (Exception e) {
            canonical = path;
        }
        boolean hasTree = hasPersistedTree(context, persistedTreeUri);
        UsbDeleteLog.i("DELETE_ATTEMPT",
                "path=" + path
                + " exists=" + existsBefore
                + " length=" + length
                + " readable=" + readable
                + " writable=" + writable
                + " canonicalPath=" + canonical
                + " safTree=" + (persistedTreeUri == null ? "none" : persistedTreeUri)
                + " safWriteGranted=" + hasTree);
        SafDiagnostics.logDocumentFileResolve(context, persistedTreeUri, file.getName());

        if (!existsBefore) {
            UsbDeleteResult gone = new UsbDeleteResult(path, false, length, readable, writable,
                    canonical, "already-absent", true, false, null, null);
            UsbDeleteLog.i("DELETE_RESULT", gone.resultLog());
            return gone;
        }

        List<Attempt> attempts = new ArrayList<>();
        attempts.add(tryFileDelete(file));
        if (file.exists()) {
            attempts.add(tryMediaStore(file));
        }
        if (file.exists()) {
            attempts.add(tryDocumentsContract(file));
        }
        if (file.exists() && persistedTreeUri != null) {
            attempts.add(tryDocumentFileTree(file, persistedTreeUri));
        } else if (file.exists() && persistedTreeUri == null) {
            UsbDeleteLog.i("DELETE_SAF", "skipped persistedTreeUri=none existsAfterDelete=" + file.exists());
        }

        Attempt last = attempts.isEmpty() ? new Attempt("none", false, null, null) : attempts.get(attempts.size() - 1);
        boolean existsAfter = file.exists();
        UsbDeleteResult result = new UsbDeleteResult(path, existsBefore, length, readable, writable,
                canonical, last.api, last.returned, existsAfter, last.exClass, last.exMsg);
        UsbDeleteLog.i("DELETE_RESULT", result.resultLog());
        return result;
    }

    private Attempt tryFileDelete(File file) {
        try {
            boolean ok = file.delete();
            boolean existsAfter = file.exists();
            UsbDeleteLog.i("DELETE_FILE_API",
                    "api=File.delete returned=" + ok
                    + " existsAfterDelete=" + existsAfter);
            return new Attempt("File.delete", ok, null, null);
        } catch (Throwable t) {
            UsbDeleteLog.e("DELETE_FILE_API",
                    "api=File.delete returned=false existsAfterDelete=" + file.exists(), t);
            return new Attempt("File.delete", false, t.getClass().getName(), t.getMessage());
        }
    }

    private Attempt tryMediaStore(File file) {
        String path = file.getAbsolutePath();
        Throwable last = null;
        boolean anyIndexed = false;
        for (Uri collection : mediaCollections()) {
            try {
                try (Cursor c = resolver.query(collection,
                        new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DATA + "=?",
                        new String[]{path}, null)) {
                    boolean indexed = c != null && c.moveToFirst();
                    UsbDeleteLog.i("DELETE_MEDIASTORE",
                            "query collection=" + collection
                            + " indexed=" + indexed
                            + " cursorNull=" + (c == null));
                    if (indexed) {
                        anyIndexed = true;
                        long id = c.getLong(0);
                        Uri item = ContentUris.withAppendedId(collection, id);
                        int n = resolver.delete(item, null, null);
                        boolean existsAfter = file.exists();
                        UsbDeleteLog.i("DELETE_MEDIASTORE",
                                "delete byId item=" + item
                                + " rows=" + n
                                + " existsAfterDelete=" + existsAfter);
                        if (n > 0 || !existsAfter) {
                            return new Attempt("MediaStore.id:" + collection, n > 0, null, null);
                        }
                    }
                }
                int n = resolver.delete(collection, MediaStore.MediaColumns.DATA + "=?", new String[]{path});
                boolean existsAfter = file.exists();
                UsbDeleteLog.i("DELETE_MEDIASTORE",
                        "delete byData collection=" + collection
                        + " rows=" + n
                        + " existsAfterDelete=" + existsAfter);
                if (n > 0 || !existsAfter) {
                    return new Attempt("MediaStore.data:" + collection, n > 0, null, null);
                }
            } catch (Throwable t) {
                last = t;
                UsbDeleteLog.e("DELETE_MEDIASTORE", "collection=" + collection, t);
            }
        }
        String msg = last == null ? (anyIndexed ? "indexed but delete left file" : "no matching row") : last.getMessage();
        UsbDeleteLog.i("DELETE_MEDIASTORE",
                "final indexed=" + anyIndexed
                + " existsAfterDelete=" + file.exists()
                + " message=" + msg);
        return new Attempt("MediaStore", false,
                last == null ? null : last.getClass().getName(),
                msg);
    }

    private List<Uri> mediaCollections() {
        LinkedHashSet<Uri> out = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Set<String> names = MediaStore.getExternalVolumeNames(context);
                for (String name : names) {
                    out.add(MediaStore.Files.getContentUri(name));
                    out.add(MediaStore.Video.Media.getContentUri(name));
                }
            } catch (Throwable ignored) {
            }
        }
        out.add(MediaStore.Files.getContentUri("external"));
        out.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        return new ArrayList<>(out);
    }

    private Attempt tryDocumentsContract(File file) {
        Throwable last = null;
        List<String> ids = candidateDocumentIds(file);
        UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT",
                "candidateIds=" + ids.size() + " ids=" + ids);
        for (String docId : ids) {
            try {
                Uri doc = DocumentsContract.buildDocumentUri(EXT_STORAGE, docId);
                UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT", "buildDocumentUri=" + doc + " docId=" + docId);
                boolean ok = DocumentsContract.deleteDocument(resolver, doc);
                boolean existsAfter = file.exists();
                UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT",
                        "deleteDocument uri=" + doc
                        + " returned=" + ok
                        + " existsAfterDelete=" + existsAfter);
                if (ok || !existsAfter) {
                    return new Attempt("DocumentsContract:" + docId, ok, null, null);
                }
            } catch (Throwable t) {
                last = t;
                UsbDeleteLog.e("DELETE_DOCUMENTS_CONTRACT", "buildDocumentUri docId=" + docId, t);
            }
            try {
                Uri tree = DocumentsContract.buildTreeDocumentUri(EXT_STORAGE, volumeRootId(docId));
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT",
                        "treeUri=" + tree + " docUsingTree=" + doc + " docId=" + docId);
                boolean ok = DocumentsContract.deleteDocument(resolver, doc);
                boolean existsAfter = file.exists();
                UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT",
                        "deleteDocument.tree uri=" + doc
                        + " returned=" + ok
                        + " existsAfterDelete=" + existsAfter);
                if (ok || !existsAfter) {
                    return new Attempt("DocumentsContract.tree:" + docId, ok, null, null);
                }
                DocumentFile single = DocumentFile.fromSingleUri(context, doc);
                UsbDeleteLog.i("DELETE_SAF",
                        "fromSingleUri=" + (single == null ? "null" : single.getUri())
                        + " exists=" + (single != null && single.exists()));
                if (single != null && single.exists()) {
                    boolean d = single.delete();
                    boolean existsAfterSingle = file.exists();
                    UsbDeleteLog.i("DELETE_SAF",
                            "fromSingleUri.delete returned=" + d
                            + " existsAfterDelete=" + existsAfterSingle);
                    if (d || !existsAfterSingle) {
                        return new Attempt("DocumentFile.fromSingleUri:" + docId, d, null, null);
                    }
                }
            } catch (Throwable t) {
                last = t;
                UsbDeleteLog.e("DELETE_DOCUMENTS_CONTRACT", "tree docId=" + docId, t);
            }
        }
        UsbDeleteLog.i("DELETE_DOCUMENTS_CONTRACT",
                "final existsAfterDelete=" + file.exists()
                + " message=" + (last == null ? "no document id worked" : last.getMessage()));
        return new Attempt("DocumentsContract", false,
                last == null ? null : last.getClass().getName(),
                last == null ? "no document id worked" : last.getMessage());
    }

    private Attempt tryDocumentFileTree(File file, Uri treeUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            UsbDeleteLog.i("DELETE_SAF",
                    "fromTreeUri=" + treeUri
                    + " root=" + (root == null ? "null" : root.getUri())
                    + " rootExists=" + (root != null && root.exists()));
            UsbDeleteLog.i(SafDiagnostics.EVENT_DOCUMENTFILE_RESOLVE,
                    "treeUri=" + treeUri
                    + " root=" + (root == null ? "null" : root.getUri())
                    + " rootExists=" + (root != null && root.exists())
                    + " rootCanWrite=" + (root != null && root.canWrite())
                    + " name=" + file.getName());
            if (root == null) {
                return new Attempt("DocumentFile.tree", false, null, "fromTreeUri=null");
            }
            DocumentFile target = findInTree(root, file.getName());
            UsbDeleteLog.i("DELETE_SAF",
                    "resolvedName=" + file.getName()
                    + " target=" + (target == null ? "none" : target.getUri())
                    + " targetExists=" + (target != null && target.exists()));
            if (target == null) {
                return new Attempt("DocumentFile.tree", false, null, "child not found: " + file.getName());
            }
            boolean ok = target.delete();
            boolean existsAfter = file.exists();
            UsbDeleteLog.i("DELETE_SAF",
                    "tree.delete returned=" + ok
                    + " existsAfterDelete=" + existsAfter);
            return new Attempt("DocumentFile.tree", ok, null, null);
        } catch (Throwable t) {
            UsbDeleteLog.e("DELETE_SAF", "fromTreeUri=" + treeUri, t);
            return new Attempt("DocumentFile.tree", false, t.getClass().getName(), t.getMessage());
        }
    }

    private static DocumentFile findInTree(DocumentFile root, String displayName) {
        DocumentFile direct = root.findFile(displayName);
        if (direct != null) {
            return direct;
        }
        DocumentFile camera = root.findFile("Camera");
        if (camera != null) {
            DocumentFile f = camera.findFile(displayName);
            if (f != null) {
                return f;
            }
        }
        DocumentFile dcim = root.findFile("DCIM");
        if (dcim != null) {
            DocumentFile cam = dcim.findFile("Camera");
            if (cam != null) {
                DocumentFile f = cam.findFile(displayName);
                if (f != null) {
                    return f;
                }
            }
            return dcim.findFile(displayName);
        }
        return null;
    }

    static List<String> candidateDocumentIds(File file) {
        List<String> ids = new ArrayList<>();
        String abs = file.getAbsolutePath().replace('\\', '/');
        String[] prefixes = {"/storage/", "/mnt/media_rw/", "/mnt/usb_storage/", "/mnt/usb/", "/mnt/usbotg/"};
        for (String prefix : prefixes) {
            if (!abs.toLowerCase(Locale.US).startsWith(prefix)) {
                continue;
            }
            String rest = abs.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String vol = rest.substring(0, slash);
            String rel = rest.substring(slash + 1);
            if ("emulated".equalsIgnoreCase(vol) || "self".equalsIgnoreCase(vol)) {
                continue;
            }
            ids.add(vol + ":" + rel);
            ids.add(vol + ":/" + rel);
        }
        return ids;
    }

    private static String volumeRootId(String docId) {
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return docId;
        }
        return docId.substring(0, colon + 1);
    }

    public static boolean hasPersistedTree(Context context, Uri treeUri) {
        if (treeUri == null || !"content".equalsIgnoreCase(treeUri.getScheme())) {
            return false;
        }
        for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri() != null && p.isWritePermission() && treeUri.toString().startsWith(p.getUri().toString())) {
                return true;
            }
            if (p.getUri() != null && p.getUri().equals(treeUri) && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private static final class Attempt {
        final String api;
        final boolean returned;
        final String exClass;
        final String exMsg;

        Attempt(String api, boolean returned, String exClass, String exMsg) {
            this.api = api;
            this.returned = returned;
            this.exClass = exClass;
            this.exMsg = exMsg;
        }
    }
}
