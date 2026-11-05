package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticContinentalnessDensityFunction;
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticMarineProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.neoforged.fml.ModList;

/** Opt-in proof that Hybrid Aquatic's Lithostitched wrapper preserves TD identity. */
public final class HybridAquaticAudit {
    private static final String INJECTOR =
            "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final List<ResourceLocation> TRENCH_BIOMES = List.of(
            id("hybrid_aquatic:warm_trench"), id("hybrid_aquatic:lukewarm_trench"),
            id("hybrid_aquatic:trench"), id("hybrid_aquatic:cold_trench"),
            id("hybrid_aquatic:frozen_trench"));
    private static final List<ResourceLocation> FEATURES = List.of(
            id("hybrid_aquatic:anemones"), id("hybrid_aquatic:sponge_patch"));
    private static final ResourceLocation TRENCH_CARVER = id("hybrid_aquatic:trench/carver");
    private static final int COARSE_MIN = -8_192;
    private static final int COARSE_MAX = 8_192;
    private static final int COARSE_DIVERSITY_SECTOR = 1_024;
    private static final int MAX_RAW_TILES_PER_KIND = 64;

    private HybridAquaticAudit() { }

    public static String run(ServerLevel level) {
        if (!ModList.get().isLoaded("hybrid_aquatic"))
            throw new IllegalStateException("Hybrid Aquatic is not loaded");
        BiomeSource active = level.getChunkSource().getGenerator().getBiomeSource();
        if (!active.getClass().getName().equals(INJECTOR))
            throw new IllegalStateException("Expected Lithostitched InjectorBiomeSource, got "
                    + active.getClass().getName());
        TerrainDiffusionBiomeSource terrain = TerrainDiffusionBiomeSources.find(active);
        if (terrain == null || !terrain.naturalBiomes())
            throw new IllegalStateException("Wrapped source is not a natural-biomes Terrain Diffusion source");
        Object root = rootDelegate(active);
        if (root != terrain)
            throw new IllegalStateException("Injector root delegate differs from Terrain Diffusion identity");

        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        List<String> missingBiomes = new ArrayList<>();
        for (ResourceLocation id : TRENCH_BIOMES)
            if (biomes.getHolder(ResourceKey.create(Registries.BIOME, id)).isEmpty()) missingBiomes.add(id.toString());
        if (!missingBiomes.isEmpty()) throw new IllegalStateException("Missing Hybrid Aquatic trench biomes: " + missingBiomes);

        var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        List<String> missingFeatures = new ArrayList<>();
        for (ResourceLocation id : FEATURES)
            if (placed.getHolder(ResourceKey.create(Registries.PLACED_FEATURE, id)).isEmpty()) missingFeatures.add(id.toString());
        if (!missingFeatures.isEmpty()) throw new IllegalStateException("Missing Hybrid Aquatic placed features: " + missingFeatures);

        var densities = level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION);
        DensityFunction trenchCarver = densities.getHolder(ResourceKey.create(Registries.DENSITY_FUNCTION, TRENCH_CARVER))
                .orElseThrow(() -> new IllegalStateException("Missing Hybrid Aquatic trench carver")).value();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator))
            throw new IllegalStateException("Expected noise chunk generator");
        NoiseRouter router = generator.generatorSettings().value().noiseRouter();
        boolean tdMarineContinents = containsMarineContinents(router.continents());
        if (!tdMarineContinents)
            throw new IllegalStateException("Hybrid Aquatic router does not contain the TD marine continentalness profile");
        return json("hybrid_aquatic_audit", "status", "passed",
                "activeBiomeSource", active.getClass().getName(),
                "rootBiomeSource", terrain.getClass().getName(),
                "naturalBiomes", terrain.naturalBiomes(),
                "trenchBiomeHolders", TRENCH_BIOMES.size(), "placedFeatures", FEATURES.size(),
                "trenchCarverClass", trenchCarver.getClass().getName(),
                "routerContinentsProfile", tdMarineContinents,
                "routerContinentsClass", router.continents().getClass().getName(),
                "routerDepthClass", router.depth().getClass().getName(),
                "routerInitialDensityClass", router.initialDensityWithoutJaggedness().getClass().getName(),
                "routerFinalDensityClass", router.finalDensity().getClass().getName());
    }


    /** Reads raw TD terrain and generator state; it never requests or writes chunks. */
    public static String runMarine(ServerLevel level) {
        return runMarine(level, selectMarineFixture(level));
    }

    /**
     * Selects three bounded, raw-height-confirmed chunks before the normal probe
     * lifecycle asks the server to generate them. Signed raw coarse elevation
     * only ranks candidates; every accepted position comes from the exact TD
     * ground map.
     */
    public static MarinePlan selectMarineFixture(ServerLevel level) {
        Samples samples = findRawSamples(level);
        if (samples.trench == null || samples.shallow == null || samples.land == null)
            throw new IllegalStateException("Missing raw marine samples after bounded broad search: trench="
                    + samples.trench + ", shallow=" + samples.shallow + ", land=" + samples.land);
        return new MarinePlan(samples, List.of(
                new FixtureTarget(samples.land.x >> 4, samples.land.z >> 4, "ecology_land"),
                new FixtureTarget(samples.trench.x >> 4, samples.trench.z >> 4, "native_trench"),
                new FixtureTarget(samples.shallow.x >> 4, samples.shallow.z >> 4, "shallow_ocean")));
    }

    /** Reads raw TD terrain and generator state; it never requests or writes chunks. */
    public static String runMarine(ServerLevel level, MarinePlan plan) {
        if (!ModList.get().isLoaded("hybrid_aquatic"))
            throw new IllegalStateException("Hybrid Aquatic is not loaded");
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator))
            throw new IllegalStateException("Expected noise chunk generator");
        NoiseRouter router = generator.generatorSettings().value().noiseRouter();
        if (!containsMarineContinents(router.continents()))
            throw new IllegalStateException("TD marine continentalness is not installed in the active router");
        Samples samples = plan.samples;
        if (samples.trench == null || samples.shallow == null || samples.land == null)
            throw new IllegalStateException("Missing raw marine samples: trench=" + samples.trench
                    + ", shallow=" + samples.shallow + ", land=" + samples.land);
        Sample trench = validateSample(level, router, samples.trench, Kind.TRENCH);
        Sample shallow = validateSample(level, router, samples.shallow, Kind.SHALLOW);
        Sample land = validateSample(level, router, samples.land, Kind.LAND);
        return json("hybrid_marine_audit", "status", "passed",
                "trench", trench.describe(), "shallow", shallow.describe(), "land", land.describe(),
                "selection", plan.describe());
    }

    private static Sample validateSample(ServerLevel level, NoiseRouter router, Sample sample, Kind kind) {
        int quartX = Math.floorDiv(sample.x, 4), quartY = Math.floorDiv(sample.groundY, 4);
        int quartZ = Math.floorDiv(sample.z, 4);
        Climate.TargetPoint point = level.getChunkSource().randomState().sampler().sample(quartX, quartY, quartZ);
        double expected = HybridAquaticMarineProfile.continentalnessForHeight(sample.groundY);
        double actual = point.continentalness() / 10_000.0;
        if (Math.abs(expected - actual) > .001)
            throw new IllegalStateException("Continentalness mismatch at " + sample.coordinate()
                    + ": expected=" + expected + ", actual=" + actual);
        String biomeId = biomeId(level, sample.x, sample.groundY, sample.z);
        sample.biome = biomeId;
        sample.continentalness = actual;
        sample.densityAtGroundMinusOne = router.finalDensity().compute(
                new DensityFunction.SinglePointContext(sample.x, sample.groundY - 1, sample.z));
        sample.densityAtGroundPlusOne = router.finalDensity().compute(
                new DensityFunction.SinglePointContext(sample.x, sample.groundY + 1, sample.z));
        if (kind == Kind.TRENCH) {
            sample.densityAtZero = router.finalDensity().compute(new DensityFunction.SinglePointContext(sample.x, 0, sample.z));
            sample.densityAtMinusFifty = router.finalDensity().compute(new DensityFunction.SinglePointContext(sample.x, -50, sample.z));
            if (!biomeId.startsWith("hybrid_aquatic:") || !biomeId.endsWith("trench"))
                throw new IllegalStateException("Trench raw sample did not select a Hybrid Aquatic trench: " + biomeId);
            if (!(sample.densityAtZero < 0.0) || !(sample.densityAtMinusFifty > 0.0))
                throw new IllegalStateException("Native trench density did not carve/retain at " + sample.coordinate()
                        + ": y0=" + sample.densityAtZero + ", y-50=" + sample.densityAtMinusFifty);
        } else if ((kind == Kind.SHALLOW && biomeId.endsWith("trench"))
                || (kind == Kind.LAND && (!biomeId.startsWith("terrain-diffusion-mc:ecology/")
                || biomeId.startsWith("hybrid_aquatic:")))
                || !(sample.densityAtGroundMinusOne > 0.0) || !(sample.densityAtGroundPlusOne < 0.0)) {
            throw new IllegalStateException(kind + " sample lost its required TD marine boundary at " + sample.coordinate()
                    + ": biome=" + biomeId + ", below=" + sample.densityAtGroundMinusOne
                    + ", above=" + sample.densityAtGroundPlusOne);
        }
        return sample;
    }

    private static Samples findRawSamples(ServerLevel level) {
        int scale = WorldScaleManager.getCurrentScale();
        int blocksPerCell = 32 * WorldPipelineModelConfig.latentCompression() * scale;
        int minCellX = Math.floorDiv(COARSE_MIN, blocksPerCell);
        int minCellZ = Math.floorDiv(COARSE_MIN, blocksPerCell);
        int maxCellX = Math.floorDiv(COARSE_MAX - 1, blocksPerCell) + 1;
        int maxCellZ = Math.floorDiv(COARSE_MAX - 1, blocksPerCell) + 1;
        FloatTensor coarse;
        try {
            coarse = LocalTerrainProvider.getPipelineCoarse(minCellZ, minCellX, maxCellZ, maxCellX);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read bounded signed coarse terrain candidates", e);
        }
        int coarseHeight = maxCellZ - minCellZ, coarseWidth = maxCellX - minCellX;
        int plane = coarseHeight * coarseWidth;
        if (coarse.shape.length != 3 || coarse.shape[0] < 7 || coarse.shape[1] != coarseHeight
                || coarse.shape[2] != coarseWidth || coarse.data.length < 7 * plane)
            throw new IllegalStateException("Unexpected signed coarse terrain tensor shape");
        List<CoarseCandidate> candidates = new ArrayList<>();
        for (int z = 0; z < coarseHeight; z++) for (int x = 0; x < coarseWidth; x++) {
            int index = z * coarseWidth + x;
            float weight = coarse.data[6 * plane + index];
            float rootMeters = weight > 1e-6f ? coarse.data[index] / weight : 0f;
            float signedMeters = rootMeters * Math.abs(rootMeters);
            int estimate = profileHeight(level, (short)Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(signedMeters))));
            int blockX = (minCellX + x) * blocksPerCell + blocksPerCell / 2;
            int blockZ = (minCellZ + z) * blocksPerCell + blocksPerCell / 2;
            candidates.add(new CoarseCandidate(blockX, blockZ, signedMeters, estimate));
        }
        if (candidates.isEmpty()) throw new IllegalStateException("Signed coarse terrain candidate field was empty");

        List<CoarseCandidate> trenchOrder = diverseOrder(candidates,
                Comparator.comparingInt(candidate -> Math.abs(candidate.estimatedGroundY - 7)));
        List<CoarseCandidate> shallowOrder = diverseOrder(candidates,
                Comparator.comparingInt(candidate -> Math.abs(candidate.estimatedGroundY - 51)));
        List<CoarseCandidate> landOrder = diverseOrder(candidates,
                Comparator.comparingInt(candidate -> Math.abs(candidate.estimatedGroundY - 72)));
        RawGroundLookup raw = new RawGroundLookup();
        Sample trench = findKind(level, raw, trenchOrder, Kind.TRENCH);
        Sample shallow = findKind(level, raw, shallowOrder, Kind.SHALLOW);
        Sample land = findKind(level, raw, landOrder, Kind.LAND);
        return new Samples(trench, shallow, land, candidates.size(), raw.tileReads);
    }

    private static Sample findKind(ServerLevel level, RawGroundLookup raw, List<CoarseCandidate> candidates, Kind kind) {
        int checked = 0;
        for (CoarseCandidate candidate : candidates) {
            TileKey key = raw.keyFor(candidate.x, candidate.z);
            if (!raw.tiles.containsKey(key) && checked++ >= MAX_RAW_TILES_PER_KIND) break;
            LocalTerrainProvider.HeightmapData tile = raw.tile(key);
            Sample found = findInTile(level, tile, key, kind);
            if (found != null) return found;
        }
        return null;
    }

    private static List<CoarseCandidate> diverseOrder(List<CoarseCandidate> candidates,
                                                       Comparator<CoarseCandidate> ranking) {
        List<CoarseCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(ranking);
        List<CoarseCandidate> result = new ArrayList<>(sorted.size());
        Set<TileKey> sectors = new HashSet<>();
        Set<CoarseCandidate> selected = new HashSet<>();
        for (CoarseCandidate candidate : sorted) {
            TileKey sector = new TileKey(Math.floorDiv(candidate.x, COARSE_DIVERSITY_SECTOR),
                    Math.floorDiv(candidate.z, COARSE_DIVERSITY_SECTOR));
            if (sectors.add(sector)) {
                result.add(candidate);
                selected.add(candidate);
            }
        }
        for (CoarseCandidate candidate : sorted) if (selected.add(candidate)) result.add(candidate);
        return result;
    }

    private static int profileHeight(ServerLevel level, short meters) {
        var source = com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources
                .find(level.getChunkSource().getGenerator().getBiomeSource());
        return HeightConverter.convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale(),
                source == null ? 1 : source.undergroundProfileVersion());
    }

    private static Sample findInTile(ServerLevel level, LocalTerrainProvider.HeightmapData tile, TileKey key, Kind kind) {
        int size = LocalTerrainProvider.sampleTileSize();
        for (int z = key.z; z < key.z + size; z += 16) for (int x = key.x; x < key.x + size; x += 16) {
            int y = profileHeight(level, tile.heightmap[z - key.z][x - key.x]);
            if ((kind == Kind.TRENCH && (y < 2 || y > 12))
                    || (kind == Kind.SHALLOW && (y < 40 || y >= 63))
                    || (kind == Kind.LAND && y < 64)) continue;
            if (kind == Kind.LAND && !biomeId(level, x, y, z).startsWith("terrain-diffusion-mc:ecology/")) continue;
            return new Sample(x, z, y);
        }
        return null;
    }

    private static String biomeId(ServerLevel level, int x, int y, int z) {
        var biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(Math.floorDiv(x, 4),
                Math.floorDiv(y, 4), Math.floorDiv(z, 4), level.getChunkSource().randomState().sampler());
        return biome.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("unkeyed");
    }

    private static boolean containsMarineContinents(DensityFunction density) {
        AtomicBoolean found = new AtomicBoolean();
        density.mapAll(function -> {
            if (function instanceof HybridAquaticContinentalnessDensityFunction) found.set(true);
            return function;
        });
        return found.get();
    }

    private enum Kind { TRENCH, SHALLOW, LAND }
    public record FixtureTarget(int chunkX, int chunkZ, String role) { }

    public static final class MarinePlan {
        private final Samples samples;
        private final List<FixtureTarget> targets;

        private MarinePlan(Samples samples, List<FixtureTarget> targets) {
            this.samples = samples;
            this.targets = List.copyOf(targets);
        }

        public List<FixtureTarget> targets() { return targets; }

        private String describe() {
            return "signed_coarse=" + COARSE_MIN + ".." + COARSE_MAX + ",diversitySector=" + COARSE_DIVERSITY_SECTOR
                    + ",coarseCandidates=" + samples.coarseCandidates + ",rawTiles=" + samples.rawTiles
                    + ",maxRawTilesPerKind=" + MAX_RAW_TILES_PER_KIND + ",no_chunk_requests";
        }
    }

    private record Samples(Sample trench, Sample shallow, Sample land, int coarseCandidates, int rawTiles) { }
    private record CoarseCandidate(int x, int z, float signedMeters, int estimatedGroundY) { }
    private record TileKey(int x, int z) { }

    private static final class RawGroundLookup {
        private final int size = LocalTerrainProvider.sampleTileSize();
        private final Map<TileKey, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();
        int tileReads;

        TileKey keyFor(int x, int z) {
            return new TileKey(Math.floorDiv(x, size) * size, Math.floorDiv(z, size) * size);
        }

        LocalTerrainProvider.HeightmapData tile(TileKey key) {
            LocalTerrainProvider.HeightmapData cached = tiles.get(key);
            if (cached != null) return cached;
            LocalTerrainProvider.HeightmapData fetched = LocalTerrainProvider.getInstance()
                    .fetchGroundHeightmap(key.z, key.x, key.z + size, key.x + size);
            if (fetched == null || fetched.heightmap == null)
                throw new IllegalStateException("Raw TD ground tile unavailable at " + key.x + "," + key.z);
            tiles.put(key, fetched);
            tileReads++;
            return fetched;
        }
    }
    private static final class Sample {
        final int x, z, groundY;
        String biome = ""; double continentalness, densityAtGroundMinusOne, densityAtGroundPlusOne;
        double densityAtZero = Double.NaN, densityAtMinusFifty = Double.NaN;
        Sample(int x, int z, int groundY) { this.x = x; this.z = z; this.groundY = groundY; }
        String coordinate() { return x + "," + z + ",rawGround=" + groundY; }
        String describe() { return coordinate() + ",biome=" + biome + ",continentalness=" + continentalness
                + ",below=" + densityAtGroundMinusOne + ",above=" + densityAtGroundPlusOne
                + (Double.isNaN(densityAtZero) ? "" : ",y0=" + densityAtZero + ",y-50=" + densityAtMinusFifty); }
    }

    private static Object rootDelegate(BiomeSource source) {
        try {
            Method method = source.getClass().getMethod("rootDelegate");
            return method.invoke(source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inspect Lithostitched root delegate", e);
        }
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    private static String json(String event, Object... kv) {
        StringBuilder out = new StringBuilder("{\"event\":\"").append(escape(event)).append('\"');
        for (int i = 0; i < kv.length; i += 2) out.append(",\"").append(escape(String.valueOf(kv[i])))
                .append("\":\"").append(escape(String.valueOf(kv[i + 1]))).append('\"');
        return out.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
