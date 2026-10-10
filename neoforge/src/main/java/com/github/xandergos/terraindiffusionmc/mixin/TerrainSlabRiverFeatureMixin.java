package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.terrainslabs.TerrainSlabRiverGuard;
import com.github.xandergos.terraindiffusionmc.compat.terrainslabs.TerrainSlabSurface;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Audited Terrain Slabs 3.1.2 candidate hooks; manual slab placement is unaffected. */
@Pseudo
@Mixin(targets = "net.countered.terrainslabs.generation.SlabFeature", remap = false)
public abstract class TerrainSlabRiverFeatureMixin {
    @WrapOperation(method = "setBlockState(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1, remap = false)
    private boolean terrainDiffusion$mineralSurfaceSlab(LevelAccessor level, BlockPos pos, BlockState state,
                                                        int flags, Operation<Boolean> original) {
        return original.call(level, pos, TerrainSlabSurface.material(level, pos, state), flags);
    }

    @Inject(method = "shouldPlaceBottomSlab", at = @At("RETURN"), cancellable = true, require = 1)
    private void terrainDiffusion$protectBottomSlab(WorldGenLevel level, BlockPos pos,
                                                    boolean isMaxY, boolean neighbourCanBeSlab,
                                                    CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && TerrainSlabRiverGuard.protects(level, pos)) result.setReturnValue(false);
    }

    @Inject(method = "shouldPlaceTopSlab", at = @At("RETURN"), cancellable = true, require = 1)
    private void terrainDiffusion$protectTopSlab(WorldGenLevel level, BlockPos pos,
                                                 CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && TerrainSlabRiverGuard.protects(level, pos)) result.setReturnValue(false);
    }
}
