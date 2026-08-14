package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java planner for the 20-clip loop.
 * 01 = newest, 20 = oldest.
 */
public final class LoopPlanner {

    public static final int MAX_VIDEOS = 20;
    public static final String TXN_NAME = "cameraloop20.txn";
    public static final String DEFAULT_EXTENSIONS = "mp4";

    private static final Pattern SLOT_NAME =
            Pattern.compile("^(0[1-9]|1[0-9]|20)\\.mp4$", Pattern.CASE_INSENSITIVE);

    private LoopPlanner() {
    }

    public static String slotName(int n) {
        return String.format(Locale.US, "%02d.mp4", n);
    }

    public static Integer slotNumber(String displayName) {
        if (displayName == null) {
            return null;
        }
        Matcher m = SLOT_NAME.matcher(displayName.trim());
        if (!m.matches()) {
            return null;
        }
        return Integer.parseInt(m.group(1));
    }

    public static boolean isSlotName(String displayName) {
        return slotNumber(displayName) != null;
    }

    public static boolean isTxnName(String displayName) {
        return TXN_NAME.equalsIgnoreCase(displayName);
    }

    public static Set<String> parseExtensions(String configured) {
        Set<String> out = new HashSet<>();
        if (configured == null || configured.trim().isEmpty()) {
            configured = DEFAULT_EXTENSIONS;
        }
        String[] parts = configured.split("[,;\\s]+");
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String ext = p.trim().toLowerCase(Locale.US);
            if (ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!ext.isEmpty()) {
                out.add(ext);
            }
        }
        if (out.isEmpty()) {
            out.add("mp4");
        }
        return out;
    }

    public static String extensionOf(String displayName) {
        if (displayName == null) {
            return "";
        }
        int dot = displayName.lastIndexOf('.');
        if (dot < 0 || dot == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isVideoFile(String displayName, Set<String> extensions) {
        if (displayName == null || displayName.startsWith(".")) {
            return false;
        }
        if (isTxnName(displayName)) {
            return false;
        }
        String ext = extensionOf(displayName);
        return extensions.contains(ext);
    }

    /**
     * Build the exact operation list for one newly completed camera clip.
     * Order: delete 20, then 19-&gt;20 ... 01-&gt;02, then new-&gt;01.
     */
    public static List<Action> planForNewVideo(Set<Integer> occupiedSlots, String newVideoName) {
        List<Action> actions = new ArrayList<>();
        if (occupiedSlots.contains(MAX_VIDEOS)) {
            actions.add(Action.delete(slotName(MAX_VIDEOS)));
        }
        for (int i = MAX_VIDEOS - 1; i >= 1; i--) {
            if (occupiedSlots.contains(i)) {
                actions.add(Action.rename(slotName(i), slotName(i + 1)));
            }
        }
        actions.add(Action.rename(newVideoName, slotName(1)));
        return Collections.unmodifiableList(actions);
    }

    public static String describe(Action action) {
        if (action.type == Action.Type.DELETE) {
            return "XÓA " + action.fromName;
        }
        return "ĐỔI TÊN " + action.fromName + " → " + action.toName;
    }

    public static final class Action {
        public enum Type { DELETE, RENAME }

        public final Type type;
        public final String fromName;
        public final String toName;

        private Action(Type type, String fromName, String toName) {
            this.type = type;
            this.fromName = fromName;
            this.toName = toName;
        }

        public static Action delete(String name) {
            return new Action(Type.DELETE, name, null);
        }

        public static Action rename(String from, String to) {
            return new Action(Type.RENAME, from, to);
        }

        @Override
        public String toString() {
            return describe(this);
        }
    }
}
