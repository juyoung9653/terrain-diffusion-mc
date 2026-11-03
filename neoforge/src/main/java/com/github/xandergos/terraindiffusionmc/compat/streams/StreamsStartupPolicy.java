package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Published before spawn generation; chunk workers perform no state or calibration I/O. */
public final class StreamsStartupPolicy {
    private static final Map<ServerLevel, Boolean> EXACT = new ConcurrentHashMap<>();

    private StreamsStartupPolicy() { }

    public static void initialize(ServerLevel level, boolean newWorld) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Streams sampler initialization requires server thread");
        if (TerrainDiffusionLifecycle.isPreviewServer(level.getServer())
                || !level.dimension().equals(Level.OVERWORLD)
                || !TerrainDiffusionBiomeSources.isTerrainDiffusion(
                        level.getChunkSource().getGenerator().getBiomeSource())) return;
        var world = level.getServer().getWorldPath(LevelResource.ROOT);
        if (!StreamsStructureShieldMixinPlugin.hasStartupAdapter()) {
            if (StreamsStartupState.hasMarker(world)) throw new IllegalStateException(
                    "This world's exact Streams terrain sampler requires an audited Streams Reflowing adapter (2.13.8, 2.15.2 or 2.16.2)");
            return;
        }
        try {
            boolean exact = StreamsStartupState.resolve(world,
                    newWorld, level.getSeed(), WorldScaleManager.getCurrentScale());
            EXACT.put(level, exact);
            if (exact) LoggerFactory.getLogger("TerrainDiffusion/StreamsStartup").info(
                    "TD Streams startup: persisted exact host terrain; skipping generic sampler calibration");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot safely resolve this world's Streams terrain sampler", failure);
        }
    }

    public static boolean useExactTerrain(ServerLevel level) { return Boolean.TRUE.equals(EXACT.get(level)); }

    public static void clear() { EXACT.clear(); }
}
