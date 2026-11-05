package com.terrain.leafocclusion.mixin;

import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext", remap = false)
public interface BlockRenderContextAccessor {
    @Accessor("level") BlockAndTintGetter tdLeaf$getLevel();
}
