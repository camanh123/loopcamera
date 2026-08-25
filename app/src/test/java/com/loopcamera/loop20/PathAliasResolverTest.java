package com.loopcamera.loop20;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathAliasResolverTest {

    @Test
    public void matchesStorageUsb1AndMediaRwAndRuntime() {
        Set<String> aliases = PathAliasResolver.collectCameraDirAliases(
                new File("/storage/USB1/DCIM/Camera"),
                Collections.singletonList(new File("/storage/USB1")),
                "36 24 8:1 / /mnt/media_rw/ABCD-1234 rw - vfat /dev/block/vold/public:8,1\n"
                        + "48 1 0:46 / /storage/USB1 rw - fuse /mnt/runtime/default/USB1\n");
        String fn = "20260825_12h00m00s.mp4";
        assertTrue(PathAliasResolver.matchesFdTarget(
                "/storage/USB1/DCIM/Camera/" + fn, fn, aliases));
        assertTrue(PathAliasResolver.matchesFdTarget(
                "/mnt/media_rw/ABCD-1234/DCIM/Camera/" + fn, fn, aliases));
        assertTrue(PathAliasResolver.matchesFdTarget(
                "/mnt/runtime/write/USB1/DCIM/Camera/" + fn, fn, aliases));
        assertTrue(PathAliasResolver.matchesFdTarget(
                "/storage/USB1/DCIM/Camera/" + fn + " (deleted)", fn, aliases));
    }

    @Test
    public void doesNotMatchEmulatedOrOtherFile() {
        Set<String> aliases = Collections.singleton("/storage/USB1/DCIM/Camera");
        String fn = "clip.mp4";
        assertFalse(PathAliasResolver.matchesFdTarget(
                "/storage/emulated/0/DCIM/Camera/" + fn, fn, aliases));
        assertFalse(PathAliasResolver.matchesFdTarget(
                "/storage/USB1/DCIM/Camera/other.mp4", fn, aliases));
        assertFalse(PathAliasResolver.matchesFdTarget("pipe:[123]", fn, aliases));
        assertFalse(PathAliasResolver.matchesFdTarget("socket:[9]", fn, aliases));
    }

    @Test
    public void inodeIdentityRequiresPositiveIno() {
        assertTrue(PathAliasResolver.sameIdentity(8, 99, 8, 99));
        assertFalse(PathAliasResolver.sameIdentity(8, 99, 8, 100));
        assertFalse(PathAliasResolver.sameIdentity(8, 0, 8, 0));
    }

    @Test
    public void parseMountPointsKeepsUsbAndMediaRw() {
        String mi = "1 2 8:1 / /mnt/media_rw/XYZ rw - vfat /dev/foo\n"
                + "2 1 0:1 / /storage/emulated/0 rw - fuse /dev/fuse\n";
        assertTrue(PathAliasResolver.parseMountPoints(mi).contains("/mnt/media_rw/XYZ"));
        assertFalse(PathAliasResolver.parseMountPoints(mi).contains("/storage/emulated/0"));
    }
}
