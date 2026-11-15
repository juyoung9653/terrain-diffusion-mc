package com.github.xandergos.terraindiffusionmc.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fast Noise 1.0.13 builds local biome palettes even above the wire limit of eight. */
@Pseudo
@Mixin(targets = "org.codeberg.zenxarch.fastnoise.noise.FastBiomeGen", remap = false)
public final class FastNoiseBiomePaletteMixin {
    @Inject(method = "populateBiomes(Lnet/minecraft/world/level/chunk/LevelChunkSection;"
            + "Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;"
            + "III[Lnet/minecraft/core/Holder;[B)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceillog2(I)I"),
            cancellable = true, require = 1, remap = false)
    private static void terrainDiffusion$globalBiomePalette(LevelChunkSection section, BiomeResolver resolver,
            Climate.Sampler sampler, int x, int y, int z, Holder<Biome>[] biomes, byte[] storage,
            CallbackInfo callback, @Local(index = 8) int size) {
        if (size <= 8) return;
        // Reuse all 64 already-sampled holders. The native section method builds a fresh
        // SECTION_BIOMES container and performs the local-to-global transition correctly.
        // Do this before Fast Noise publishes its incompatible Data; never repair packets.
        section.fillBiomesFromNoise((qx, qy, qz, ignored) ->
                biomes[Byte.toUnsignedInt(storage[((qy - y) << 4) | ((qz - z) << 2) | (qx - x)])],
                sampler, x, y, z);
        callback.cancel();
    }
}
