package com.terrain.leafocclusion.mixin;

import com.terrain.leafocclusion.LeafOcclusion;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
abstract class SodiumLeafOcclusionMixin {
    @Inject(method = "renderModel(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void tdLeaf$cullInterior(BakedModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        BlockRenderContextAccessor access = (BlockRenderContextAccessor) (Object) this;
        if (LeafOcclusion.shouldCull(access.tdLeaf$getLevel(), state, pos)) ci.cancel();
    }
}
