package com.github.xandergos.terraindiffusionmc.mixin;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(PoolElementStructurePiece.class)
public interface PoolElementPieceAccess {
    @Accessor("structureTemplateManager") StructureTemplateManager terrainDiffusion$templates();
}
