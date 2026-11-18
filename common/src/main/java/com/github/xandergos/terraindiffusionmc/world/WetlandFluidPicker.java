package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

import java.lang.ref.WeakReference;

/** Uses the same immutable terrain tile for native height queries and chunk filling. */
public final class WetlandFluidPicker implements Aquifer.FluidPicker {
    private final Aquifer.FluidPicker underlying;
    private final BlockState fluid;
    private final int seaLevel;
    private volatile Tile tile;
    private volatile Water water;

    private record Tile(LocalTerrainProvider provider, long generation, long seed, int scale, int x, int z,
                        WeakReference<LocalTerrainProvider.HeightmapData> data) { }
    private record Water(int level, Aquifer.FluidStatus status) { }

    public WetlandFluidPicker(Aquifer.FluidPicker underlying, BlockState fluid, int seaLevel) {
        this.underlying = underlying;
        this.fluid = fluid;
        this.seaLevel = seaLevel;
    }

    @Override
    public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
        // Coastal water already belongs to the original picker. Never raise deep aquifers
        // or fill caves beneath a perched freshwater basin.
        if (y < seaLevel) return underlying.computeFluid(x, y, z);
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        int size = LocalTerrainProvider.sampleTileSize();
        int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
        long seed = LocalTerrainProvider.getSeed();
        int scale = WorldScaleManager.getCurrentScale();
        long generation;
        LocalTerrainProvider.HeightmapData data;
        while (true) {
            generation = provider.terrainGeneration();
            Tile current = tile;
            data = current == null ? null : current.data.get();
            if (data != null && current.provider == provider && current.generation == generation
                    && current.seed == seed && current.scale == scale && current.x == x0 && current.z == z0
                    && data.width == size && data.height == size) {
                // Check once more before using the weak tile. A clear after this
                // point may finish the in-flight lookup with old data, but it
                // cannot publish or retain that data for the new generation.
                if (generation == provider.terrainGeneration()) break;
                continue;
            }
            // A cache clear can race the fetch. Publish only a tile whose
            // generation was observed both before and after it was produced;
            // otherwise a pre-clear tile could be labeled as current.
            do {
                generation = provider.terrainGeneration();
                data = provider.fetchGroundHeightmap(z0, x0, z0 + size, x0 + size);
            } while (generation != provider.terrainGeneration());
            tile = new Tile(provider, generation, seed, scale, x0, z0, new WeakReference<>(data));
            if (generation == provider.terrainGeneration()) break;
        }
        int level = data.wetlandWaterLevels == null ? 0 : data.wetlandWaterLevels[z - z0][x - x0];
        if (level <= seaLevel || y < level - 3 || y >= level)
            return underlying.computeFluid(x, y, z);
        Water cached = water;
        if (cached == null || cached.level != level) {
            cached = new Water(level, new Aquifer.FluidStatus(level, fluid));
            water = cached;
        }
        return cached.status;
    }
}
