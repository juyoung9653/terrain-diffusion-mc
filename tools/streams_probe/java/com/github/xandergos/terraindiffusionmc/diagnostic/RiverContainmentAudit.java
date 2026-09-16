package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

/**
 * Opt-in, bounded evidence collector for water that reaches formerly dry grass.
 * This class only reads loaded full chunks; it never asks Minecraft to load or generate one.
 */
public final class RiverContainmentAudit {
    private static final long MAX_VOLUME = 4_000_000L;
    private static final int SAMPLE_LIMIT = 64;

    private RiverContainmentAudit() { }

    /**
     * Captures actual water and grass state in inclusive block bounds. Every intersecting chunk must already be loaded.
     *
     * @throws IllegalArgumentException for inverted, out-of-height, or over-cap bounds
     * @throws IllegalStateException if an intersecting chunk is absent (and therefore not known to be fully loaded)
     */
    public static Snapshot capture(ServerLevel level, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        Bounds bounds = Bounds.checked(level, minX, maxX, minY, maxY, minZ, maxZ);
        Map<Long, LevelChunk> chunks = requireLoadedChunks(level, bounds);
        Set<Long> water = new LinkedHashSet<>();
        Set<Long> dryGrass = new LinkedHashSet<>();
        Set<Long> wetGrass = new LinkedHashSet<>();
        Set<Long> waterOverGrass = new LinkedHashSet<>();
        int sourceWater = 0;
        int flowingWater = 0;

        for (long rawX = minX; rawX <= maxX; rawX++) {
            int x = (int) rawX;
            for (long rawZ = minZ; rawZ <= maxZ; rawZ++) {
                int z = (int) rawZ;
                LevelChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
                for (long rawY = minY; rawY <= maxY; rawY++) {
                    int y = (int) rawY;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    FluidState fluid = state.getFluidState();
                    long key = pos.asLong();
                    if (fluid.is(FluidTags.WATER)) {
                        water.add(key);
                        if (fluid.isSource()) sourceWater++; else flowingWater++;
                        if (y > level.getMinBuildHeight() && chunk.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK)) {
                            waterOverGrass.add(key);
                        }
                    }
                    if (state.is(Blocks.GRASS_BLOCK)) {
                        if (hasWaterAbove(level, chunks, x, y, z)) wetGrass.add(key); else dryGrass.add(key);
                    }
                }
            }
        }
        return new Snapshot(bounds, water, dryGrass, wetGrass, waterOverGrass, sourceWater, flowingWater);
    }

    /** Schedules one ordinary fluid tick for every captured cell which still contains actual water. */
    public static int scheduleWaterTicks(ServerLevel level, Snapshot snapshot) {
        int scheduled = 0;
        for (long key : snapshot.water) {
            BlockPos pos = BlockPos.of(key);
            LevelChunk chunk = requireLoadedChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
            FluidState fluid = chunk.getBlockState(pos).getFluidState();
            if (!fluid.is(FluidTags.WATER)) continue;
            level.scheduleTick(pos, fluid.getType(), 1);
            scheduled++;
        }
        return scheduled;
    }

    /** Compares two bounded captures and reports actual fluid movement plus wetting of originally dry grass. */
    public static Result compare(Snapshot before, Snapshot after) {
        if (!before.bounds.equals(after.bounds)) {
            throw new IllegalArgumentException("Snapshots must use identical bounds");
        }
        int added = differenceCount(after.water, before.water);
        int removed = differenceCount(before.water, after.water);
        Set<Long> newlyInundated = new LinkedHashSet<>();
        for (long grassKey : before.dryGrass) {
            BlockPos grass = BlockPos.of(grassKey);
            if (grass.getY() < before.bounds.maxY && after.water.contains(grass.above().asLong())) {
                newlyInundated.add(grassKey);
            }
        }
        return new Result(before.bounds, added, removed, newlyInundated.size(), before.wetGrass.size(),
                before.sourceWater, before.flowingWater, after.sourceWater, after.flowingWater,
                sampledCoordinates(newlyInundated));
    }

    /** Writes only aggregate evidence and bounded coordinate samples, never a per-cell dump. */
    public static void write(Path path, Snapshot snapshot) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, snapshot.toCompactJson(), StandardCharsets.UTF_8);
    }

    private static int differenceCount(Set<Long> left, Set<Long> right) {
        int count = 0;
        for (long key : left) if (!right.contains(key)) count++;
        return count;
    }

    /** Native plan evidence at every wet-grass cell, without interpreting grass replacement as containment. */
    public static int writePlans(ServerLevel level, Path path, Snapshot snapshot) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Object engine = worldgen.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
        if (engine == null) throw new IllegalStateException("Missing native engine");
        var plan = engine.getClass().getMethod("plan", int.class, int.class);
        int outside = 0;
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("x,grassY,z,inChannel,inCorridor,distance,width,waterSurfaceY,bedY,source,terminal\n");
            for (long key : snapshot.wetGrass) {
                BlockPos pos = BlockPos.of(key);
                Object column = plan.invoke(engine, pos.getX(), pos.getZ());
                if (!(Boolean) column.getClass().getMethod("inChannel").invoke(column)) outside++;
                writer.write(pos.getX()+","+pos.getY()+","+pos.getZ());
                for (String field : List.of("inChannel","inCorridor","distance","width","waterSurfaceY","bedY","source","terminal"))
                    writer.write(","+column.getClass().getMethod(field).invoke(column));
                writer.newLine();
            }
        }
        if (Boolean.getBoolean("td.streamsProbe.containmentDetails")) {
            Map<Long, Integer> wetColumns = new java.util.TreeMap<>();
            for (long key : snapshot.water) {
                BlockPos p = BlockPos.of(key);
                wetColumns.merge(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY(),Math::max);
            }
            try (var writer = Files.newBufferedWriter(path.resolveSibling(path.getFileName()+".footprint.csv"), StandardCharsets.UTF_8)) {
                writer.write("x,z,actualWaterY,inChannel,inCorridor,distance,width,waterSurfaceY\n");
                for (var entry : wetColumns.entrySet()) {
                    BlockPos p = BlockPos.of(entry.getKey());
                    Object c = plan.invoke(engine,p.getX(),p.getZ());
                    writer.write(p.getX()+","+p.getZ()+","+entry.getValue());
                    for (String field : List.of("inChannel","inCorridor","distance","width","waterSurfaceY"))
                        writer.write(","+c.getClass().getMethod(field).invoke(c));
                    writer.newLine();
                }
            }
            Set<Long> columns = new LinkedHashSet<>();
            if (Boolean.getBoolean("td.streamsProbe.containmentTraceAllColumns")) {
                for (int x=snapshot.bounds.minX;x<=snapshot.bounds.maxX;x++)
                    for (int z=snapshot.bounds.minZ;z<=snapshot.bounds.maxZ;z++) columns.add(BlockPos.asLong(x,0,z));
            }
            for (long key : snapshot.wetGrass) {
                BlockPos p = BlockPos.of(key);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    int x = p.getX()+dx, z = p.getZ()+dz;
                    if (x >= snapshot.bounds.minX && x <= snapshot.bounds.maxX && z >= snapshot.bounds.minZ && z <= snapshot.bounds.maxZ)
                        columns.add(BlockPos.asLong(x, 0, z));
                }
            }
            try (var writer = Files.newBufferedWriter(path.resolveSibling(path.getFileName()+".neighbors.csv"), StandardCharsets.UTF_8)) {
                writer.write("x,z,inChannel,inCorridor,distance,width,waterSurfaceY,source,terminal,lakeNearChunk,below,at,above,above2\n");
                for (long key : columns) {
                    BlockPos p = BlockPos.of(key);
                    Object c = plan.invoke(engine, p.getX(), p.getZ());
                    int y = ((Number)c.getClass().getMethod("waterSurfaceY").invoke(c)).intValue();
                    writer.write(p.getX()+","+p.getZ());
                    for (String field : List.of("inChannel","inCorridor","distance","width","waterSurfaceY","source","terminal"))
                        writer.write(","+c.getClass().getMethod(field).invoke(c));
                    writer.write(","+engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class).invoke(engine, p.getX() & ~15, p.getZ() & ~15, 8));
                    for (int dy = -1; dy <= 2; dy++) {
                        var state = requireLoadedChunk(level,p.getX()>>4,p.getZ()>>4).getBlockState(new BlockPos(p.getX(),y+dy,p.getZ()));
                        writer.write(",\""+state.toString().replace("\"","\"\"")+"\"");
                    }
                    writer.newLine();
                }
            }
        }
        return outside;
    }

    private static Map<Long, LevelChunk> requireLoadedChunks(ServerLevel level, Bounds bounds) {
        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (long rawChunkX = bounds.minX >> 4; rawChunkX <= (bounds.maxX >> 4); rawChunkX++) {
            for (long rawChunkZ = bounds.minZ >> 4; rawChunkZ <= (bounds.maxZ >> 4); rawChunkZ++) {
                int chunkX = (int) rawChunkX;
                int chunkZ = (int) rawChunkZ;
                chunks.put(ChunkPos.asLong(chunkX, chunkZ), requireLoadedChunk(level, chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private static LevelChunk requireLoadedChunk(ServerLevel level, int chunkX, int chunkZ) {
        // getChunkNow only returns a currently loaded LevelChunk; it does not create or generate one.
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) throw new IllegalStateException("Containment audit requires loaded full chunk " + chunkX + "," + chunkZ);
        return chunk;
    }

    private static boolean hasWaterAbove(ServerLevel level, Map<Long, LevelChunk> chunks, int x, int y, int z) {
        if (y >= level.getMaxBuildHeight() - 1) return false;
        LevelChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk == null) chunk = requireLoadedChunk(level, x >> 4, z >> 4);
        return chunk.getBlockState(new BlockPos(x, y + 1, z)).getFluidState().is(FluidTags.WATER);
    }

    private static List<String> sampledCoordinates(Set<Long> positions) {
        List<String> sample = new ArrayList<>(Math.min(SAMPLE_LIMIT, positions.size()));
        for (long key : positions) {
            if (sample.size() == SAMPLE_LIMIT) break;
            BlockPos pos = BlockPos.of(key);
            sample.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        return List.copyOf(sample);
    }

    public static final class Snapshot {
        private final Bounds bounds;
        private final Set<Long> water;
        private final Set<Long> dryGrass;
        private final Set<Long> wetGrass;
        private final Set<Long> waterOverGrass;
        private final int sourceWater;
        private final int flowingWater;

        private Snapshot(Bounds bounds, Set<Long> water, Set<Long> dryGrass, Set<Long> wetGrass, Set<Long> waterOverGrass,
                         int sourceWater, int flowingWater) {
            this.bounds = bounds;
            this.water = immutableCopy(water);
            this.dryGrass = immutableCopy(dryGrass);
            this.wetGrass = immutableCopy(wetGrass);
            this.waterOverGrass = immutableCopy(waterOverGrass);
            this.sourceWater = sourceWater;
            this.flowingWater = flowingWater;
        }

        public int waterCount() { return water.size(); }
        public int sourceWaterCount() { return sourceWater; }
        public int flowingWaterCount() { return flowingWater; }
        public int dryGrassCount() { return dryGrass.size(); }
        public int wetGrassCount() { return wetGrass.size(); }
        public int waterOverGrassCount() { return waterOverGrass.size(); }
        public Set<Long> waterPositions() { return water; }
        public Set<Long> dryGrassPositions() { return dryGrass; }
        public Set<Long> wetGrassPositions() { return wetGrass; }
        public Set<Long> waterOverGrassPositions() { return waterOverGrass; }
        public String toCompactJson() {
            return "{\"bounds\":" + bounds.json() + ",\"water\":{\"total\":" + water.size()
                    + ",\"source\":" + sourceWater + ",\"flowing\":" + flowingWater + "}"
                    + ",\"grass\":{\"dry\":" + dryGrass.size() + ",\"wet\":" + wetGrass.size()
                    + ",\"waterOverGrass\":" + waterOverGrass.size() + "}"
                    + ",\"samples\":{\"water\":" + jsonArray(sampledCoordinates(water))
                    + ",\"dryGrass\":" + jsonArray(sampledCoordinates(dryGrass))
                    + ",\"wetGrass\":" + jsonArray(sampledCoordinates(wetGrass)) + "}}\n";
        }
    }

    public record Result(Bounds bounds, int addedWater, int removedWater, int newlyInundatedOriginalDryGrass,
                         int preexistingWetGrass, int beforeSourceWater, int beforeFlowingWater,
                         int afterSourceWater, int afterFlowingWater, List<String> violatingCoordinates) {
        public String toJson() {
            return "{\"bounds\":" + bounds.json() + ",\"water\":{\"added\":" + addedWater
                    + ",\"removed\":" + removedWater + ",\"before\":{\"source\":" + beforeSourceWater
                    + ",\"flowing\":" + beforeFlowingWater + "},\"after\":{\"source\":" + afterSourceWater
                    + ",\"flowing\":" + afterFlowingWater + "}},\"grass\":{\"newlyInundatedOriginalDry\":"
                    + newlyInundatedOriginalDryGrass + ",\"preexistingWet\":" + preexistingWetGrass
                    + "},\"violatingCoordinates\":" + jsonArray(violatingCoordinates) + "}";
        }
    }

    public record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private static Bounds checked(ServerLevel level, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Containment audit bounds are inverted");
            if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight()) {
                throw new IllegalArgumentException("Containment audit Y bounds must be within world build height");
            }
            long xSpan = span(minX, maxX);
            long ySpan = span(minY, maxY);
            long zSpan = span(minZ, maxZ);
            if (xSpan > MAX_VOLUME / ySpan || xSpan * ySpan > MAX_VOLUME / zSpan) {
                throw new IllegalArgumentException("Containment audit volume exceeds " + MAX_VOLUME);
            }
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private static long span(int min, int max) { return ((long) max - min) + 1L; }
        private String json() {
            return "{\"minX\":" + minX + ",\"maxX\":" + maxX + ",\"minY\":" + minY
                    + ",\"maxY\":" + maxY + ",\"minZ\":" + minZ + ",\"maxZ\":" + maxZ + "}";
        }
    }

    private static Set<Long> immutableCopy(Set<Long> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(values.get(i)).append('"');
        }
        return json.append(']').toString();
    }
}
