package com.github.xandergos.terraindiffusionmc.world.ecology;

public final class ShoreTurfPolicyTest {
    public static void main(String[] args) {
        require(ShoreTurfPolicy.replaceNarrowDryTurfProjection(true, 2, 1),
                "a dry one-cell turf spur between mineral shore columns must be trimmed");
        require(ShoreTurfPolicy.replaceNarrowDryTurfProjection(true, 3, 0),
                "an isolated dry turf pad in a marine sand apron must be trimmed");
        require(ShoreTurfPolicy.replaceVanillaBeachTurf(true, false, 2, 1),
                "an ordinary vanilla beach pad must route to the narrow repair");
        require(!ShoreTurfPolicy.replaceVanillaBeachTurf(false, false, 3, 0),
                "a non-beach column must not enter the vanilla beach branch");
        require(!ShoreTurfPolicy.replaceVanillaBeachTurf(true, true, 3, 0),
                "an existing mineral beach route must keep ownership of its column");
        require(!ShoreTurfPolicy.replaceNarrowDryTurfProjection(false, 3, 0),
                "submerged or otherwise wet turf must not be repainted");
        require(!ShoreTurfPolicy.replaceNarrowDryTurfProjection(true, 1, 1),
                "a broad grass-to-sand margin must keep its single shore neighbour");
        require(!ShoreTurfPolicy.replaceNarrowDryTurfProjection(true, 2, 2),
                "a connected turf bank must not be narrowed");
        System.out.println("ShoreTurfPolicyTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
