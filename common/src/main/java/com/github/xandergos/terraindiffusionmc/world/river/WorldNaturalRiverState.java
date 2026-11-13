package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

/**
 * Immutable, persisted opt-in state for the natural river generator.
 *
 * <p>Initialization happens only on the server thread. Generation threads read
 * the published cache and never access saved data.</p>
 */
public final class WorldNaturalRiverState extends SavedData {
    private static final String DATA_NAME = "terrain_diffusion_natural_rivers";
    /**
     * Each routing revision has a separate namespace. Existing worlds retain
     * their stored profile when new worlds adopt a later revision.
     */
    private static final int VERSION = NaturalRiverProfile.VERSION;
    private static final Settings DISABLED = new Settings(false, 0, 0L, WorldScaleManager.DEFAULT_SCALE);
    private static final Map<ServerLevel, Settings> SETTINGS = new ConcurrentHashMap<>();
    private static volatile boolean adapterAvailable;
    private static volatile int adapterVersion = NaturalRiverProfile.LEGACY_ADAPTER_VERSION;
    private static volatile int adapterMinimumVersion = 1;

    /** Loader bootstrap advertises an installed, audited native adapter before any world loads. */
    public static void setAdapterAvailable(boolean available) {
        setAdapterAvailable(available, NaturalRiverProfile.LEGACY_ADAPTER_VERSION);
    }

    public static void setAdapterAvailable(boolean available, int supportedVersion) {
        setAdapterAvailable(available, 1, supportedVersion);
    }

    public static void setAdapterAvailable(boolean available, int minimumVersion, int supportedVersion) {
        if (minimumVersion < 1 || minimumVersion > supportedVersion || supportedVersion > VERSION) {
            throw new IllegalArgumentException("Unsupported adapter profile " + supportedVersion);
        }
        adapterMinimumVersion = minimumVersion;
        adapterVersion = supportedVersion;
        adapterAvailable = available;
    }

    public static final SavedData.Factory<WorldNaturalRiverState> TYPE = new SavedData.Factory<>(
            WorldNaturalRiverState::createDefault,
            WorldNaturalRiverState::fromNbt,
            null
    );

    private Settings settings;

    private WorldNaturalRiverState(Settings settings) {
        this.settings = settings;
    }

    public static WorldNaturalRiverState createDefault() {
        // Version zero exists only until initialize decides whether this is a
        // new world or an unmarked legacy world.
        return new WorldNaturalRiverState(DISABLED);
    }

    public static WorldNaturalRiverState fromNbt(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        requireType(nbt, "enabled", Tag.TAG_BYTE);
        requireType(nbt, "version", Tag.TAG_INT);
        requireType(nbt, "seed", Tag.TAG_LONG);
        requireType(nbt, "scale", Tag.TAG_INT);

        int version = nbt.getInt("version");
        if (version < 1 || version > VERSION) {
            throw new IllegalArgumentException("Unsupported persisted natural river version: " + version);
        }
        if ((nbt.getByte("enabled") != 0 && nbt.getByte("enabled") != 1)
                || nbt.getInt("scale") < 1 || nbt.getInt("scale") > 6) {
            throw new IllegalArgumentException("Invalid persisted natural river enabled/scale value");
        }
        return new WorldNaturalRiverState(new Settings(nbt.getBoolean("enabled"), version,
                nbt.getLong("seed"), nbt.getInt("scale")));
    }

    private static void requireType(CompoundTag nbt, String key, int type) {
        if (!nbt.contains(key, type)) {
            throw new IllegalArgumentException("Invalid persisted natural river settings: missing or invalid " + key);
        }
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        nbt.putBoolean("enabled", settings.enabled);
        nbt.putInt("version", settings.version);
        nbt.putLong("seed", settings.seed);
        nbt.putInt("scale", settings.scale);
        return nbt;
    }

    /**
     * Resolves and persists the profile once, before it is made visible to
     * world-generation threads.
     */
    public static void initialize(ServerLevel level, boolean newWorld) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Natural river settings must initialize on the server thread");
        }
        if (TerrainDiffusionLifecycle.isPreviewServer(level.getServer())) {
            SETTINGS.put(level, DISABLED);
            return;
        }
        if (!level.dimension().equals(Level.OVERWORLD)
                || !TerrainDiffusionBiomeSources.isTerrainDiffusion(level.getChunkSource().getGenerator().getBiomeSource())) {
            SETTINGS.put(level, DISABLED);
            return;
        }

        var worldPath = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var marker = worldPath.resolve("data").resolve(DATA_NAME + ".dat");
        boolean markerExists = Files.exists(marker);
        boolean nativeCacheExists = false;
        for (int version = 1; version <= VERSION; version++) {
            nativeCacheExists |= Files.exists(worldPath.resolve("streamsreflowing/minecraft_overworld/td-natural-v" + version));
        }
        WorldNaturalRiverState state = level.getDataStorage().computeIfAbsent(TYPE, DATA_NAME);
        if (state.settings.version == 0) {
            // DimensionDataStorage logs and swallows malformed NBT/load failures. Do not
            // interpret its replacement factory value as a genuinely unmarked world.
            if (markerExists || nativeCacheExists) {
                throw new IllegalStateException("Natural river state is missing or unreadable; restore this world's matching profile and cache");
            }
            boolean enabled = newWorld && adapterAvailable
                    && !WorldRiverPreviewState.settings(level).enabled()
                    && Boolean.parseBoolean(System.getProperty("td.rivers.natural", "true"));
            state.settings = new Settings(enabled, adapterVersion, level.getSeed(), WorldScaleManager.getCurrentScale());
            // C2ME can defer DimensionDataStorage.save(), and SavedData swallows write errors.
            // Publish this first immutable marker ourselves, atomically, before generation.
            try {
                Files.createDirectories(marker.getParent());
                CompoundTag root = new CompoundTag();
                root.put("data", state.save(new CompoundTag(), level.registryAccess()));
                NbtUtils.addCurrentDataVersion(root);
                var temporary = Files.createTempFile(marker.getParent(), DATA_NAME, ".tmp");
                try {
                    NbtIo.writeCompressed(root, temporary);
                    try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    try { Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, marker); }
                } finally { Files.deleteIfExists(temporary); }
                WorldNaturalRiverState saved = fromNbt(NbtIo.readCompressed(marker, NbtAccounter.unlimitedHeap()).getCompound("data"),
                        level.registryAccess());
                if (saved.settings.enabled != state.settings.enabled || saved.settings.version != state.settings.version
                        || saved.settings.seed != state.settings.seed || saved.settings.scale != state.settings.scale) {
                    throw new IOException("profile readback mismatch");
                }
                state.setDirty(false);
            } catch (IOException e) {
                throw new IllegalStateException("Could not durably save natural river profile", e);
            }
        }
        if (state.settings.enabled && !adapterAvailable) {
            throw new IllegalStateException("This world requires the audited NeoForge Streams natural-river adapter before terrain generation");
        }
        if (state.settings.enabled && state.settings.version > adapterVersion) {
            throw new IllegalStateException("Natural river profile " + state.settings.version
                    + " requires a newer Streams adapter; this adapter supports through " + adapterVersion);
        }
        if (state.settings.enabled && state.settings.version < adapterMinimumVersion) {
            throw new IllegalStateException("Natural river profile " + state.settings.version
                    + " belongs to an older Streams adapter; use a fresh world for profile " + adapterVersion);
        }
        if (state.settings.enabled && state.settings.seed != level.getSeed()) {
            throw new IllegalStateException("Persisted natural river seed does not match this world");
        }
        if (state.settings.enabled && state.settings.scale != WorldScaleManager.getCurrentScale()) {
            throw new IllegalStateException("Persisted natural river scale does not match this world");
        }
        SETTINGS.put(level, state.settings);
    }

    /** Returns the cached profile state only. */
    public static boolean enabled(ServerLevel level) {
        return SETTINGS.getOrDefault(level, DISABLED).enabled;
    }

    /** Returns zero before initialization, otherwise the persisted format version. */
    public static int version(ServerLevel level) {
        return SETTINGS.getOrDefault(level, DISABLED).version;
    }

    public static void clear() {
        SETTINGS.clear();
    }

    private static final class Settings {
        private final boolean enabled;
        private final int version;
        private final long seed;
        private final int scale;

        private Settings(boolean enabled, int version, long seed, int scale) {
            this.enabled = enabled;
            this.version = version;
            this.seed = seed;
            this.scale = scale;
        }
    }
}
