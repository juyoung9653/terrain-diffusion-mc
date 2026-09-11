package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.chunk.ChunkAccess;

/** Per-{@code CarvingContext} state; never shared between chunk carving passes. */
public interface LowlandCarvingContext {
    boolean terrainDiffusion$isWorld();

    int terrainDiffusion$seaLevel();

    int terrainDiffusion$originalSurfaceY(ChunkAccess chunk, int localX, int localZ);
}
