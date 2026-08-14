package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoopUiState {
    public static final String PHASE_IDLE = "Not connected";
    public static final String PHASE_WAITING_USB = "Waiting for USB";
    public static final String PHASE_SCANNING = "Scanning";
    public static final String PHASE_MONITORING = "Monitoring";
    public static final String PHASE_CONNECTED = "Connected";

    public final boolean connected;
    public final String folderLabel;
    public final int videoCount;
    public final String mode;
    public final boolean failsafe;
    public final String failsafeReason;
    public final String newest;
    public final String oldest;
    public final String newestState;
    public final String oldestState;
    public final List<String> planned;
    public final String statusDetail;
    public final String phase;
    public final boolean processing;

    public LoopUiState(boolean connected, String folderLabel, int videoCount, String mode,
                       boolean failsafe, String failsafeReason, String newest, String oldest,
                       String newestState, String oldestState, List<String> planned,
                       String statusDetail, String phase, boolean processing) {
        this.connected = connected;
        this.folderLabel = folderLabel;
        this.videoCount = videoCount;
        this.mode = mode;
        this.failsafe = failsafe;
        this.failsafeReason = failsafeReason;
        this.newest = newest;
        this.oldest = oldest;
        this.newestState = newestState;
        this.oldestState = oldestState;
        this.planned = planned == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(planned));
        this.statusDetail = statusDetail;
        this.phase = phase;
        this.processing = processing;
    }

    public static LoopUiState idle(String mode, boolean failsafe, String reason, String phase, String detail) {
        return new LoopUiState(false, "(chưa chọn)", 0, mode, failsafe, reason,
                "—", "—", "—", "—", Collections.emptyList(), detail, phase, false);
    }
}
