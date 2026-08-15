package com.loopcamera.loop20;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeletePolicyTest {

    @Test
    public void selectsOldestCompleteByFilenameTimestamp() {
        String[] names = {
                "20260814_11h04m17s.mp4",
                "20260814_12h00m00s.mp4",
                "20260814_10h36m17s.mp4",
                "readme.txt",
                "01.mp4"
        };
        assertEquals("20260814_10h36m17s.mp4", DeletePolicy.oldestName(names));
    }

    @Test
    public void ignoresNonTimestampNames() {
        assertNull(DeletePolicy.oldestName(new String[]{"01.mp4", "30.mp4", "video.mp4"}));
    }

    @Test
    public void testModeNeverAllowsRealDelete() {
        assertFalse(DeletePolicy.allowRealDelete("TEST", false, 34));
        assertFalse(DeletePolicy.allowRealDelete("TEST", false, 31));
    }

    @Test
    public void loopModeDeletesOnlyWhenCompleteExceedsMax() {
        assertFalse(DeletePolicy.allowRealDelete("LOOP", false, 30));
        assertFalse(DeletePolicy.allowRealDelete("LOOP", false, 0));
        assertTrue(DeletePolicy.allowRealDelete("LOOP", false, 31));
        assertTrue(DeletePolicy.allowRealDelete("LOOP", false, 34));
    }

    @Test
    public void failsafeBlocksDelete() {
        assertFalse(DeletePolicy.allowRealDelete("LOOP", true, 34));
    }

    @Test
    public void failsafeAfterThreeConsecutiveFailures() {
        assertFalse(DeletePolicy.enterFailsafe(0));
        assertFalse(DeletePolicy.enterFailsafe(2));
        assertTrue(DeletePolicy.enterFailsafe(3));
        assertTrue(DeletePolicy.enterFailsafe(4));
    }

    @Test
    public void failsafeResetClearsFlagStreakAndReturnsToTest() {
        assertFalse(DeletePolicy.failsafeResetComplete(true, 3, "TEST"));
        assertFalse(DeletePolicy.failsafeResetComplete(false, 3, "TEST"));
        assertFalse(DeletePolicy.failsafeResetComplete(false, 0, "LOOP"));
        assertTrue(DeletePolicy.failsafeResetComplete(false, 0, "TEST"));
    }

    @Test
    public void usbDeleteLogcatTagIsCameraLoopUSB() {
        assertEquals("CameraLoopUSB", UsbDeleteLog.TAG);
    }

    @Test
    public void postDeleteMustSeeFileGone() {
        assertFalse(DeletePolicy.confirmedDeleted(true));
        assertTrue(DeletePolicy.confirmedDeleted(false));
    }

    @Test
    public void requiresRetrieverReleasedBeforeDelete() {
        assertFalse(DeletePolicy.readyToDelete(false, true, true));
        assertFalse(DeletePolicy.readyToDelete(true, false, true));
        assertFalse(DeletePolicy.readyToDelete(true, true, false));
        assertTrue(DeletePolicy.readyToDelete(true, true, true));
    }

    @Test
    public void usbPathMapsToExternalStorageDocumentId() {
        File f = new File("/storage/USB1/DCIM/Camera/20260814_11h04m17s.mp4");
        List<String> ids = UsbDeleter.candidateDocumentIds(f);
        assertTrue(ids.contains("USB1:DCIM/Camera/20260814_11h04m17s.mp4"));
    }

    @Test
    public void mediaRwUuidMapsToDocumentId() {
        File f = new File("/mnt/media_rw/ABCD-1234/DCIM/Camera/20260814_11h04m17s.mp4");
        List<String> ids = UsbDeleter.candidateDocumentIds(f);
        assertTrue(ids.contains("ABCD-1234:DCIM/Camera/20260814_11h04m17s.mp4"));
    }

    @Test
    public void emulatedStorageIsNotTreatedAsUsbDocument() {
        File f = new File("/storage/emulated/0/DCIM/Camera/20260814_11h04m17s.mp4");
        assertTrue(UsbDeleter.candidateDocumentIds(f).isEmpty());
    }

    @Test
    public void deleteResultFalsePositiveWhenExistsAfter() {
        UsbDeleteResult r = new UsbDeleteResult(
                "/storage/USB1/DCIM/Camera/a.mp4", true, 100, true, true,
                "/storage/USB1/DCIM/Camera/a.mp4", "File.delete", true, true, null, null);
        assertFalse(r.success());
        assertTrue(r.attemptLog().contains("DELETE_ATTEMPT"));
        assertTrue(r.resultLog().contains("existsAfterDelete=true"));
    }

    @Test
    public void deleteResultSuccessOnlyWhenGone() {
        UsbDeleteResult r = new UsbDeleteResult(
                "/storage/USB1/DCIM/Camera/a.mp4", true, 100, true, true,
                "/storage/USB1/DCIM/Camera/a.mp4", "MediaStore", true, false, null, null);
        assertTrue(r.success());
        assertTrue(r.resultLog().contains("existsAfterDelete=false"));
    }
}
