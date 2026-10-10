package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
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
        int crownSnow = 0, bareEligibleCrowns = 0, maskedCrownSnow = 0;
        List<String> bareCrownDetails = new ArrayList<>();
        boolean snowHook = false;
        try {
            Class<?> feature = Class.forName("bl4ckscor3.mod.snowundertrees.SnowUnderTreesFeature");
            for (Method method : feature.getDeclaredMethods()) {
                if (method.getName().contains("terrainDiffusion$sharedSnowCover")) snowHook = true;
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
                    var generator = level.getChunkSource().getGenerator();
                    boolean naturalCold = NaturalSnowCover.eligible(generator, x, z);
                    if (snow && !naturalCold) warmSnow++;
                    if (BuiltInRegistries.BLOCK.getKey(floorState.getBlock()).getNamespace().equals("greeneries")
                            && (floorState.is(GREENERIES_GRASS) || floorState.is(GREENERIES_FERN))) {
                        greeneriesGrass++;
                        if (support.is(BlockTags.SAND)) sandGrass++;
                    }
                    BlockPos canopy = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
                    BlockState canopyState = level.getBlockState(canopy);
                    BlockState rawCanopy = NaturalSnowCover.raw(level, canopy, canopyState);
                    // Thin snow has no motion-blocking collision: the heightmap can still point
                    // at its supporting leaf. Check both representations before declaring it bare.
                    boolean topSnow = NaturalSnowCover.isSnow(canopyState);
                    boolean snowCap = topSnow || rawCanopy.is(BlockTags.LEAVES)
                            && isSnow(level.getBlockState(canopy.above()));
                    boolean coveredLeaves = rawCanopy.is(BlockTags.LEAVES)
                            || (topSnow && NaturalSnowCover.raw(level, canopy.below(),
                                    level.getBlockState(canopy.below())).is(BlockTags.LEAVES));
                    if (coveredLeaves && snowCap && !naturalCold) maskedCrownSnow++;
                    if (coveredLeaves && canopy.getY() > floor.getY() + 1
                            && naturalCold) {
                        snowyCanopy++;
                        if (snowCap) crownSnow++;
                        else if (level.getBrightness(LightLayer.BLOCK, canopy.above()) < 10
                                && Blocks.SNOW.defaultBlockState().canSurvive(level, canopy.above())) {
                            bareEligibleCrowns++;
                            if (bareCrownDetails.size() < 16) {
                                BlockPos above = canopy.above();
                                BlockState aboveState = level.getBlockState(above);
                                var surface = NaturalEcology.surfaceSample(generator, x, z);
                                bareCrownDetails.add("{\"x\":" + x + ",\"y\":" + canopy.getY() + ",\"z\":" + z
                                        + ",\"floorY\":" + floor.getY() + ",\"canopy\":\"" + canopyState
                                        + "\",\"rawCanopy\":\"" + rawCanopy + "\",\"above\":\"" + aboveState
                                        + "\",\"aboveSnow\":" + isSnow(aboveState)
                                        + ",\"aboveFluid\":" + !aboveState.getFluidState().isEmpty()
                                        + ",\"light\":" + level.getBrightness(LightLayer.BLOCK, above)
                                        + ",\"survives\":" + Blocks.SNOW.defaultBlockState().canSurvive(level, above)
                                        + ",\"elevation\":" + (surface == null ? "null" : surface.elevation())
                                        + ",\"temperature\":" + (surface == null ? "null" : surface.climate().temperature()) + "}");
                            }
                        }
                        if (snow) floorSnow++;
                        else if (!floorState.isAir() && level.getFluidState(floor).isEmpty()) floorPlants++;
                    }
                }
            }
        }
        String status = !greeneriesRegistered || !snowHook || sandGrass > 0 || warmSnow > 0 || waterSnow > 0
                || bareEligibleCrowns > 0 || maskedCrownSnow > 0
                ? "failed" : chunks == 0 || greeneriesGrass == 0 || snowyCanopy == 0
                ? "inconclusive" : floorSnow == 0 ? "failed" : "passed";
        return "{\"event\":\"vegetation_snow_audit\",\"status\":\"" + status
                + "\",\"snowHook\":" + snowHook
                + ",\"chunks\":" + chunks + ",\"greeneriesGroundcover\":" + greeneriesGrass
                + ",\"greeneriesRegistered\":" + greeneriesRegistered
                + ",\"greeneriesGeneratedPositive\":" + (greeneriesGrass > 0)
                + ",\"greeneriesOnSand\":" + sandGrass + ",\"snowyCanopyColumns\":" + snowyCanopy
                + ",\"snowCappedCrowns\":" + crownSnow + ",\"bareEligibleCrowns\":" + bareEligibleCrowns
                + ",\"snowOnMaskedCrowns\":" + maskedCrownSnow
                + ",\"bareCrownDetails\":[" + String.join(",", bareCrownDetails) + "]"
                + ",\"underCanopySnow\":" + floorSnow + ",\"underCanopyPlants\":" + floorPlants
                + ",\"snowInWarmBiome\":" + warmSnow + ",\"snowInWater\":" + waterSnow + "}";
    }

    private static boolean isSnow(BlockState state) {
        return NaturalSnowCover.isSnow(state);
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
        BlockPos bright = sites.get(2);
        boolean[] eligible = new boolean[4];
        for (int i = 0; i < 4; i++) {
            BlockPos floor = sites.get(i);
            eligible[i] = NaturalSnowCover.eligible(level.getChunkSource().getGenerator(), floor.getX(), floor.getZ());
            fixtureBiomes.put(columnKey(floor), warm);
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
                    if (method.getName().equals("getBrightness") && args[0] == LightLayer.BLOCK
                            && args[1] instanceof BlockPos pos && columnKey(pos) == columnKey(bright)) return 12;
                    // Virtual fluid avoids scheduling a real fluid tick in the restored fixture.
                    if (args != null && args.length == 1 && virtualWater.equals(args[0])) {
                        if (method.getName().equals("getBlockState")) return Blocks.WATER.defaultBlockState();
                        if (method.getName().equals("getFluidState")) return Blocks.WATER.defaultBlockState().getFluidState();
                    }
                    if (method.getName().equals("setBlock") && args != null && args[0] instanceof BlockPos pos
                            && !originals.containsKey(pos)) {
                        blockedWrites[0]++;
                        return false;
                    }
                    try { return method.invoke(level, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        boolean floorMask = true, crownMask = true, plantKept = true, waterKept = true, lightKept = true;
        boolean idempotent = true, freezeCold = false, freezeWarm = false, unsupportedKept = false;
        boolean unalignedOrigin = false;
        String failure = null;
        try {
            FeaturePlaceContext<NoneFeatureConfiguration> context = new FeaturePlaceContext<>(
                    Optional.empty(), gated, level.getChunkSource().getGenerator(), RandomSource.create(9165L),
                    new BlockPos(chunkX * 16, 0, chunkZ * 16), NoneFeatureConfiguration.INSTANCE);
            for (int pass = 0; pass < 2; pass++) {
                Holder<Biome> forcedBiome = pass == 0 ? warm : cold;
                fixtureBiomes.replaceAll((key, value) -> forcedBiome);
                for (int i = 0; i < 4; i++) {
                    BlockPos floor = sites.get(i);
                    for (int dy = 5; dy >= 0; dy--) level.setBlock(floor.above(dy), Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(floor.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    if (i == 1) level.setBlock(floor, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    // Both crown layers start bare: pre-capped fixtures cannot prove crown placement.
                    for (int dy : new int[]{2, 4}) level.setBlock(floor.above(dy),
                            Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2);
                }
                Feature.FREEZE_TOP_LAYER.place(context);
                Map<BlockPos, BlockState> once = new HashMap<>();
                originals.keySet().forEach(pos -> once.put(pos, level.getBlockState(pos)));
                feature.place(context);
                idempotent &= once.entrySet().stream().allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
                for (int i = 0; i < 4; i++) {
                    BlockPos floor = sites.get(i);
                    boolean floorExpected = eligible[i] && i < 2;
                    boolean crownExpected = eligible[i] && i != 2;
                    floorMask &= isSnow(level.getBlockState(floor)) == floorExpected;
                    crownMask &= isSnow(level.getBlockState(floor.above(5))) == crownExpected;
                }
                plantKept &= NaturalSnowCover.raw(gated, sites.get(1), level.getBlockState(sites.get(1))).is(Blocks.SHORT_GRASS);
                waterKept &= level.getBlockState(virtualWater).isAir();
                lightKept &= level.getBlockState(bright).isAir() && level.getBlockState(bright.above(5)).isAir();
            }
            BlockPos unsupported = sites.get(0);
            level.setBlock(unsupported, Blocks.AIR.defaultBlockState(), 2);
            WorldGenLevel unsupportedWorld = (WorldGenLevel) Proxy.newProxyInstance(
                    VegetationSnowAudit.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getBlockState") && unsupported.below().equals(args[0]))
                            return Blocks.AIR.defaultBlockState();
                        try { return method.invoke(gated, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            unsupportedKept = !NaturalSnowCover.place(unsupportedWorld, unsupported)
                    && level.getBlockState(unsupported).isAir();
            // Exercise the retained vanilla freeze path against a virtual surface-water column.
            boolean[] frozen = {false};
            int[] queriedBounds = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
            Holder<Biome>[] freezeBiome = new Holder[]{cold};
            WorldGenLevel freezeWorld = (WorldGenLevel) Proxy.newProxyInstance(
                    VegetationSnowAudit.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("getHeight") && args.length == 3) {
                            queriedBounds[0] = Math.min(queriedBounds[0], (int) args[1]);
                            queriedBounds[1] = Math.max(queriedBounds[1], (int) args[1]);
                            queriedBounds[2] = Math.min(queriedBounds[2], (int) args[2]);
                            queriedBounds[3] = Math.max(queriedBounds[3], (int) args[2]);
                            return virtualWater.getY() + 1;
                        }
                        if (name.equals("getBiome")) return freezeBiome[0];
                        if (name.equals("getBrightness")) return 0;
                        if (name.equals("getBlockState")) return virtualWater.equals(args[0])
                                ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        if (name.equals("getFluidState")) return virtualWater.equals(args[0])
                                ? Blocks.WATER.defaultBlockState().getFluidState() : Blocks.AIR.defaultBlockState().getFluidState();
                        if (name.equals("setBlock")) {
                            if (virtualWater.equals(args[0]) && ((BlockState) args[1]).is(Blocks.ICE)) frozen[0] = true;
                            return false;
                        }
                        try { return method.invoke(level, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            BlockPos shiftedOrigin = context.origin().offset(1, 0, 1);
            NaturalSnowCover.freeze(freezeWorld, shiftedOrigin);
            unalignedOrigin = queriedBounds[0] == shiftedOrigin.getX()
                    && queriedBounds[1] == shiftedOrigin.getX() + 15
                    && queriedBounds[2] == shiftedOrigin.getZ()
                    && queriedBounds[3] == shiftedOrigin.getZ() + 15;
            freezeCold = frozen[0];
            frozen[0] = false;
            freezeBiome[0] = warm;
            NaturalSnowCover.freeze(freezeWorld, shiftedOrigin);
            freezeWarm = !frozen[0];
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).replace('"', '\'');
        } finally {
            for (int i = 0; i < 4; i++) {
                BlockPos floor = sites.get(i);
                for (int dy = 5; dy >= -1; dy--) {
                    BlockPos pos = floor.offset(0, dy, 0);
                    level.setBlock(pos, originals.get(pos), 2);
                }
            }
        }
        boolean restored = originals.entrySet().stream().allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
        boolean passed = failure == null && floorMask && crownMask && plantKept && waterKept && lightKept
                && idempotent && freezeCold && freezeWarm && unsupportedKept && unalignedOrigin && restored;
        int eligibleSites = 0;
        for (boolean value : eligible) if (value) eligibleSites++;
        return "{\"event\":\"vegetation_snow_fixture\",\"status\":\"" + (passed ? "passed" : "failed")
                + "\",\"chunkX\":" + chunkX + ",\"chunkZ\":" + chunkZ + ",\"eligibleSites\":" + eligibleSites
                + ",\"floorMask\":" + floorMask + ",\"crownMask\":" + crownMask + ",\"plantKept\":" + plantKept
                + ",\"waterKept\":" + waterKept + ",\"lightKept\":" + lightKept + ",\"idempotent\":" + idempotent
                + ",\"freezeCold\":" + freezeCold + ",\"freezeWarm\":" + freezeWarm
                + ",\"unsupportedKept\":" + unsupportedKept
                + ",\"unalignedOrigin\":" + unalignedOrigin
                + ",\"restored\":" + restored + ",\"blockedOutsideWrites\":" + blockedWrites[0]
                + ",\"error\":\"" + (failure == null ? "" : failure) + "\"}";
    }

    private static List<BlockPos> findSites(ServerLevel level, int chunkX, int chunkZ) {
        List<BlockPos> sites = new ArrayList<>(4);
        if (!level.hasChunk(chunkX, chunkZ)) return sites;
        for (int dz = 2; dz < 14 && sites.size() < 4; dz++) {
            for (int dx = 2; dx < 14 && sites.size() < 4; dx++) {
                int x = chunkX * 16 + dx, z = chunkZ * 16 + dz;
                BlockPos floor = NaturalSnowCover.floor(level, x, z);
                if (floor == null) continue;
                if (floor.getY() + 6 >= level.getMaxBuildHeight() || floor.getY() <= level.getMinBuildHeight()) continue;
                BlockState support = level.getBlockState(floor.below());
                if (!support.isSolid() || support.hasBlockEntity()
                        || level.getBrightness(LightLayer.BLOCK, floor) >= 10) continue;
                boolean clear = true;
                for (int dy = 0; dy <= 5; dy++) {
                    BlockState state = level.getBlockState(floor.above(dy));
                    if ((!state.isAir() && !(dy == 0 && state.is(Blocks.SNOW)))
                            || state.hasBlockEntity()) { clear = false; break; }
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
        BlockPos floor = NaturalSnowCover.floor(level, x, z);
        return floor == null ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : floor.getY();
    }
}
