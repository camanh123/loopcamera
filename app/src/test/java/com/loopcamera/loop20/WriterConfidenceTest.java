package com.loopcamera.loop20;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WriterConfidenceTest {

    @Test
    public void fdHitIsProven() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(
                "com.vendor.dvr (pid 1234 uid 1000 cmdline=com.vendor.dvr)",
                null,
                Collections.emptyList());
        assertEquals(WriterConfidence.PROVEN, v.level);
        assertTrue(v.identity.contains("com.vendor.dvr"));
        assertTrue(v.finalLine().startsWith("WRITER PROVEN:"));
        assertTrue(v.finalLine().contains("com.vendor.dvr"));
    }

    @Test
    public void lsofHitIsProven() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(
                null,
                "mediaserver (pid 77 uid 1013)",
                Collections.emptyList());
        assertEquals(WriterConfidence.PROVEN, v.level);
        assertTrue(v.finalLine().startsWith("WRITER PROVEN:"));
    }

    @Test
    public void twoAgreeingIndirectSignalsAreVeryStrong() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(null, null, Arrays.asList(
                new WriterConfidence.Signal(WriterConfidence.TYPE_MEDIASTORE, "com.vendor.dvr", "owner"),
                new WriterConfidence.Signal(WriterConfidence.TYPE_FOREGROUND, "com.vendor.dvr", "fg")
        ));
        assertEquals(WriterConfidence.VERY_STRONG, v.level);
        assertEquals("com.vendor.dvr", v.identity);
        assertTrue(v.finalLine().startsWith("WRITER VERY STRONGLY INDICATED:"));
    }

    @Test
    public void singleIndirectSignalIsCandidate() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(null, null, Collections.singletonList(
                new WriterConfidence.Signal(WriterConfidence.TYPE_MEDIASTORE, "com.vendor.dvr", "owner")
        ));
        assertEquals(WriterConfidence.CANDIDATE, v.level);
        assertTrue(v.finalLine().startsWith("WRITER CANDIDATE:"));
    }

    @Test
    public void noSignalsStayUnknownWithoutGuessingAPackage() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(null, null, Collections.emptyList());
        assertEquals(WriterConfidence.UNKNOWN, v.level);
        assertNull(v.identity);
        assertEquals("WRITER UNKNOWN:\nAndroid/ROM restrictions prevented process attribution.",
                v.finalLine());
        assertFalse(v.finalLine().contains("com.DeviceTest"));
        assertFalse(v.finalLine().contains("com.android.launcher16"));
        assertFalse(v.finalLine().contains("com.ly.cardvr"));
    }

    @Test
    public void launcherForegroundIsNotAWriterGuess() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(null, null, Collections.singletonList(
                new WriterConfidence.Signal(WriterConfidence.TYPE_FOREGROUND,
                        "com.android.launcher16", "widget")
        ));
        assertEquals(WriterConfidence.UNKNOWN, v.level);
        assertNull(v.identity);
        assertTrue(WriterConfidence.isNoiseIdentity("com.android.launcher16"));
        assertTrue(WriterConfidence.isNoiseIdentity("com.loopcamera.loop20"));
    }

    @Test
    public void conflictingIndirectDoesNotUpgradeUnknownGuess() {
        WriterConfidence.Verdict v = WriterConfidence.evaluate(null, null, Arrays.asList(
                new WriterConfidence.Signal(WriterConfidence.TYPE_MEDIASTORE, "com.a.dvr", "owner"),
                new WriterConfidence.Signal(WriterConfidence.TYPE_FOREGROUND, "com.b.other", "fg")
        ));
        assertEquals(WriterConfidence.CANDIDATE, v.level);
        assertTrue(v.identity.equals("com.a.dvr") || v.identity.equals("com.b.other"));
    }

    @Test
    public void extractPackageTokenSkipsSelf() {
        assertEquals("com.vendor.dvr", WriterConfidence.extractPackageToken(
                "I MPEG4: com.vendor.dvr started muxer DCIM/Camera"));
        assertNull(WriterConfidence.extractPackageToken(
                "I CameraLoop30 com.loopcamera.loop20 watching"));
    }
}
