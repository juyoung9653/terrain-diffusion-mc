package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread initialization and world-generation-thread lookup for the
 * finite river preview. Lookups never touch saved data.
 */
public final class WorldRiverPreviewState extends SavedData {
    private static final String DATA_NAME = "terrain_diffusion_river_preview";
    private static final Map<ServerLevel, RiverPreviewSettings> SETTINGS = new ConcurrentHashMap<>();
    private static final java.util.Set<ServerLevel> FIRST_CACHE_ALLOWED = ConcurrentHashMap.newKeySet();
    private static final RiverPreviewSettings DISABLED = RiverPreviewSettings.disabled(0L);
    private static final Codec<WorldRiverPreviewState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", false).forGetter(state -> state.settings.enabled()),
            Codec.INT.optionalFieldOf("version", 0).forGetter(state -> state.settings.version()),
            Codec.LONG.optionalFieldOf("seed", 0L).forGetter(state -> state.settings.seed()),
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE).forGetter(state -> state.settings.scale()),
            Codec.INT.optionalFieldOf("origin_x", RiverPreviewSettings.DEFAULT_ORIGIN).forGetter(state -> state.settings.originX()),
            Codec.INT.optionalFieldOf("origin_z", RiverPreviewSettings.DEFAULT_ORIGIN).forGetter(state -> state.settings.originZ()),
            Codec.INT.optionalFieldOf("size", RiverPreviewSettings.DEFAULT_SIZE).forGetter(state -> state.settings.size()),
            Codec.INT.optionalFieldOf("spacing", RiverPreviewSettings.DEFAULT_SPACING).forGetter(state -> state.settings.spacing()),
            Codec.INT.optionalFieldOf("min_catchment", RiverPreviewSettings.DEFAULT_MIN_CATCHMENT).forGetter(state -> state.settings.minCatchment()),
            Codec.INT.optionalFieldOf("max_fill_depth", RiverPreviewSettings.DEFAULT_MAX_FILL_DEPTH).forGetter(state -> state.settings.maxFillDepth()),
            Codec.INT.optionalFieldOf("max_cut", RiverPreviewSettings.DEFAULT_MAX_CUT).forGetter(state -> state.settings.maxCut())
    ).apply(instance, (enabled, version, seed, scale, originX, originZ, size, spacing, minCatchment, maxFillDepth, maxCut) ->
            new WorldRiverPreviewState(new RiverPreviewSettings(enabled, version, seed, scale, originX, originZ,
                    size, spacing, minCatchment, maxFillDepth, maxCut))));

    public static final SavedData.Factory<WorldRiverPreviewState> TYPE = new SavedData.Factory<>(
            WorldRiverPreviewState::createDefault,
            WorldRiverPreviewState::fromNbt,
            null
    );

    private RiverPreviewSettings settings;

    private WorldRiverPreviewState(RiverPreviewSettings settings) {
        this.settings = settings;
    }

    public static WorldRiverPreviewState createDefault() {
        return new WorldRiverPreviewState(RiverPreviewSettings.uninitialized());
    }

    public static WorldRiverPreviewState fromNbt(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return CODEC.parse(NbtOps.INSTANCE, nbt)
                .resultOrPartial(message -> {
                    throw new IllegalArgumentException("Invalid persisted river preview settings: " + message);
                })
                .orElseThrow();
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .resultOrPartial(message -> {
                    throw new IllegalStateException("Could not save river preview settings: " + message);
                })
                .ifPresent(encoded -> nbt.merge((CompoundTag) encoded));
        return nbt;
    }

    /**
     * Must run on the server thread before spawn preparation. A saved version-1
     * state is immutable; only a newly created world may consume JVM settings.
     */
    public static void initialize(ServerLevel level, boolean newWorld) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("River preview settings must initialize on the server thread");
        }
        if (!level.dimension().equals(Level.OVERWORLD)
                || !(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            SETTINGS.put(level, RiverPreviewSettings.disabled(level.getSeed()));
            return;
        }

        WorldRiverPreviewState state = level.getDataStorage().computeIfAbsent(TYPE, DATA_NAME);
        boolean freshState = state.settings.version() == 0;
        boolean cacheExists = java.nio.file.Files.exists(level.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-river-preview-cache-v1.bin.gz"));
        if (cacheExists && (freshState || !state.settings.enabled())) {
            throw new IllegalStateException("River cache exists without matching enabled settings; restore the world backup");
        }
        if (state.settings.version() == 0) {
            RiverPreviewSettings resolved = newWorld
                    ? RiverPreviewSettings.fromSystemProperties(level.getSeed())
                    : RiverPreviewSettings.disabled(level.getSeed());
            state.settings = resolved;
            state.setDirty();
            // Persist intent before a world-generation thread can publish a cache
            // or carve a chunk. A crash must not silently disable an enabled world.
            level.getDataStorage().save();
        }
        if (state.settings.enabled() && state.settings.seed() != level.getSeed()) {
            throw new IllegalStateException("Persisted river preview seed does not match this world");
        }
        if (state.settings.enabled() && state.settings.scale() != WorldScaleManager.getCurrentScale()) {
            throw new IllegalStateException("Persisted river preview scale does not match this world");
        }
        if (state.settings.enabled() && freshState && newWorld) FIRST_CACHE_ALLOWED.add(level);
        SETTINGS.put(level, state.settings);
    }

    /**
     * Returns a cached value only. World-generation threads must not create or
     * load saved data when no bootstrap value is present.
     */
    public static RiverPreviewSettings settings(ServerLevel level) {
        return SETTINGS.getOrDefault(level, DISABLED);
    }

    static boolean mayCreateFirstCache(ServerLevel level) { return FIRST_CACHE_ALLOWED.contains(level); }

    public static void clear() {
        SETTINGS.clear();
        FIRST_CACHE_ALLOWED.clear();
        RiverPreviewService.clear();
    }
}
