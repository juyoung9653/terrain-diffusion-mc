package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic.HybridAquaticKelpHeightmapAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic.HybridAquaticKelpNoiseCountAccessor;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** TD-only scope for the original, unmodified HA kelp placement chain. */
public final class HybridAquaticKelpPlacementScope {
    private static final Entry BULL_KELP = new Entry(id("bull_kelp"), 0x44CBA31A0D1237E1L, true);
    private static final Entry DELESSERIA = new Entry(id("delesseria"), 0x6FC1E0B9782A5D43L, false);
    private static final Entry SARGASSUM = new Entry(id("sargassum"), 0x1985B3E42D96CF07L, false);
    private static final ThreadLocal<Entry> ACTIVE = new ThreadLocal<>();

    private HybridAquaticKelpPlacementScope() { }

    public static Scope enter(ResourceLocation placedFeature, List<PlacementModifier> placement, PlacementContext context) {
        Entry entry = entry(placedFeature);
        Entry previous = ACTIVE.get();
        if (entry == null && previous == null) return Scope.NOOP;
        TerrainDiffusionBiomeSource biomeSource = entry == null ? null
                : TerrainDiffusionBiomeSources.find(context.generator().getBiomeSource());
        if (entry == null || !context.getLevel().getLevel().dimension().equals(Level.OVERWORLD) || !isOriginalChain(placement)
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
        return entry == null || HybridAquaticKelpPlacementPolicy.accept(level.getSeed(), entry.salt,
                entry.shallowShelf ? waterDepth(level, pos) : 12, pos.getX(), pos.getZ());
    }

    /** The bounded scan is independent of neighbouring chunk generation order. */
    private static int waterDepth(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos scan = pos.mutable();
        int depth = 0;
        while (depth < 12 && scan.getY() < level.getMaxBuildHeight()
                && level.getFluidState(scan).is(FluidTags.WATER)) {
            depth++;
            scan.move(0, 1, 0);
        }
        return depth;
    }

    private static boolean isOriginalChain(List<PlacementModifier> placement) {
        if (placement.size() != 4
                || !(placement.get(0) instanceof NoiseBasedCountPlacement noise)
                || !(placement.get(1) instanceof InSquarePlacement)
                || !(placement.get(2) instanceof HeightmapPlacement heightmap)
                || !(placement.get(3) instanceof BiomeFilter)) return false;
        HybridAquaticKelpNoiseCountAccessor noiseAccess = (HybridAquaticKelpNoiseCountAccessor) noise;
        return noiseAccess.terrainDiffusion$noiseToCountRatio() == 120
                && noiseAccess.terrainDiffusion$noiseFactor() == 80.0
                && noiseAccess.terrainDiffusion$noiseOffset() == 0.0
                && ((HybridAquaticKelpHeightmapAccessor) heightmap).terrainDiffusion$heightmap() == Heightmap.Types.OCEAN_FLOOR;
    }

    private static Entry entry(ResourceLocation placedFeature) {
        if (BULL_KELP.id.equals(placedFeature)) return BULL_KELP;
        if (DELESSERIA.id.equals(placedFeature)) return DELESSERIA;
        return SARGASSUM.id.equals(placedFeature) ? SARGASSUM : null;
    }

    private static void restore(Entry previous) {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("hybrid_aquatic", path);
    }

    private record Entry(ResourceLocation id, long salt, boolean shallowShelf) { }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> { };
        @Override void close();
    }
}
