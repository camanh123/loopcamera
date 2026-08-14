package com.loopcamera.loop20;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoopUiState {
    public final boolean connected;
    public final String folderLabel;
    public final int videoCount;
    public final String mode;
    public final boolean failsafe;
    public final String failsafeReason;
    public final String newest;
    public final String oldest;
    public final List<String> planned;
    public final String statusDetail;
    public final boolean processing;

    public LoopUiState(boolean connected, String folderLabel, int videoCount, String mode,
                       boolean failsafe, String failsafeReason, String newest, String oldest,
                       List<String> planned, String statusDetail, boolean processing) {
        this.connected = connected;
        this.folderLabel = folderLabel;
        this.videoCount = videoCount;
        this.mode = mode;
        this.failsafe = failsafe;
        this.failsafeReason = failsafeReason;
        this.newest = newest;
        this.oldest = oldest;
        this.planned = planned == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(planned));
        this.statusDetail = statusDetail;
        this.processing = processing;
    }

    public static LoopUiState disconnected(String mode, boolean failsafe, String reason) {
        return new LoopUiState(false, "(chưa chọn)", 0, mode, failsafe, reason,
                "—", "—", Collections.emptyList(), "Not connected", false);
    }
}
