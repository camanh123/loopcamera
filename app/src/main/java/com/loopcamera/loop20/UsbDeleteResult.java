package com.loopcamera.loop20;

/**
 * Diagnostic record for one USB delete attempt. Pure data — unit-testable.
 */
public final class UsbDeleteResult {

    public final String path;
    public final boolean existsBefore;
    public final long length;
    public final boolean readable;
    public final boolean writable;
    public final String canonicalPath;
    public final String apiUsed;
    public final boolean apiReturnedTrue;
    public final boolean existsAfter;
    public final String exceptionClass;
    public final String exceptionMessage;

    public UsbDeleteResult(String path, boolean existsBefore, long length, boolean readable,
                           boolean writable, String canonicalPath, String apiUsed,
                           boolean apiReturnedTrue, boolean existsAfter,
                           String exceptionClass, String exceptionMessage) {
        this.path = path;
        this.existsBefore = existsBefore;
        this.length = length;
        this.readable = readable;
        this.writable = writable;
        this.canonicalPath = canonicalPath;
        this.apiUsed = apiUsed;
        this.apiReturnedTrue = apiReturnedTrue;
        this.existsAfter = existsAfter;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
    }

    public boolean success() {
        return DeletePolicy.confirmedDeleted(existsAfter);
    }

    public String attemptLog() {
        return "DELETE_ATTEMPT path=" + path
                + " exists=" + existsBefore
                + " length=" + length
                + " readable=" + readable
                + " writable=" + writable
                + " canonicalPath=" + canonicalPath;
    }

    public String resultLog() {
        return "DELETE_RESULT api=" + apiUsed
                + " returned=" + apiReturnedTrue
                + " existsAfterDelete=" + existsAfter
                + " success=" + success()
                + (exceptionClass == null ? "" : " exception=" + exceptionClass + ": " + exceptionMessage);
    }
}
