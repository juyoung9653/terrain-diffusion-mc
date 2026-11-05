package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic.TerrainDiffusionBiomeSourceAccessor;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;

/** Adapts placement coordinates; HA still owns carving, predicates and biome replacement. */
public final class HybridAquaticCompatibility {
    private static volatile WeakReference<TerrainDiffusionBiomeSource> active = new WeakReference<>(null);
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private HybridAquaticCompatibility() { }

    public static NoiseRouter adaptRouter(NoiseRouter router, MinecraftServer server) {
        if (TerrainDiffusionLifecycle.isPreviewServer(server)) return router;
        // loadLevel HEAD runs before server.overworld() exists.
        LevelStem stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        TerrainDiffusionBiomeSource terrain = stem == null ? null
                : TerrainDiffusionBiomeSources.find(stem.generator().getBiomeSource());
        active = new WeakReference<>(null);
        if (terrain == null) return router;
        try {
            boolean enabled = HybridAquaticWorldProfile.resolve(
                    server.getWorldPath(LevelResource.ROOT),
                    !server.getWorldData().overworldData().isInitialized(),
                    server.getWorldData().worldGenOptions().seed(), biomesEnabled());
            if (!enabled) return router;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot preserve this world's Hybrid Aquatic marine profile", failure);
        }
        active = new WeakReference<>(terrain);
        LoggerFactory.getLogger(HybridAquaticCompatibility.class).info(
                "Hybrid Aquatic native marine generation uses TD ground depth; trench band is 48..64 blocks deep before carving");
        return new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                new HybridAquaticContinentalnessDensityFunction(terrain.undergroundProfileVersion()),
                router.erosion(), router.depth(),
                router.ridges(), router.initialDensityWithoutJaggedness(), router.finalDensity(),
                router.veinToggle(), router.veinRidged(), router.veinGap());
    }

    private static boolean biomesEnabled() {
        var path = FMLPaths.CONFIGDIR.get().resolve("hybrid_aquatic.json");
        if (!Files.exists(path)) return true; // HA's default; its load hook normally creates this first.
        try (var reader = Files.newBufferedReader(path)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var biomes = root.getAsJsonObject("biomes");
            if (biomes == null || !biomes.has("enableBiomes")) return true;
            var enabled = biomes.get("enableBiomes");
            if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("enableBiomes must be boolean");
            return enabled.getAsBoolean();
        } catch (Exception failure) {
            LoggerFactory.getLogger(HybridAquaticCompatibility.class).warn(
                    "Cannot read Hybrid Aquatic biome configuration; TD marine adapter is inactive", failure);
            return false;
        }
    }

    public static Scope enter(BiomeSource source, int quartX, int quartZ) {
        Scope previous = SCOPE.get();
        TerrainDiffusionBiomeSource terrain = TerrainDiffusionBiomeSources.find(source);
        if (terrain != null && terrain == active.get()) SCOPE.set(new Scope(terrain, quartX * 4, quartZ * 4));
        else SCOPE.remove();
        return previous;
    }

    public static void restore(Scope previous) {
        if (previous == null) SCOPE.remove();
        else SCOPE.set(previous);
    }

    public static Holder<Biome> alias(Holder<Biome> actual) {
        Scope scope = SCOPE.get();
        if (scope == null) return actual;
        var key = actual.unwrapKey().map(ResourceKey::location).orElse(null);
        if (key == null) return actual;
        // Reject nonmarine IDs before sampling any terrain.
        if (HybridAquaticMarineProfile.oceanAlias(key.getNamespace(), key.getPath(), 0) == null) return actual;
        if (scope.groundY == Integer.MIN_VALUE)
            scope.groundY = HybridAquaticContinentalnessDensityFunction.groundHeight(
                    scope.x, scope.z, scope.terrain.undergroundProfileVersion());
        String alias = HybridAquaticMarineProfile.oceanAlias(key.getNamespace(), key.getPath(), scope.groundY);
        if (alias == null) return actual;
        var lookup = ((TerrainDiffusionBiomeSourceAccessor) scope.terrain).terrainDiffusion$biomeLookup();
        var candidate = lookup.get(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(alias)));
        return candidate.isPresent() ? candidate.get() : actual;
    }

    public static final class Scope {
        private final TerrainDiffusionBiomeSource terrain;
        private final int x, z;
        private int groundY = Integer.MIN_VALUE;
        private Scope(TerrainDiffusionBiomeSource terrain, int x, int z) {
            this.terrain = terrain; this.x = x; this.z = z;
        }
    }
}
