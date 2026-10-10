package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/** Restores the base vegetation before Greeneries' own ADD-phase patches run. */
public record GreeneriesNativeVegetationModifier(HolderSet<Biome> biomes,
                                                 HolderSet<PlacedFeature> features) implements BiomeModifier {
    public static final MapCodec<GreeneriesNativeVegetationModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Biome.LIST_CODEC.fieldOf("biomes").forGetter(GreeneriesNativeVegetationModifier::biomes),
                    PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(GreeneriesNativeVegetationModifier::features))
                    .apply(instance, GreeneriesNativeVegetationModifier::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase == Phase.BEFORE_EVERYTHING && biomes.contains(biome)) {
            features.forEach(feature -> builder.getGenerationSettings()
                    .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, feature));
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
