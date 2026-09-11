package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.CoarseBiomeHints;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
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
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
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
                    Codec.BOOL.optionalFieldOf("terralith", false).forGetter(source -> source.terralith)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private final HolderGetter<Biome> biomeLookup;
    private final boolean terralith;
    private final Map<Short, Holder<Biome>> terralithBiomes;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this(biomeLookup, false);
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, boolean terralith) {
        this.biomeLookup = biomeLookup;
        this.terralith = terralith;
        Map<Short, Holder<Biome>> entries = new HashMap<>();
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
        requireBiomeIdMap();
        return Stream.concat(biomeIdMap.values().stream(), terralithBiomes.values().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        return biomeFromTile(blockX, QuartPos.toBlock(y), blockZ, blockStartX, blockStartZ, data);
    }

    private Holder<Biome> biomeFromTile(int blockX, int blockY, int blockZ,
                                        int blockStartX, int blockStartZ, HeightmapData data) {
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            if (terralith && data.terralithBiomeIds != null) {
                short surfaceId = data.terralithBiomeIds[localZ][localX];
                int surfaceY = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
                short caveId = TerralithBiomeClassifier.selectCave(blockX, blockY, blockZ,
                        surfaceY, surfaceId);
                Holder<Biome> selected = terralithBiomes.get(caveId == 0 ? surfaceId : caveId);
                if (selected != null) return selected;
            }
            Holder<Biome> entry = biomeIdMap.get(data.biomeIds[localZ][localX]);
            if (entry != null) return entry;
        }

        return biomeIdMap.get((short) 1);
    }

    /** Called only by concentric-ring placement; ordinary biome searches remain unchanged. */
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
