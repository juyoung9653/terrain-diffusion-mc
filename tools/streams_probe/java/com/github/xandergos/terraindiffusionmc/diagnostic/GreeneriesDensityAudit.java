package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.greeneries.GreeneriesNativePatchScope;
import com.github.xandergos.terraindiffusionmc.world.ecology.GreeneriesSoilPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Deterministic native-provider, canopy-heightmap, and restored real-world survival checks. */
public final class GreeneriesDensityAudit {
    private static final int TRIALS = 2000;
    private GreeneriesDensityAudit() { }

    public static String runFixture(ServerLevel level, int chunkX, int chunkZ) {
        List<String> failures = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<String> biomeRows = new ArrayList<>();
        int biomeCount = 0, survivalCases = 0;
        boolean nativeScope = false, restored = false;
        boolean configuredBaseline = false, isolatedBaseline = false;
        NativePlacementResult openGroundcover = NativePlacementResult.EMPTY;
        NativePlacementResult canopyGroundcover = NativePlacementResult.EMPTY;
        NativePlacementResult sandGroundcover = NativePlacementResult.EMPTY;
        NativePlacementResult waterGroundcover = NativePlacementResult.EMPTY;
        Map<BlockPos, BlockState> originals = new HashMap<>();
        try {
            var configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
            var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            Holder<Biome> td = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                    id("terrain-diffusion-mc:ecology/temperate_grassland")));
            Holder<Biome> vanilla = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME, id("minecraft:plains")));
            Holder<ConfiguredFeature<?, ?>> nativePatch = configured.getHolderOrThrow(ResourceKey.create(
                    Registries.CONFIGURED_FEATURE, id("greeneries:patch_blue_grass")));
            RandomPatchConfiguration patch = (RandomPatchConfiguration) nativePatch.value().config();
            ConfiguredFeature<?, ?> simple = patch.feature().value().feature().value();
            BlockPos virtualPos = new BlockPos(chunkX * 16 + 8, 160, chunkZ * 16 + 8);
            VirtualGround ground = new VirtualGround(level, td, virtualPos);
            // Streams vetoes ConfiguredFeature.place throughout a river column,
            // including the synthetic platform above it. Soil/survival checks must
            // enter at Feature.place, retaining the provider and SimpleBlock hooks.
            configuredBaseline = simple.place(ground.world, level.getChunkSource().getGenerator(),
                    RandomSource.create(73), virtualPos);
            ground.blocks.clear();
            isolatedBaseline = placePlant(simple, ground.world, level, RandomSource.create(73), virtualPos);
            ground.blocks.clear();
            if (!isolatedBaseline) failures.add("isolated_grass_baseline");
            String[] supports = {"minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
                    "minecraft:sand", "minecraft:red_sand", "immersive_weathering:rooted_grass_block",
                    "immersive_weathering:grassy_sandy_dirt", "immersive_weathering:grassy_silt",
                    "immersive_weathering:grassy_earthen_clay", "immersive_weathering:grassy_permafrost",
                    "immersive_weathering:loam", "immersive_weathering:silt", "immersive_weathering:earthen_clay",
                    "immersive_weathering:mulch_block", "immersive_weathering:sandy_dirt", "immersive_weathering:permafrost",
                    "terrain_slabs:grass_slab", "terrain_slabs:dirt_slab", "terrain_slabs:coarse_slab",
                    "terrain_slabs:sand_slab", "terrain_slabs:red_sand_slab"};
            for (String supportId : supports) {
                BlockState support = block(supportId);
                ground.support = support;
                int accepted = 0;
                RandomSource random = RandomSource.create(0x475245454E45524CL);
                for (int i = 0; i < TRIALS; i++) {
                    ground.blocks.clear();
                    if (GreeneriesNativePatchScope.place(() -> placePlant(simple, ground.world,
                            level, random, virtualPos))) accepted++;
                }
                float chance = GreeneriesSoilPolicy.acceptance(support);
                // Expected categories are independent of production tag membership.
                float expected = supportId.contains("grassy_") || supportId.contains("rooted_grass")
                        || supportId.endsWith("grass_block") || supportId.endsWith("grass_slab") ? 1F
                        : supportId.contains("coarse") || supportId.endsWith(":sandy_dirt")
                        || supportId.endsWith(":permafrost") ? .1F
                        : supportId.endsWith(":sand") || supportId.endsWith(":red_sand")
                        || supportId.endsWith("sand_slab") ? 0F : .5F;
                if (chance != expected || Math.abs(accepted / (float) TRIALS - expected) > .04F)
                    failures.add("acceptance:" + supportId);
                rows.add("{\"support\":\"" + supportId + "\",\"accepted\":" + accepted
                        + ",\"trials\":" + TRIALS + ",\"expected\":" + expected + "}");
            }
            // Scope must not thin vanilla/TD base SimpleBlockFeature calls, nor vanilla biomes.
            ground.support = Blocks.COARSE_DIRT.defaultBlockState();
            for (int i = 0; i < 100; i++) {
                ground.blocks.clear();
                if (!placePlant(simple, ground.world, level, RandomSource.create(i + 1), virtualPos))
                    failures.add("base_pass_thinned");
            }
            ground.biome = vanilla;
            for (int i = 0; i < 20; i++) {
                ground.blocks.clear();
                RandomSource random = RandomSource.create(i + 1);
                if (!GreeneriesNativePatchScope.place(() -> placePlant(simple, ground.world,
                        level, random, virtualPos)))
                    failures.add("non_td_thinned");
            }
            ground.biome = td;
            ground.support = Blocks.GRASS_BLOCK.defaultBlockState();
            ground.blocks.clear();
            ground.observedScope = false;
            new PlacedFeature(nativePatch, List.of()).place(ground.world, level.getChunkSource().getGenerator(),
                    RandomSource.create(73), virtualPos);
            nativeScope = ground.observedScope;
            if (!nativeScope || GreeneriesNativePatchScope.active()) failures.add("native_scope");
            for (String path : List.of("patch_grass", "patch_grass_jungle", "patch_taiga_grass",
                    "patch_tall_grass", "patch_large_fern", "single_piece_of_grass")) {
                var original = configured.getHolderOrThrow(ResourceKey.create(
                        Registries.CONFIGURED_FEATURE, id("minecraft:" + path)));
                ground.blocks.clear();
                ground.observedScope = false;
                new PlacedFeature(original, List.of()).place(ground.world, level.getChunkSource().getGenerator(),
                        RandomSource.create(73), virtualPos);
                boolean expectedScope = !path.equals("single_piece_of_grass");
                if (ground.observedScope != expectedScope || GreeneriesNativePatchScope.active())
                    failures.add("overridden_native_scope:" + path);
            }
            try {
                GreeneriesNativePatchScope.place(() -> { throw new IllegalStateException("fixture"); });
            } catch (IllegalStateException expected) { }
            if (GreeneriesNativePatchScope.active()) failures.add("scope_leak_after_exception");

            for (var entry : biomes.entrySet()) {
                ResourceLocation biomeId = entry.getKey().location();
                if (!biomeId.getNamespace().equals("terrain-diffusion-mc") || !biomeId.getPath().startsWith("ecology/")) continue;
                biomeCount++;
                Map<ResourceLocation, Integer> counts = new HashMap<>();
                List<ResourceLocation> order = new ArrayList<>();
                var steps = entry.getValue().getGenerationSettings().features();
                if (steps.size() > GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()) {
                    for (Holder<PlacedFeature> feature : steps.get(GenerationStep.Decoration.VEGETAL_DECORATION.ordinal())) {
                        feature.unwrapKey().ifPresent(key -> {
                            counts.merge(key.location(), 1, Integer::sum);
                            order.add(key.location());
                        });
                    }
                }
                if (counts.isEmpty() || counts.values().stream().anyMatch(count -> count != 1))
                    failures.add("biome_features:" + biomeId);
                if (counts.containsKey(id("terrain-diffusion-mc:eco_groundcover")))
                    failures.add("duplicate_climate_pass:" + biomeId);
                if (!counts.containsKey(id("terrain-diffusion-mc:eco_trees")))
                    failures.add("missing_td_trees:" + biomeId);
                if (counts.keySet().stream().noneMatch(key -> key.getNamespace().equals("greeneries")
                        && !key.getPath().equals("patch_reeds"))) failures.add("missing_native_patch:" + biomeId);
                if (counts.keySet().stream().noneMatch(key -> key.getPath().startsWith("greeneries_native/")
                        && key.getPath().contains("flower"))) failures.add("missing_original_flowers:" + biomeId);
                int firstAddition = order.size(), lastOriginal = -1;
                for (int i = 0; i < order.size(); i++) {
                    if (order.get(i).getNamespace().equals("greeneries")) firstAddition = Math.min(firstAddition, i);
                    if (order.get(i).getPath().startsWith("greeneries_native/")) lastOriginal = i;
                }
                if (lastOriginal >= firstAddition) failures.add("native_base_after_additions:" + biomeId);
                var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
                for (var featureId : counts.keySet()) {
                    if (!featureId.getNamespace().equals("terrain-diffusion-mc")
                            || !featureId.getPath().startsWith("greeneries_native/")) continue;
                    var copy = placed.get(featureId);
                    var original = placed.get(id("minecraft:" + featureId.getPath().substring("greeneries_native/".length())));
                    if (copy == null || original == null || !copy.feature().equals(original.feature()))
                        failures.add("native_provider_replaced:" + featureId);
                }
                List<String> featureCounts = counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> "\"" + e.getKey() + "\":" + e.getValue()).toList();
                String orderedFeatures = order.stream().map(key -> "\"" + key + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
                biomeRows.add("{\"biome\":\"" + biomeId + "\",\"features\":{" + String.join(",", featureCounts)
                        + "},\"order\":[" + orderedFeatures + "]}");
            }
            if (biomeCount != 15) failures.add("biome_count:" + biomeCount);

            if (!level.hasChunk(chunkX, chunkZ)) throw new IllegalStateException("fixture_chunk_not_loaded");
            int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 8;
            if (y + 2 >= level.getMaxBuildHeight()) throw new IllegalStateException("fixture_height");
            BlockPos pos = new BlockPos(x, y, z);
            for (BlockPos p : List.of(pos.below(), pos, pos.above(), pos.east())) {
                BlockState old = level.getBlockState(p);
                if (!old.isAir()) throw new IllegalStateException("fixture_requires_air");
                originals.put(p, old);
            }
            WorldGenLevel renderWorld = delegated(level);
            for (String supportId : supports) {
                if (supportId.endsWith(":sand") || supportId.endsWith(":red_sand") || supportId.endsWith("sand_slab")) continue;
                BlockState baseSupport = block(supportId);
                boolean slab = baseSupport.hasProperty(SlabBlock.TYPE);
                for (SlabType type : slab ? SlabType.values() : new SlabType[]{SlabType.DOUBLE}) {
                    BlockState support = slab ? baseSupport.setValue(SlabBlock.TYPE, type) : baseSupport;
                    for (String plantId : List.of("greeneries:short_blue_grass", "greeneries:tall_royal_fern")) {
                        BlockState plant = block(plantId);
                        level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        level.setBlock(pos.below(), support, 2);
                        ConfiguredFeature<?, ?> onePlant = new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                                new SimpleBlockConfiguration(BlockStateProvider.simple(plant)));
                        boolean placed = placePlant(onePlant, level, level, RandomSource.create(5), pos);
                        level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), 3);
                        level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
                        boolean tall = plant.getBlock() instanceof DoublePlantBlock;
                        boolean alive = level.getBlockState(pos).is(plant.getBlock())
                                && level.getBlockState(pos).canSurvive(level, pos)
                                && (!tall || level.getBlockState(pos.above()).is(plant.getBlock())
                                    && level.getBlockState(pos.above()).canSurvive(level, pos.above()));
                        double offset = level.getBlockState(pos).getOffset(renderWorld, pos).y;
                        double upperOffset = tall ? level.getBlockState(pos.above()).getOffset(renderWorld, pos.above()).y : offset;
                        level.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                        double fullOffset = level.getBlockState(pos).getOffset(renderWorld, pos).y;
                        double fullUpperOffset = tall ? level.getBlockState(pos.above()).getOffset(renderWorld, pos.above()).y : fullOffset;
                        level.setBlock(pos.below(), support, 2);
                        boolean correctOffset = type == SlabType.BOTTOM ? offset == -.5 && upperOffset == -.5
                                : offset == fullOffset && upperOffset == fullUpperOffset;
                        if (!placed || !alive || !correctOffset) failures.add("slab:" + supportId + ":" + type + ":" + plantId
                                + ":placed=" + placed + ":alive=" + alive + ":offset=" + correctOffset);
                        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
                        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) failures.add("orphan_plant");
                        survivalCases++;
                    }
                    if (slab) {
                        ground.support = support.setValue(SlabBlock.WATERLOGGED, true);
                        ground.blocks.clear();
                        if (block("greeneries:short_blue_grass").canSurvive(ground.world, virtualPos)
                                || GreeneriesSoilPolicy.acceptance(ground.support) != 0) failures.add("waterlogged_slab");
                    }
                }
            }

            // Opt-in FULL ServerLevel integration fixture. Exercise the registered native placed
            // feature so its real in-square, heightmap, biome, provider, and patch filters run.
            // This proves the final-heightmap overlay ignores leaves; it does not attribute
            // fresh FEATURES-stage density, where WORLD_SURFACE_WG is frozen after CARVERS.
            int minX = chunkX * 16, minZ = chunkZ * 16;
            int highest = level.getMinBuildHeight();
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++)
                highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                        minX + dx, minZ + dz));
            int supportY = highest + 4;
            int canopyY = supportY + 6;
            if (canopyY + 1 >= level.getMaxBuildHeight())
                throw new IllegalStateException("groundcover_fixture_height");
            BlockPos patchOrigin = new BlockPos(minX, supportY + 1, minZ);
            PlacedFeature redFescue = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
                    .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,
                            id("greeneries:patch_red_fescue_common")));
            Set<ResourceLocation> redFescueSpecies = Set.of(id("greeneries:short_red_fescue"),
                    id("greeneries:bushy_red_fescue"), id("greeneries:very_short_red_fescue"),
                    id("greeneries:medium_red_fescue"));
            List<Long> placementSeeds = java.util.stream.LongStream.rangeClosed(1, 24).boxed().toList();
            openGroundcover = exerciseNativePlacement(level, redFescue, patchOrigin, supportY, canopyY,
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), false,
                    placementSeeds, originals);
            canopyGroundcover = exerciseNativePlacement(level, redFescue, patchOrigin, supportY, canopyY,
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), true,
                    placementSeeds, originals);
            sandGroundcover = exerciseNativePlacement(level, redFescue, patchOrigin, supportY, canopyY,
                    Blocks.SAND.defaultBlockState(), Blocks.AIR.defaultBlockState(), false,
                    placementSeeds, originals);
            waterGroundcover = exerciseNativePlacement(level, redFescue, patchOrigin, supportY, canopyY,
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState(), false,
                    placementSeeds, originals);
            if (openGroundcover.plants() == 0) failures.add("native_open_groundcover");
            if (!openGroundcover.equals(canopyGroundcover)) failures.add("native_canopy_groundcover");
            if (!openGroundcover.species().keySet().equals(redFescueSpecies))
                failures.add("native_provider_species:" + openGroundcover.species().keySet());
            if (sandGroundcover.plants() != 0) failures.add("native_sand_groundcover");
            if (waterGroundcover.plants() != 0) failures.add("native_water_groundcover");
        } catch (Exception error) {
            failures.add(error.getClass().getSimpleName() + ":" + error.getMessage());
        } finally {
            for (var entry : originals.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
            restored = originals.entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue()));
            if (!restored) failures.add("fixture_restore");
        }
        return "{\"event\":\"greeneries_density_fixture\",\"status\":\"" + (failures.isEmpty() ? "passed" : "failed")
                + "\",\"nativeScope\":" + nativeScope + ",\"biomes\":" + biomeCount
                + ",\"survivalCases\":" + survivalCases + ",\"restored\":" + restored
                + ",\"configuredBaseline\":" + configuredBaseline + ",\"isolatedBaseline\":" + isolatedBaseline
                + ",\"nativePlacedFeature\":{\"open\":" + placementJson(openGroundcover)
                + ",\"canopy\":" + placementJson(canopyGroundcover)
                + ",\"sand\":" + placementJson(sandGroundcover)
                + ",\"water\":" + placementJson(waterGroundcover) + "}"
                + ",\"acceptance\":[" + String.join(",", rows) + "],\"biomeFeatures\":[" + String.join(",", biomeRows)
                + "],\"failures\":\""
                + String.join(";", failures).replace("\\", "/").replace("\"", "'").replace("\n", " ") + "\"}";
    }

    private static BlockState block(String name) {
        return BuiltInRegistries.BLOCK.getOptional(id(name)).orElseThrow(() -> new IllegalStateException("missing:" + name)).defaultBlockState();
    }

    private static <C extends FeatureConfiguration> boolean placePlant(ConfiguredFeature<C, ?> configured,
            WorldGenLevel world, ServerLevel level, RandomSource random, BlockPos pos) {
        return configured.feature().place(new FeaturePlaceContext<>(Optional.empty(), world,
                level.getChunkSource().getGenerator(), random, pos, configured.config()));
    }

    private static ResourceLocation id(String name) { return ResourceLocation.parse(name); }

    private static NativePlacementResult exerciseNativePlacement(ServerLevel level, PlacedFeature feature,
            BlockPos origin, int supportY, int canopyY, BlockState support, BlockState floor,
            boolean canopy, List<Long> seeds, Map<BlockPos, BlockState> originals) {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            fixtureBlock(level, originals, new BlockPos(x, supportY, z), support);
            fixtureBlock(level, originals, new BlockPos(x, supportY + 1, z), floor);
            fixtureBlock(level, originals, new BlockPos(x, canopyY, z),
                    canopy ? leaves : Blocks.AIR.defaultBlockState());
        }
        int plants = 0;
        Map<ResourceLocation, Integer> species = new HashMap<>();
        for (long seed : seeds) {
            feature.placeWithBiomeCheck(level, level.getChunkSource().getGenerator(),
                    RandomSource.create(seed), origin);
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                BlockPos pos = new BlockPos(origin.getX() + dx, supportY + 1, origin.getZ() + dz);
                BlockState state = level.getBlockState(pos);
                if (!state.equals(floor)) {
                    species.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1, Integer::sum);
                    plants++;
                    fixtureBlock(level, originals, pos, floor);
                }
            }
        }
        return new NativePlacementResult(plants, Map.copyOf(species));
    }

    private static void fixtureBlock(ServerLevel level, Map<BlockPos, BlockState> originals,
            BlockPos pos, BlockState state) {
        originals.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 2);
    }

    private static String placementJson(NativePlacementResult result) {
        String species = result.species().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"plants\":" + result.plants() + ",\"species\":{" + species + "}}";
    }

    private record NativePlacementResult(int plants, Map<ResourceLocation, Integer> species) {
        private static final NativePlacementResult EMPTY = new NativePlacementResult(0, Map.of());
    }

    private static WorldGenLevel delegated(ServerLevel level) {
        return (WorldGenLevel) Proxy.newProxyInstance(GreeneriesDensityAudit.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    try { return method.invoke(level, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }

    private static final class VirtualGround {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private BlockState support = Blocks.GRASS_BLOCK.defaultBlockState();
        private Holder<Biome> biome;
        private boolean observedScope;
        private final WorldGenLevel world;

        private VirtualGround(ServerLevel level, Holder<Biome> biome, BlockPos origin) {
            this.biome = biome;
            world = (WorldGenLevel) Proxy.newProxyInstance(GreeneriesDensityAudit.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                        observedScope |= GreeneriesNativePatchScope.active();
                        String name = method.getName();
                        if (name.equals("getBiome")) return this.biome;
                        if (name.equals("getBlockState") || name.equals("getFluidState")) {
                            BlockPos p = (BlockPos) args[0];
                            BlockState state = blocks.getOrDefault(p,
                                    p.getY() == origin.getY() - 1 ? support : Blocks.AIR.defaultBlockState());
                            return name.equals("getFluidState") ? state.getFluidState() : state;
                        }
                        if (name.equals("setBlock")) { blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]); return true; }
                        if (name.equals("isEmptyBlock")) {
                            BlockPos p = (BlockPos) args[0];
                            return p.getY() != origin.getY() - 1 && blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()).isAir();
                        }
                        try { return method.invoke(level, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
        }
    }
}
