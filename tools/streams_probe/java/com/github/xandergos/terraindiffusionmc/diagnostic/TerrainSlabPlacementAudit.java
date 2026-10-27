package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.github.xandergos.terraindiffusionmc.compat.terrainslabs.TerrainSlabContour;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.fml.ModList;

/** Native Terrain Slabs methods on controlled terrain, with the loaded biome and feature registries. */
public final class TerrainSlabPlacementAudit {
    private TerrainSlabPlacementAudit() { }

    public static void run(ServerLevel level) throws Exception {
        require(WorldNaturalRiverState.version(level) >= 10, "fixture requires natural river V10");
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> ocean = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.parse("terralith:deep_warm_ocean")));
        Holder<Biome> inland = biomes.getHolderOrThrow(Biomes.RIVER);
        require(ocean.is(BiomeTags.IS_OCEAN), "Terralith deep warm ocean tag missing");
        require(ocean.value().getGenerationSettings().features().stream()
                .flatMap(set -> set.stream()).filter(holder -> holder.is(ResourceLocation.parse(
                        "terrain-diffusion-mc:terrain_slabs_ocean"))).count() == 1,
                "deep ocean must have exactly one isolated slab placement");
        verifyFeatureOrder(level, ocean);

        Class<?> type = Class.forName("net.countered.terrainslabs.generation.SlabFeature");
        Object feature = type.getConstructors()[0].newInstance(NoneFeatureConfiguration.CODEC);
        Method candidate = type.getDeclaredMethod("shouldPlaceBottomSlab", WorldGenLevel.class,
                BlockPos.class, boolean.class, boolean.class);
        candidate.setAccessible(true);
        Method place = type.getDeclaredMethod("placeBottomSlab", WorldGenLevel.class, BlockPos.class);
        place.setAccessible(true);
        Method generate = type.getDeclaredMethod("generateSlabs", WorldGenLevel.class, BlockPos.class);
        generate.setAccessible(true);
        BlockPos origin = new BlockPos(600, -40, 688);
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();
        int cases = 0;
        for (var decor : new BlockState[]{Blocks.TURTLE_EGG.defaultBlockState(),
                Blocks.SEA_PICKLE.defaultBlockState(), Blocks.FLOWER_POT.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.CHEST.defaultBlockState()}) {
            require(!admitted(level, ocean, feature, candidate, origin, air, decor, false, false, false),
                    "decoration seeded slab: " + decor);
            cases++;
        }
        require(admitted(level, ocean, feature, candidate, origin, air, sand, false, false, false),
                "ordinary dry contour rejected");
        require(admitted(level, ocean, feature, candidate, origin, water, sand, false, false, false),
                "ordinary wet contour rejected");
        // The native feature has already established a mapped wall and opposite
        // support. The only relaxed condition is its TD tangent-contour filter
        // for top source water of an ocean shore.
        WorldGenLevel waterline = fixture(level, ocean, pos -> pos.getY() < origin.getY() ? sand
                : pos.equals(origin.east()) ? sand
                : pos.getY() == origin.getY() ? water : air,
                new HashMap<>(), pos -> origin.getY() - 1, origin.getY() - 4, origin.getY() + 1);
        require(TerrainSlabContour.allowsBottom(waterline, origin, false),
                "ocean surface-water connection rejected");
        require((Boolean) candidate.invoke(feature, waterline, origin, false, false),
                "native ocean surface-water connection rejected");
        WorldGenLevel isolatedDry = fixture(level, ocean, pos -> pos.getY() < origin.getY() ? sand
                : pos.equals(origin.east()) ? sand : air,
                new HashMap<>(), pos -> origin.getY() - 1, origin.getY() - 4, origin.getY() + 1);
        require(!TerrainSlabContour.allowsBottom(isolatedDry, origin, false),
                "isolated dry bump admitted as coastal connection");
        require(!admitted(level, ocean, feature, candidate, origin, air, sand, true, false, false),
                "dry tall cliff behavior changed");
        require(admitted(level, ocean, feature, candidate, origin, water, sand, true, false, false),
                "tall ocean riser rejected");
        require(!admitted(level, inland, feature, candidate, origin, water, sand, true, false, false),
                "river water protection bypassed");
        require(!admitted(level, ocean, feature, candidate, origin, water, sand, true, true, false),
                "submerged cave admitted");
        require(!admitted(level, ocean, feature, candidate, origin, water, air, false, false, false),
                "flat ocean admitted");
        cases += 10;
        BlockState flowing = water.setValue(LiquidBlock.LEVEL, 1);
        require(!admitted(level, ocean, feature, candidate, origin, flowing, sand, true, false, false),
                "tall fallback converted flowing water to source");
        require(admitted(level, ocean, feature, candidate, origin, flowing, sand, false, false, false),
                "native short-step water admission changed");
        cases += 2;
        for (boolean lava : new boolean[]{false, true}) {
            WorldGenLevel blocked = fixture(level, ocean, pos ->
                    lava && pos.equals(origin.west()) ? Blocks.LAVA.defaultBlockState()
                    : !lava && pos.equals(origin.below()) ? air
                    : pos.getY() < origin.getY() ? sand
                    : pos.getX() == origin.getX() + 1 && pos.getY() <= origin.getY() + 2 ? sand : water,
                    new HashMap<>(), pos -> origin.getY(), origin.getY() - 4, 63);
            require(!(Boolean) candidate.invoke(feature, blocked, origin, false, false),
                    "tall fallback bypassed " + (lava ? "lava veto" : "support requirement"));
            cases++;
        }
        for (BlockState center : new BlockState[]{air, water}) {
            for (boolean pit : new boolean[]{false, true}) {
                WorldGenLevel isolated = fixture(level, ocean, pos -> pos.getY() < origin.getY() ? sand
                        : pos.getY() == origin.getY()
                        && (pit ? !pos.equals(origin) : pos.equals(origin.east())) ? sand : center,
                        new HashMap<>(), pos -> origin.getY(), origin.getY() - 4, 63);
                require(!(Boolean) candidate.invoke(feature, isolated, origin, false, false),
                        "isolated " + (pit ? "pit" : "bump") + " seeded slab in " + center);
                cases++;
            }
        }
        BlockState slab = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("terrain_slabs:sand_slab"))
                .defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BooleanProperty generated = (BooleanProperty) slab.getProperties().stream()
                .filter(p -> p.getName().equals("generated")).findFirst().orElseThrow();
        require(!admitted(level, ocean, feature, candidate, origin, air, slab, false, false, true),
                "manual slab seeded extension");
        require(admitted(level, ocean, feature, candidate, origin, air, slab.setValue(generated, true),
                false, false, true), "native generated contour extension rejected");
        cases += 2;

        var writes = new HashMap<BlockPos, BlockState>();
        WorldGenLevel wetFixture = fixture(level, ocean, pos ->
                pos.getY() < origin.getY() ? sand
                : pos.getX() == origin.getX() + 1 && pos.getY() <= origin.getY() + 2 ? sand : water,
                writes, pos -> origin.getY(), origin.getY() - 4, 63);
        require((Boolean) candidate.invoke(feature, wetFixture, origin, false, false), "wet placement admission");
        place.invoke(feature, wetFixture, origin);
        BlockState placed = writes.get(origin);
        require(placed != null && placed.getValue(SlabBlock.WATERLOGGED)
                && placed.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && placed.getValue(generated)
                && placed.getFluidState().isSource(), "native placement lost generated/source-water state");

        // Full native scan at several depths and cliff heights, not just a direct candidate call.
        int generatedCount = 0;
        for (int bed : new int[]{56, 32, -40}) {
            for (int rise : new int[]{1, 2, 3}) {
                var scanWrites = new HashMap<BlockPos, BlockState>();
                Function<BlockPos, Integer> floor = p -> bed + (p.getX() >= 600 ? rise : 0) + 1;
                WorldGenLevel scan = fixture(level, ocean, p -> p.getY() < floor.apply(p) ? sand
                        : p.getY() <= 62 ? water : air, scanWrites, floor, bed - 2, 63);
                generate.invoke(feature, scan, new BlockPos(592, 0, 688));
                require(scanWrites.size() == 16, "native scan bed=" + bed + " rise=" + rise
                        + " expected 16 contour slabs, got " + scanWrites.size());
                for (var entry : scanWrites.entrySet()) {
                    require(entry.getKey().getX() == 599 && entry.getKey().getY() == bed + 1
                            && entry.getValue().getValue(SlabBlock.WATERLOGGED)
                            && entry.getValue().getValue(generated), "off-contour/invalid slab " + entry);
                }
                generatedCount += scanWrites.size();
            }
        }
        // A flat beach with a one-block bump and a one-block pit must stay uncluttered
        // through the full native pass, including extension/corner candidate collection.
        var microWrites = new HashMap<BlockPos, BlockState>();
        Function<BlockPos, Integer> microFloor = p ->
                p.getX() == 600 && p.getZ() == 694 ? 54
                : p.getX() == 600 && p.getZ() == 699 ? 52 : 53;
        WorldGenLevel micro = fixture(level, ocean,
                p -> p.getY() < microFloor.apply(p) ? sand : air,
                microWrites, microFloor, 49, 55);
        generate.invoke(feature, micro, new BlockPos(592, 0, 688));
        require(microWrites.isEmpty(), "isolated beach microsteps produced slabs: " + microWrites);
        verifyLandmarks(level);
        // Exercise actual FEATURES -> FULL generation as well as the controlled block fixtures.
        var fullChunk = level.getServer().submit(() -> level.getChunk(0, -33)).get(300, TimeUnit.SECONDS);
        require(fullChunk.getPos().x == 0 && fullChunk.getPos().z == -33, "wrong native chunk generated");
        System.out.println("TerrainSlabPlacementAudit: PASS candidateCases=" + cases
                + " nativeScans=10 generatedWaterloggedSlabs=" + generatedCount
                + " isolatedBeachSlabs=" + microWrites.size()
                + " deepOceanTagged=true deepOceanFeature=true nativeFullChunks=1");
    }

    private static boolean admitted(ServerLevel level, Holder<Biome> biome, Object feature, Method method,
                                    BlockPos origin, BlockState center, BlockState wall, boolean tall,
                                    boolean cave, boolean extension) throws Exception {
        WorldGenLevel fixture = fixture(level, biome, pos -> pos.getY() < origin.getY()
                ? Blocks.SAND.defaultBlockState()
                : pos.getX() == origin.getX() + 1
                && (pos.getY() == origin.getY() || tall && pos.getY() == origin.getY() + 1) ? wall : center,
                new HashMap<>(), pos -> origin.getY() + (cave ? 8 : 0), origin.getY() - 4, 63);
        return (Boolean) method.invoke(feature, fixture, origin, false, extension);
    }

    private static WorldGenLevel fixture(ServerLevel level, Holder<Biome> biome,
                                         Function<BlockPos, BlockState> terrain,
                                         Map<BlockPos, BlockState> writes,
                                         Function<BlockPos, Integer> floor, int minY, int seaY) {
        Function<BlockPos, BlockState> states = pos -> writes.getOrDefault(pos, terrain.apply(pos));
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getLevel" -> level;
                    case "getBlockState" -> states.apply((BlockPos) args[0]);
                    case "getFluidState" -> states.apply((BlockPos) args[0]).getFluidState();
                    case "getBiome" -> biome;
                    case "getMinBuildHeight" -> minY;
                    case "getHeight" -> args[0] == Heightmap.Types.OCEAN_FLOOR_WG
                            ? floor.apply(new BlockPos((Integer) args[1], 0, (Integer) args[2])) : seaY;
                    case "setBlock" -> {
                        writes.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                        yield true;
                    }
                    default -> throw new AssertionError("Unexpected slab fixture access: " + called.getName());
                });
    }

    private static void verifyFeatureOrder(ServerLevel level, Holder<Biome> ocean) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var features = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        var nativeSlabs = features.getHolderOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,
                ResourceLocation.parse("terrain_slabs:slab_feature_placed")));
        var oceanSlabs = features.getHolderOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,
                ResourceLocation.parse("terrain-diffusion-mc:terrain_slabs_ocean")));
        require(nativeSlabs.value().feature().equals(oceanSlabs.value().feature()),
                "ocean placement changed the native configured feature");
        // FeatureSorter keys PlacedFeature record VALUES; a second registry key alone is insufficient.
        require(!nativeSlabs.value().equals(oceanSlabs.value()), "ocean placement aliases native sort identity");
        var oceans = biomes.holders().filter(b -> b.is(BiomeTags.IS_OCEAN)).toList();
        require(oceans.size() >= 10, "fixture must include all vanilla oceans and Terralith deep warm ocean");
        for (var biome : oceans) {
            long count = biome.value().getGenerationSettings().features().stream().flatMap(step -> step.stream())
                    .filter(f -> f.value().feature().equals(nativeSlabs.value().feature())).count();
            require(count == 1, "expected exactly one slab pass for " + biome.key().location() + ", got " + count);
        }
        require(oceanSlabs.value().placement().size() == 1, "unexpected ocean placement modifiers");
        var random = RandomSource.create(19L);
        var control = RandomSource.create(19L);
        var origin = new BlockPos(600, -40, 688);
        require(oceanSlabs.value().placement().getFirst().getPositions(null, random, origin).toList()
                        .equals(List.of(origin)) && random.nextLong() == control.nextLong(),
                "ocean placement changed origin, count or random stream");
        var boreal = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.parse("terrain-diffusion-mc:ecology/boreal_forest")));
        for (var biome : List.of(ocean, boreal)) {
            System.out.println("TerrainSlabFeatureOrderInput: biome=" + biome.unwrapKey().orElseThrow().location()
                    + " isOverworld=" + biome.is(BiomeTags.IS_OVERWORLD) + " features="
                    + biome.value().getGenerationSettings().features().stream().map(step -> step.stream()
                            .map(f -> f.unwrapKey().map(k -> k.location().toString()).orElse("inline")).toList()).toList());
        }
        // Reconstruct the shipped regression with today's real modified biome lists.
        boolean reproduced = false;
        try {
            FeatureSorter.buildFeaturesPerStep(List.of(ocean, boreal), biome ->
                    biome.value().getGenerationSettings().features().stream()
                            .map(step -> HolderSet.direct(step.stream()
                                    .map(feature -> feature.equals(oceanSlabs) ? nativeSlabs : feature).toList()))
                            .map(step -> (HolderSet<PlacedFeature>) step).toList(), true);
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Feature order cycle found")) throw expected;
            reproduced = true;
        }
        if (ModList.get().isLoaded("apotheosis")) {
            require(reproduced, "Apotheosis fixture must reproduce the shipped deep-ocean/boreal cycle");
        }
        // A reduced mod fixture need not contain the other side of the user's cycle.
        // Independently prove that this alias breaks opposed shared-feature ordering
        // with the actual native sorter and loaded placement values.
        var shared = features.getHolderOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,
                ResourceLocation.parse("minecraft:monster_room")));
        boolean sharedCycle = false;
        try {
            FeatureSorter.buildFeaturesPerStep(List.of(false, true), oceanOrder -> List.of(oceanOrder
                    ? HolderSet.direct(shared, nativeSlabs) : HolderSet.direct(nativeSlabs, shared)), true);
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Feature order cycle found")) throw expected;
            sharedCycle = true;
        }
        require(sharedCycle, "shared placement negative control did not cycle");
        FeatureSorter.buildFeaturesPerStep(List.of(false, true), oceanOrder -> List.of(oceanOrder
                ? HolderSet.direct(shared, oceanSlabs) : HolderSet.direct(nativeSlabs, shared)), true);
        var overworld = biomes.holders().filter(b -> b.is(BiomeTags.IS_OVERWORLD) || b.is(BiomeTags.IS_OCEAN)
                || b.key().location().getNamespace().equals("terrain-diffusion-mc")).toList();
        FeatureSorter.buildFeaturesPerStep(overworld, b -> b.value().getGenerationSettings().features(), true);
        var active = List.copyOf(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
        FeatureSorter.buildFeaturesPerStep(active, b -> b.value().getGenerationSettings().features(), true);
        System.out.println("TerrainSlabFeatureOrderAudit: PASS aliasNegativeControl=true loadedOldCycleReproduced="
                + reproduced + " overworldBiomes="
                + overworld.size() + " activeBiomes=" + active.size() + " oceanBiomes=" + oceans.size()
                + " originAndRandomPreserved=true");
    }

    private static void verifyLandmarks(ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        Map<String, Integer> chances = Map.of("fairy_ring", 600, "forest_clearing", 144,
                "ocean_rocks_deep", 750, "ocean_rocks_shallow", 900, "pond", 1200,
                "rocks", 600, "sinkhole", 600, "thermal_vent", 300, "underwater_rocks", 300);
        boolean loaded = ModList.get().isLoaded("landmarks");
        for (var entry : chances.entrySet()) {
            var id = ResourceLocation.fromNamespaceAndPath("landmarks", entry.getKey());
            var feature = registry.get(id);
            if (!loaded) {
                require(feature == null, "optional Landmarks feature loaded without mod: " + id);
                continue;
            }
            require(feature != null, "missing Landmarks feature: " + id);
            int rarityFilters = 0;
            for (var modifier : feature.placement()) {
                JsonElement json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).getOrThrow();
                if ("minecraft:rarity_filter".equals(json.getAsJsonObject().get("type").getAsString())) {
                    require(json.getAsJsonObject().get("chance").getAsInt() == entry.getValue(),
                            "Landmarks runtime rarity mismatch: " + id);
                    rarityFilters++;
                }
            }
            require(rarityFilters == 1, "unexpected rarity-filter count: " + id);
        }
        System.out.println("LandmarksPlacementAudit: PASS loaded=" + loaded + " checked=9");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
