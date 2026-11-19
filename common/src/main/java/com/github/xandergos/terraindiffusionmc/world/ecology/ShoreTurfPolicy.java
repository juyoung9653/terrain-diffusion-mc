package com.github.xandergos.terraindiffusionmc.world.ecology;

/**
 * Keeps a dry, marine shore from ending in one-cell turf fingers while leaving
 * a connected inland lawn alone.  Callers must supply neighbours from the
 * settled surface, rather than from climate samples, so this only resolves an
 * already exposed sand/turf contact.
 */
public final class ShoreTurfPolicy {
    private ShoreTurfPolicy() { }

    public static boolean replaceNarrowDryTurfProjection(boolean exposedDrySurface,
                                                          int adjacentDryMineral,
                                                          int adjacentDryOrganicSoil) {
        return exposedDrySurface
                && adjacentDryMineral >= 2
                && adjacentDryOrganicSoil <= 1;
    }

    /**
     * A retained vanilla beach is eligible only for the local grass-pad repair;
     * ordinary beach columns stay outside the ecology material palette.
     */
    public static boolean replaceVanillaBeachTurf(boolean ordinaryBeachTurf,
                                                  boolean alreadyMineralRoute,
                                                  int adjacentDryMineral,
                                                  int adjacentDryOrganicSoil) {
        return ordinaryBeachTurf && !alreadyMineralRoute
                && replaceNarrowDryTurfProjection(true, adjacentDryMineral, adjacentDryOrganicSoil);
    }
}
