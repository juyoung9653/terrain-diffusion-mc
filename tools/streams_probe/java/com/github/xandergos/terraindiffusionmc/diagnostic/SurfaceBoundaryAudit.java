package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.ecology.EcoSurfaceFeature;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.ecology.SurfaceBoundaryDither;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only native fixture for the production final-surface boundary path. */
public final class SurfaceBoundaryAudit {
    private static final String ECOLOGY_PREFIX = "ecology/";

    private SurfaceBoundaryAudit() { }

    public static String run(ServerLevel level, int centerX, int centerZ, int radius) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("SurfaceBoundaryAudit must run on the server thread");
        try {
            Fixture fixture = findFixture(level, centerX, centerZ, Math.max(0, Math.min(128, radius)));
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Holder<Biome> targetHolder = level.getBiome(fixture.target());
            ResourceLocation targetId = id(targetHolder);
            Holder<Biome> donorHolder = registry.entrySet().stream()
                    .filter(entry -> isEcology(entry.getKey().location()))
                    .filter(entry -> !entry.getKey().location().equals(targetId))
                    .map(entry -> (Holder<Biome>) registry.getHolder(entry.getKey()).orElseThrow())
                    .findFirst().orElseThrow(() -> new IllegalStateException("second ecology biome unavailable"));
            Holder<Biome> riverHolder = registry.getHolderOrThrow(Biomes.RIVER);

            ReflectionPath path = ReflectionPath.create();
            NaturalEcology.SurfaceSample donor = fixture.donorSample();
            float targetSlope = fixture.targetSample().climate().slope();
            EcologyClassifier.Sample slopeCarrier = new EcologyClassifier.Sample(
                    fixture.targetSample().climate().biomeId(), .8F, 2F, 1.5F, targetSlope);
            EcologyClassifier.Sample expectedDonor = SurfaceBoundaryDither.selectClimate(true, slopeCarrier,
                    fixture.targetSample().elevation(), donor.climate(), donor.elevation());
            FeatureEcologyMath.SurfaceMaterial donorMaterial = material(expectedDonor,
                    fixture.targetSample().elevation(), level.getSeed(), fixture.target());
            EcologyClassifier.Sample syntheticTarget = donorMaterial == FeatureEcologyMath.SurfaceMaterial.GRASS
                    ? new EcologyClassifier.Sample(slopeCarrier.biomeId(), .1F, 28F, .10F, targetSlope)
                    : slopeCarrier;
            NaturalEcology.SurfaceSample target = new NaturalEcology.SurfaceSample(syntheticTarget,
                    (short) 0, fixture.targetSample().elevation(), fixture.targetSample().snowRetention());
            FeatureEcologyMath.SurfaceMaterial targetMaterial = material(syntheticTarget, target.elevation(),
                    level.getSeed(), fixture.target());

            var provider = LocalTerrainProvider.getInstance();
            var rejectBefore = provider.performanceSnapshot();
            EcologyClassifier.Sample same = path.invoke(proxy(level, targetHolder), fixture.target(), targetId,
                    target, level.getSeed());
            EcologyClassifier.Sample river = path.invoke(proxy(level, riverHolder), fixture.target(), targetId,
                    target, level.getSeed());
            var rejectAfter = provider.performanceSnapshot();
            require(same == syntheticTarget, "same-holder path did not retain target climate");
            require(river == syntheticTarget, "non-TD river donor did not retain target climate");
            require(rejectAfter.hits() == rejectBefore.hits()
                            && rejectAfter.misses() == rejectBefore.misses()
                            && rejectAfter.computedWindows() == rejectBefore.computedWindows(),
                    "rejected donor touched the terrain provider");

            Holder<Biome> sourceTargetBefore = sourceBiome(level, fixture.target());
            Holder<Biome> sourceDonorBefore = sourceBiome(level, fixture.donor());
            var mixedBefore = provider.performanceSnapshot();
            EcologyClassifier.Sample selected = path.invoke(proxy(level, donorHolder), fixture.target(), targetId,
                    target, level.getSeed());
            var mixedAfter = provider.performanceSnapshot();
            Holder<Biome> sourceTargetAfter = sourceBiome(level, fixture.target());
            Holder<Biome> sourceDonorAfter = sourceBiome(level, fixture.donor());

            require(selected.biomeId() == donor.climate().biomeId(), "mixed holder did not select donor climate");
            require(selected.treeCover() == donor.climate().treeCover()
                            && selected.moisture() == donor.climate().moisture(),
                    "mixed holder did not retain donor cover and moisture");
            require(selected.slope() == syntheticTarget.slope(), "mixed holder replaced target slope");
            require(Math.abs(selected.temperature() - expectedDonor.temperature()) < .0001F,
                    "mixed holder did not lapse-correct donor temperature to target height");
            FeatureEcologyMath.SurfaceMaterial selectedMaterial = material(selected, target.elevation(),
                    level.getSeed(), fixture.target());
            require(selectedMaterial == donorMaterial && selectedMaterial != targetMaterial,
                    "production boundary path did not change material through donor climate ownership");
            require(sourceTargetBefore.equals(sourceTargetAfter) && sourceDonorBefore.equals(sourceDonorAfter),
                    "surface dither changed biome-source IDs");
            require(mixedAfter.misses() == mixedBefore.misses()
                            && mixedAfter.computedWindows() == mixedBefore.computedWindows(),
                    "cached same-reference donor started terrain inference");

            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("event", "surface_boundary_audit");
            receipt.put("status", "passed");
            receipt.put("target", fixture.target().toShortString());
            receipt.put("donor", fixture.donor().toShortString());
            receipt.put("targetHolder", targetId.toString());
            receipt.put("fixtureDonorHolder", id(donorHolder).toString());
            receipt.put("sourceTarget", id(sourceTargetAfter).toString());
            receipt.put("sourceDonor", id(sourceDonorAfter).toString());
            receipt.put("targetMaterial", targetMaterial.toString());
            receipt.put("selectedMaterial", selectedMaterial.toString());
            receipt.put("targetElevation", target.elevation());
            receipt.put("donorElevation", donor.elevation());
            receipt.put("targetSlope", selected.slope());
            receipt.put("providerHits", mixedAfter.hits() - mixedBefore.hits());
            receipt.put("providerMisses", mixedAfter.misses() - mixedBefore.misses());
            receipt.put("computedWindows", mixedAfter.computedWindows() - mixedBefore.computedWindows());
            receipt.put("referenceTileSize", TerrainDiffusionConfig.tileSize());
            return new Gson().toJson(receipt);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Surface boundary reflection failed", error);
        }
    }

    private static Fixture findFixture(ServerLevel level, int centerX, int centerZ, int radius) {
        var generator = level.getChunkSource().getGenerator();
        int tileSize = TerrainDiffusionConfig.tileSize();
        for (int distance = 0; distance <= radius; distance++) {
            for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                for (int x = centerX - distance; x <= centerX + distance; x++) {
                    if (distance > 0 && x != centerX - distance && x != centerX + distance
                            && z != centerZ - distance && z != centerZ + distance) continue;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                    BlockPos target = new BlockPos(x, y, z);
                    if (!isEcology(idOrNull(level.getBiome(target)))) continue;
                    NaturalEcology.SurfaceSample targetSample = NaturalEcology.surfaceSample(generator, x, z);
                    if (targetSample == null || targetSample.terrainBiomeId() != 0
                            || targetSample.climate().biomeId() == 0) continue;
                    SurfaceBoundaryDither.Offset offset = SurfaceBoundaryDither.offset(level.getSeed(), x, z);
                    if (offset.x() == 0 && offset.z() == 0) continue;
                    int donorX = x + offset.x(), donorZ = z + offset.z();
                    if (Math.floorDiv(x, tileSize) != Math.floorDiv(donorX, tileSize)
                            || Math.floorDiv(z, tileSize) != Math.floorDiv(donorZ, tileSize)
                            || !level.hasChunk(donorX >> 4, donorZ >> 4)) continue;
                    NaturalEcology.SurfaceSample donorSample = NaturalEcology.surfaceSample(generator, donorX, donorZ);
                    if (donorSample == null || donorSample.terrainBiomeId() != 0
                            || donorSample.climate().biomeId() == 0) continue;
                    return new Fixture(target, new BlockPos(donorX, y, donorZ), targetSample, donorSample);
                }
            }
        }
        throw new IllegalStateException("no loaded same-reference ecology fixture within radius " + radius);
    }

    private static FeatureEcologyMath.SurfaceMaterial material(EcologyClassifier.Sample sample, float elevation,
                                                                 long seed, BlockPos pos) {
        return FeatureEcologyMath.surfaceMaterial(sample.temperature(), sample.moisture(), sample.slope(),
                sample.treeCover(), elevation, seed, pos.getX(), pos.getZ(),
                com.github.xandergos.terraindiffusionmc.world.WorldScaleManager.getCurrentScale());
    }

    private static Holder<Biome> sourceBiome(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2,
                level.getChunkSource().randomState().sampler());
    }

    private static WorldGenLevel proxy(ServerLevel level, Holder<Biome> donor) {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBiome") && args != null && args.length == 1
                            && args[0] instanceof BlockPos) return donor;
                    try {
                        return method.invoke(level, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private static ResourceLocation id(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location)
                .orElseThrow(() -> new IllegalStateException("unkeyed biome holder"));
    }

    private static ResourceLocation idOrNull(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).orElse(null);
    }

    private static boolean isEcology(ResourceLocation id) {
        return id != null && id.getNamespace().equals("terrain-diffusion-mc")
                && id.getPath().startsWith(ECOLOGY_PREFIX);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(BlockPos target, BlockPos donor, NaturalEcology.SurfaceSample targetSample,
                           NaturalEcology.SurfaceSample donorSample) { }

    private record ReflectionPath(Object sampler, Method method) {
        static ReflectionPath create() throws ReflectiveOperationException {
            Class<?> samplerClass = Class.forName(EcoSurfaceFeature.class.getName() + "$BoundedSurfaceSampler");
            Constructor<?> constructor = samplerClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object sampler = constructor.newInstance();
            Method method = EcoSurfaceFeature.class.getDeclaredMethod("boundaryClimate", WorldGenLevel.class,
                    BlockPos.class, ResourceLocation.class, NaturalEcology.SurfaceSample.class,
                    samplerClass, long.class);
            method.setAccessible(true);
            return new ReflectionPath(sampler, method);
        }

        EcologyClassifier.Sample invoke(WorldGenLevel level, BlockPos target, ResourceLocation targetBiome,
                                         NaturalEcology.SurfaceSample targetSample, long seed)
                throws ReflectiveOperationException {
            try {
                return (EcologyClassifier.Sample) method.invoke(null, level, target, targetBiome,
                        targetSample, sampler, seed);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                if (error.getCause() instanceof Error fatal) throw fatal;
                throw error;
            }
        }
    }
}
