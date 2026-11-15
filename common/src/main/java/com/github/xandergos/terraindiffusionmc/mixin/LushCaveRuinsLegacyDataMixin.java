package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.LushCaveRuinsLegacyData;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Decode the installed LCR surface marker on 1.21.1 without editing its JAR. */
@Mixin(RegistryDataLoader.class)
public class LushCaveRuinsLegacyDataMixin {
    @ModifyExpressionValue(method = "loadElementFromResource", at = @At(value = "INVOKE",
            target = "Lcom/google/gson/JsonParser;parseReader(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"),
            require = 1, allow = 1)
    private static JsonElement terrainDiffusion$lushRuinsLegacyData(JsonElement original,
            @Local(argsOnly = true) ResourceKey<?> key) {
        return LushCaveRuinsLegacyData.adapt(key.registry().toString(), key.location().toString(), original);
    }
}
