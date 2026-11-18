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
        for (int y = 79; y <= 87; y++)
            expect(true, LowlandCarvingGuard.protectsWaterBed(86, 88, y), "inland floor and water " + y);
        expect(false, LowlandCarvingGuard.protectsWaterBed(86, 88, 78), "deep caves under inland water");
        expect(false, LowlandCarvingGuard.protectsWaterBed(86, 88, 88), "air above inland water");
        expect(false, LowlandCarvingGuard.protectsWaterBed(86, 0, 86), "dry inland column");
        expect(false, LowlandCarvingGuard.protectsWaterBed(86, 87, 86), "zero-depth dry bank");
        expect(false, LowlandCarvingGuard.protectsWaterBed(30, 63, 30), "deep ocean caves stay available");
        expect(true, LowlandCarvingGuard.dryTidalRoof(63, 62, 0, 62), "dry estuary cap before cave aquifer writes water");
        expect(true, LowlandCarvingGuard.dryTidalRoof(63, 62, 0, 55), "last block of tidal roof");
        expect(false, LowlandCarvingGuard.dryTidalRoof(63, 62, 0, 54), "caves below tidal roof stay open");
        expect(false, LowlandCarvingGuard.dryTidalRoof(63, 61, 0, 61), "lower columns not in tidal cap");
        expect(false, LowlandCarvingGuard.dryTidalRoof(63, 62, 65, 62), "existing pond uses separate wet bed policy");
        expect(true, LowlandCarvingGuard.protectsWaterBed(60, 63, 60), "shallow mangrove floor");
    }

    private static void expect(boolean expected, boolean actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}
