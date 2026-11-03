package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelData;
import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelDataHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessRiverChannelDataMixin implements RiverChannelDataHolder {
    @Unique
    private final Map<Integer, RiverChannelData.Entry> terrainDiffusion$riverChannelEntries = RiverChannelData.newStorage();

    @Override
    public Map<Integer, RiverChannelData.Entry> terrainDiffusion$riverChannelEntries() {
        return terrainDiffusion$riverChannelEntries;
    }
}
