package com.github.xandergos.terraindiffusionmc.mixin.memory;

import com.github.xandergos.terraindiffusionmc.world.structure.TemplatePositionInterner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Limits coordinate sharing to the NBT template loader, never runtime structure block infos. */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplatePositionMixin {
    @ModifyArg(
            method = "loadPalette(Lnet/minecraft/core/HolderGetter;Lnet/minecraft/nbt/ListTag;Lnet/minecraft/nbt/ListTag;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/nbt/CompoundTag;)V"),
            index = 0,
            require = 1,
            allow = 1)
    private BlockPos terrainDiffusion$internLoadedTemplatePosition(BlockPos position) {
        return TemplatePositionInterner.intern(position);
    }
}
