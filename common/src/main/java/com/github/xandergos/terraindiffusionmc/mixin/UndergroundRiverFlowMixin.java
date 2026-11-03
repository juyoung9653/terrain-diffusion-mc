package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRivers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class UndergroundRiverFlowMixin {
    @Inject(method = "getFlow", at = @At("RETURN"), cancellable = true)
    private void terrainDiffusion$caveCurrent(BlockGetter view, BlockPos pos, FluidState fluid,
                                              CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(UndergroundRivers.flow(view, pos, fluid, cir.getReturnValue()));
    }
}
