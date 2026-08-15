package com.loopcamera.loop20;

/**
 * Pure policy for loop delete decisions. No Android APIs — unit-testable.
 */
public final class DeletePolicy {

    public static final int FAILSAFE_AFTER = 3;

    private DeletePolicy() {
    }

    public static boolean allowRealDelete(String mode, boolean failsafe, int completeCount) {
        return "LOOP".equals(mode)
                && !failsafe
                && completeCount > LoopPlanner.MAX_VIDEOS;
    }

    public static boolean enterFailsafe(int consecutiveFailures) {
        return consecutiveFailures >= FAILSAFE_AFTER;
    }

    /**
     * Success only if the file is gone. A true return from File.delete()
     * while the file still exists is a false positive.
     */
    public static boolean confirmedDeleted(boolean existsAfterDelete) {
        return !existsAfterDelete;
    }

    public static boolean readyToDelete(boolean retrieverReleased, boolean complete, boolean sizeUnchanged) {
        return retrieverReleased && complete && sizeUnchanged;
    }

    public static String oldestName(String[] timestampNames) {
        if (timestampNames == null || timestampNames.length == 0) {
            return null;
        }
        String oldest = null;
        long oldestKey = Long.MAX_VALUE;
        for (String name : timestampNames) {
            if (!LoopPlanner.isManagedVideo(name)) {
                continue;
            }
            long key = LoopPlanner.timestampSortKey(name);
            if (key >= 0 && key < oldestKey) {
                oldestKey = key;
                oldest = name;
            }
        }
        return oldest;
    }
}
