package com.github.xandergos.terraindiffusionmc.world.river;

/**
 * Preserves a planned one-block river bed when the native carver requests its
 * isolated extra cut.  This is deliberately a pure value policy: the caller
 * supplies the already-planned column values and the native helper result.
 */
public final class ShallowRiverBedPolicy {
    public static final int FIRST_FIXED_PROFILE_VERSION = 12;

    private ShallowRiverBedPolicy() { }

    public static int normalizeDepth(int returnedDepth, boolean activeNaturalRiver,
            int profileVersion, boolean inChannel, boolean source, boolean terminal,
            int plannedWaterY, int plannedBedY, int carverWaterY) {
        if (returnedDepth != 2
                || !activeNaturalRiver
                || profileVersion < FIRST_FIXED_PROFILE_VERSION
                || !inChannel
                || source
                || terminal
                || carverWaterY != plannedWaterY
                || plannedWaterY - plannedBedY != 1) {
            return returnedDepth;
        }
        return 1;
    }
}
