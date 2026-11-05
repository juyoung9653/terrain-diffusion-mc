package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap=false)
abstract class StageSodiumMixin {
    private static String td$layer(RenderType type) {
        if (type == RenderType.solid()) return "sodium.layer.solid";
        if (type == RenderType.cutoutMipped()) return "sodium.layer.cutoutMipped";
        if (type == RenderType.cutout()) return "sodium.layer.cutout";
        if (type == RenderType.translucent()) return "sodium.layer.translucent";
        return "sodium.layer.other";
    }
    @Inject(method="drawChunkLayer", at=@At("HEAD"), require=1)
    private void td$begin(RenderType type, @Coerce Object matrices, double x, double y, double z, CallbackInfo ci) { RenderStageProfiler.begin(td$layer(type)); }
    @Inject(method="drawChunkLayer", at=@At("RETURN"), require=1)
    private void td$end(RenderType type, @Coerce Object matrices, double x, double y, double z, CallbackInfo ci) { RenderStageProfiler.end(td$layer(type)); }
}
