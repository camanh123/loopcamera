package com.loopcamera.loop20;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecorderCandidateScorerTest {

    private static RecorderCandidate dvr() {
        RecorderCandidate c = new RecorderCandidate();
        c.packageName = "com.vendor.dvr";
        c.label = "Car DVR";
        c.systemApp = true;
        c.cameraPermission = true;
        c.writeStoragePermission = true;
        c.readStoragePermission = true;
        c.services.add("com.vendor.dvr.RecordService");
        c.exportedServices.add("com.vendor.dvr.RecordService");
        c.activities.add("com.vendor.dvr.SettingsActivity");
        c.activities.add("com.vendor.dvr.StoragePathActivity");
        return c;
    }

    @Test
    public void cameraLoopSelfIsNotARecorderCandidate() {
        RecorderCandidate self = new RecorderCandidate();
        self.packageName = "com.loopcamera.loop20";
        self.label = "Camera Loop 30";
        self.cameraPermission = false;
        self.activities.add("com.loopcamera.loop20.MainActivity");
        assertEquals(0, RecorderCandidateScorer.score(self));
        assertFalse(RecorderCandidateScorer.isLikely(self));
        assertTrue(RecorderCandidate.looksLikeNoise("com.loopcamera.loop20"));
    }

    @Test
    public void vendorDvrOutranksGenericSettings() {
        RecorderCandidate settings = new RecorderCandidate();
        settings.packageName = "com.android.settings";
        settings.label = "Settings";
        assertEquals(0, RecorderCandidateScorer.score(settings));

        RecorderCandidate dvr = dvr();
        dvr.score = RecorderCandidateScorer.score(dvr);
        assertTrue(dvr.score >= 40);
        assertTrue(RecorderCandidateScorer.isLikely(dvr));
        List<RecorderCandidate> ranked = RecorderCandidateScorer.rank(Arrays.asList(settings, dvr));
        assertEquals(1, ranked.size());
        assertEquals("com.vendor.dvr", ranked.get(0).packageName);
    }

    @Test
    public void cameraPermissionAloneCanBeLikely() {
        RecorderCandidate cam = new RecorderCandidate();
        cam.packageName = "com.vendor.camera";
        cam.label = "Camera";
        cam.cameraPermission = true;
        cam.writeStoragePermission = true;
        cam.score = RecorderCandidateScorer.score(cam);
        assertTrue(cam.score >= 40);
    }

    @Test
    public void emptyScanIsVerdictE() {
        assertEquals(RecorderCandidate.VERDICT_E, RecorderCandidateScorer.verdict(null));
        assertTrue(RecorderCandidateScorer.verdictReason(null).startsWith("E"));
        assertEquals(RecorderCandidate.VERDICT_E,
                RecorderCandidateScorer.verdict(new RecorderCandidate()));
    }

    @Test
    public void identifiedWithoutPublicApiIsVerdictD() {
        RecorderCandidate dvr = dvr();
        dvr.score = RecorderCandidateScorer.score(dvr);
        assertEquals(RecorderCandidate.VERDICT_D, RecorderCandidateScorer.verdict(dvr));
        assertTrue(RecorderCandidateScorer.verdictReason(dvr).startsWith("D"));
        RecorderCandidateScorer.classifyHints(dvr);
        assertFalse(dvr.pathLike.isEmpty());
        assertFalse(dvr.settingsLike.isEmpty());
    }

    @Test
    public void productionLoopUnchanged() {
        assertEquals(30, LoopPlanner.MAX_VIDEOS);
        assertFalse(DeletePolicy.allowRealDelete("TEST", false, 34));
        assertTrue(DeletePolicy.enterFailsafe(3));
        assertFalse(DeletePolicy.enterFailsafe(2));
    }

    @Test
    public void screenReportContainsRequiredLabels() {
        RecorderDiscovery.Report r = new RecorderDiscovery.Report();
        r.scanPass = true;
        r.packageCount = 12;
        r.likelyCount = 1;
        RecorderCandidate dvr = dvr();
        dvr.score = RecorderCandidateScorer.score(dvr);
        r.verdict = RecorderCandidateScorer.verdict(dvr);
        r.verdictReason = RecorderCandidateScorer.verdictReason(dvr);
        r.strongestPackage = dvr.packageName;
        r.strongestLabel = dvr.label;
        String text = RecorderDiscovery.buildBody(r, Collections.singletonList(dvr), dvr);
        assertTrue(text.contains("CARFU CAMERA / DVR DISCOVERY"));
        assertTrue(text.contains("Camera Loop records MP4:\nNO"));
        assertTrue(text.contains("package: com.vendor.dvr"));
        assertTrue(text.contains("VERDICT:\nD"));
        assertTrue(text.contains("CAMERA permission: YES"));
    }
}
