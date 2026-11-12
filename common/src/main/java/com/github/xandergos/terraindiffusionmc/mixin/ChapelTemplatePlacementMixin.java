package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.structure.ChapelTerrainProcessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SinglePoolElement.class)
public abstract class ChapelTemplatePlacementMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z"))
    private boolean terrainDiffusion$preserveChapelSurroundings(StructureTemplate template,
            ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
            RandomSource random, int flags, Operation<Boolean> original) {
        var element = (SinglePoolElement)(Object)this;
        var access = (SinglePoolTemplateAccess)this;
        if (level instanceof WorldGenRegion
                && TerrainDiffusionBiomeSources.isTerrainDiffusion(level.getLevel().getChunkSource().getGenerator().getBiomeSource())
                && element.getClass() == SinglePoolElement.class
                && element.getProjection() == StructureTemplatePool.Projection.RIGID
                && access.terrainDiffusion$processors().value().list().isEmpty()
                && access.terrainDiffusion$templateIdentity().left().filter(ChapelTerrainProcessor::handles).isPresent()) {
            settings = settings.copy().addProcessor(new ChapelTerrainProcessor(template));
        }
        return original.call(template, level, offset, pivot, settings, random, flags);
    }
}
