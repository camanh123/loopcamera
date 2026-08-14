package com.loopcamera.loop20;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Storage Access Framework access to the user-granted tree only.
 * Never uses hardcoded USB mount paths.
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

    public DcimStore(Context context) {
        this.resolver = context.getContentResolver();
    }

    public Folder open(Uri selectedTree) {
        if (selectedTree == null) {
            return null;
        }
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(selectedTree);
        } catch (Exception e) {
            LoopLog.get().e("URI cây không hợp lệ", e);
            return null;
        }
        List<DcimEntry> top = listChildren(selectedTree, rootId);
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
        if (queryName(selectedTree, parentId) != null && "DCIM".equalsIgnoreCase(label)) {
            // already DCIM or found DCIM child
        } else if (top.isEmpty() && label != null) {
            // empty folder is still accessible
        }
        Folder folder = new Folder(selectedTree, parentId, label == null ? "DCIM" : label);
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
        return listChildren(folder.treeUri, folder.parentDocumentId);
    }

    public List<DcimEntry> listChildren(Uri treeUri, String parentDocumentId) {
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
        try {
            return DocumentsContract.deleteDocument(resolver, entry.uri);
        } catch (Exception e) {
            LoopLog.get().e("Lỗi xóa " + entry.displayName, e);
            return false;
        }
    }

    public boolean rename(DcimEntry entry, String newName) {
        try {
            DocumentsContract.renameDocument(resolver, entry.uri, newName);
            return true;
        } catch (Exception e) {
            LoopLog.get().e("Lỗi đổi tên " + entry.displayName + " → " + newName, e);
            return false;
        }
    }

    public boolean writeTxn(Folder folder, String content) {
        try {
            DcimEntry existing = findByName(folder, LoopPlanner.TXN_NAME);
            Uri target = existing != null ? existing.uri : null;
            if (target == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, folder.parentDocumentId);
                target = DocumentsContract.createDocument(resolver, parent, "text/plain", LoopPlanner.TXN_NAME);
            }
            if (target == null) {
                return false;
            }
            try (OutputStream os = resolver.openOutputStream(target, "wt")) {
                if (os == null) {
                    return false;
                }
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            }
        } catch (Exception e) {
            LoopLog.get().e("Không ghi được file giao dịch fail-safe", e);
            return false;
        }
    }

    public boolean deleteTxn(Folder folder) {
        DcimEntry txn = findByName(folder, LoopPlanner.TXN_NAME);
        if (txn == null) {
            return true;
        }
        return delete(txn);
    }

    public boolean hasTxn(Folder folder) {
        return findByName(folder, LoopPlanner.TXN_NAME) != null;
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

    public static final class Folder {
        public final Uri treeUri;
        public final String parentDocumentId;
        public final String label;

        public Folder(Uri treeUri, String parentDocumentId, String label) {
            this.treeUri = treeUri;
            this.parentDocumentId = parentDocumentId;
            this.label = label;
        }
    }
}
