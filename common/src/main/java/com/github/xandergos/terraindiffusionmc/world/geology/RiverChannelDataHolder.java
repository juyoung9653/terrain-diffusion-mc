package com.github.xandergos.terraindiffusionmc.world.geology;

import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelData;

import java.util.Map;

/** Internal bridge from shared chunk data helpers to their ChunkAccess mixin field. */
public interface RiverChannelDataHolder {
    Map<Integer, RiverChannelData.Entry> terrainDiffusion$riverChannelEntries();
}
