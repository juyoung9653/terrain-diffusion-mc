package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(JigsawStructure.class)
public abstract class JigsawStructureOriginMixin {
    // Before both vanilla placement and Lithostitched's redirected AlternateJigsawStructure.
    @ModifyVariable(method="findGenerationPoint",at=@At("STORE"),ordinal=0)
    private BlockPos terrainDiffusion$alternateOrigin(BlockPos origin) {
        return StructurePlacementRuntime.offsetJigsaw(origin);
    }
}
