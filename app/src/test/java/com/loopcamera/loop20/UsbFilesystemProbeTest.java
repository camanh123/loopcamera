package com.loopcamera.loop20;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UsbFilesystemProbeTest {

    @Test
    public void probePathIsExactHiddenTmpNotMp4() {
        assertEquals("/storage/USB1/DCIM/Camera/.cameraloop_probe.tmp", UsbFilesystemProbe.PROBE_PATH);
        assertTrue(UsbFilesystemProbe.isProbePath(UsbFilesystemProbe.PROBE_PATH));
        assertFalse(UsbFilesystemProbe.isProbePath("/storage/USB1/DCIM/Camera/20260814_11h04m17s.mp4"));
        assertFalse(UsbFilesystemProbe.isCameraMp4(UsbFilesystemProbe.PROBE_NAME));
        assertTrue(UsbFilesystemProbe.isCameraMp4("20260814_11h04m17s.mp4"));
    }

    @Test
    public void existingProbeFileMustNotBeTouched() {
        assertTrue(UsbFilesystemProbe.skipIfProbeAlreadyExists(true));
        assertFalse(UsbFilesystemProbe.skipIfProbeAlreadyExists(false));
    }

    @Test
    public void deleteSuccessOnlyWhenGone() {
        assertFalse(UsbFilesystemProbe.probeDeleteSuccess(true));
        assertTrue(UsbFilesystemProbe.probeDeleteSuccess(false));
    }

    @Test
    public void shellWhitelistRejectsRmAndMutation() {
        assertTrue(UsbFilesystemProbe.isAllowedShell(new String[]{"id"}));
        assertTrue(UsbFilesystemProbe.isAllowedShell(new String[]{"ls", "-ld", "/storage/USB1"}));
        assertTrue(UsbFilesystemProbe.isAllowedShell(new String[]{"ls", "-ld", "/storage/USB1/DCIM"}));
        assertTrue(UsbFilesystemProbe.isAllowedShell(new String[]{"ls", "-ld", "/storage/USB1/DCIM/Camera"}));
        assertTrue(UsbFilesystemProbe.isAllowedShell(new String[]{"stat", "/storage/USB1/DCIM/Camera"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"rm", UsbFilesystemProbe.PROBE_PATH}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"rm", "-f", "a.mp4"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"mv", "a", "b"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"chmod", "777", "/storage/USB1"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"chown", "root", "/storage/USB1"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"mount"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"umount", "/storage/USB1"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"sh", "-c", "rm a.mp4"}));
        assertFalse(UsbFilesystemProbe.isAllowedShell(new String[]{"ls", "-ld", "/storage/USB1/DCIM/Camera/foo.mp4"}));
    }

    @Test
    public void mountLineParserExtractsFsAndOptions() {
        String parsed = UsbFilesystemProbe.parseMountLine(
                "/dev/block/vold/public:8,1 /storage/USB1 vfat rw,dirsync,nosuid,nodev,noexec,uid=1023,gid=1023,fmask=0002 0 0");
        assertTrue(parsed.contains("fsType=vfat"));
        assertTrue(parsed.contains("rw=true"));
        assertTrue(parsed.contains("uid=1023"));
        assertTrue(parsed.contains("gid=1023"));
        assertTrue(UsbFilesystemProbe.lineContainsUsb1("/dev/fuse /storage/USB1 fuse rw,uid=0,gid=0"));
        assertFalse(UsbFilesystemProbe.lineContainsUsb1("/data /data ext4 rw"));
    }

    @Test
    public void probeFileIsNotManagedCameraVideo() {
        assertFalse(LoopPlanner.isManagedVideo(UsbFilesystemProbe.PROBE_NAME));
        assertEquals(30, LoopPlanner.MAX_VIDEOS);
    }
}
