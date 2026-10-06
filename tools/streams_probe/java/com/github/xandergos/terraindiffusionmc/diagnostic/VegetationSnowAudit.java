package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Read-only receipt for newly generated, already loaded TD chunks. */
public final class VegetationSnowAudit {
    private static final TagKey<Block> GREENERIES_GRASS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "grass"));
    private static final TagKey<Block> GREENERIES_FERN = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "fern"));
    private VegetationSnowAudit() { }

    public static String run(ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
        int chunks = 0, greeneriesGrass = 0, sandGrass = 0;
        int snowyCanopy = 0, floorSnow = 0, floorPlants = 0, warmSnow = 0, waterSnow = 0;
        boolean snowHook = false;
        try {
            Class<?> feature = Class.forName("bl4ckscor3.mod.snowundertrees.SnowUnderTreesFeature");
            for (Method method : feature.getDeclaredMethods()) {
                if (method.getName().contains("terrainDiffusion$recognizeSnowCappedLeaves")) snowHook = true;
            }
        } catch (ClassNotFoundException absent) {
            // Optional snow mod is not installed in this fixture.
        }
        boolean greeneriesRegistered = BuiltInRegistries.BLOCK.getOptional(
                ResourceLocation.fromNamespaceAndPath("greeneries", "short_blue_grass")).isPresent();
        for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
            for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
                if (!level.hasChunk(cx, cz)) continue;
                chunks++;
                for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    int x = cx * 16 + dx, z = cz * 16 + dz;
                    BlockPos floor = new BlockPos(x, floorHeight(level, x, z), z);
                    BlockState floorState = level.getBlockState(floor);
                    BlockState support = level.getBlockState(floor.below());
                    boolean snow = isSnow(floorState);
                    boolean water = !level.getFluidState(floor).isEmpty();
                    if (snow && water) waterSnow++;
                    if (snow && level.getBiome(floor).value().getPrecipitationAt(floor) != Biome.Precipitation.SNOW) warmSnow++;
                    if (BuiltInRegistries.BLOCK.getKey(floorState.getBlock()).getNamespace().equals("greeneries")
                            && (floorState.is(GREENERIES_GRASS) || floorState.is(GREENERIES_FERN))) {
                        greeneriesGrass++;
                        if (support.is(BlockTags.SAND)) sandGrass++;
                    }
                    BlockPos canopy = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
                    BlockState canopyState = level.getBlockState(canopy);
                    boolean coveredLeaves = canopyState.is(BlockTags.LEAVES)
                            || (canopyState.is(Blocks.SNOW)
                                    && level.getBlockState(canopy.below()).is(BlockTags.LEAVES));
                    if (coveredLeaves && canopy.getY() > floor.getY() + 1
                            && level.getBiome(floor).value().getPrecipitationAt(floor) == Biome.Precipitation.SNOW) {
                        snowyCanopy++;
                        if (snow) floorSnow++;
                        else if (!floorState.isAir() && level.getFluidState(floor).isEmpty()) floorPlants++;
                    }
                }
            }
        }
        String status = !greeneriesRegistered || !snowHook || sandGrass > 0 || warmSnow > 0 || waterSnow > 0
                ? "failed" : chunks == 0 || greeneriesGrass == 0 || snowyCanopy == 0
                ? "inconclusive" : floorSnow == 0 ? "failed" : "passed";
        return "{\"event\":\"vegetation_snow_audit\",\"status\":\"" + status
                + "\",\"snowHook\":" + snowHook
                + ",\"chunks\":" + chunks + ",\"greeneriesGroundcover\":" + greeneriesGrass
                + ",\"greeneriesRegistered\":" + greeneriesRegistered
                + ",\"greeneriesGeneratedPositive\":" + (greeneriesGrass > 0)
                + ",\"greeneriesOnSand\":" + sandGrass + ",\"snowyCanopyColumns\":" + snowyCanopy
                + ",\"underCanopySnow\":" + floorSnow + ",\"underCanopyPlants\":" + floorPlants
                + ",\"snowInWarmBiome\":" + warmSnow + ",\"snowInWater\":" + waterSnow + "}";
    }

    private static boolean isSnow(BlockState state) {
        return state.is(BlockTags.SNOW)
                || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("snowrealmagic");
    }

    /** Test-world-only fixture. Uses a loaded chunk near a player or spawn. */
    public static String runFixture(ServerLevel level) {
        BlockPos origin = level.players().isEmpty() ? level.getSharedSpawnPos()
                : level.players().get(0).blockPosition();
        int centerX = Math.floorDiv(origin.getX(), 16), centerZ = Math.floorDiv(origin.getZ(), 16);
        for (int radius = 0; radius <= 8; radius++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    if (level.hasChunk(x, z) && findSites(level, x, z).size() >= 4)
                        return runFixture(level, x, z);
                }
            }
        }
        return "{\"event\":\"vegetation_snow_fixture\",\"status\":\"inconclusive\",\"reason\":\"no loaded clear chunk\"}";
    }

    /** Test-world-only fixture for a specific already loaded FULL chunk. Restores edited blocks. */
    @SuppressWarnings("unchecked")
    public static String runFixture(ServerLevel level, int chunkX, int chunkZ) {
        if (!(level.getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source) || !source.naturalBiomes())
            return fixtureError("not_natural_td_world");
        if (!level.hasChunk(chunkX, chunkZ)) return fixtureError("chunk_not_loaded");
        List<BlockPos> sites = findSites(level, chunkX, chunkZ);
        if (sites.size() < 4) return fixtureError("four_clear_columns_unavailable");
        ResourceLocation featureId = ResourceLocation.fromNamespaceAndPath("snowundertrees", "snow_under_trees");
        Optional<Feature<?>> registered = BuiltInRegistries.FEATURE.getOptional(featureId);
        if (registered.isEmpty()) return fixtureError("snowundertrees_feature_absent");
        Feature<NoneFeatureConfiguration> feature = (Feature<NoneFeatureConfiguration>) registered.get();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> cold = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/snowy_boreal_forest")));
        Holder<Biome> warm = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/tropical_forest")));

        Map<BlockPos, BlockState> originals = new HashMap<>();
        Map<Long, Holder<Biome>> fixtureBiomes = new HashMap<>();
        BlockPos virtualWater = sites.get(3);
        for (int i = 0; i < 4; i++) {
            BlockPos floor = sites.get(i);
            fixtureBiomes.put(columnKey(floor), i == 2 ? warm : cold);
            for (int dy = -1; dy <= 5; dy++) {
                BlockPos pos = floor.offset(0, dy, 0);
                originals.put(pos, level.getBlockState(pos));
            }
        }
        int[] blockedWrites = {0};
        WorldGenLevel gated = (WorldGenLevel) Proxy.newProxyInstance(
                VegetationSnowAudit.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBiome") && args != null && args[0] instanceof BlockPos pos) {
                        Holder<Biome> override = fixtureBiomes.get(columnKey(pos));
                        if (override != null) return override;
                    }
                    // A virtual water cell exercises the guard without scheduling a real fluid tick.
                    if (args != null && args.length == 1 && virtualWater.equals(args[0])) {
                        if (method.getName().equals("getBlockState")) return Blocks.WATER.defaultBlockState();
                        if (method.getName().equals("getFluidState"))
                            return Blocks.WATER.defaultBlockState().getFluidState();
                    }
                    if (method.getName().equals("setBlock") && args != null && args[0] instanceof BlockPos pos
                            && !originals.containsKey(pos)) {
                        blockedWrites[0]++;
                        return false;
                    }
                    try {
                        return method.invoke(level, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        boolean drySnow = false, plantSnow = false, warmClear = false, waterKept = false;
        String failure = null;
        try {
            for (int i = 0; i < 4; i++) {
                BlockPos floor = sites.get(i);
                level.setBlock(floor.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(floor, i == 1 ? Blocks.SHORT_GRASS.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(floor.above(4), Blocks.SPRUCE_LEAVES.defaultBlockState()
                        .setValue(LeavesBlock.PERSISTENT, true), 2);
                level.setBlock(floor.above(5), Blocks.SNOW.defaultBlockState(), 2);
            }
            FeaturePlaceContext<NoneFeatureConfiguration> context = new FeaturePlaceContext<>(
                    Optional.empty(), gated, level.getChunkSource().getGenerator(), RandomSource.create(9165L),
                    new BlockPos(chunkX * 16, 0, chunkZ * 16), NoneFeatureConfiguration.INSTANCE);
            feature.place(context);
            drySnow = isSnow(level.getBlockState(sites.get(0)));
            plantSnow = isSnow(level.getBlockState(sites.get(1)));
            warmClear = level.getBlockState(sites.get(2)).isAir();
            waterKept = level.getBlockState(virtualWater).isAir()
                    && level.getBlockState(virtualWater.above()).isAir();
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName();
        } finally {
            // Top-down restoration removes generated snow and its block entity before support.
            for (int i = 0; i < 4; i++) {
                BlockPos floor = sites.get(i);
                for (int dy = 5; dy >= -1; dy--) {
                    BlockPos pos = floor.offset(0, dy, 0);
                    level.setBlock(pos, originals.get(pos), 2);
                }
            }
        }
        boolean restored = originals.entrySet().stream().allMatch(entry ->
                level.getBlockState(entry.getKey()).equals(entry.getValue()));
        String status = failure == null && drySnow && plantSnow && warmClear && waterKept && restored
                ? "passed" : "failed";
        return "{\"event\":\"vegetation_snow_fixture\",\"status\":\"" + status
                + "\",\"chunkX\":" + chunkX + ",\"chunkZ\":" + chunkZ
                + ",\"drySnow\":" + drySnow + ",\"plantSnow\":" + plantSnow
                + ",\"warmClear\":" + warmClear + ",\"waterKept\":" + waterKept
                + ",\"restored\":" + restored + ",\"blockedOutsideWrites\":" + blockedWrites[0]
                + ",\"error\":\"" + (failure == null ? "" : failure) + "\"}";
    }

    private static List<BlockPos> findSites(ServerLevel level, int chunkX, int chunkZ) {
        List<BlockPos> sites = new ArrayList<>(4);
        if (!level.hasChunk(chunkX, chunkZ)) return sites;
        for (int dz = 2; dz < 14 && sites.size() < 4; dz++) {
            for (int dx = 2; dx < 14 && sites.size() < 4; dx++) {
                int x = chunkX * 16 + dx, z = chunkZ * 16 + dz;
                BlockPos floor = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (floor.getY() + 6 >= level.getMaxBuildHeight() || floor.getY() <= level.getMinBuildHeight()) continue;
                BlockState support = level.getBlockState(floor.below());
                if (!support.isSolid() || support.hasBlockEntity()
                        || level.getBrightness(LightLayer.BLOCK, floor) >= 10) continue;
                boolean clear = true;
                for (int dy = 0; dy <= 5; dy++) {
                    BlockState state = level.getBlockState(floor.above(dy));
                    if (!state.isAir() || state.hasBlockEntity()) { clear = false; break; }
                }
                if (clear) sites.add(floor);
            }
        }
        return sites;
    }

    private static long columnKey(BlockPos pos) {
        return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static String fixtureError(String reason) {
        return "{\"event\":\"vegetation_snow_fixture\",\"status\":\"inconclusive\",\"reason\":\""
                + reason + "\"}";
    }

    private static int floorHeight(ServerLevel level, int x, int z) {
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int capY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, capY, z);
        if (!level.getBlockState(cursor).is(Blocks.SNOW)
                || !level.getBlockState(cursor.below()).is(BlockTags.LEAVES)) {
            BlockPos belowHeight = new BlockPos(x, height - 1, z);
            return isSnow(level.getBlockState(belowHeight)) ? height - 1 : height;
        }
        for (int y = capY - 1; y >= Math.max(level.getMinBuildHeight(), capY - 64); y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(state))
                return isSnow(state) ? y : y + 1;
        }
        return height;
    }
}
