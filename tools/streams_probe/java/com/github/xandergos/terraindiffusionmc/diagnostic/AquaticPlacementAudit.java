package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.ecology.SubmergedPlantPolicy;
import com.mojang.serialization.Codec;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.WorldGenLevel;

/** Read-only native receipt: transformed hooks, worldgen counts, and policy sample. */
public final class AquaticPlacementAudit {
    private static final ProbabilityFeatureConfiguration HALF_TALL = new ProbabilityFeatureConfiguration(.5F);
    private AquaticPlacementAudit() { }

    public static String run(ServerLevel level) {
        boolean naturalTerrain = level.getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source && source.naturalBiomes();
        boolean vanillaHook = hasMixinMethod(SeagrassFeature.class, "terrainDiffusion$limitGeneratedSeagrass");
        boolean streamsPresent = false;
        boolean streamsHook = false;
        try {
            Class<?> streams = Class.forName("dev.streamsreflowing.worldgen.StreamVegetationFeature",
                    false, AquaticPlacementAudit.class.getClassLoader());
            streamsPresent = true;
            streamsHook = hasMixinMethod(streams, "terrainDiffusion$limitTallSeagrass")
                    && hasMixinMethod(streams, "terrainDiffusion$limitShortSeagrass");
        } catch (ClassNotFoundException absent) {
            streamsPresent = false;
        }
        var counts = SubmergedPlantPlacementGuard.counts();
        boolean nonTerrainUnchanged = SubmergedPlantPolicy.allows(false, false,
                true, false, false, 0, level.getSeed(), 0, 0);
        double dryBeach = SubmergedPlantPolicy.probability(true, false, true, false,
                true, 0, level.getSeed(), 0, 0);
        double marineShallow = SubmergedPlantPolicy.probability(true, true, true, false, true,
                3, level.getSeed(), 0, 0);
        double freshShallow = SubmergedPlantPolicy.probability(true, true, false, true, true,
                3, level.getSeed(), 0, 0);
        boolean policyGuards = nonTerrainUnchanged && dryBeach == 0
                && marineShallow > 0 && marineShallow < 1
                && freshShallow > marineShallow && freshShallow < 1;
        long candidateCount = counts.allowed() + counts.rejected() + counts.dry();
        boolean hooksAndPolicy = naturalTerrain && vanillaHook
                && (!streamsPresent || streamsHook) && policyGuards;
        String status = !hooksAndPolicy ? "failed" : candidateCount == 0 ? "inconclusive" : "passed";
        return "{\"event\":\"aquatic_placement_audit\",\"status\":\"" + status
                + "\",\"naturalTerrain\":" + naturalTerrain
                + ",\"vanillaHook\":" + vanillaHook
                + ",\"streamsPresent\":" + streamsPresent
                + ",\"streamsHook\":" + streamsHook
                + ",\"policyGuards\":" + policyGuards
                + ",\"nonTerrainUnchanged\":" + nonTerrainUnchanged
                + ",\"dryBeachProbability\":" + dryBeach
                + ",\"marineShallowProbability\":" + marineShallow
                + ",\"freshShallowProbability\":" + freshShallow
                + ",\"nonNaturalTerrainCandidates\":" + counts.nonNaturalTerrain()
                + ",\"naturalCandidates\":" + candidateCount
                + ",\"allowed\":" + counts.allowed()
                + ",\"rejected\":" + counts.rejected()
                + ",\"dry\":" + counts.dry() + "}";
    }

    private static boolean hasMixinMethod(Class<?> target, String name) {
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().contains(name)) return true;
        }
        return false;
    }

    /**
     * Mutates and restores two sky columns in an isolated, already-loaded test chunk.
     * The caller must own the fixture world and invoke this on the server thread.
     */
    public static String runFixture(ServerLevel level, int chunkX, int chunkZ) {
        if (!(level.getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source) || !source.naturalBiomes()) {
            throw new IllegalArgumentException("fixture requires natural Terrain Diffusion world");
        }
        if (!level.hasChunk(chunkX, chunkZ)) throw new IllegalArgumentException("fixture chunk not loaded");
        int y = level.getMaxBuildHeight() - 16;
        BlockPos accepted = findSite(level, chunkX, chunkZ, y, true, null);
        BlockPos rejected = findSite(level, chunkX, chunkZ, y, false, accepted);
        if (accepted == null || rejected == null) {
            return "{\"event\":\"aquatic_placement_fixture\",\"status\":\"inconclusive\","
                    + "\"reason\":\"no safe accepted and rejected sky columns\"}";
        }
        long shortSeed = seedFor(false);
        long tallSeed = seedFor(true);
        boolean acceptedShort = tryVanilla(level, accepted, shortSeed, false, true);
        boolean acceptedTall = tryVanilla(level, accepted, tallSeed, true, true);
        boolean rejectedShort = tryVanilla(level, rejected, shortSeed, false, false);
        boolean rejectedTall = tryVanilla(level, rejected, tallSeed, true, false);
        boolean streamsPresent = false, streamsAcceptedShort = false, streamsAcceptedTall = false;
        boolean streamsRejectedShort = false, streamsRejectedTall = false;
        try {
            Class<?> type = Class.forName("dev.streamsreflowing.worldgen.StreamVegetationFeature");
            streamsPresent = true;
            Object feature = type.getConstructor(Codec.class).newInstance(NoneFeatureConfiguration.CODEC);
            Method placeColumn = type.getDeclaredMethod("a", WorldGenLevel.class, RandomSource.class,
                    BlockPos.MutableBlockPos.class, int.class, int.class, int.class, int.class);
            placeColumn.setAccessible(true);
            long streamsShortSeed = streamSeedFor(false), streamsTallSeed = streamSeedFor(true);
            streamsAcceptedShort = tryStreams(level, accepted, feature, placeColumn,
                    streamsShortSeed, false, true);
            streamsAcceptedTall = tryStreams(level, accepted, feature, placeColumn,
                    streamsTallSeed, true, true);
            streamsRejectedShort = tryStreams(level, rejected, feature, placeColumn,
                    streamsShortSeed, false, false);
            streamsRejectedTall = tryStreams(level, rejected, feature, placeColumn,
                    streamsTallSeed, true, false);
        } catch (ClassNotFoundException absent) {
            // Optional Streams integration is not present.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams seagrass fixture invocation failed", error);
        }
        boolean streamsPassed = !streamsPresent || (streamsAcceptedShort && streamsAcceptedTall
                && streamsRejectedShort && streamsRejectedTall);
        boolean passed = acceptedShort && acceptedTall && rejectedShort && rejectedTall && streamsPassed;
        return "{\"event\":\"aquatic_placement_fixture\",\"status\":\""
                + (passed ? "passed" : "failed") + "\",\"acceptedShort\":" + acceptedShort
                + ",\"acceptedTallPair\":" + acceptedTall
                + ",\"rejectedShort\":" + rejectedShort
                + ",\"rejectedTallPair\":" + rejectedTall
                + ",\"streamsPresent\":" + streamsPresent
                + ",\"streamsAcceptedShort\":" + streamsAcceptedShort
                + ",\"streamsAcceptedTallPair\":" + streamsAcceptedTall
                + ",\"streamsRejectedShort\":" + streamsRejectedShort
                + ",\"streamsRejectedTallPair\":" + streamsRejectedTall + "}";
    }

    private static BlockPos findSite(ServerLevel level, int chunkX, int chunkZ, int y,
                                     boolean wantAllowed, BlockPos except) {
        for (int dz = 1; dz < 15; dz++) {
            for (int dx = 1; dx < 15; dx++) {
                BlockPos pos = new BlockPos(chunkX * 16 + dx, y, chunkZ * 16 + dz);
                if (pos.equals(except) || !clearSky(level, pos)) continue;
                // A site must give the same policy answer for all possible local biome groups.
                boolean marine = SubmergedPlantPolicy.allows(true, true, true, false,
                        true, 3, level.getSeed(), pos.getX(), pos.getZ());
                boolean fresh = SubmergedPlantPolicy.allows(true, true, false, true,
                        true, 3, level.getSeed(), pos.getX(), pos.getZ());
                boolean other = SubmergedPlantPolicy.allows(true, true, false, false,
                        true, 3, level.getSeed(), pos.getX(), pos.getZ());
                if (marine == wantAllowed && fresh == wantAllowed && other == wantAllowed) return pos;
            }
        }
        return null;
    }

    private static boolean clearSky(ServerLevel level, BlockPos pos) {
        for (int dy = -1; dy <= 2; dy++) {
            if (!level.getBlockState(pos.offset(0, dy, 0)).isAir()) return false;
        }
        return true;
    }

    private static long seedFor(boolean tall) {
        for (long seed = 0; seed < 100_000; seed++) {
            RandomSource random = RandomSource.create(seed);
            int dx = random.nextInt(8) - random.nextInt(8);
            int dz = random.nextInt(8) - random.nextInt(8);
            if (dx == 0 && dz == 0 && (random.nextDouble() < .5) == tall) return seed;
        }
        throw new IllegalStateException("no deterministic zero-offset seagrass seed");
    }

    private static long streamSeedFor(boolean tall) {
        for (long seed = 0; seed < 100_000; seed++) {
            RandomSource random = RandomSource.create(seed);
            random.nextInt(100);
            if (random.nextBoolean() == tall) return seed;
        }
        throw new IllegalStateException("no deterministic Streams seagrass seed");
    }

    private static boolean tryStreams(ServerLevel level, BlockPos pos, Object feature, Method placeColumn,
                                      long seed, boolean tall, boolean expectAllowed)
            throws ReflectiveOperationException {
        BlockPos floor = pos.below(), upper = pos.above(), cap = pos.above(2);
        BlockState oldFloor = level.getBlockState(floor), oldLower = level.getBlockState(pos);
        BlockState oldUpper = level.getBlockState(upper), oldCap = level.getBlockState(cap);
        try {
            if (!oldFloor.isAir() || !oldLower.isAir() || !oldUpper.isAir() || !oldCap.isAir()) {
                throw new IllegalStateException("Streams fixture column is occupied");
            }
            level.setBlock(floor, Blocks.SAND.defaultBlockState(), 2);
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(upper, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(cap, Blocks.WATER.defaultBlockState(), 2);
            boolean placed = (boolean) placeColumn.invoke(feature, level, RandomSource.create(seed),
                    pos.mutable(), pos.getX(), floor.getY(), pos.getZ(), 1_000_000);
            BlockState lowerState = level.getBlockState(pos);
            BlockState upperState = level.getBlockState(upper);
            if (!expectAllowed) return placed && lowerState.is(Blocks.WATER) && upperState.is(Blocks.WATER);
            if (!placed) return false;
            if (!tall) return lowerState.is(Blocks.SEAGRASS) && upperState.is(Blocks.WATER);
            return lowerState.is(Blocks.TALL_SEAGRASS)
                    && lowerState.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.LOWER
                    && upperState.is(Blocks.TALL_SEAGRASS)
                    && upperState.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.UPPER;
        } finally {
            level.setBlock(cap, oldCap, 2);
            level.setBlock(upper, oldUpper, 2);
            level.setBlock(pos, oldLower, 2);
            level.setBlock(floor, oldFloor, 2);
            if (!level.getBlockState(floor).equals(oldFloor) || !level.getBlockState(pos).equals(oldLower)
                    || !level.getBlockState(upper).equals(oldUpper) || !level.getBlockState(cap).equals(oldCap)) {
                throw new IllegalStateException("Streams fixture column restoration failed");
            }
        }
    }

    private static boolean tryVanilla(ServerLevel level, BlockPos pos, long seed,
                                      boolean tall, boolean expectAllowed) {
        BlockPos floor = pos.below(), upper = pos.above(), cap = pos.above(2);
        BlockState oldFloor = level.getBlockState(floor), oldLower = level.getBlockState(pos);
        BlockState oldUpper = level.getBlockState(upper), oldCap = level.getBlockState(cap);
        try {
            if (!oldFloor.isAir() || !oldLower.isAir() || !oldUpper.isAir() || !oldCap.isAir()) {
                throw new IllegalStateException("fixture column is occupied");
            }
            level.setBlock(floor, Blocks.SAND.defaultBlockState(), 2);
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(upper, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(cap, Blocks.WATER.defaultBlockState(), 2);
            if (level.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ()) != pos.getY()) {
                throw new IllegalStateException("fixture ocean-floor heightmap did not select candidate");
            }
            boolean placed = Feature.SEAGRASS.place(HALF_TALL, level,
                    level.getChunkSource().getGenerator(), RandomSource.create(seed), pos);
            BlockState lowerState = level.getBlockState(pos);
            BlockState upperState = level.getBlockState(upper);
            if (!expectAllowed) return !placed && lowerState.is(Blocks.WATER) && upperState.is(Blocks.WATER);
            if (!placed) return false;
            if (!tall) return lowerState.is(Blocks.SEAGRASS) && upperState.is(Blocks.WATER);
            return lowerState.is(Blocks.TALL_SEAGRASS)
                    && lowerState.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.LOWER
                    && upperState.is(Blocks.TALL_SEAGRASS)
                    && upperState.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.UPPER;
        } finally {
            level.setBlock(cap, oldCap, 2);
            level.setBlock(upper, oldUpper, 2);
            level.setBlock(pos, oldLower, 2);
            level.setBlock(floor, oldFloor, 2);
            if (!level.getBlockState(floor).equals(oldFloor) || !level.getBlockState(pos).equals(oldLower)
                    || !level.getBlockState(upper).equals(oldUpper) || !level.getBlockState(cap).equals(oldCap)) {
                throw new IllegalStateException("fixture column restoration failed");
            }
        }
    }
}
