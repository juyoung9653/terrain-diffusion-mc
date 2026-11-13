package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.ecology.SubmergedPlantPolicy;
import com.mojang.serialization.Codec;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration;
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
        boolean seaPickleHook = hasMixinMethod(Feature.SEA_PICKLE.getClass(),
                "terrainDiffusion$limitGeneratedSeaPickle");
        boolean reefSeaPickleHook = hasMixinMethod(Feature.CORAL_TREE.getClass().getSuperclass(),
                "terrainDiffusion$limitReefSeaPickle");
        boolean streamsPresent = false;
        boolean streamsHook = false;
        try {
            Class<?> streams = Class.forName("dev.streamsreflowing.worldgen.AllRightsReservedNiceJohn_004",
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
        boolean dryRejected = SubmergedPlantPolicy.probability(true, false, true, false,
                true, 0, level.getSeed(), 0, 0) == 0;
        boolean pickleRejected = SubmergedPlantPolicy.seaPickleProbability(true, true,
                false, true, false, false, true, 3, 0, level.getSeed(), 0, 0) == 0;
        boolean policyGuards = nonTerrainUnchanged && dryRejected && pickleRejected;
        long candidateCount = counts.allowed() + counts.rejected() + counts.dry();
        boolean hooksAndPolicy = naturalTerrain && vanillaHook && seaPickleHook && reefSeaPickleHook
                && (!streamsPresent || streamsHook) && policyGuards;
        String status = !hooksAndPolicy ? "failed" : candidateCount == 0 ? "inconclusive" : "passed";
        return "{\"event\":\"aquatic_placement_audit\",\"status\":\"" + status
                + "\",\"naturalTerrain\":" + naturalTerrain
                + ",\"vanillaHook\":" + vanillaHook
                + ",\"seaPickleHook\":" + seaPickleHook
                + ",\"reefSeaPickleHook\":" + reefSeaPickleHook
                + ",\"streamsPresent\":" + streamsPresent
                + ",\"streamsHook\":" + streamsHook
                + ",\"policyGuards\":" + policyGuards
                + ",\"nonTerrainUnchanged\":" + nonTerrainUnchanged
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
        BlockPos accepted = findNearbySite(level, chunkX, chunkZ, y, true, false, null);
        BlockPos rejected = findNearbySite(level, chunkX, chunkZ, y, false, false, accepted);
        BlockPos acceptedTallPos = findNearbySite(level, chunkX, chunkZ, y, true, true, rejected);
        BlockPos rejectedTallPos = findNearbySite(level, chunkX, chunkZ, y, false, true, acceptedTallPos);
        if (accepted == null || rejected == null || acceptedTallPos == null || rejectedTallPos == null) {
            return "{\"event\":\"aquatic_placement_fixture\",\"status\":\"inconclusive\","
                    + "\"reason\":\"no safe accepted and rejected sky columns\"}";
        }
        long shortSeed = seedFor(false);
        long tallSeed = seedFor(true);
        boolean acceptedShort = tryVanilla(level, accepted, shortSeed, false, true);
        boolean acceptedTall = tryVanilla(level, acceptedTallPos, tallSeed, true, true);
        boolean rejectedShort = tryVanilla(level, rejected, shortSeed, false, false);
        boolean rejectedTall = tryVanilla(level, rejectedTallPos, tallSeed, true, false);
        BlockPos pickle = findNearbyPickleSite(level, chunkX, chunkZ, y, true);
        BlockPos pickleRejected = findNearbyPickleSite(level, chunkX, chunkZ, y, false);
        boolean acceptedSeaPickle = pickle != null && trySeaPickle(level, pickle, true);
        boolean rejectedSeaPickle = pickleRejected != null && trySeaPickle(level, pickleRejected, false);
        boolean acceptedReefSeaPickle = pickle != null && tryReefPickle(level, pickle, true);
        boolean rejectedReefSeaPickle = pickleRejected != null && tryReefPickle(level, pickleRejected, false);
        boolean streamsPresent = false, streamsAcceptedShort = false, streamsAcceptedTall = false;
        boolean streamsRejectedShort = false, streamsRejectedTall = false;
        try {
            Class<?> type = Class.forName("dev.streamsreflowing.worldgen.AllRightsReservedNiceJohn_004");
            streamsPresent = true;
            Object feature = type.getConstructor(Codec.class).newInstance(NoneFeatureConfiguration.CODEC);
            Method placeColumn = type.getDeclaredMethod("proprietary1", WorldGenLevel.class, RandomSource.class,
                    BlockPos.MutableBlockPos.class, int.class, int.class, int.class, int.class);
            placeColumn.setAccessible(true);
            long streamsShortSeed = streamSeedFor(false), streamsTallSeed = streamSeedFor(true);
            streamsAcceptedShort = tryStreams(level, accepted, feature, placeColumn,
                    streamsShortSeed, false, true);
            streamsAcceptedTall = tryStreams(level, acceptedTallPos, feature, placeColumn,
                    streamsTallSeed, true, true);
            streamsRejectedShort = tryStreams(level, rejected, feature, placeColumn,
                    streamsShortSeed, false, false);
            streamsRejectedTall = tryStreams(level, rejectedTallPos, feature, placeColumn,
                    streamsTallSeed, true, false);
        } catch (ClassNotFoundException absent) {
            // Optional Streams integration is not present.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams seagrass fixture invocation failed", error);
        }
        boolean streamsPassed = !streamsPresent || (streamsAcceptedShort && streamsAcceptedTall
                && streamsRejectedShort && streamsRejectedTall);
        boolean passed = acceptedShort && acceptedTall && rejectedShort && rejectedTall
                && acceptedSeaPickle && rejectedSeaPickle && acceptedReefSeaPickle && rejectedReefSeaPickle && streamsPassed;
        return "{\"event\":\"aquatic_placement_fixture\",\"status\":\""
                + (passed ? "passed" : "failed") + "\",\"acceptedShort\":" + acceptedShort
                + ",\"acceptedTallPair\":" + acceptedTall
                + ",\"rejectedShort\":" + rejectedShort
                + ",\"rejectedTallPair\":" + rejectedTall
                + ",\"acceptedSeaPickle\":" + acceptedSeaPickle
                + ",\"pickleAcceptedSiteFound\":" + (pickle != null)
                + ",\"rejectedSeaPickle\":" + rejectedSeaPickle
                + ",\"acceptedReefSeaPickle\":" + acceptedReefSeaPickle
                + ",\"rejectedReefSeaPickle\":" + rejectedReefSeaPickle
                + ",\"streamsPresent\":" + streamsPresent
                + ",\"streamsAcceptedShort\":" + streamsAcceptedShort
                + ",\"streamsAcceptedTallPair\":" + streamsAcceptedTall
                + ",\"streamsRejectedShort\":" + streamsRejectedShort
                + ",\"streamsRejectedTallPair\":" + streamsRejectedTall + "}";
    }

    /** Search only the already loaded 3x3 fixture neighbourhood; never admit extra chunks. */
    private static BlockPos findNearbySite(ServerLevel level, int chunkX, int chunkZ, int y,
                                           boolean allowed, boolean tall, BlockPos except) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int x = chunkX + dx, z = chunkZ + dz;
            if (!level.hasChunk(x, z)) continue;
            BlockPos found = findSite(level, x, z, y, allowed, tall, except);
            if (found != null) return found;
        }
        return null;
    }

    private static BlockPos findSite(ServerLevel level, int chunkX, int chunkZ, int y,
                                     boolean wantAllowed, boolean tall, BlockPos except) {
        for (int dz = 1; dz < 15; dz++) {
            for (int dx = 1; dx < 15; dx++) {
                BlockPos pos = new BlockPos(chunkX * 16 + dx, y, chunkZ * 16 + dz);
                if (pos.equals(except) || !clearSky(level, pos)) continue;
                if (allowsStaged(level, pos, tall) == wantAllowed) return pos;
            }
        }
        return null;
    }

    private static boolean allowsStaged(ServerLevel level, BlockPos pos, boolean tall) {
        BlockPos floor = pos.below(), upper = pos.above(), cap = pos.above(2);
        BlockState oldFloor = level.getBlockState(floor), old = level.getBlockState(pos);
        BlockState oldUpper = level.getBlockState(upper), oldCap = level.getBlockState(cap);
        try {
            level.setBlock(floor, Blocks.SAND.defaultBlockState(), 2);
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(upper, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(cap, Blocks.WATER.defaultBlockState(), 2);
            return SubmergedPlantPlacementGuard.allowsPlacement(level, pos, tall);
        } finally {
            level.setBlock(cap, oldCap, 2); level.setBlock(upper, oldUpper, 2);
            level.setBlock(pos, old, 2); level.setBlock(floor, oldFloor, 2);
        }
    }

    private static BlockPos findNearbyPickleSite(ServerLevel level, int chunkX, int chunkZ, int y,
                                                 boolean warm) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int x = chunkX + dx, z = chunkZ + dz;
            if (!level.hasChunk(x, z)) continue;
            BlockPos found = findPickleSite(level, x, z, y, warm);
            if (found != null) return found;
        }
        return null;
    }

    private static BlockPos findPickleSite(ServerLevel level, int chunkX, int chunkZ, int y,
                                           boolean warm) {
        for (int dz = 1; dz < 15; dz++) for (int dx = 1; dx < 15; dx++) {
            BlockPos pos = new BlockPos(chunkX * 16 + dx, y, chunkZ * 16 + dz);
            if (!clearSky(level, pos)) continue;
            if (allowsPickleStaged(level, pos, warm) == warm) return pos;
        }
        return null;
    }

    private static boolean allowsPickleStaged(ServerLevel level, BlockPos pos, boolean warm) {
        try (PickleColumn ignored = new PickleColumn(level, pos)) {
            return SubmergedPlantPlacementGuard.allowsSeaPickle(biomeLevel(level, warm ? Biomes.WARM_OCEAN : Biomes.PLAINS), pos);
        }
    }

    private static boolean trySeaPickle(ServerLevel level, BlockPos pos, boolean warm) {
        try (PickleColumn ignored = new PickleColumn(level, pos)) {
            long seed = seedForPickle();
            Feature.SEA_PICKLE.place(new CountConfiguration(1), biomeLevel(level,
                    warm ? Biomes.WARM_OCEAN : Biomes.PLAINS), level.getChunkSource().getGenerator(), RandomSource.create(seed), pos);
            // SeaPickleFeature increments its local success count after the wrapped call even when
            // the guard short-circuits it, so the physical state is the reliable assertion.
            return warm ? level.getBlockState(pos).is(Blocks.SEA_PICKLE)
                    : level.getBlockState(pos).is(Blocks.WATER);
        }
    }

    private static boolean tryReefPickle(ServerLevel level, BlockPos pickle, boolean warm) {
        BlockPos base = pickle.below();
        BlockPos[] saved = {base, base.north(), base.south(), base.east(), base.west()};
        BlockState[] old = new BlockState[saved.length];
        for (int i = 0; i < saved.length; i++) old[i] = level.getBlockState(saved[i]);
        try (PickleColumn ignored = new PickleColumn(level, pickle)) {
            for (BlockPos p : saved) level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
            Method place = net.minecraft.world.level.levelgen.feature.CoralFeature.class.getDeclaredMethod(
                    "placeCoralBlock", net.minecraft.world.level.LevelAccessor.class, RandomSource.class,
                    BlockPos.class, BlockState.class);
            place.setAccessible(true);
            boolean placed = (boolean) place.invoke(Feature.CORAL_TREE, biomeLevel(level,
                    warm ? Biomes.WARM_OCEAN : Biomes.PLAINS), RandomSource.create(seedForReefPickle()),
                    base, Blocks.TUBE_CORAL_BLOCK.defaultBlockState());
            return warm ? placed && level.getBlockState(pickle).is(Blocks.SEA_PICKLE)
                    : placed && level.getBlockState(pickle).is(Blocks.WATER);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("coral pickle fixture failed", error); }
        finally { for (int i = saved.length - 1; i >= 0; i--) level.setBlock(saved[i], old[i], 2); }
    }

    /** Five source-water cells exercise reef cover outside the shallow-water penalty. */
    private static final class PickleColumn implements AutoCloseable {
        private final ServerLevel level;
        private final BlockPos[] positions = new BlockPos[AquaticFixturePlan.PICKLE_WATER_DEPTH + 1];
        private final BlockState[] states = new BlockState[positions.length];

        PickleColumn(ServerLevel level, BlockPos pos) {
            this.level = level;
            for (int i = 0; i < positions.length; i++) {
                positions[i] = pos.offset(0, i - 1, 0);
                states[i] = level.getBlockState(positions[i]);
                if (!states[i].isAir()) throw new IllegalStateException("pickle fixture occupied");
            }
            for (int i = 0; i < positions.length; i++) {
                level.setBlock(positions[i], (i == 0 ? Blocks.TUBE_CORAL_BLOCK : Blocks.WATER)
                        .defaultBlockState(), 2);
            }
        }

        @Override public void close() {
            for (int i = positions.length - 1; i >= 0; i--) level.setBlock(positions[i], states[i], 2);
            for (int i = 0; i < positions.length; i++) {
                if (!level.getBlockState(positions[i]).equals(states[i]))
                    throw new IllegalStateException("pickle fixture restoration failed");
            }
        }
    }

    private static long seedForReefPickle() {
        for (long seed = 0; seed < 100_000; seed++) { RandomSource r = RandomSource.create(seed);
            if (r.nextFloat() >= .25F && r.nextFloat() < .05F) return seed; }
        throw new IllegalStateException("no coral sea-pickle seed");
    }

    private static long seedForPickle() {
        for (long seed = 0; seed < 100_000; seed++) { RandomSource r = RandomSource.create(seed);
            if (r.nextInt(8) == r.nextInt(8) && r.nextInt(8) == r.nextInt(8)) return seed; }
        throw new IllegalStateException("no zero-offset sea-pickle seed");
    }

    @SuppressWarnings("unchecked")
    private static WorldGenLevel biomeLevel(ServerLevel level, net.minecraft.resources.ResourceKey<Biome> key) {
        Holder<Biome> biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
        return (WorldGenLevel) Proxy.newProxyInstance(AquaticPlacementAudit.class.getClassLoader(),
                new Class<?>[] {WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBiome")) return biome;
                    return method.invoke(level, args);
                });
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
