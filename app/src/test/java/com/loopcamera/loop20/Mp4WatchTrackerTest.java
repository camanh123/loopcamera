package com.loopcamera.loop20;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Mp4WatchTrackerTest {

    @Test
    public void newThenGrowThenStable() {
        Mp4WatchTracker t = new Mp4WatchTracker();
        t.markStartSnapshotDone();
        long t0 = 1_000_000L;
        t.observe("20260825_12h00m00s.mp4", "/storage/USB1/DCIM/Camera/20260825_12h00m00s.mp4",
                4096, t0, t0);
        assertEquals(Mp4WatchTracker.NEW, t.sessionState());
        assertEquals("20260825_12h00m00s.mp4", t.current().filename);

        t.observe("20260825_12h00m00s.mp4", "/storage/USB1/DCIM/Camera/20260825_12h00m00s.mp4",
                262144, t0 + 500, t0 + 500);
        assertEquals(Mp4WatchTracker.GROWING, t.sessionState());
        assertTrue(t.current().grew);

        t.observe("20260825_12h00m00s.mp4", "/storage/USB1/DCIM/Camera/20260825_12h00m00s.mp4",
                262144, t0 + 500, t0 + 500 + Mp4WatchTracker.STABLE_MS);
        assertEquals(Mp4WatchTracker.CLOSED, t.sessionState());
    }

    @Test
    public void preexistingDoesNotCountAsNewUntilGrowth() {
        Mp4WatchTracker t = new Mp4WatchTracker();
        long t0 = 5_000L;
        t.snapshotExisting("old.mp4", "/storage/USB1/DCIM/Camera/old.mp4", 1_000_000, t0, t0);
        t.markStartSnapshotDone();
        assertEquals(Mp4WatchTracker.WAITING, t.sessionState());
        t.observe("old.mp4", "/storage/USB1/DCIM/Camera/old.mp4", 1_000_000, t0, t0 + 800);
        assertEquals(Mp4WatchTracker.WAITING, t.sessionState());
        t.observe("old.mp4", "/storage/USB1/DCIM/Camera/old.mp4", 2_000_000, t0 + 900, t0 + 900);
        assertEquals(Mp4WatchTracker.GROWING, t.sessionState());
        assertTrue(t.current().preexisting);
    }

    @Test
    public void ignoresNonMp4() {
        Mp4WatchTracker t = new Mp4WatchTracker();
        t.observe("photo.jpg", "/storage/USB1/DCIM/Camera/photo.jpg", 100, 1, 1);
        assertEquals(Mp4WatchTracker.WAITING, t.sessionState());
        assertTrue(Mp4WatchTracker.isMp4("clip.MP4"));
        assertFalse(Mp4WatchTracker.isMp4("clip.tmp"));
    }

    @Test
    public void createEventOpensNewTrack() {
        Mp4WatchTracker t = new Mp4WatchTracker();
        t.onFsEvent("abc.mp4", "CREATE", 10);
        assertEquals(Mp4WatchTracker.NEW, t.sessionState());
        assertEquals(Collections.singletonList("CREATE"), t.current().fsEvents);
        t.onFsEvent("abc.mp4", "MODIFY", 11);
        t.onFsEvent("abc.mp4", "CLOSE_WRITE", 12);
        assertTrue(t.current().fsEvents.containsAll(Arrays.asList("CREATE", "MODIFY", "CLOSE_WRITE")));
        assertEquals(Mp4WatchTracker.NEW, t.sessionState());
    }
}
