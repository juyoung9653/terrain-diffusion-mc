package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.greeneries.GreeneriesNativePatchScope;
import com.github.xandergos.terraindiffusionmc.world.ecology.GreeneriesSoilPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Deterministic native-provider acceptance plus restored real-world slab survival checks. */
public final class GreeneriesDensityAudit {
    private static final int TRIALS = 2000;
    private GreeneriesDensityAudit() { }

    public static String runFixture(ServerLevel level, int chunkX, int chunkZ) {
        List<String> failures = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<String> biomeRows = new ArrayList<>();
        int biomeCount = 0, survivalCases = 0;
        boolean nativeScope = false, restored = false;
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
                    if (GreeneriesNativePatchScope.place(() -> simple.place(ground.world,
                            level.getChunkSource().getGenerator(), random, virtualPos))) accepted++;
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
                if (!simple.place(ground.world, level.getChunkSource().getGenerator(), RandomSource.create(i + 1), virtualPos))
                    failures.add("base_pass_thinned");
            }
            ground.biome = vanilla;
            for (int i = 0; i < 20; i++) {
                ground.blocks.clear();
                RandomSource random = RandomSource.create(i + 1);
                if (!GreeneriesNativePatchScope.place(() -> simple.place(ground.world,
                        level.getChunkSource().getGenerator(), random, virtualPos)))
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
            try {
                GreeneriesNativePatchScope.place(() -> { throw new IllegalStateException("fixture"); });
            } catch (IllegalStateException expected) { }
            if (GreeneriesNativePatchScope.active()) failures.add("scope_leak_after_exception");

            for (var entry : biomes.entrySet()) {
                ResourceLocation biomeId = entry.getKey().location();
                if (!biomeId.getNamespace().equals("terrain-diffusion-mc") || !biomeId.getPath().startsWith("ecology/")) continue;
                biomeCount++;
                Map<ResourceLocation, Integer> counts = new HashMap<>();
                var steps = entry.getValue().getGenerationSettings().features();
                if (steps.size() > GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()) {
                    for (Holder<PlacedFeature> feature : steps.get(GenerationStep.Decoration.VEGETAL_DECORATION.ordinal())) {
                        feature.unwrapKey().ifPresent(key -> {
                            if (key.location().getNamespace().equals("greeneries")
                                    && !key.location().getPath().equals("patch_reeds")) counts.merge(key.location(), 1, Integer::sum);
                        });
                    }
                }
                if (counts.isEmpty() || counts.values().stream().anyMatch(count -> count != 1))
                    failures.add("biome_features:" + biomeId);
                List<String> featureCounts = counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> "\"" + e.getKey() + "\":" + e.getValue()).toList();
                biomeRows.add("{\"biome\":\"" + biomeId + "\",\"features\":{" + String.join(",", featureCounts) + "}}");
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
                        boolean placed = onePlant.place(level, level.getChunkSource().getGenerator(), RandomSource.create(5), pos);
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
                        if (!placed || !alive || !correctOffset) failures.add("slab:" + supportId + ":" + type + ":" + plantId);
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
                + ",\"acceptance\":[" + String.join(",", rows) + "],\"biomeFeatures\":[" + String.join(",", biomeRows)
                + "],\"failures\":\""
                + String.join(";", failures).replace("\\", "/").replace("\"", "'").replace("\n", " ") + "\"}";
    }

    private static BlockState block(String name) {
        return BuiltInRegistries.BLOCK.getOptional(id(name)).orElseThrow(() -> new IllegalStateException("missing:" + name)).defaultBlockState();
    }

    private static ResourceLocation id(String name) { return ResourceLocation.parse(name); }

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
