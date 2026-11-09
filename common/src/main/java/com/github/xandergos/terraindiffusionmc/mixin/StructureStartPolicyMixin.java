package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.PolicyStructureStart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureStart.class)
public abstract class StructureStartPolicyMixin implements PolicyStructureStart {
    @Unique private int terrainDiffusion$policyVersion;
    @Override public int terrainDiffusion$policyVersion() { return terrainDiffusion$policyVersion; }
    @Override public void terrainDiffusion$policyVersion(int version) { terrainDiffusion$policyVersion=version; }

    @Inject(method="placeInChunk",at=@At("RETURN"))
    private void terrainDiffusion$finishFoundation(net.minecraft.world.level.WorldGenLevel level,
            net.minecraft.world.level.StructureManager manager,net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.util.RandomSource random,net.minecraft.world.level.levelgen.structure.BoundingBox box,
            ChunkPos chunk,CallbackInfo ci) {
        com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime.finishFoundation(
                level,generator,(StructureStart)(Object)this,box,chunk);
    }

    @Inject(method="createTag",at=@At("RETURN"))
    private void terrainDiffusion$savePolicy(StructurePieceSerializationContext context, ChunkPos pos,
            CallbackInfoReturnable<CompoundTag> cir) {
        if (terrainDiffusion$policyVersion>0) cir.getReturnValue().putInt("TDPlacementPolicy",terrainDiffusion$policyVersion);
    }
    @Inject(method="loadStaticStart",at=@At("RETURN"))
    private static void terrainDiffusion$loadPolicy(StructurePieceSerializationContext context, CompoundTag tag,
            long seed, CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start=cir.getReturnValue();
        if (start!=null && start.isValid()) ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion(tag.getInt("TDPlacementPolicy"));
    }
}
