package com.loopcamera.loop20;

import android.net.Uri;

public final class DcimEntry {
    public final Uri uri;
    public final String documentId;
    public final String displayName;
    public final String mimeType;
    public final long lastModified;
    public final long size;
    public final boolean directory;

    public DcimEntry(Uri uri, String documentId, String displayName, String mimeType,
                     long lastModified, long size, boolean directory) {
        this.uri = uri;
        this.documentId = documentId;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.lastModified = lastModified;
        this.size = size;
        this.directory = directory;
    }
}
