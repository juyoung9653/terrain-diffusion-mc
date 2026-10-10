package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverBanks;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="dev.streamsreflowing.worldgen.RiverCarverFeature", remap=false)
public final class StreamsRiverBanksMixin {
    @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at=@At(value="INVOKE",target="Ldev/streamsreflowing/worldgen/RiverCarverFeature;a(IILjava/lang/String;II)I"),
            require=1,remap=false)
    private int terrainDiffusion$connectedPools(int x,int z,String reason,int current,int proposed,
            Operation<Integer> original,FeaturePlaceContext<NoneFeatureConfiguration> context) {
        return original.call(x,z,reason,current,StreamsRiverRaster.poolGrade(context.level(),x,z,reason,current,proposed));
    }
    @Inject(method="place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at=@At("RETURN"), require=1, remap=false)
    private void terrainDiffusion$finishBanks(FeaturePlaceContext<NoneFeatureConfiguration> context, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) StreamsRiverBanks.finish(context.level(), context.origin().getX() & ~15, context.origin().getZ() & ~15);
        else StreamsRiverBanks.finishDryLakeShore(context.level(), context.origin().getX() & ~15, context.origin().getZ() & ~15);
    }
}
