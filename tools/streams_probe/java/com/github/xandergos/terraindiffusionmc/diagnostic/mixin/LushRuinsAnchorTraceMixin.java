package com.github.xandergos.terraindiffusionmc.diagnostic.mixin;

import com.github.xandergos.terraindiffusionmc.diagnostic.LushCaveRuinsAudit;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Mixin(Structure.class)
public abstract class LushRuinsAnchorTraceMixin {
    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), require = 1)
    private void terrainDiffusion$observeLushAnchor(Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        cir.getReturnValue().ifPresent(stub ->
                LushCaveRuinsAudit.observeAnchor((Structure) (Object) this, context, stub));
    }
}
