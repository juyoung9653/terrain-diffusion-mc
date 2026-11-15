package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.LushCaveRuinsLegacyData;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LootDataType.class)
public class LushCaveRuinsLegacyLootMixin {
    @ModifyVariable(method = "deserialize", at = @At("HEAD"), argsOnly = true, index = 3, require = 1)
    private Object terrainDiffusion$lushRuinsLoot(Object original,
            @Local(argsOnly = true) ResourceLocation id) {
        if ((Object) this != LootDataType.TABLE || !(original instanceof JsonElement json)) return original;
        return LushCaveRuinsLegacyData.adaptLoot(id.toString(), json);
    }
}
