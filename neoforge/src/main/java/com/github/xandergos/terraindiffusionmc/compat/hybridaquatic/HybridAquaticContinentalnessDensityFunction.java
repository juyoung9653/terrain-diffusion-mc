package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Samples raw TD ground, never the carved router or a biome/chunk query. */
public final class HybridAquaticContinentalnessDensityFunction implements DensityFunction {
    public static final MapCodec<HybridAquaticContinentalnessDensityFunction> CODEC =
            MapCodec.unit(HybridAquaticContinentalnessDensityFunction::new);
    public static final HybridAquaticContinentalnessDensityFunction INSTANCE =
            new HybridAquaticContinentalnessDensityFunction();

    public static int groundHeight(int x, int z) {
        return new GroundCursor().height(x, z);
    }

    @Override public double compute(FunctionContext pos) {
        return HybridAquaticMarineProfile.continentalnessForHeight(groundHeight(pos.blockX(), pos.blockZ()));
    }

    @Override public void fillArray(double[] values, ContextProvider contexts) {
        GroundCursor cursor = new GroundCursor();
        for (int i = 0; i < values.length; i++) {
            FunctionContext pos = contexts.forIndex(i);
            values[i] = HybridAquaticMarineProfile.continentalnessForHeight(cursor.height(pos.blockX(), pos.blockZ()));
        }
    }

    private static final class GroundCursor {
        private HeightmapData tile;
        private int startX, startZ, size;

        int height(int x, int z) {
            int scoped = LocalTerrainProvider.scopedGroundHeight(x, z);
            if (scoped != Integer.MIN_VALUE) return HeightConverter.convertToMinecraftHeight((short) scoped);
            int currentSize = LocalTerrainProvider.sampleTileSize();
            int sx = Math.floorDiv(x, currentSize) * currentSize;
            int sz = Math.floorDiv(z, currentSize) * currentSize;
            if (tile == null || size != currentSize || startX != sx || startZ != sz) {
                startX = sx; startZ = sz; size = currentSize;
                tile = LocalTerrainProvider.getInstance().fetchGroundHeightmap(sz, sx, sz + size, sx + size);
            }
            if (tile == null || tile.heightmap == null) {
                throw new IllegalStateException("TD ground unavailable for Hybrid Aquatic marine placement");
            }
            int lx = Math.max(0, Math.min(tile.width - 1, x - startX));
            int lz = Math.max(0, Math.min(tile.height - 1, z - startZ));
            return HeightConverter.convertToMinecraftHeight(tile.heightmap[lz][lx]);
        }
    }

    @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
    @Override public double minValue() { return -1.05; }
    @Override public double maxValue() { return 1; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
