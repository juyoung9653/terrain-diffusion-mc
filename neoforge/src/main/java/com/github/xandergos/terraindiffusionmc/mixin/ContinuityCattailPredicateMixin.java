package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailHeightMarker;
import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailWaterDepth;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.IntPredicate;

@Pseudo
@Mixin(targets = "me.pepperbell.continuity.client.processor.BaseProcessingPredicate", remap = false)
public abstract class ContinuityCattailPredicateMixin {
    @Shadow protected IntPredicate heightPredicate;
    @Unique private boolean terrainDiffusion$cattailDepth;

    @Inject(method = "<init>(Ljava/util/EnumSet;Ljava/util/function/Predicate;Ljava/util/function/IntPredicate;Ljava/util/function/Predicate;)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void terrainDiffusion$readDepthMarker(CallbackInfo callback) {
        terrainDiffusion$cattailDepth = heightPredicate instanceof CattailHeightMarker;
        if (terrainDiffusion$cattailDepth) heightPredicate = null;
    }

    @Inject(method = "shouldProcessQuad(Lnet/fabricmc/fabric/api/renderer/v1/mesh/QuadView;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lme/pepperbell/continuity/api/client/ProcessingDataProvider;)Z",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$checkLocalWaterDepth(@Coerce Object quad, TextureAtlasSprite sprite,
            BlockAndTintGetter view, BlockState appearanceState, BlockState state, BlockPos pos,
            @Coerce Object dataProvider, CallbackInfoReturnable<Boolean> callback) {
        if (terrainDiffusion$cattailDepth && callback.getReturnValueZ()
                && !CattailWaterDepth.allows(view, pos, state)) callback.setReturnValue(false);
    }
}
