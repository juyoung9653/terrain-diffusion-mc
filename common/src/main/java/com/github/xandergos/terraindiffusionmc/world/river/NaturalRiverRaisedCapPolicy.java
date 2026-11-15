package com.github.xandergos.terraindiffusionmc.world.river;

/** Pure admission rule for the one-block redundant cap above a planned TD water column. */
public final class NaturalRiverRaisedCapPolicy {
    private NaturalRiverRaisedCapPolicy() {
    }

    public static boolean suppress(int profile, boolean inCorridor, boolean inChannel,
                                   int originalGround, int water, int capY,
                                   boolean targetAir, boolean soilCap) {
        return profile == 14 && inCorridor && !inChannel && soilCap && targetAir
                && originalGround >= water && capY > originalGround && capY > water;
    }
}
