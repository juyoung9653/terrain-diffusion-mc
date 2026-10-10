package com.github.xandergos.terraindiffusionmc.compat.continuity;

/** Standalone test: no Minecraft, optional loader, or native libraries. */
public final class CattailDepthPolicyTest {
    public static void main(String[] args) {
        for (int y : new int[]{-63, 0, 61, 62, 125, 196, 319}) {
            require(CattailDepthPolicy.allows(true, false, y, y + 1.0), "one cell at " + y);
            require(CattailDepthPolicy.allows(true, false, y + 0.5, y + 1.0), "same-cell bottom slab " + y);
            for (double depth : new double[]{0, 0.25, 0.75, 1.5, 2, 3, 8.0 / 9.0})
                require(!CattailDepthPolicy.allows(true, false, y, y + depth), "reject depth " + depth);
            require(!CattailDepthPolicy.allows(true, false, y - 0.5, y + 1.0), "slab below is 1.5 deep");
            require(!CattailDepthPolicy.allows(true, true, y, y + 1.0), "water above");
            require(!CattailDepthPolicy.allows(false, false, y, y + 1.0), "flowing or non-water");
        }
        require(!CattailDepthPolicy.allows(true, false, Double.NaN, 1), "invalid root");
        require(!CattailDepthPolicy.allows(true, false, 0, Double.POSITIVE_INFINITY), "invalid surface");
        for (int i = 0; i < 5; i++) require(CattailDepthPolicy.isCattailRule("minecraft",
                "optifine/ctm/cattails1/" + i + ".properties", "0.5 1", "2147483647"), "paired rule " + i);
        require(!CattailDepthPolicy.isCattailRule("other", "optifine/ctm/cattails1/0.properties", "0.5 1", "2147483647"), "namespace");
        require(!CattailDepthPolicy.isCattailRule("minecraft", "optifine/ctm/algae/0.properties", "0.5 1", "2147483647"), "unrelated CTM");
        require(!CattailDepthPolicy.isCattailRule("minecraft", "optifine/ctm/cattails1/0.properties", null, "62-319"), "unmarked source rule");
        require(CattailDepthPolicy.supportsRuntime(true, CattailDepthPolicy.CONTINUITY_VERSION), "audited client");
        require(!CattailDepthPolicy.supportsRuntime(false, CattailDepthPolicy.CONTINUITY_VERSION), "dedicated server");
        require(!CattailDepthPolicy.supportsRuntime(true, null), "no Continuity");
        require(!CattailDepthPolicy.supportsRuntime(true, "3.0.1"), "unknown Continuity");
        require(!CattailHeightMarker.INSTANCE.test(2147483647), "partial hook fails closed");
        System.out.println("PASS CattailDepthPolicyTest: seven elevations, exact half/full depth, deep/flow/covered rejection, paired scope, optional runtime gates");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
