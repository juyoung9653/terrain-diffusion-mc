package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.TreeGroundMaterialPolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.AlterGroundDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AlterGroundDecorator.class)
public abstract class SubmergedTreeGroundMixin {
    // NeoForge's two-argument bridge delegates to the event-aware overload.
    @WrapOperation(method = "placeBlockAt(Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/event/level/AlterGroundEvent$StateProvider;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"),
            require = 1, expect = 1)
    private void terrainDiffusion$retainSubmergedGround(TreeDecorator.Context context, BlockPos pos,
            BlockState proposed, Operation<Void> original) {
        if (TreeGroundMaterialPolicy.preserveGround(context.level(), pos)) {
            TreeGroundMaterialPolicy.recordSkippedWrite();
            return;
        }
        original.call(context, pos, proposed);
    }
}
