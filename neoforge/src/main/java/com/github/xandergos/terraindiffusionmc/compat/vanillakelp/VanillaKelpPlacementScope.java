package com.github.xandergos.terraindiffusionmc.compat.vanillakelp;

import com.github.xandergos.terraindiffusionmc.mixin.vanillakelp.VanillaKelpHeightmapAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.vanillakelp.VanillaKelpNoiseCountAccessor;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** TD-only scope for the unmodified vanilla cold and warm kelp chains. */
public final class VanillaKelpPlacementScope {
    private static final Entry COLD = new Entry(id("kelp_cold"), 120,
            0x3EAE9B59A7F116C1L, VanillaKelpPlacementPolicy.COLD_ACCEPTANCE);
    private static final Entry WARM = new Entry(id("kelp_warm"), 80,
            0x76D4F9082C5AB137L, VanillaKelpPlacementPolicy.WARM_ACCEPTANCE);
    private static final ThreadLocal<Entry> ACTIVE = new ThreadLocal<>();

    private VanillaKelpPlacementScope() { }

    public static Scope enter(ResourceLocation placedFeature, List<PlacementModifier> placement,
                              PlacementContext context) {
        Entry entry = entry(placedFeature);
        Entry previous = ACTIVE.get();
        TerrainDiffusionBiomeSource biomeSource = entry == null ? null
                : TerrainDiffusionBiomeSources.find(context.generator().getBiomeSource());
        if (entry == null || !context.getLevel().getLevel().dimension().equals(Level.OVERWORLD)
                || !isOriginalChain(placement, entry.noiseToCountRatio)
                || biomeSource == null || !biomeSource.naturalBiomes()) {
            if (previous == null) return Scope.NOOP;
            ACTIVE.remove();
            return () -> restore(previous);
        }
        ACTIVE.set(entry);
        return () -> restore(previous);
    }

    public static boolean replacesNoiseCount() {
        return ACTIVE.get() != null;
    }

    public static boolean accept(WorldGenLevel level, BlockPos pos) {
        Entry entry = ACTIVE.get();
        return entry == null || VanillaKelpPlacementPolicy.accept(level.getSeed(), entry.salt,
                entry.acceptance, waterDepth(level, pos), pos.getX(), pos.getZ());
    }

    /** Inspect only the loaded candidate column; five represents normal/deeper ocean water. */
    private static int waterDepth(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos scan = pos.mutable();
        int depth = 0;
        while (depth < 5 && scan.getY() < level.getMaxBuildHeight()
                && level.getBlockState(scan).is(net.minecraft.world.level.block.Blocks.WATER)) {
            depth++;
            scan.move(0, 1, 0);
        }
        return depth;
    }

    private static boolean isOriginalChain(List<PlacementModifier> placement, int ratio) {
        if (placement.size() != 4
                || !(placement.get(0) instanceof NoiseBasedCountPlacement noise)
                || !(placement.get(1) instanceof InSquarePlacement)
                || !(placement.get(2) instanceof HeightmapPlacement heightmap)
                || !(placement.get(3) instanceof BiomeFilter)) return false;
        VanillaKelpNoiseCountAccessor noiseAccess = (VanillaKelpNoiseCountAccessor) noise;
        return noiseAccess.terrainDiffusion$noiseToCountRatio() == ratio
                && noiseAccess.terrainDiffusion$noiseFactor() == 80.0
                && noiseAccess.terrainDiffusion$noiseOffset() == 0.0
                && ((VanillaKelpHeightmapAccessor) heightmap).terrainDiffusion$heightmap()
                == Heightmap.Types.OCEAN_FLOOR_WG;
    }

    private static Entry entry(ResourceLocation id) {
        if (COLD.id.equals(id)) return COLD;
        return WARM.id.equals(id) ? WARM : null;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static void restore(Entry previous) {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }

    private record Entry(ResourceLocation id, int noiseToCountRatio, long salt, double acceptance) { }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> { };
        @Override void close();
    }
}
