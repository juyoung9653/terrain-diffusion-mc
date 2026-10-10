package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailDepthPolicy;
import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailHeightMarker;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Properties;
import java.util.function.IntPredicate;

@Pseudo
@Mixin(targets = "me.pepperbell.continuity.client.properties.BaseCtmProperties", remap = false)
public abstract class ContinuityCattailPropertiesMixin {
    @Shadow protected Properties properties;
    @Shadow protected ResourceLocation resourceId;
    @Shadow protected IntPredicate heightPredicate;

    @Inject(method = "parseHeights()V", at = @At("RETURN"), require = 1, remap = false)
    private void terrainDiffusion$markCattailDepth(CallbackInfo callback) {
        if (CattailDepthPolicy.isCattailRule(resourceId.getNamespace(), resourceId.getPath(),
                properties.getProperty(CattailDepthPolicy.PROPERTY), properties.getProperty("heights"))) {
            heightPredicate = CattailHeightMarker.INSTANCE;
        }
    }
}
