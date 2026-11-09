package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Correlates Streams' immutable column plans with fully generated river-bed
 * columns.  This deliberately does not infer a plan from the final blocks:
 * native carving is allowed to add depth below {@code ColumnPlan.bedY()}.
 */
public final class RiverBedAppearanceAudit {
    private static final int[][] REPORTED = {
            {-58, 1692}, {-53, 1692}, {-58, 1693}, {-62, 1696},
            {-56, 1696}, {-59, 1697}, {-57, 1697}, {-62, 1698}
    };
    private static final long TIMEOUT_SECONDS = 600L;

    private RiverBedAppearanceAudit() { }

    static List<ChunkPos> prepare(ServerLevel level) {
        assertFixture(level);
        return generateWithHalo(level, Bounds.fromProperties()).values().stream()
                .map(LevelChunk::getPos).toList();
    }

    /**
     * Generates only the bounded diagnostic rectangle and its one-block halo,
     * then reads plans and blocks on the server thread.  The caller may be a
     * probe worker; no player world is selected by this method.
     */
    public static String run(ServerLevel level) {
        try {
            Bounds bounds = Bounds.fromProperties();
            return onServer(level, () -> inspect(level, bounds));
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger("TerrainDiffusion/RiverBedAudit")
                    .error("River bed appearance audit failed", failure);
            throw new IllegalStateException("River bed appearance audit failed", failure);
        }
    }

    private static String inspect(ServerLevel level, Bounds bounds) throws Exception {
        assertFixture(level);
        Map<Long, LevelChunk> chunks = generateWithHalo(level, bounds);
        int generated = chunks.size();
        PlanAccess plans = PlanAccess.open(level);
        List<Column> columns = new ArrayList<>(bounds.area());
        for (int z = bounds.minZ; z <= bounds.maxZ; z++) for (int x = bounds.minX; x <= bounds.maxX; x++)
            columns.add(read(level, plans, chunks, x, z));

        int isolated = 0, wet = 0, solidBeds = 0;
        for (Column column : columns) {
            if (column.waterSurfaceY != Integer.MIN_VALUE) wet++;
            if (column.solidBed) solidBeds++;
            column.isolatedPhysicalDip = isolatedPhysicalDip(level, chunks, column);
            if (column.isolatedPhysicalDip) isolated++;
        }
        List<Column> reported = new ArrayList<>();
        for (int[] point : REPORTED) {
            Column column = read(level, plans, chunks, point[0], point[1]);
            column.isolatedPhysicalDip = isolatedPhysicalDip(level, chunks, column);
            reported.add(column);
        }

        Path output = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("td-river-bed-appearance.csv");
        writeCsv(output, columns, reported);
        boolean covered = generated > 0 && columns.size() == bounds.area();
        boolean passed = covered && wet > 0 && solidBeds > 0;
        if (Boolean.getBoolean("td.streamsProbe.requireSmoothBed") && isolated != 0) passed = false;
        if (!passed) throw new IllegalStateException("river bed audit incomplete: generated=" + generated
                + " columns=" + columns.size() + " wet=" + wet + " solidBeds=" + solidBeds
                + " isolatedPhysicalDips=" + isolated);

        JsonObject receipt = new JsonObject();
        receipt.addProperty("event", "river_bed_appearance_audit");
        receipt.addProperty("status", "passed"); receipt.addProperty("passed", true);
        receipt.addProperty("seed", level.getSeed());
        receipt.addProperty("bounds", bounds.toString()); receipt.addProperty("fullChunks", generated);
        receipt.addProperty("columns", columns.size()); receipt.addProperty("waterColumns", wet);
        receipt.addProperty("supportedWaterColumns", solidBeds); receipt.addProperty("isolatedOneCellDips", isolated);
        receipt.addProperty("requireSmoothBed", Boolean.getBoolean("td.streamsProbe.requireSmoothBed"));
        receipt.addProperty("csvFile", output.getFileName().toString());
        JsonArray known = new JsonArray();
        for (Column column : reported) known.add(column.json(plans));
        receipt.add("reportedColumns", known);
        return receipt.toString();
    }

    private static void assertFixture(ServerLevel level) {
        long expectedSeed = Long.getLong("td.streamsProbe.riverBedExpectedSeed", -8990126270512117809L);
        int expectedScale = Integer.getInteger("td.streamsProbe.riverBedExpectedScale", 1);
        int expectedProfile = Integer.getInteger("td.streamsProbe.riverBedExpectedNaturalProfile", 12);
        if (level.getSeed() != expectedSeed) throw new IllegalStateException("expected seed " + expectedSeed
                + ", found " + level.getSeed());
        if (WorldScaleManager.getCurrentScale() != expectedScale) throw new IllegalStateException("expected scale "
                + expectedScale + ", found " + WorldScaleManager.getCurrentScale());
        if (WorldNaturalRiverState.version(level) != expectedProfile) throw new IllegalStateException(
                "expected natural river profile " + expectedProfile + ", found " + WorldNaturalRiverState.version(level));
    }

    /** Every sampled column and each cardinal neighbour is already FULL before a read. */
    private static Map<Long, LevelChunk> generateWithHalo(ServerLevel level, Bounds bounds) {
        int minChunkX = Math.floorDiv(bounds.minX - 1, 16), maxChunkX = Math.floorDiv(bounds.maxX + 1, 16);
        int minChunkZ = Math.floorDiv(bounds.minZ - 1, 16), maxChunkZ = Math.floorDiv(bounds.maxZ + 1, 16);
        Map<Long, LevelChunk> generated = new HashMap<>();
        List<ChunkPos> order = new ArrayList<>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            order.add(new ChunkPos(chunkX, chunkZ));
        }
        if (Boolean.getBoolean("td.streamsProbe.appearanceReverseOrder")) java.util.Collections.reverse(order);
        for (ChunkPos requested : order) {
            LevelChunk chunk = level.getChunk(requested.x, requested.z);
            if (!chunk.getPos().equals(requested))
                throw new IllegalStateException("wrong generated chunk " + chunk.getPos());
            generated.put(requested.toLong(), chunk);
        }
        return generated;
    }

    private static Column read(ServerLevel level, PlanAccess plans, Map<Long, LevelChunk> chunks, int x, int z) throws Exception {
        Object plan = plans.plan.invoke(plans.engine, x, z);
        Column result = new Column(x, z);
        result.inChannel = (Boolean) plans.inChannel.invoke(plan);
        result.inCorridor = (Boolean) plans.inCorridor.invoke(plan);
        result.planWaterY = ((Number) plans.waterSurfaceY.invoke(plan)).intValue();
        result.planBedY = ((Number) plans.bedY.invoke(plan)).intValue();
        result.source = (Boolean) plans.source.invoke(plan);
        result.terminal = (Boolean) plans.terminal.invoke(plan);
        LevelChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk == null) throw new IllegalStateException("generated chunk was unloaded at " + x + "," + z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            pos.set(x, y, z);
            if (chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) {
                result.waterSurfaceY = y;
                break;
            }
        }
        if (result.waterSurfaceY == Integer.MIN_VALUE) return result;
        int y = result.waterSurfaceY;
        while (y >= level.getMinBuildHeight()) {
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) break;
            result.waterLevels.add(y--);
        }
        result.solidBedY = y;
        if (y >= level.getMinBuildHeight()) {
            pos.set(x, y, z);
            var state = chunk.getBlockState(pos);
            result.solidBed = !state.canBeReplaced() && state.getFluidState().isEmpty();
            result.solidBedBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }
        return result;
    }

    private static boolean isolatedPhysicalDip(ServerLevel level, Map<Long, LevelChunk> chunks, Column center) {
        if (!center.solidBed || center.waterLevels.size() != 2) return false;
        for (int[] delta : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            try {
                // The chunk halo makes this a read-only neighbour sample, not a generation request.
                Column neighbour = physical(level, chunks, center.x + delta[0], center.z + delta[1]);
                if (!neighbour.solidBed || neighbour.waterLevels.size() != 1
                        || neighbour.waterSurfaceY != center.waterSurfaceY) return false;
            } catch (Exception failure) {
                throw new IllegalStateException("cannot read river-bed neighbour", failure);
            }
        }
        return true;
    }

    private static Column physical(ServerLevel level, Map<Long, LevelChunk> chunks, int x, int z) {
        Column result = new Column(x, z);
        LevelChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk == null) throw new IllegalStateException("missing halo chunk at " + x + "," + z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            pos.set(x, y, z);
            if (chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) { result.waterSurfaceY = y; break; }
        }
        if (result.waterSurfaceY == Integer.MIN_VALUE) return result;
        int y = result.waterSurfaceY;
        while (y >= level.getMinBuildHeight()) {
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) break;
            result.waterLevels.add(y--);
        }
        result.solidBedY = y;
        if (y >= level.getMinBuildHeight()) {
            pos.set(x, y, z);
            var state = chunk.getBlockState(pos);
            result.solidBed = !state.canBeReplaced() && state.getFluidState().isEmpty();
            result.solidBedBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }
        return result;
    }

    private static void writeCsv(Path output, List<Column> columns, List<Column> reported) throws Exception {
        StringBuilder csv = new StringBuilder("kind,x,z,inChannel,inCorridor,planWaterY,planBedY,source,terminal,actualWaterSurfaceY,actualWaterLevels,solidBedY,solidBed,solidBedBlock,isolatedPhysicalOneBlockDip\n");
        for (Column column : columns) csv.append("area,").append(column.csv()).append('\n');
        for (Column column : reported) csv.append("reported,").append(column.csv()).append('\n');
        Files.writeString(output, csv, StandardCharsets.UTF_8);
    }

    private static <T> T onServer(ServerLevel level, Callable<T> work) throws Exception {
        if (level.getServer().isSameThread()) return work.call();
        return level.getServer().submit(() -> {
            try { return work.call(); }
            catch (Exception failure) { throw new IllegalStateException("River bed audit task failed", failure); }
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
        static Bounds fromProperties() {
            int minX = Integer.getInteger("td.streamsProbe.riverBedMinX", -80);
            int maxX = Integer.getInteger("td.streamsProbe.riverBedMaxX", -33);
            int minZ = Integer.getInteger("td.streamsProbe.riverBedMinZ", 1680);
            int maxZ = Integer.getInteger("td.streamsProbe.riverBedMaxZ", 1727);
            if (maxX < minX || maxZ < minZ || (long) (maxX - minX + 1) * (maxZ - minZ + 1) > 65_536L)
                throw new IllegalArgumentException("invalid bounded river-bed area");
            return new Bounds(minX, maxX, minZ, maxZ);
        }
        int area() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
        @Override public String toString() { return minX + "," + minZ + ".." + maxX + "," + maxZ; }
    }

    private static final class PlanAccess {
        final Object engine; final Method plan;
        final Field inChannel, inCorridor, waterSurfaceY, bedY, source, terminal;
        private PlanAccess(Object engine, Method plan, Class<?> type) throws Exception {
            this.engine = engine; this.plan = plan;
            inChannel = new Field("inChannel"); inCorridor = new Field("inCorridor");
            waterSurfaceY = new Field("waterSurfaceY"); bedY = new Field("bedY");
            source = new Field("source"); terminal = new Field("terminal");
        }
        static PlanAccess open(ServerLevel level) throws Exception {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Class<?> type = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            return new PlanAccess(engine, engine.getClass().getMethod("plan", int.class, int.class), type);
        }
    }

    private record Field(String name) {
        Object invoke(Object plan) { return StreamsRecordAccess.read(plan, name); }
    }

    private static final class Column {
        final int x, z; boolean inChannel, inCorridor, source, terminal, solidBed, isolatedPhysicalDip;
        int planWaterY, planBedY, waterSurfaceY = Integer.MIN_VALUE, solidBedY = Integer.MIN_VALUE;
        String solidBedBlock = "none"; final List<Integer> waterLevels = new ArrayList<>();
        Column(int x, int z) { this.x = x; this.z = z; }
        String csv() { return x + "," + z + "," + inChannel + "," + inCorridor + "," + planWaterY + "," + planBedY
                + "," + source + "," + terminal + "," + waterSurfaceY + ",\"" + waterLevels + "\"," + solidBedY
                + "," + solidBed + "," + solidBedBlock + "," + isolatedPhysicalDip; }
        JsonObject json(PlanAccess plans) throws Exception {
            JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("z", z);
            result.addProperty("inChannel", inChannel); result.addProperty("inCorridor", inCorridor);
            result.addProperty("planWaterY", planWaterY); result.addProperty("planBedY", planBedY);
            result.addProperty("source", source); result.addProperty("terminal", terminal);
            result.addProperty("actualWaterSurfaceY", waterSurfaceY); result.addProperty("solidBedY", solidBedY);
            result.addProperty("solidBed", solidBed); result.addProperty("solidBedBlock", solidBedBlock);
            JsonArray levels = new JsonArray(); for (int y : waterLevels) levels.add(y); result.add("actualWaterLevels", levels);
            result.addProperty("isolatedPhysicalOneBlockDip", isolatedPhysicalDip);
            JsonArray neighbours = new JsonArray();
            for (int[] delta : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                Object neighbour = plans.plan.invoke(plans.engine, x + delta[0], z + delta[1]);
                JsonObject plan = new JsonObject(); plan.addProperty("x", x + delta[0]); plan.addProperty("z", z + delta[1]);
                plan.addProperty("inChannel", (Boolean) plans.inChannel.invoke(neighbour));
                plan.addProperty("inCorridor", (Boolean) plans.inCorridor.invoke(neighbour));
                plan.addProperty("waterSurfaceY", ((Number) plans.waterSurfaceY.invoke(neighbour)).intValue());
                plan.addProperty("bedY", ((Number) plans.bedY.invoke(neighbour)).intValue());
                plan.addProperty("source", (Boolean) plans.source.invoke(neighbour));
                plan.addProperty("terminal", (Boolean) plans.terminal.invoke(neighbour)); neighbours.add(plan);
            }
            result.add("cardinalPlans", neighbours); return result;
        }
    }
}
