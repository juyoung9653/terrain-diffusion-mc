package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.CoarseBiomeHints;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.LevelReader;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final Codec<String> SHOWCASE_CODEC = Codec.STRING.validate(name ->
            name.isEmpty() || EcologyClassifier.BIOMES.contains(name) ? DataResult.success(name)
                    : DataResult.error(() -> "Unknown ecology showcase biome: " + name));
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionBiomeSource.class);
    private final AtomicBoolean ringSearchStarted = new AtomicBoolean();
    private final AtomicInteger completedRingSearches = new AtomicInteger();
    private final AtomicInteger searchedRingTiles = new AtomicInteger();
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME),
                    Codec.BOOL.optionalFieldOf("terralith", false).forGetter(source -> source.terralith),
                    Codec.BOOL.optionalFieldOf("natural_biomes", false).forGetter(source -> source.naturalBiomes),
                    SHOWCASE_CODEC.optionalFieldOf("ecology_showcase", "").forGetter(source -> source.ecologyShowcase),
                    Codec.BOOL.optionalFieldOf("underground", false).forGetter(source -> source.underground),
                    Codec.intRange(0, 6).optionalFieldOf("underground_scale", 0).forGetter(source -> source.undergroundScale),
                    Codec.BOOL.optionalFieldOf("underground_rivers", false).forGetter(source -> source.undergroundRivers),
                    Codec.intRange(1, 3).optionalFieldOf("underground_river_version", 1)
                            .forGetter(source -> source.undergroundRiverVersion),
                    Codec.intRange(1, 2).optionalFieldOf("underground_profile_version", 1)
                            .forGetter(source -> source.undergroundProfileVersion)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private final HolderGetter<Biome> biomeLookup;
    private final boolean terralith;
    private final boolean naturalBiomes;
    private final String ecologyShowcase;
    private final boolean underground;
    private final boolean undergroundRivers;
    private final int undergroundRiverVersion;
    private final int undergroundProfileVersion;
    private volatile int undergroundScale;
    private final Map<Integer, Holder<Biome>> ecologicalBiomes;
    private final Map<Short, Holder<Biome>> terralithBiomes;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this(biomeLookup, false);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith) {
        this(biomeLookup, terralith, false);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes) {
        this(biomeLookup, terralith, naturalBiomes, "");
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase) {
        this(biomeLookup, terralith, naturalBiomes, ecologyShowcase, false);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase, boolean underground) {
        this(biomeLookup, terralith, naturalBiomes, ecologyShowcase, underground, 0);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase, boolean underground, int undergroundScale) {
        this(biomeLookup, terralith, naturalBiomes, ecologyShowcase, underground, undergroundScale, false);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase, boolean underground, int undergroundScale, boolean undergroundRivers) {
        this(biomeLookup, terralith, naturalBiomes, ecologyShowcase, underground, undergroundScale,
                undergroundRivers, 1);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase, boolean underground, int undergroundScale,
                                       boolean undergroundRivers, int undergroundRiverVersion) {
        this(biomeLookup, terralith, naturalBiomes, ecologyShowcase, underground, undergroundScale,
                undergroundRivers, undergroundRiverVersion, 1);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith, boolean naturalBiomes,
                                       String ecologyShowcase, boolean underground, int undergroundScale,
                                       boolean undergroundRivers, int undergroundRiverVersion, int undergroundProfileVersion) {
        if (undergroundProfileVersion < 1 || undergroundProfileVersion > 2
                || undergroundRiverVersion < 1 || undergroundRiverVersion > 3)
            throw new IllegalArgumentException("Unsupported underground generation profile");
        if (undergroundProfileVersion == 2 && !underground)
            throw new IllegalArgumentException("Deep profile requires underground terrain");
        if (underground && undergroundRivers && (undergroundRiverVersion == 3) != (undergroundProfileVersion == 2))
            throw new IllegalArgumentException("Underground river V3 requires geology profile V2 and vice versa");
        if (naturalBiomes && !terralith) throw new IllegalArgumentException("natural_biomes requires terralith=true and the compatibility pack");
        if (!ecologyShowcase.isEmpty() && (!naturalBiomes || !EcologyClassifier.BIOMES.contains(ecologyShowcase)))
            throw new IllegalArgumentException("ecology_showcase requires natural_biomes and a known ecology biome");
        this.biomeLookup = biomeLookup;
        this.terralith = terralith;
        this.naturalBiomes = naturalBiomes;
        this.ecologyShowcase = ecologyShowcase;
        this.underground = underground;
        this.undergroundRivers = underground && undergroundRivers;
        this.undergroundRiverVersion = undergroundRiverVersion;
        this.undergroundProfileVersion = undergroundProfileVersion;
        this.undergroundScale = undergroundScale;
        Map<Integer, Holder<Biome>> ecological = new HashMap<>();
        if (naturalBiomes) {
            for (int i = 0; i < EcologyClassifier.BIOMES.size(); i++) {
                ecological.put(i + 1, biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME,
                        ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/" + EcologyClassifier.BIOMES.get(i)))));
            }
        }
        this.ecologicalBiomes = Map.copyOf(ecological);
        Map<Short, Holder<Biome>> entries = new HashMap<>();
        if (naturalBiomes) {
            entries.put(ShoreClassifier.BEACH, biomeLookup.getOrThrow(Biomes.BEACH));
            entries.put(ShoreClassifier.SNOWY_BEACH, biomeLookup.getOrThrow(Biomes.SNOWY_BEACH));
            entries.put(ShoreClassifier.STONY_SHORE, biomeLookup.getOrThrow(Biomes.STONY_SHORE));
        }
        if (terralith) {
            // Fail during world loading if the required pack is missing, rather
            // than silently generating a different world in unexplored chunks.
            short id = 1;
            for (String name : TerralithBiomeClassifier.biomeNames()) {
                entries.put(id++, biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME,
                        ResourceLocation.parse(name))));
            }
        }
        this.terralithBiomes = Map.copyOf(entries);
        requireBiomeIdMap();
    }

    public boolean naturalBiomes() { return naturalBiomes; }
    public boolean undergroundRivers() { return undergroundRivers; }
    public int undergroundRiverVersion() { return undergroundRiverVersion; }
    public int undergroundProfileVersion() { return undergroundProfileVersion; }
    public String ecologyShowcase() { return ecologyShowcase; }

    /** Called on world load, before async generation; the codec preserves this identity. */
    public synchronized void bindUndergroundScale(int scale) {
        if (!underground) return;
        int selected = WorldScaleManager.clampScale(scale);
        if (undergroundScale != 0 && undergroundScale != selected)
            throw new IllegalStateException("Underground world scale is fixed at " + undergroundScale
                    + "; changing saved scale to " + selected + " would split existing terrain");
        undergroundScale = selected;
    }

    public int boundUndergroundScale() {
        if (undergroundScale == 0) throw new IllegalStateException("Underground scale was not bound on world load");
        return undergroundScale;
    }

    private int undergroundScale() {
        // Unbound sources exist in the creation preview, whose terrain also uses this scope.
        return undergroundScale == 0 ? WorldScaleManager.getCurrentScale() : undergroundScale;
    }

    /** Immutable-source adapter for verified background searches; never fetches another tile. */
    Holder<Biome> fastLocateBiome(int blockX, int blockY, int blockZ, int startX, int startZ,
                                HeightmapData data) {
        if (data == null || data.biomeIds == null)
            throw new IllegalStateException("Exact biome tile unavailable");
        return biomeFromTile(blockX, blockY, blockZ, startX, startZ, data);
    }

    /** Coarse labels are ranking hints only, including in the natural ecology preset. */
    Holder<Biome> fastLocateHint(int x, int z,
            com.github.xandergos.terraindiffusionmc.pipeline.BroadEcologyClimate climate,
            CoarseBiomeHints hints) {
        if (naturalBiomes) {
            if (!ecologyShowcase.isEmpty()) return showcaseBiome();
            var v = climate.sample(x, z);
            var selection = EcologyClassifier.select(x, z, v.elevation(), 0, false,
                    v.temperature(), v.precipitation(), v.elevation());
            return selection.terrainBiomeId() != 0 ? terralithBiomes.get(selection.terrainBiomeId())
                    : ecologicalBiomes.get(selection.sample().biomeId());
        }
        Holder<Biome> hint = terralith ? terralithBiomes.get(hints.terralithAt(x, z)) : null;
        return hint != null ? hint : biomeIdMap.get(hints.vanillaAt(x, z));
    }

    private Holder<Biome> showcaseBiome() {
        return ecologicalBiomes.get(EcologyClassifier.BIOMES.indexOf(ecologyShowcase) + 1);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            biomeIdMap = Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
            );
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        if (!ecologyShowcase.isEmpty()) return Stream.concat(
                Stream.concat(Stream.of(showcaseBiome()), biomeIdMap.entrySet().stream()
                        .filter(entry -> EcologyShowcaseProfile.isVanillaOcean(entry.getKey())).map(Map.Entry::getValue)),
                terralithBiomes.entrySet().stream()
                        .filter(entry -> EcologyShowcaseProfile.retainsTerrainBiome(entry.getKey()))
                        .map(Map.Entry::getValue)).distinct();
        requireBiomeIdMap();
        return Stream.concat(Stream.concat(biomeIdMap.values().stream(), terralithBiomes.values().stream()),
                ecologicalBiomes.values().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = LocalTerrainProvider.sampleTileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchBiomeHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        return biomeFromTile(blockX, QuartPos.toBlock(y), blockZ, blockStartX, blockStartZ, data);
    }

    private Holder<Biome> biomeFromTile(int blockX, int blockY, int blockZ,
                                        int blockStartX, int blockStartZ, HeightmapData data) {
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            if (terralith && data.terralithBiomeIds != null) {
                if (naturalBiomes && data.ecology == null) throw new IllegalStateException("Natural ecology data missing");
                short surfaceId = naturalBiomes ? data.ecology.terrainBiomeId(localX, localZ)
                        : data.terralithBiomeIds[localZ][localX];
                if (!ecologyShowcase.isEmpty()) {
                    if (EcologyShowcaseProfile.retainsTerrainBiome(surfaceId)) {
                        Holder<Biome> marine = terralithBiomes.get(surfaceId);
                        if (marine == null) throw new IllegalStateException("Missing showcase marine biome: " + surfaceId);
                        return marine;
                    }
                    short vanillaId = data.biomeIds[localZ][localX];
                    if (EcologyShowcaseProfile.isVanillaOcean(vanillaId)) return biomeIdMap.get(vanillaId);
                    return showcaseBiome();
                }
                int surfaceY = underground
                        ? HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX], undergroundScale(), undergroundProfileVersion)
                        : HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
                short caveId = TerralithBiomeClassifier.selectCave(blockX, blockY, blockZ,
                        surfaceY, surfaceId, underground, undergroundScale());
                if (naturalBiomes && caveId == 0) {
                    Holder<Biome> ecological = ecologicalBiomes.get(data.ecology.biomeId(localX, localZ));
                    if (ecological != null) return ecological;
                }
                Holder<Biome> selected = terralithBiomes.get(caveId == 0 ? surfaceId : caveId);
                if (selected != null) return selected;
            }
            Holder<Biome> entry = biomeIdMap.get(data.biomeIds[localZ][localX]);
            if (entry != null) return entry;
        }

        return biomeIdMap.get((short) 1);
    }

    /** Exact /locate biome traversal with request-local reuse of consecutive tile reads. */
    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos pos, int radius,
            int horizontalStep, int verticalStep, Predicate<Holder<Biome>> biomePredicate,
            Climate.Sampler sampler, LevelReader level) {
        // A subclass may override getNoiseBiome; preserve its virtual dispatch.
        if (getClass() != TerrainDiffusionBiomeSource.class
                || "false".equalsIgnoreCase(System.getProperty("td.locate.biomeCache")))
            return super.findClosestBiome3d(pos, radius, horizontalStep, verticalStep,
                    biomePredicate, sampler, level);
        Set<Holder<Biome>> accepted = possibleBiomes().stream().filter(biomePredicate)
                .collect(Collectors.toUnmodifiableSet());
        if (accepted.isEmpty()) return null;
        Predicate<Holder<Biome>> matches = accepted::contains;
        int steps = Math.floorDiv(radius, horizontalStep);
        int[] heights = Mth.outFromOrigin(pos.getY(), level.getMinBuildHeight() + 1,
                level.getMaxBuildHeight(), verticalStep).toArray();
        // Only these two modes return before all Y-dependent cave selection in biomeFromTile.
        boolean verticallyInvariant = !terralith || !ecologyShowcase.isEmpty();
        ExactBiomeSearch<HeightmapData, Holder<Biome>> search = new ExactBiomeSearch<>(
                LocalTerrainProvider.sampleTileSize(),
                (x, z, size) -> LocalTerrainProvider.getInstance()
                        .fetchBiomeHeightmap(z, x, z + size, x + size),
                this::biomeFromTile, verticallyInvariant);
        // Keep Minecraft's own spiral and vertical sequence: first-hit/tie order is unchanged.
        for (BlockPos.MutableBlockPos offset : BlockPos.spiralAround(BlockPos.ZERO, steps,
                Direction.EAST, Direction.SOUTH)) {
            int x = pos.getX() + offset.getX() * horizontalStep;
            int z = pos.getZ() + offset.getZ() * horizontalStep;
            ExactBiomeSearch.Match<Holder<Biome>> match = search.findColumn(x, z, heights, matches);
            if (match != null) return Pair.of(new BlockPos(x, match.blockY(), z), match.biome());
        }
        return null;
    }

    /** Called only by concentric-ring placement; separate from the command search above. */
    public Pair<BlockPos, Holder<Biome>> findRingBiome(int x, int y, int z, int radius,
                                                     Predicate<Holder<Biome>> preferred, RandomSource random) {
        requireBiomeIdMap();
        if (collectPossibleBiomes().noneMatch(preferred)) return null;
        int tileSize = TerrainDiffusionConfig.tileSize();
        int scale = WorldScaleManager.getCurrentScale();
        List<RingBiomeSearch.Tile> tiles = RingBiomeSearch.tiles(x, z, radius, tileSize);
        if (ringSearchStarted.compareAndSet(false, true)) {
            LOG.info("Preparing structure ring positions with coarse-ranked terrain tiles (tile size {})", tileSize);
        }
        int qr = radius / 4;
        int minX = (Math.floorDiv(x, 4) - qr) * 4, maxX = (Math.floorDiv(x, 4) + qr) * 4;
        int minZ = (Math.floorDiv(z, 4) - qr) * 4, maxZ = (Math.floorDiv(z, 4) + qr) * 4;
        CoarseBiomeHints hints;
        try {
            hints = LocalTerrainProvider.getCoarseBiomeHints(minX, minZ, maxX, maxZ, scale);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during structure biome search", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare structure biome hints", e);
        }
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        int blockY = QuartPos.toBlock(QuartPos.fromBlock(y));
        RingBiomeSearch.Result<Holder<Biome>> result = RingBiomeSearch.find(tiles, tile -> {
            // Count approximate matches over the tile's search portion, not just its center.
            int score = 0;
            for (int qz : new int[]{tile.minQuartZ(), (tile.minQuartZ() + tile.maxQuartZ()) >> 1, tile.maxQuartZ()}) {
                for (int qx : new int[]{tile.minQuartX(), (tile.minQuartX() + tile.maxQuartX()) >> 1, tile.maxQuartX()}) {
                    Holder<Biome> predicted = terralith ? terralithBiomes.get(hints.terralithAt(qx * 4, qz * 4)) : null;
                    if (predicted == null) predicted = biomeIdMap.get(hints.vanillaAt(qx * 4, qz * 4));
                    if (predicted != null && preferred.test(predicted)) score++;
                }
            }
            return score;
        }, tile -> {
            // The closure retains this exact tile through the whole scan, even if the
            // shared LRU cache is evicted by other concurrent structure searches.
            HeightmapData data = provider.fetchHeightmap(tile.blockZ(), tile.blockX(),
                    tile.blockZ() + tileSize, tile.blockX() + tileSize);
            return (bx, bz) -> biomeFromTile(bx, blockY, bz, tile.blockX(), tile.blockZ(), data);
        }, preferred, random::nextInt);
        int totalTiles = searchedRingTiles.addAndGet(result.loadedTiles());
        int finished = completedRingSearches.incrementAndGet();
        if (finished % 16 == 0) LOG.info("Structure biome searches: {} complete, {} detailed tile reads", finished, totalTiles);
        LOG.debug("Structure biome search at ({}, {}): {} tiles, {} samples, preferred biome found={}",
                x, z, result.loadedTiles(), result.testedSamples(), result.biome() != null);
        return result.biome() == null ? null : Pair.of(new BlockPos(result.blockX(), y, result.blockZ()), result.biome());
    }
}
