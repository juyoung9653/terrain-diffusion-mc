package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

@Mixin(ChunkGeneratorStructureState.class)
public class ChunkGeneratorStructureStateMixin {
    // The invocation lives in an async synthetic lambda. Match its unique callsite
    // rather than a lambda name that changes between Mojang and Fabric mappings.
    @Redirect(method = "*", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/biome/BiomeSource;findBiomeHorizontal(IIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/biome/Climate$Sampler;)Lcom/mojang/datafixers/util/Pair;"), require = 1, allow = 1)
    private Pair<BlockPos, Holder<Biome>> terrainDiffusion$findRingBiome(BiomeSource source,
            int x, int y, int z, int radius, Predicate<Holder<Biome>> preferred,
            RandomSource random, Climate.Sampler sampler) {
        if (source instanceof TerrainDiffusionBiomeSource) {
            // Null retains the original ring position in Minecraft's caller. Do
            // not query terrain here: even bounded searches generate distant AI
            // terrain during world startup. Ordinary biome searches bypass this
            // callsite and remain available, including /locate biome.
            return null;
        }
        return source.findBiomeHorizontal(x, y, z, radius, preferred, random, sampler);
    }
}
