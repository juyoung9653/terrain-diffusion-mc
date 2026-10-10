package com.github.xandergos.terraindiffusionmc.compat.lush;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/** Re-adds the single Lush feature after TD's safe default removal when its adapter is active. */
public record LushEcologyVegetationModifier(HolderSet<Biome> biomes,
                                            HolderSet<PlacedFeature> features) implements BiomeModifier {
    public static final MapCodec<LushEcologyVegetationModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Biome.LIST_CODEC.fieldOf("biomes").forGetter(LushEcologyVegetationModifier::biomes),
                    PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(LushEcologyVegetationModifier::features))
                    .apply(instance, LushEcologyVegetationModifier::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase == Phase.AFTER_EVERYTHING && LushCompatibility.adapterAvailable() && biomes.contains(biome)) {
            features.forEach(feature -> builder.getGenerationSettings()
                    .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, feature));
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
