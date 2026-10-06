package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Intercepts the selected bank state before its top and depth-fill writes in audited Streams versions.
 * The native helper receives x, z, and scanTop.  Its legacy ordinal capture is intentionally
 * frozen for profiles 1-6; v7 reads the correctly positioned mutable surface instead.
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
            @Local(argsOnly = true) BlockPos.MutableBlockPos surface,
            @Local(argsOnly = true, ordinal = 0) int blockX,
            @Local(argsOnly = true, ordinal = 2) int blockZ) {
        BlockState proposed = original.call(style, zone, noise, pointBar);
        // Native sets this mutable position to the scanned solid surface before zoneBlock.
        // V7 uses it because the legacy ordinal-2 capture is scanTop, retained for v1-v6.
        if (WorldNaturalRiverState.version(level.getLevel()) >= 7) {
            return StreamsBankMaterialPolicy.preserveV7NaturalShoreSubstrate(level, surface, zone, pointBar, proposed);
        }
        return StreamsBankMaterialPolicy.preserveNaturalShoreSubstrate(level, blockX, blockZ, zone, pointBar, proposed);
    }
}
