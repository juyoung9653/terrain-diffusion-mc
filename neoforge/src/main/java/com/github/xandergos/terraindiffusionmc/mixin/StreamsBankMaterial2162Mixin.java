package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNativeBankFill;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Streams 2.16.2 retains the one native bank-material choice and its separate
 * exposed-top helper, but changes the implementation classes. The hooks retain
 * native ring selection and random draws. V13 separately bounds native
 * containment fills before they can build detached soil walls.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsBankMaterial2162Mixin {
    private static final String BANK_HELPER =
            "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
                    + "Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/"
                    + "BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/"
                    + "state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;"
                    + "IZLjava/util/List;Ljava/util/List;Ljava/util/Map;"
                    + "Ldev/streamsreflowing/worldgen/ProprietaryNiceJohn_003;II"
                    + "Ljava/lang/String;ZZLdev/streamsreflowing/core/compose/ColumnPlan;)V";
    private static final String TOP_HELPER =
            "Ldev/streamsreflowing/worldgen/RiverCarverFeature;"
                    + "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
                    + "Lnet/minecraft/core/BlockPos$MutableBlockPos;III"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)V";
    private static final String NATIVE_BANK_FILL =
            "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
                    + "Ldev/streamsreflowing/core/RiverEngine;"
                    + "Lnet/minecraft/core/BlockPos$MutableBlockPos;"
                    + "Ldev/streamsreflowing/core/compose/ColumnPlan;IIIIIZII)V";

    @Inject(method = NATIVE_BANK_FILL, at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private static void terrainDiffusion$guardNativeBankFill(WorldGenLevel level,
            @Coerce Object engine, BlockPos.MutableBlockPos mutable, @Coerce Object plan,
            int ignored4, int ignored5, int targetX, int targetZ, int crestY,
            boolean mayAddLip, int minY, int ignored11, CallbackInfo ci) {
        if (!StreamsNativeBankFill.allows(level, targetX, targetZ, crestY, minY, mayAddLip)) {
            ci.cancel();
        }
    }

    // Ordinal 0 restores the containing crest; ordinal 1 adds the optional raised lip.
    @WrapOperation(method = NATIVE_BANK_FILL,
            at = @At(value = "INVOKE", target = TOP_HELPER, ordinal = 1),
            require = 1, remap = false)
    private static void terrainDiffusion$guardRaisedBankLip(WorldGenLevel level,
            BlockPos.MutableBlockPos mutable, int x, int y, int z, BlockState proposed, int minY,
            Operation<Void> original, WorldGenLevel enclosingLevel, @Coerce Object engine,
            BlockPos.MutableBlockPos enclosingMutable, @Coerce Object plan,
            int ignored4, int ignored5, int targetX, int targetZ, int crestY,
            boolean mayAddLip, int enclosingMinY, int seaLevel) {
        if (StreamsNativeBankFill.allowsLip(level, engine, x, y, z)) {
            original.call(level, mutable, x, y, z, proposed, minY);
        }
    }

    /**
     * 2.16.2 writes the exposed recap at ordinal 12, then its short/tall grass
     * decoration at ordinals 13--15. These calls occur after its native bank
     * material pass, so they do not alter its bank geometry or random draws.
     */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/"
                    + "FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
                    + "WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 12),
            require = 1, remap = false)
    private boolean terrainDiffusion$climateRecap(WorldGenLevel level, BlockPos pos,
            BlockState proposed, int flags, Operation<Boolean> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, pos.getX(), pos.getY(), pos.getZ(), proposed)
                : proposed;
        return original.call(level, pos, material, flags);
    }

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/"
                    + "FeaturePlaceContext;)Z",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
                            + "WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 13),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
                            + "WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 14),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
                            + "WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 15)
            }, require = 3, remap = false)
    private boolean terrainDiffusion$recapVegetation(WorldGenLevel level, BlockPos pos,
            BlockState proposed, int flags, Operation<Boolean> original) {
        if (WorldNaturalRiverState.version(level.getLevel()) >= 10) {
            boolean upper = proposed.is(Blocks.TALL_GRASS)
                    && proposed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            BlockState support = level.getBlockState(pos.below());
            if (upper ? !support.is(Blocks.TALL_GRASS) : !support.is(Blocks.GRASS_BLOCK)) return false;
        }
        return original.call(level, pos, proposed, flags);
    }

    /**
     * The 2.16.2 carver also raises exposed caps through its private top helper.
     * Those five direct calls in {@code place} bypass the recap setBlock ordinal,
     * so route the proposed cap through the same original-climate policy before
     * the helper writes it.
     */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/"
                    + "FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = TOP_HELPER),
            require = 5, remap = false)
    private void terrainDiffusion$climateRaisedCap(WorldGenLevel level,
            BlockPos.MutableBlockPos mutable, int x, int y, int z, BlockState proposed, int minY,
            Operation<Void> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, x, y, z, proposed)
                : proposed;
        original.call(level, mutable, x, y, z, material, minY);
    }

    @WrapOperation(
            method = BANK_HELPER,
            at = @At(value = "INVOKE", target =
                    "Lstreamsreflowing/proprietary/AllRightsReservedNiceJohn_009;"
                            + "proprietary1(JIIIDDLstreamsreflowing/proprietary/"
                            + "AllRightsReservedNiceJohn_009$LicensedCodeNotForAnalysis_001;I)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1,
            remap = false)
    private static BlockState terrainDiffusion$preserveSelectedNaturalShoreMaterial(
            @Coerce Object ring, long seed, int x, int y, int z, double width, double distance,
            @Coerce Object metrics, int ringIndex, Operation<BlockState> original,
            @Local(argsOnly = true) WorldGenLevel level,
            @Local(argsOnly = true, index = 22) boolean pointBar) {
        BlockState proposed = original.call(ring, seed, x, y, z, width, distance, metrics, ringIndex);
        if (WorldNaturalRiverState.version(level.getLevel()) >= 7) {
            return StreamsBankMaterialPolicy.preserveV7NaturalShoreSubstrate(
                    level, new BlockPos.MutableBlockPos(x, y, z), ringIndex, pointBar, proposed);
        }
        return proposed == null ? null : StreamsBankMaterialPolicy.preserveNaturalShoreSubstrate(
                level, x, z, ringIndex, pointBar, proposed);
    }

    @WrapOperation(
            method = BANK_HELPER,
            at = @At(value = "INVOKE", target = TOP_HELPER),
            require = 1,
            remap = false)
    private static void terrainDiffusion$climateBankTop(WorldGenLevel level,
            BlockPos.MutableBlockPos mutable, int x, int y, int z, BlockState proposed, int minY,
            Operation<Void> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 7
                ? StreamsBankMaterialPolicy.climateBankTop(level, x, y, z, proposed)
                : proposed;
        original.call(level, mutable, x, y, z, material, minY);
    }
}
