package com.github.xandergos.terraindiffusionmc.world.structure;

/** Seed-only decisions for the shared village population; no Minecraft or mutable counters. */
public final class VillageDistributionGeometry {
    public static final String SET_ID = "terrain-diffusion-mc:unified_villages";
    public static final int MICROCELLS_PER_SIDE = 3;
    public static final int HOPS = MICROCELLS_PER_SIDE * MICROCELLS_PER_SIDE;
    public enum Group { BASIC, DISTINCTIVE }
    private VillageDistributionGeometry() {}

    /** Nine candidate microcells per common cell compensate bounded terrain/biome failures. */
    public static int microSpacing(int requestedMeanBlocks) {
        return microSpacing(requestedMeanBlocks,2);
    }

    public static int microSpacing(int requestedMeanBlocks,int algorithmVersion) {
        int side=microcellsPerSide(algorithmVersion);
        if (requestedMeanBlocks < 256 || requestedMeanBlocks > 4096)
            throw new IllegalArgumentException("Village mean distance outside 256..4096 blocks");
        // Keep the established target-to-grid ratio; wider trial grids left large empty regions.
        // Version one retains its exact two-by-two candidate layout.
        // Actual accepted centers, rather than this grid width, must be measured in a census.
        double cellScale=.7;
        return Math.max(2, (int)Math.round(requestedMeanBlocks * cellScale / (16.0 * side)));
    }

    public static int cell(int chunk, int microSpacing) {
        return cell(chunk,microSpacing,2);
    }

    public static int cell(int chunk,int microSpacing,int algorithmVersion) {
        return Math.floorDiv(chunk, Math.multiplyExact(microSpacing,microcellsPerSide(algorithmVersion)));
    }

    private static int microcellsPerSide(int algorithmVersion) {
        return switch (algorithmVersion) {
            case 1 -> 2;
            case 2 -> MICROCELLS_PER_SIDE;
            default -> throw new IllegalArgumentException("Unsupported village distribution algorithm version: "+algorithmVersion);
        };
    }

    public record Microcell(int x,int z) {}

    /** Query coordinates understood by the inherited RandomSpread locate implementation. */
    public static java.util.List<Microcell> microcells(int chunkX,int chunkZ,int spacing) {
        return microcells(chunkX,chunkZ,spacing,2);
    }

    public static java.util.List<Microcell> microcells(int chunkX,int chunkZ,int spacing,int algorithmVersion) {
        int side=microcellsPerSide(algorithmVersion);
        if (spacing<2 || spacing>128) throw new IllegalArgumentException("Invalid village microcell spacing");
        int width=side*spacing;
        int x=cell(chunkX,spacing,algorithmVersion)*width,z=cell(chunkZ,spacing,algorithmVersion)*width;
        var result=new java.util.ArrayList<Microcell>(side*side);
        for (int dx=0;dx<side;dx++)
            for (int dz=0;dz<side;dz++)
                result.add(new Microcell(x+dx*spacing,z+dz*spacing));
        return java.util.List.copyOf(result);
    }

    public static boolean isVillage(String id, boolean tagged) {
        // These installed Cataclysm ruins are independent dungeon structures, not living villages.
        if (id.equals("cataclysm:abandoned_village") || id.equals("cataclysm:desert_occupied_village")) return false;
        if (tagged) return true;
        int colon=id.indexOf(':');
        String path=colon<0?id:id.substring(colon+1);
        // Living village IDs also work for datapacks that omit the vanilla village tag.
        return path.startsWith("village_") || path.endsWith("_village")
                || path.startsWith("basic_village_") || path.startsWith("village/")
                || path.equals("village");
    }

    public static boolean densityEligible(long seed,String id,int chunkX,int chunkZ,double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier<1 || multiplier>8)
            throw new IllegalArgumentException("Invalid structure density multiplier");
        return PlacementGeometry.densityFraction(seed,SET_ID+"/density/"+id,chunkX,chunkZ)<1/(multiplier*multiplier);
    }

    public static Group group(String id, boolean epicLoaded) {
        if (id.startsWith("terrain-diffusion-mc:basic_village_") || id.startsWith("mmv:"))
            return Group.BASIC;
        if (id.startsWith("minecraft:village_") && !epicLoaded) return Group.BASIC;
        return Group.DISTINCTIVE;
    }

    /** Draw only after both groups have been assessed for local viability. */
    public static Group choose(long seed,int cellX,int cellZ,int basicPercent,boolean basic,boolean distinctive) {
        if (basicPercent<0 || basicPercent>100) throw new IllegalArgumentException("Invalid basic share");
        if (basicPercent==0) return distinctive ? Group.DISTINCTIVE : null;
        if (basicPercent==100) return basic ? Group.BASIC : null;
        if (!basic) return distinctive ? Group.DISTINCTIVE : null;
        if (!distinctive) return Group.BASIC;
        return PlacementGeometry.densityFraction(seed,SET_ID+"/group",cellX,cellZ)<basicPercent/100.0
                ? Group.BASIC : Group.DISTINCTIVE;
    }
}
