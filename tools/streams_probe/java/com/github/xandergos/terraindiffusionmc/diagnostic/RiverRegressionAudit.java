package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Exact post-settlement receipt for the two river defects captured in the
 * November 15 screenshots.  The checks deliberately compare loaded blocks to
 * Streams' immutable native plan, instead of trying to reconstruct a channel
 * from the final terrain.
 */
final class RiverRegressionAudit {
    private static final long EXPECTED_SEED = 7756831701670037456L;
    private static final int EXPECTED_SCALE = 1;
    private static final int EXPECTED_PROFILE = 14;
    private static final int BANK_X = -1009, BANK_Z = 971;
    private static final int BED_X = -1106, BED_Z = 1156;
    private static final int[][] BANK_CHANNEL_SUPPORTS = {{-1009, 973}, {-1009, 974}};
    private static final Set<Long> REQUIRED_CHUNKS = Set.of(
            ChunkPos.asLong(BANK_X >> 4, BANK_Z >> 4), ChunkPos.asLong(BED_X >> 4, BED_Z >> 4));

    private RiverRegressionAudit() { }

    static String run(ServerLevel level, List<ChunkPos> selectedChunks, long settledTicks) {
        if (settledTicks < 100) throw new IllegalArgumentException("river regression audit requires 100 settled ticks");
        assertFixture(level);
        Set<Long> selected = new HashSet<>();
        for (ChunkPos chunk : selectedChunks) selected.add(chunk.toLong());
        if (!selected.containsAll(REQUIRED_CHUNKS))
            throw new IllegalStateException("river regression audit missing required full chunks " + requiredChunks());
        for (long chunk : REQUIRED_CHUNKS) {
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            if (level.getChunkSource().getChunkNow(x, z) == null)
                throw new IllegalStateException("river regression audit selected chunk is not loaded " + x + "," + z);
        }

        try {
            PlanAccess plans = PlanAccess.open(level);
            Bank bank = inspectBank(level, plans);
            JsonArray supports = new JsonArray();
            boolean supportsPassed = true;
            for (int[] point : BANK_CHANNEL_SUPPORTS) {
                WaterColumn support = inspectWaterColumn(level, plans, point[0], point[1], false);
                supports.add(support.json());
                supportsPassed &= support.passed;
            }
            WaterColumn shallowBed = inspectWaterColumn(level, plans, BED_X, BED_Z, true);
            boolean passed = bank.passed && supportsPassed && shallowBed.passed;
            if (!passed) throw new IllegalStateException("river regression audit failed bank=" + bank.json()
                    + " supports=" + supports + " shallowBed=" + shallowBed.json());

            JsonObject receipt = new JsonObject();
            receipt.addProperty("event", "river_regression_audit");
            receipt.addProperty("status", "passed");
            receipt.addProperty("phase", "post_native_water_settlement");
            receipt.addProperty("settledTicks", settledTicks);
            receipt.addProperty("seed", level.getSeed());
            receipt.addProperty("scale", WorldScaleManager.getCurrentScale());
            receipt.addProperty("naturalRiverProfile", WorldNaturalRiverState.version(level));
            JsonArray covered = new JsonArray();
            for (long chunk : REQUIRED_CHUNKS) covered.add(ChunkPos.getX(chunk) + "," + ChunkPos.getZ(chunk));
            receipt.add("coveredChunks", covered);
            receipt.add("bank", bank.json());
            receipt.add("bankChannelSupports", supports);
            receipt.add("shallowBed", shallowBed.json());
            return receipt.toString();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot inspect Streams native plan", error);
        }
    }

    private static Bank inspectBank(ServerLevel level, PlanAccess plans) throws ReflectiveOperationException {
        Plan plan = plans.read(BANK_X, BANK_Z);
        int originalGroundY = level.getChunkSource().getGenerator().getBaseHeight(BANK_X, BANK_Z,
                Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState()) - 1;
        int capY = originalGroundY + 1;
        BlockState originalGround = state(level, BANK_X, originalGroundY, BANK_Z);
        BlockState cap = state(level, BANK_X, capY, BANK_Z);
        BlockState aboveCap = state(level, BANK_X, capY + 1, BANK_Z);
        var generator = level.getChunkSource().getGenerator();
        // OCEAN_FLOOR_WG is the first-air boundary, which is also the native
        // source-biome sampling convention used by the surface audit.  This is
        // distinct from the saved river overlay at the solid ground block.
        ResourceLocation sourceBiome = generator.getBiomeSource().getNoiseBiome(BANK_X >> 2,
                RiverRegressionContract.sourceBiomeQuartY(originalGroundY),
                BANK_Z >> 2, level.getChunkSource().randomState().sampler()).unwrapKey()
                .map(ResourceKey::location).orElse(null);
        ResourceLocation savedBiome = level.getBiome(new BlockPos(BANK_X, originalGroundY, BANK_Z)).unwrapKey()
                .map(ResourceKey::location).orElse(null);
        var ecology = NaturalEcology.surfaceSample(generator, BANK_X, BANK_Z);
        int ecologyBiomeId = ecology == null ? -1 : ecology.climate().biomeId();
        int desertEcologyId = RiverRegressionContract.desertEcologyId();
        boolean desert = RiverRegressionContract.isDesertSourceAndEcology(
                sourceBiome == null ? "" : sourceBiome.toString(), ecologyBiomeId);
        boolean originalAlreadyContainsWater = originalGroundY >= plan.waterY;
        boolean sandSurface = originalGround.is(Blocks.SAND);
        // Cactus/dead bush are intentional sparse desert decorations.  Only a
        // non-fluid solid cap is a terrain raise, and fern is explicitly invalid.
        boolean noSolidCap = !cap.isSolid() && cap.getFluidState().isEmpty();
        boolean noFern = !cap.is(Blocks.FERN) && !aboveCap.is(Blocks.FERN);
        boolean passed = desert && originalAlreadyContainsWater && sandSurface && noSolidCap && noFern;
        return new Bank(originalGroundY, capY, plan.waterY, desert, sourceBiome, savedBiome, ecologyBiomeId,
                desertEcologyId, originalAlreadyContainsWater,
                sandSurface, noSolidCap, noFern, blockName(originalGround), blockName(cap), blockName(aboveCap), passed);
    }

    private static WaterColumn inspectWaterColumn(ServerLevel level, PlanAccess plans, int x, int z, boolean requireShallow)
            throws ReflectiveOperationException {
        Plan plan = plans.read(x, z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) throw new IllegalStateException("unloaded selected audit column " + x + "," + z);
        int waterY = Integer.MIN_VALUE;
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            if (chunk.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER)) { waterY = y; break; }
        }
        int bedY = Integer.MIN_VALUE;
        BlockState bed = Blocks.AIR.defaultBlockState();
        if (waterY != Integer.MIN_VALUE) {
            bedY = waterY;
            while (bedY >= level.getMinBuildHeight()
                    && chunk.getBlockState(new BlockPos(x, bedY, z)).getFluidState().is(FluidTags.WATER)) bedY--;
            if (bedY >= level.getMinBuildHeight()) bed = chunk.getBlockState(new BlockPos(x, bedY, z));
        }
        boolean solidBed = bedY != Integer.MIN_VALUE && !bed.canBeReplaced() && bed.getFluidState().isEmpty();
        boolean plannedShallowNonEndpoint = plan.inChannel && !plan.source && !plan.terminal
                && plan.waterY - plan.bedY == 1;
        boolean actualMatchesPlan = waterY == plan.waterY && bedY == plan.bedY;
        boolean passed = plan.inChannel && actualMatchesPlan && solidBed
                && (!requireShallow || plannedShallowNonEndpoint);
        return new WaterColumn(x, z, plan, waterY, bedY, solidBed, blockName(bed),
                plannedShallowNonEndpoint, actualMatchesPlan, passed);
    }

    private static BlockState state(ServerLevel level, int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) throw new IllegalStateException("unloaded selected audit column " + x + "," + z);
        return chunk.getBlockState(new BlockPos(x, y, z));
    }

    private static String blockName(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

    private static void assertFixture(ServerLevel level) {
        if (level.getSeed() != EXPECTED_SEED) throw new IllegalStateException("expected seed " + EXPECTED_SEED + ", found " + level.getSeed());
        if (WorldScaleManager.getCurrentScale() != EXPECTED_SCALE) throw new IllegalStateException("expected scale 1");
        if (!WorldNaturalRiverState.enabled(level) || WorldNaturalRiverState.version(level) != EXPECTED_PROFILE)
            throw new IllegalStateException("expected enabled natural river profile 14");
    }

    private static String requiredChunks() {
        return REQUIRED_CHUNKS.stream().map(value -> ChunkPos.getX(value) + "," + ChunkPos.getZ(value)).toList().toString();
    }

    private record Plan(boolean inChannel, boolean source, boolean terminal, int waterY, int bedY) { }
    private record PlanAccess(Object engine, Method plan) {
        static PlanAccess open(ServerLevel level) throws ReflectiveOperationException {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            return new PlanAccess(engine, engine.getClass().getMethod("plan", int.class, int.class));
        }
        Plan read(int x, int z) throws ReflectiveOperationException {
            Object value = plan.invoke(engine, x, z);
            return new Plan((Boolean) StreamsRecordAccess.read(value, "inChannel"),
                    (Boolean) StreamsRecordAccess.read(value, "source"),
                    (Boolean) StreamsRecordAccess.read(value, "terminal"),
                    (int) StreamsRecordAccess.number(value, "waterSurfaceY"),
                    (int) StreamsRecordAccess.number(value, "bedY"));
        }
    }
    private record Bank(int originalGroundY, int capY, int planWaterY, boolean desertBiome,
                        ResourceLocation sourceBiome, ResourceLocation savedBiome, int ecologyBiomeId, int desertEcologyId,
                        boolean originalGroundContainsPlanWater, boolean sandSurface, boolean noSolidCap,
                        boolean noFern, String originalGroundBlock, String capBlock, String aboveCapBlock, boolean passed) {
        JsonObject json() { JsonObject o = new JsonObject(); o.addProperty("x", BANK_X); o.addProperty("z", BANK_Z);
            o.addProperty("originalGroundY", originalGroundY); o.addProperty("capY", capY); o.addProperty("planWaterY", planWaterY);
            o.addProperty("desertBiome", desertBiome); o.addProperty("originalGroundContainsPlanWater", originalGroundContainsPlanWater);
            o.addProperty("sourceBiome", sourceBiome == null ? "unknown" : sourceBiome.toString());
            o.addProperty("savedBiome", savedBiome == null ? "unknown" : savedBiome.toString());
            o.addProperty("ecologyBiomeId", ecologyBiomeId); o.addProperty("desertEcologyId", desertEcologyId);
            o.addProperty("sandSurface", sandSurface); o.addProperty("noSolidCap", noSolidCap); o.addProperty("noFern", noFern);
            o.addProperty("originalGroundBlock", originalGroundBlock); o.addProperty("capBlock", capBlock); o.addProperty("aboveCapBlock", aboveCapBlock); o.addProperty("passed", passed); return o; }
    }
    private record WaterColumn(int x, int z, Plan plan, int actualWaterSurfaceY, int actualBedY, boolean solidBed,
                               String bedBlock, boolean plannedShallowNonEndpoint, boolean actualMatchesPlan, boolean passed) {
        JsonObject json() { JsonObject o = new JsonObject(); o.addProperty("x", x); o.addProperty("z", z);
            o.addProperty("inChannel", plan.inChannel); o.addProperty("source", plan.source); o.addProperty("terminal", plan.terminal);
            o.addProperty("planWaterY", plan.waterY); o.addProperty("planBedY", plan.bedY); o.addProperty("actualWaterSurfaceY", actualWaterSurfaceY);
            o.addProperty("actualBedY", actualBedY); o.addProperty("solidBed", solidBed); o.addProperty("bedBlock", bedBlock);
            o.addProperty("oneBlockPlanned", plannedShallowNonEndpoint); o.addProperty("actualMatchesPlan", actualMatchesPlan); o.addProperty("passed", passed); return o; }
    }
}
