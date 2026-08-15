package com.loopcamera.loop20;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Access to the user-selected DCIM folder via SAF tree URI or a discovered File path.
 * Never hard-codes a single USB mount UUID.
 */
public final class DcimStore {

    private static final String[] PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private final ContentResolver resolver;
    private final Context appContext;

    public DcimStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.resolver = this.appContext.getContentResolver();
    }

    public Folder open(Uri selected) {
        if (selected == null) {
            return null;
        }
        if (isFileUri(selected)) {
            String path = selected.getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            return openFile(new File(path));
        }
        return openSaf(selected);
    }

    public Folder openFile(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        if (dir.list() == null) {
            return null;
        }
        return new Folder(Uri.fromFile(dir), dir.getAbsolutePath(), dir.getAbsolutePath(), dir);
    }

    private Folder openSaf(Uri selectedTree) {
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(selectedTree);
        } catch (Exception e) {
            LoopLog.get().e("URI cây không hợp lệ", e);
            return null;
        }
        List<DcimEntry> top = listSafChildren(selectedTree, rootId);
        if (top == null) {
            return null;
        }
        String parentId = rootId;
        String label = queryName(selectedTree, rootId);
        for (DcimEntry e : top) {
            if (e.directory && "DCIM".equalsIgnoreCase(e.displayName)) {
                parentId = e.documentId;
                label = e.displayName;
                break;
            }
        }
        Folder folder = new Folder(selectedTree, parentId, label == null ? "DCIM" : label, null);
        if (listChildren(folder) == null) {
            return null;
        }
        return folder;
    }

    public boolean isAccessible(Folder folder) {
        return folder != null && listChildren(folder) != null;
    }

    public List<DcimEntry> listChildren(Folder folder) {
        if (folder == null) {
            return null;
        }
        if (folder.directory != null) {
            return listFileChildren(folder.directory);
        }
        return listSafChildren(folder.treeUri, folder.parentDocumentId);
    }

    private List<DcimEntry> listFileChildren(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<DcimEntry> out = new ArrayList<>();
        for (File f : files) {
            boolean isDir = f.isDirectory();
            String mime = isDir
                    ? DocumentsContract.Document.MIME_TYPE_DIR
                    : mimeFromName(f.getName());
            out.add(new DcimEntry(
                    Uri.fromFile(f),
                    f.getAbsolutePath(),
                    f.getName(),
                    mime,
                    f.lastModified(),
                    f.length(),
                    isDir));
        }
        return out;
    }

    private List<DcimEntry> listSafChildren(Uri treeUri, String parentDocumentId) {
        if (treeUri == null || parentDocumentId == null) {
            return null;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        List<DcimEntry> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, PROJECTION, null, null, null);
            if (cursor == null) {
                return null;
            }
            int iId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int iMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int iMod = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int iSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String id = iId >= 0 ? cursor.getString(iId) : null;
                String name = iName >= 0 ? cursor.getString(iName) : null;
                String mime = iMime >= 0 ? cursor.getString(iMime) : null;
                long mod = iMod >= 0 && !cursor.isNull(iMod) ? cursor.getLong(iMod) : 0L;
                long size = iSize >= 0 && !cursor.isNull(iSize) ? cursor.getLong(iSize) : 0L;
                if (id == null || name == null) {
                    continue;
                }
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                out.add(new DcimEntry(docUri, id, name, mime, mod, size, dir));
            }
            return out;
        } catch (SecurityException se) {
            LoopLog.get().e("Mất quyền SAF tới thư mục đã chọn", se);
            return null;
        } catch (Exception e) {
            LoopLog.get().e("Không đọc được danh sách file DCIM", e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public DcimEntry findByName(Folder folder, String displayName) {
        List<DcimEntry> list = listChildren(folder);
        if (list == null) {
            return null;
        }
        for (DcimEntry e : list) {
            if (e.displayName.equalsIgnoreCase(displayName)) {
                return e;
            }
        }
        return null;
    }

    public boolean delete(DcimEntry entry) {
        if (entry == null) {
            return false;
        }
        File file = null;
        if (isFileUri(entry.uri) && entry.documentId != null) {
            file = new File(entry.documentId);
        } else if (entry.documentId != null && entry.documentId.startsWith("/")) {
            file = new File(entry.documentId);
        }
        if (file != null) {
            UsbDeleter deleter = new UsbDeleter(appContext);
            Uri tree = new AppPreferences(appContext).getSafTreeUri();
            UsbDeleteResult result = deleter.deleteVideo(file, tree);
            return result.success();
        }
        return deleteSafEntry(entry);
    }

    /**
     * SAF-only path (no POSIX File). Success requires the document to be gone.
     */
    private boolean deleteSafEntry(DcimEntry entry) {
        LoopLog.get().i("DELETE_ATTEMPT path=" + entry.displayName
                + " uri=" + entry.uri
                + " exists=true length=" + entry.size
                + " readable=n/a writable=n/a canonicalPath=" + entry.documentId);
        String api = "none";
        boolean returned = false;
        String exClass = null;
        String exMsg = null;
        try {
            DocumentFile single = DocumentFile.fromSingleUri(appContext, entry.uri);
            if (single != null && single.exists()) {
                api = "DocumentFile.fromSingleUri";
                returned = single.delete();
                boolean existsAfter = single.exists();
                LoopLog.get().i("DELETE_RESULT api=" + api
                        + " returned=" + returned
                        + " existsAfterDelete=" + existsAfter
                        + " success=" + !existsAfter);
                if (!existsAfter) {
                    return true;
                }
            }
            api = "DocumentsContract.deleteDocument";
            returned = DocumentsContract.deleteDocument(resolver, entry.uri);
            DocumentFile after = DocumentFile.fromSingleUri(appContext, entry.uri);
            boolean existsAfter = after != null && after.exists();
            LoopLog.get().i("DELETE_RESULT api=" + api
                    + " returned=" + returned
                    + " existsAfterDelete=" + existsAfter
                    + " success=" + !existsAfter);
            return DeletePolicy.confirmedDeleted(existsAfter);
        } catch (Exception e) {
            exClass = e.getClass().getName();
            exMsg = e.getMessage();
            LoopLog.get().i("DELETE_RESULT api=" + api
                    + " returned=" + returned
                    + " existsAfterDelete=true"
                    + " success=false exception=" + exClass + ": " + exMsg);
            LoopLog.get().e("Lỗi xóa SAF " + entry.displayName, e);
            return false;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.2f MB", kb / 1024.0);
    }

    public static boolean isFileUri(Uri uri) {
        return uri != null && "file".equalsIgnoreCase(uri.getScheme());
    }

    private String queryName(Uri treeUri, String documentId) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor c = resolver.query(docUri, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String mimeFromName(String name) {
        String ext = LoopPlanner.extensionOf(name);
        if (ext.isEmpty()) {
            return "application/octet-stream";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    public static final class Folder {
        public final Uri treeUri;
        public final String parentDocumentId;
        public final String label;
        public final File directory;

        public Folder(Uri treeUri, String parentDocumentId, String label, File directory) {
            this.treeUri = treeUri;
            this.parentDocumentId = parentDocumentId;
            this.label = label;
            this.directory = directory;
        }

        public boolean isFileMode() {
            return directory != null;
        }
    }
}
