package com.loopcamera.loop20;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class WriterReportFormatterTest {

    @Test
    public void compactReportUsesRequiredHeaderAndUnknownFinalLine() {
        WriterReportFormatter.Model m = new WriterReportFormatter.Model();
        m.targetDirectory = "/storage/USB1/DCIM/Camera";
        m.observedMp4 = "20260825_12h00m00s.mp4";
        m.firstSeenTime = "12:00:00.001";
        m.growthObserved = true;
        m.confidence = WriterConfidence.UNKNOWN;
        m.finalLine = WriterConfidence.evaluate(null, null, null).finalLine();
        String text = WriterReportFormatter.compactReport(m);
        assertTrue(text.startsWith("CARFU MP4 WRITER DISCOVERY"));
        assertTrue(text.contains("Target directory"));
        assertTrue(text.contains("/storage/USB1/DCIM/Camera"));
        assertTrue(text.contains("Growth observed\nYES"));
        assertTrue(text.contains("WRITER UNKNOWN:"));
        assertTrue(text.contains("Android/ROM restrictions prevented process attribution."));
        String live = WriterReportFormatter.livePanel(m);
        assertTrue(live.contains("LIVE MP4 WRITER DISCOVERY"));
        assertTrue(live.contains("Current file:"));
        assertTrue(live.contains("Confidence:"));
    }
}
