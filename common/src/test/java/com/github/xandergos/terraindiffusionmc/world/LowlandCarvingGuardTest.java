package com.github.xandergos.terraindiffusionmc.world;

/** Pure boundary checks runnable with {@code java -ea}. */
public final class LowlandCarvingGuardTest {
    public static void main(String[] args) {
        int seaLevel = 63;

        expect(true, LowlandCarvingGuard.protects(true, seaLevel, 63, 63), "sea-level surface");
        expect(true, LowlandCarvingGuard.protects(true, seaLevel, 71, 64), "upper lowland roof edge");
        expect(true, LowlandCarvingGuard.protects(true, seaLevel, 68, 61), "lowest protected roof block");
        expect(false, LowlandCarvingGuard.protects(true, seaLevel, 68, 60), "deep cave below roof");
        expect(false, LowlandCarvingGuard.protects(true, seaLevel, 68, 69), "air above surface");
        expect(false, LowlandCarvingGuard.protects(true, seaLevel, 62, 62), "sub-sea column");
        expect(false, LowlandCarvingGuard.protects(true, seaLevel, 72, 72), "highland entrance");
        expect(false, LowlandCarvingGuard.protects(false, seaLevel, 63, 63), "non-TD world");
    }

    private static void expect(boolean expected, boolean actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}
