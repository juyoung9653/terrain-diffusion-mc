package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone checks for species substitution without changing placement attempts. */
public final class OptionalGroundcoverPolicyTest {
    public static void main(String[] args) {
        require("short_blue_grass".equals(OptionalGroundcoverPolicy.grass(-5, .9F, .2F, .1F, .5F)),
                "cold grass should use blue grass");
        require("short_red_fescue".equals(OptionalGroundcoverPolicy.grass(28, .3F, .2F, .1F, .5F)),
                "warm dry grass should use red fescue");
        require("short_common_bent".equals(OptionalGroundcoverPolicy.grass(10, .4F, .1F, .1F, .5F)),
                "temperate open grass should use common bent");
        require(OptionalGroundcoverPolicy.grass(10, 1, .5F, 1, .5F) == null,
                "vanilla grass must remain in the mixture");
        require("short_royal_fern".equals(OptionalGroundcoverPolicy.fern(12, 1.4F, .8F, .1F, .1F)),
                "wet shelter should allow royal fern");
        require(OptionalGroundcoverPolicy.fern(12, .2F, .8F, .1F, .1F) == null,
                "dry floor must not receive royal fern");
        System.out.println("OptionalGroundcoverPolicyTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
