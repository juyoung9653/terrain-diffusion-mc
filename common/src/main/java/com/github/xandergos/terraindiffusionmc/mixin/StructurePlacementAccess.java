package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Optional;

@Mixin(StructurePlacement.class)
public interface StructurePlacementAccess {
    @Accessor("exclusionZone") Optional<StructurePlacement.ExclusionZone> terrainDiffusion$exclusionZone();
}
