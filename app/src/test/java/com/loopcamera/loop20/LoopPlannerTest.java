package com.loopcamera.loop20;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoopPlannerTest {

    @Test
    public void maxVideosIs30() {
        assertEquals(30, LoopPlanner.MAX_VIDEOS);
    }

    @Test
    public void acceptsCarfuTimestampName() {
        assertTrue(LoopPlanner.isManagedVideo("20260814_11h04m17s.mp4"));
        assertTrue(LoopPlanner.isManagedVideo("20260814_11H04M17S.MP4"));
    }

    @Test
    public void rejectsSlotAndOtherNames() {
        assertFalse(LoopPlanner.isManagedVideo("01.mp4"));
        assertFalse(LoopPlanner.isManagedVideo("30.mp4"));
        assertFalse(LoopPlanner.isManagedVideo("20260814_110417.mp4"));
        assertFalse(LoopPlanner.isManagedVideo("photo.jpg"));
    }

    @Test
    public void sortKeyOrdersOldestFirst() {
        long a = LoopPlanner.timestampSortKey("20260814_10h36m17s.mp4");
        long b = LoopPlanner.timestampSortKey("20260814_11h04m17s.mp4");
        assertTrue(a < b);
    }
}
