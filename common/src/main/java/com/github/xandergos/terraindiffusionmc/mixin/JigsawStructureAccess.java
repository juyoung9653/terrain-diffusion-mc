package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Optional;

@Mixin(JigsawStructure.class)
public interface JigsawStructureAccess {
    @Accessor("projectStartToHeightmap") Optional<Heightmap.Types> terrainDiffusion$surfaceProjection();
    @Accessor("startHeight") HeightProvider terrainDiffusion$startHeight();
}
