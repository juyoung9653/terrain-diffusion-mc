package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureCheck.class)
public abstract class StructureCheckMixin {
    @Shadow @Final private BiomeSource biomeSource;

    @Inject(method = "canCreateStructure", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$deferLockedTerrainProbe(ChunkPos pos, Structure structure,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (biomeSource.getClass() == TerrainDiffusionBiomeSource.class) {
            // Async Locator synchronizes checkStart AND onStructureLoad on this object.
            // A speculative generation point can wait for native terrain for seconds.
            // True means CHUNK_LOAD_NEEDED, never START_PRESENT: the caller still checks
            // the actual STRUCTURE_STARTS chunk. Saved starts and placement restrictions
            // were already checked before this method. Bounded alternate jigsaw origins
            // may succeed when the original stub fails, so unlocked TD probes also defer.
            cir.setReturnValue(true);
        }
    }
}
