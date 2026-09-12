package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Intercepts Streams 2.13.1's selected bank state before its top and depth-fill writes.
 * The renderer receives coordinates as x, y, z; only the first and third int arguments
 * select the TD ecology field.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsBankMaterialMixin {
    @WrapOperation(
            method = "a(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos$MutableBlockPos;IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Map;IILjava/lang/String;ZZ)V",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/bank/BankStyle;zoneBlock(IDZ)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1,
            remap = false)
    private static BlockState terrainDiffusion$preserveSelectedNaturalShoreMaterial(
            @Coerce Object style, int zone, double noise, boolean pointBar, Operation<BlockState> original,
            @Local(argsOnly = true) WorldGenLevel level,
            @Local(argsOnly = true, ordinal = 0) int blockX,
            @Local(argsOnly = true, ordinal = 2) int blockZ) {
        BlockState proposed = original.call(style, zone, noise, pointBar);
        return StreamsBankMaterialPolicy.preserveNaturalShoreSubstrate(level, blockX, blockZ, zone, pointBar, proposed);
    }
}
