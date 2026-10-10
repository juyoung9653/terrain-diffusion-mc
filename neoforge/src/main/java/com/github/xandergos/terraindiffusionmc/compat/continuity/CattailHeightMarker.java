package com.github.xandergos.terraindiffusionmc.compat.continuity;

import java.util.function.IntPredicate;

/** Passed from CTM properties to its processing predicate; fails closed unless the paired hook is active. */
public final class CattailHeightMarker implements IntPredicate {
    public static final CattailHeightMarker INSTANCE = new CattailHeightMarker();
    private CattailHeightMarker() { }
    @Override public boolean test(int height) { return false; }
}
