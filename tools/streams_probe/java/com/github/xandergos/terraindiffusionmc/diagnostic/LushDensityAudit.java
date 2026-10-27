package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Opt-in integration audit for Lush's real direct-write vegetation feature. */
public final class LushDensityAudit {
    private static final int TRIALS = 160;
    private static final ResourceLocation LUSH_CONFIGURED_FEATURE = id("lush:lush_vegetation");
    private static final ResourceLocation TD_LUSH_PLACED_FEATURE =
            id("terrain-diffusion-mc:lush_ecology_vegetation");
    private static final ResourceLocation ECO_GROUNDCOVER = id("terrain-diffusion-mc:eco_groundcover");

    private LushDensityAudit() { }

    public static String run(ServerLevel level, int chunkX, int chunkZ) {
        List<String> failures = new ArrayList<>();
        Map<String, Sample> samples = new LinkedHashMap<>();
        Map<BlockPos, BlockState> originals = new HashMap<>();
        int ecologyBiomes = 0;
        BlockPos fixtureOrigin = BlockPos.ZERO;
        try {
            var configuredRegistry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
            ConfiguredFeature<?, ?> lush = configuredRegistry.getHolderOrThrow(ResourceKey.create(
                    Registries.CONFIGURED_FEATURE, LUSH_CONFIGURED_FEATURE)).value();

            var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            for (var entry : biomeRegistry.entrySet()) {
                ResourceLocation biomeId = entry.getKey().location();
                if (!biomeId.getNamespace().equals("terrain-diffusion-mc")
                        || !biomeId.getPath().startsWith("ecology/")) continue;
                ecologyBiomes++;
                int lushCount = 0;
                boolean climateGroundcover = false;
                var steps = entry.getValue().getGenerationSettings().features();
                if (steps.size() > GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()) {
                    for (Holder<?> feature : steps.get(GenerationStep.Decoration.VEGETAL_DECORATION.ordinal())) {
                        ResourceLocation featureId = feature.unwrapKey().map(ResourceKey::location).orElse(null);
                        if (TD_LUSH_PLACED_FEATURE.equals(featureId)) lushCount++;
                        if (LUSH_CONFIGURED_FEATURE.equals(featureId))
                            failures.add("shared_lush_holder:" + biomeId);
                        if (ECO_GROUNDCOVER.equals(featureId)) climateGroundcover = true;
                    }
                }
                if (lushCount != 1) failures.add("lush_feature_count:" + biomeId + ":" + lushCount);
                if (climateGroundcover) failures.add("duplicate_climate_groundcover:" + biomeId);
            }
            if (ecologyBiomes != 19) failures.add("ecology_biome_count:" + ecologyBiomes);

            int highest = level.getMinBuildHeight();
            int minX = chunkX * 16, minZ = chunkZ * 16;
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++)
                highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                        minX + dx, minZ + dz));
            int supportY = highest + 6;
            if (supportY + 2 >= level.getMaxBuildHeight())
                throw new IllegalStateException("fixture_height");
            BlockPos origin = findEcologyOrigin(level, chunkX, chunkZ, supportY + 1);
            fixtureOrigin = origin;

            samples.put("grass", exercise(level, lush, origin, supportY,
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), originals));
            samples.put("dirt", exercise(level, lush, origin, supportY,
                    Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState(), originals));
            samples.put("coarse_dirt", exercise(level, lush, origin, supportY,
                    Blocks.COARSE_DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState(), originals));
            samples.put("sand", exercise(level, lush, origin, supportY,
                    Blocks.SAND.defaultBlockState(), Blocks.AIR.defaultBlockState(), originals));
            samples.put("water", exercise(level, lush, origin, supportY,
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState(), originals));
            addOptionalSample(samples, level, lush, origin, supportY, originals,
                    "iw_grass", "immersive_weathering:rooted_grass_block");
            addOptionalSample(samples, level, lush, origin, supportY, originals,
                    "grass_slab", "terrain_slabs:grass_slab");

            int grass = samples.get("grass").plants();
            int dirt = samples.get("dirt").plants();
            int coarse = samples.get("coarse_dirt").plants();
            if (grass == 0) failures.add("grass_empty");
            if (grass > 0 && (dirt / (double) grass < .20 || dirt / (double) grass > .40))
                failures.add("dirt_ratio:" + dirt + "/" + grass);
            if (grass > 0 && (coarse / (double) grass < .04 || coarse / (double) grass > .17))
                failures.add("coarse_ratio:" + coarse + "/" + grass);
            if (samples.get("sand").plants() != 0) failures.add("sand_spill");
            if (samples.get("water").plants() != 0) failures.add("water_spill");
            for (String optional : List.of("iw_grass", "grass_slab")) {
                Sample sample = samples.get(optional);
                if (sample != null && sample.plants() == 0) failures.add(optional + "_empty");
            }
            for (var entry : samples.entrySet()) {
                if (entry.getValue().outsideEcology() != 0)
                    failures.add("biome_border_spill:" + entry.getKey() + ":" + entry.getValue().outsideEcology());
                if (entry.getKey().equals("sand") || entry.getKey().equals("water")) continue;
                if (!entry.getValue().species().keySet().stream().allMatch(LushDensityAudit::isNativeBoostSpecies))
                    failures.add("unexpected_species:" + entry.getKey() + ":" + entry.getValue().species().keySet());
            }
            if (samples.get("grass").species().size() != 2)
                failures.add("native_provider_species:" + samples.get("grass").species().keySet());
        } catch (Exception error) {
            failures.add(error.getClass().getSimpleName() + ":" + error.getMessage());
        } finally {
            originals.forEach((pos, state) -> level.setBlock(pos, state, 2));
        }

        List<String> sampleRows = samples.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue().json()).toList();
        return "{\"event\":\"lush_density_audit\",\"status\":\""
                + (failures.isEmpty() ? "passed" : "failed") + "\",\"ecologyBiomes\":" + ecologyBiomes
                + ",\"adapterAvailable\":"
                + com.github.xandergos.terraindiffusionmc.compat.lush.LushCompatibility.adapterAvailable()
                + ",\"fixtureOrigin\":\"" + fixtureOrigin.toShortString() + "\",\"trials\":" + TRIALS
                + ",\"samples\":{" + String.join(",", sampleRows)
                + "},\"failures\":\"" + String.join(";", failures).replace("\\", "/")
                .replace("\"", "'").replace("\n", " ") + "\"}";
    }

    private static void addOptionalSample(Map<String, Sample> samples, ServerLevel level,
            ConfiguredFeature<?, ?> lush, BlockPos origin, int supportY,
            Map<BlockPos, BlockState> originals, String label, String blockId) {
        BuiltInRegistries.BLOCK.getOptional(id(blockId)).ifPresent(block -> samples.put(label,
                exercise(level, lush, origin, supportY, block.defaultBlockState(),
                        Blocks.AIR.defaultBlockState(), originals)));
    }

    private static Sample exercise(ServerLevel level, ConfiguredFeature<?, ?> lush, BlockPos origin,
            int supportY, BlockState support, BlockState plantLayer,
            Map<BlockPos, BlockState> originals) {
        Map<ResourceLocation, Integer> species = new HashMap<>();
        int plants = 0;
        int outsideEcology = 0;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            fixtureBlock(level, originals, new BlockPos(origin.getX() + dx, supportY, origin.getZ() + dz), support);
            fixtureBlock(level, originals, new BlockPos(origin.getX() + dx, supportY + 1, origin.getZ() + dz), plantLayer);
            fixtureBlock(level, originals, new BlockPos(origin.getX() + dx, supportY + 2, origin.getZ() + dz),
                    Blocks.AIR.defaultBlockState());
        }
        for (int trial = 0; trial < TRIALS; trial++) {
            // Streams can veto ConfiguredFeature.place for an entire river column. Enter the
            // registered Lush Feature directly so this fixture isolates its provider and soil hook.
            place(lush, level, RandomSource.create(0x4C555348L + trial), origin);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                BlockPos pos = new BlockPos(origin.getX() + dx, supportY + 1, origin.getZ() + dz);
                BlockState state = level.getBlockState(pos);
                if (!state.equals(plantLayer)) {
                    plants++;
                    if (!isEcology(level.getBiome(pos))) outsideEcology++;
                    species.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1, Integer::sum);
                    level.setBlock(pos, plantLayer, 2);
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return new Sample(plants, outsideEcology, Map.copyOf(species));
    }

    private static BlockPos findEcologyOrigin(ServerLevel level, int chunkX, int chunkZ, int y) {
        int minX = chunkX * 16, minZ = chunkZ * 16;
        BlockPos fallback = null;
        for (int x = minX + 3; x <= minX + 12; x++) for (int z = minZ + 3; z <= minZ + 12; z++) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (!isEcology(level.getBiome(candidate))) continue;
            if (fallback == null) fallback = candidate;
            boolean fullPatch = true;
            for (int dx = -3; dx <= 3 && fullPatch; dx++) for (int dz = -3; dz <= 3; dz++) {
                if (!isEcology(level.getBiome(candidate.offset(dx, 0, dz)))) {
                    fullPatch = false;
                    break;
                }
            }
            if (fullPatch) return candidate;
        }
        if (fallback != null) return fallback;
        throw new IllegalStateException("fixture_no_ecology_biome");
    }

    private static boolean isEcology(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location)
                .filter(location -> location.getNamespace().equals("terrain-diffusion-mc"))
                .filter(location -> location.getPath().startsWith("ecology/"))
                .isPresent();
    }

    private static <C extends FeatureConfiguration> boolean place(ConfiguredFeature<C, ?> configured,
            WorldGenLevel level, RandomSource random, BlockPos pos) {
        return configured.feature().place(new FeaturePlaceContext<>(Optional.empty(), level,
                level.getLevel().getChunkSource().getGenerator(), random, pos, configured.config()));
    }

    private static void fixtureBlock(ServerLevel level, Map<BlockPos, BlockState> originals,
            BlockPos pos, BlockState state) {
        originals.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 2);
    }

    private static boolean isNativeBoostSpecies(ResourceLocation blockId) {
        return blockId.equals(id("minecraft:short_grass"))
                || blockId.equals(id("minecraft:fern"));
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private record Sample(int plants, int outsideEcology, Map<ResourceLocation, Integer> species) {
        private String json() {
            String rows = species.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue()).collect(
                            java.util.stream.Collectors.joining(","));
            return "{\"plants\":" + plants + ",\"outsideEcology\":" + outsideEcology
                    + ",\"species\":{" + rows + "}}";
        }
    }
}
