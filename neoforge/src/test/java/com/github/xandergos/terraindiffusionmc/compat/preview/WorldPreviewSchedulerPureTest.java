package com.github.xandergos.terraindiffusionmc.compat.preview;

import java.util.HashSet;
import java.util.Set;

/** Standalone JDK assertions for the bounded, negative-coordinate tile cursor. */
public final class WorldPreviewSchedulerPureTest {
    private WorldPreviewSchedulerPureTest() {
    }

    public static void main(String[] args) {
        assertCoverage(-1025, -769, 1024, 768, 256, 80);
        assertCoverage(-513, -257, -1, -1, 256, 6);
        assertCoverage(0, 0, 8191, 8191, 256, 1024);
        assertLazyReplacement();
        System.out.println("WorldPreviewSchedulerPureTest: PASS");
    }

    private static void assertCoverage(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ,
                                       int tileSize, int expectedCount) {
        WorldPreviewScheduler.TileCursor cursor =
                new WorldPreviewScheduler.TileCursor(minBlockX, minBlockZ, maxBlockX, maxBlockZ, tileSize);
        Set<WorldPreviewScheduler.TileKey> actual = new HashSet<>();
        for (WorldPreviewScheduler.TileKey tile; (tile = cursor.next()) != null;) {
            if (!actual.add(tile)) throw new AssertionError("duplicate tile " + tile);
        }
        Set<WorldPreviewScheduler.TileKey> expected = new HashSet<>();
        for (int z = Math.floorDiv(minBlockZ, tileSize); z <= Math.floorDiv(maxBlockZ, tileSize); z++) {
            for (int x = Math.floorDiv(minBlockX, tileSize); x <= Math.floorDiv(maxBlockX, tileSize); x++) {
                expected.add(new WorldPreviewScheduler.TileKey(x, z));
            }
        }
        if (actual.size() != expectedCount || !actual.equals(expected)) {
            throw new AssertionError("expected complete tile coverage " + expected.size() + ", got " + actual.size());
        }
    }

    private static void assertLazyReplacement() {
        WorldPreviewScheduler.TileCursor oldCursor = new WorldPreviewScheduler.TileCursor(0, 0, 8191, 8191, 256);
        for (int i = 0; i < 9; i++) {
            if (oldCursor.next() == null) throw new AssertionError("large viewport ended before bounded queue filled");
        }
        // A replacement cursor starts independently: abandoning queued old tiles cannot leak them into a new request.
        WorldPreviewScheduler.TileCursor replacement = new WorldPreviewScheduler.TileCursor(-513, -257, -1, -1, 256);
        WorldPreviewScheduler.TileKey first = replacement.next();
        if (!new WorldPreviewScheduler.TileKey(-2, -1).equals(first)) {
            throw new AssertionError("replacement viewport did not restart at its own center: " + first);
        }
    }
}
