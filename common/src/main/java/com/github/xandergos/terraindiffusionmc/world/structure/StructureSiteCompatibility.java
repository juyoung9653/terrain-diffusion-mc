package com.github.xandergos.terraindiffusionmc.world.structure;

/** Pure, bounded deep-water admission check for the known Better Ocean Monuments coast clear. */
public final class StructureSiteCompatibility {
    private static final String BETTER_OCEAN_MONUMENT = "betteroceanmonuments:ocean_monument";
    private StructureSiteCompatibility() { }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (maxX < minX || maxY < minY || maxZ < minZ) throw new IllegalArgumentException("Inverted structure bounds");
        }
        public long columnCount() { return ((long)maxX - minX + 1L) * ((long)maxZ - minZ + 1L); }
    }
    /** Heights use the same first-occupied conventions as native worldgen heightmaps. */
    public record Heights(int oceanFloor, int worldSurface) { }
    @FunctionalInterface public interface HeightLookup { Heights at(int x, int z); }
    public record Result(boolean accepted, boolean applicable, int sampledColumns, String reason) { }

    public static boolean appliesTo(String structureId) { return BETTER_OCEAN_MONUMENT.equals(structureId); }

    /**
     * Every footprint column must be deep water: its floor may not exceed the
     * lowest structure Y plus allowed cut, and its surface stays at sea level.
     */
    public static Result check(String structureId, Bounds bounds, int maxCutBlocks,
                               int maxTerrainSamples, int seaLevel, HeightLookup heights) {
        if (!appliesTo(structureId)) return new Result(true, false, 0, "not Better Ocean Monuments");
        if (maxCutBlocks < 0 || maxTerrainSamples < 0) throw new IllegalArgumentException("Negative site compatibility budget");
        long columns = bounds.columnCount();
        if (columns > maxTerrainSamples) return new Result(false, true, 0, "sample budget " + columns + ">" + maxTerrainSamples);
        int floorLimit = Math.addExact(bounds.minY(), maxCutBlocks);
        int sampled = 0;
        for (long rawX = bounds.minX(); rawX <= bounds.maxX(); rawX++) for (long rawZ = bounds.minZ(); rawZ <= bounds.maxZ(); rawZ++) {
            int x = (int)rawX, z = (int)rawZ;
            Heights column = heights.at(x, z);
            sampled++;
            if (column == null) return new Result(false, true, sampled, "missing height sample at " + x + "," + z);
            if (column.oceanFloor() > floorLimit)
                return new Result(false, true, sampled, "shallow floor at " + x + "," + z + " floor=" + column.oceanFloor() + " limit=" + floorLimit);
            if (column.oceanFloor() >= column.worldSurface())
                return new Result(false, true, sampled, "no water column at " + x + "," + z);
            if (Math.abs((long)column.worldSurface() - seaLevel) > 1L)
                return new Result(false, true, sampled, "water surface away from sea level at " + x + "," + z + " surface=" + column.worldSurface() + " sea=" + seaLevel);
        }
        return new Result(true, true, sampled, "continuous deep water");
    }
}
