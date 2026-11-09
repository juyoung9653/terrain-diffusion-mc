package com.github.xandergos.terraindiffusionmc.world.structure;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Bounded admission for restoring a small void directly below an intended
 * natural structure floor.  The caller owns block classification and writing.
 */
public final class FoundationSupport {
    private FoundationSupport() { }

    /**
     * Returns the contiguous air depth to fill, or zero when the column is not
     * an anchored, bounded void. Depth one is directly below the floor.
     */
    public static int repairDepth(int maxFill, IntPredicate airAtDepth,
                                  IntPredicate naturalAtDepth) {
        if (maxFill < 1) return 0;
        Objects.requireNonNull(airAtDepth, "airAtDepth");
        Objects.requireNonNull(naturalAtDepth, "naturalAtDepth");
        if (!airAtDepth.test(1)) return 0;
        int gap = 1;
        while (gap < maxFill && airAtDepth.test(gap + 1)) gap++;
        // A fill must join a two-block, dry natural footing. This also rejects
        // fluids and protected/non-natural material when the caller classifies
        // them as non-natural.
        return naturalAtDepth.test(gap + 1) && naturalAtDepth.test(gap + 2) ? gap : 0;
    }
}
