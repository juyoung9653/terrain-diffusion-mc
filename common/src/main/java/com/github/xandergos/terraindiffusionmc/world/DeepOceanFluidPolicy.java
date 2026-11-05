package com.github.xandergos.terraindiffusionmc.world;

/** Profile-2 global-fluid floor; legacy worlds keep vanilla's picker unchanged. */
public final class DeepOceanFluidPolicy {
    private DeepOceanFluidPolicy() { }

    /** Vanilla uses lava strictly below -54. Deep profile keeps only eight floor blocks lava. */
    public static boolean useLava(int y, int minY, int seaLevel) {
        return y < Math.min(minY + 8, seaLevel);
    }
}
