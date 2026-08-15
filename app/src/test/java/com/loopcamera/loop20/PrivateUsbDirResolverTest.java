package com.loopcamera.loop20;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PrivateUsbDirResolverTest {

    private static PrivateUsbDirResolver.DirInfo dir(String path, boolean canWrite) {
        return new PrivateUsbDirResolver.DirInfo(path, true, true, true, canWrite);
    }

    @Test
    public void noExternalUsbDirectoryFound() {
        assertNull(PrivateUsbDirResolver.selectWritableUsb(null));
        assertNull(PrivateUsbDirResolver.selectWritableUsb(new PrivateUsbDirResolver.DirInfo[0]));
        PrivateUsbDirResolver.DirInfo[] onlyNulls = {null, null};
        assertNull(PrivateUsbDirResolver.selectWritableUsb(onlyNulls));
    }

    @Test
    public void internalStorageDirectoryOnlyIsRejected() {
        PrivateUsbDirResolver.DirInfo[] onlyInternal = {
                dir("/storage/emulated/0/Android/data/com.loopcamera.loop20/files", true)
        };
        assertNull(PrivateUsbDirResolver.selectWritableUsb(onlyInternal));
    }

    @Test
    public void usbExternalFilesDirectoryDiscoveredWithoutAssumingIndexOne() {
        PrivateUsbDirResolver.DirInfo emulated = dir(
                "/storage/emulated/0/Android/data/com.loopcamera.loop20/files", true);
        PrivateUsbDirResolver.DirInfo usb = dir(
                "/storage/USB1/Android/data/com.loopcamera.loop20/files", true);
        PrivateUsbDirResolver.DirInfo[] mixed = {null, emulated, null, usb};
        PrivateUsbDirResolver.DirInfo chosen = PrivateUsbDirResolver.selectWritableUsb(mixed);
        assertNotNull(chosen);
        assertEquals(usb.path, chosen.path);
        assertFalse(chosen.path.equals(mixed[1].path));
        assertEquals("removable-usb", chosen.volumeKind);
    }

    @Test
    public void usbAtIndexZeroSelectedNotIndexOne() {
        PrivateUsbDirResolver.DirInfo[] dirs = {
                dir("/storage/USB1/Android/data/com.loopcamera.loop20/files", true),
                dir("/storage/emulated/0/Android/data/com.loopcamera.loop20/files", true)
        };
        assertEquals(dirs[0].path, PrivateUsbDirResolver.selectWritableUsb(dirs).path);
    }

    @Test
    public void storageVolumeRemovableHintSelectsUuidPath() {
        PrivateUsbDirResolver.DirInfo[] dirs = {
                dir("/storage/emulated/0/Android/data/com.loopcamera.loop20/files", true),
                dir("/storage/ABCD-1234/Android/data/com.loopcamera.loop20/files", true)
        };
        PrivateUsbDirResolver.VolumeHint[] vols = {
                new PrivateUsbDirResolver.VolumeHint("/storage/emulated/0", false, true, "Internal", "mounted"),
                new PrivateUsbDirResolver.VolumeHint("/storage/ABCD-1234", true, false, "USB", "mounted")
        };
        PrivateUsbDirResolver.DirInfo chosen = PrivateUsbDirResolver.selectWritableUsb(dirs, vols);
        assertEquals("/storage/ABCD-1234/Android/data/com.loopcamera.loop20/files", chosen.path);
        assertTrue(chosen.removableUsb);
    }

    @Test
    public void rejectsNonWritableUsbCandidate() {
        PrivateUsbDirResolver.DirInfo[] dirs = {
                dir("/storage/USB1/Android/data/com.loopcamera.loop20/files", false)
        };
        assertNull(PrivateUsbDirResolver.selectWritableUsb(dirs));
    }

    @Test
    public void cameraSubdirectoryResolution() {
        String cam = PrivateUsbDirResolver.cameraSubdir(
                "/storage/USB1/Android/data/com.loopcamera.loop20/files");
        assertEquals("/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera", cam);
        assertEquals(cam + "/.cameraloop_probe.tmp", PrivateUsbDirResolver.probeFile(cam));
        File root = new File(System.getProperty("java.io.tmpdir"), "cl_priv_" + System.nanoTime());
        File camera = new File(PrivateUsbDirResolver.cameraSubdir(root.getAbsolutePath()));
        assertTrue(PrivateUsbDirResolver.ensureDirectory(camera));
        assertTrue(camera.isDirectory());
        camera.delete();
        root.delete();
    }

    @Test
    public void probeSuccessRequiresExistsAfterDeleteFalse() {
        assertFalse(PrivateUsbDirResolver.probeFilesystemSuccess(true, true));
        assertFalse(PrivateUsbDirResolver.probeFilesystemSuccess(false, false));
        assertTrue(PrivateUsbDirResolver.probeFilesystemSuccess(true, false));
    }

    @Test
    public void deleteReturnedTrueWhileFileRemainsIsFail() {
        assertTrue(PrivateUsbDirResolver.falsePositiveDelete(true, true));
        assertFalse(PrivateUsbDirResolver.probeFilesystemSuccess(true, true));
    }

    @Test
    public void existingProbeFileIsSafeAbort() {
        assertTrue(PrivateUsbDirResolver.abortIfProbeAlreadyExists(true));
        assertFalse(PrivateUsbDirResolver.abortIfProbeAlreadyExists(false));
    }

    @Test
    public void productionMp4PathsAreNeverAllowedProbeDeleteTargets() {
        assertFalse(PrivateUsbDirResolver.isAllowedProbeDeletePath(
                "/storage/USB1/DCIM/Camera/20260814_11h04m17s.mp4"));
        assertFalse(PrivateUsbDirResolver.isAllowedProbeDeletePath(
                "/storage/USB1/DCIM/Camera/01.mp4"));
        assertFalse(PrivateUsbDirResolver.isAllowedProbeDeletePath(
                "/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera/20260814_11h04m17s.mp4"));
        assertTrue(PrivateUsbDirResolver.isAllowedProbeDeletePath(
                "/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera/.cameraloop_probe.tmp"));
        assertFalse(LoopPlanner.isManagedVideo(".cameraloop_probe.tmp"));
    }

    @Test
    public void unusualDeleteReturnedFalseButGoneIsReported() {
        assertTrue(PrivateUsbDirResolver.unusualDeleteReturnedFalseButGone(false, false));
        assertTrue(PrivateUsbDirResolver.probeFilesystemSuccess(true, false));
    }

    @Test
    public void mountParserReadsCarfuStyleLine() {
        List<String> lines = Arrays.asList(
                "/dev/block/vold/public:8,1 /mnt/media_rw/USB1 vfat rw,dirsync,uid=1023,gid=1023,fmask=0007,dmask=0007 0 0",
                "/mnt/media_rw/USB1 /storage/USB1 fuse rw,uid=0,gid=0 0 0"
        );
        PrivateUsbMountInfo info = PrivateUsbMountInfo.parseBest(
                "/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera", lines);
        assertEquals("/storage/USB1", info.mountPoint);
        assertEquals("fuse", info.fsType);
        assertEquals("rw", info.mountMode);
        assertEquals("0", info.mountUid);
        assertEquals("0", info.mountGid);
        PrivateUsbMountInfo media = PrivateUsbMountInfo.parseBest("/mnt/media_rw/USB1/x", lines);
        assertEquals("vfat", media.fsType);
        assertEquals("1023", media.mountUid);
        assertEquals("1023", media.mountGid);
        assertEquals("rw", media.mountMode);
    }

    @Test
    public void productionLoopUnchanged() {
        assertEquals(30, LoopPlanner.MAX_VIDEOS);
        assertFalse(DeletePolicy.allowRealDelete("TEST", false, 34));
        assertTrue(DeletePolicy.enterFailsafe(3));
        assertFalse(DeletePolicy.enterFailsafe(2));
        assertTrue(FolderResolver.looksLikeCameraFolder(new File("/storage/USB1/DCIM/Camera")));
        assertFalse(FolderResolver.looksLikeCameraFolder(
                new File("/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera")));
    }

    @Test
    public void screenReportUsesRequiredLabels() {
        PrivateUsbProbe.Report r = new PrivateUsbProbe.Report();
        r.resolverPass = true;
        r.selectedPrivateDir = "/storage/USB1/Android/data/com.loopcamera.loop20/files";
        r.cameraDir = "/storage/USB1/Android/data/com.loopcamera.loop20/files/Camera";
        r.dirExists = true;
        r.dirReadable = true;
        r.dirWritable = true;
        r.createPass = true;
        r.writePass = true;
        r.existsBefore = true;
        r.deleteAttempted = true;
        r.deleteReturned = true;
        r.existsAfterDelete = false;
        r.success = true;
        r.reason = "existsBefore=true AND existsAfterDelete=false";
        String t = r.screenText();
        assertTrue(t.contains("USB PRIVATE STORAGE PROBE"));
        assertTrue(t.contains("Resolver:\nPASS"));
        assertTrue(t.contains("Selected USB private directory:"));
        assertTrue(t.contains("Camera directory:"));
        assertTrue(t.contains("Directory exists:\nYES"));
        assertTrue(t.contains("Probe create:\nPASS"));
        assertTrue(t.contains("Probe write:\nPASS"));
        assertTrue(t.contains("File.delete() returned:\ntrue"));
        assertTrue(t.contains("Probe exists after delete:\nNO"));
        assertTrue(t.contains("FINAL PROBE RESULT:\nPASS"));
        r.success = false;
        r.existsAfterDelete = true;
        r.reason = "FAIL delete() returned true but existsAfterDelete=true";
        assertTrue(r.screenText().contains("FINAL PROBE RESULT:\nFAIL"));
        r.deleteAttempted = false;
        r.reason = "PROBE_FILE_ALREADY_EXISTS";
        assertTrue(r.screenText().contains("File.delete() returned:\nNOT RUN"));
        assertTrue(r.screenText().contains("PROBE_FILE_ALREADY_EXISTS"));
    }

    @Test
    public void localTempCreateWriteDeleteIsNotHardwareProof() throws Exception {
        File camera = new File(System.getProperty("java.io.tmpdir"),
                "cl_private_probe_" + System.nanoTime());
        assertTrue(PrivateUsbDirResolver.ensureDirectory(camera));
        File probe = new File(PrivateUsbDirResolver.probeFile(camera.getAbsolutePath()));
        assertFalse(PrivateUsbDirResolver.abortIfProbeAlreadyExists(probe.exists()));
        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write("CameraLoop30 private USB probe. Not a video.\n".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(probe.exists());
        assertTrue(probe.length() > 0);
        boolean existsBefore = probe.exists();
        boolean deleteReturned = probe.delete();
        boolean existsAfter = probe.exists();
        assertTrue(PrivateUsbDirResolver.probeFilesystemSuccess(existsBefore, existsAfter));
        assertTrue(deleteReturned);
        camera.delete();
    }
}
