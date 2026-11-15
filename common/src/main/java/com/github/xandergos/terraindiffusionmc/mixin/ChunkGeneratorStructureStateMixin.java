package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.RingBiomeSourcePolicy;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import com.github.xandergos.terraindiffusionmc.world.structure.UnifiedVillageSets;
import com.github.xandergos.terraindiffusionmc.world.structure.LushCaveRuinsCompatibility;
import com.github.xandergos.terraindiffusionmc.world.structure.InstanceStructureCompatibility;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import java.util.Map;

import java.util.function.Predicate;

@Mixin(ChunkGeneratorStructureState.class)
public class ChunkGeneratorStructureStateMixin {
    @Shadow @Final private BiomeSource biomeSource;
    @Shadow @Final @Mutable private List<Holder<StructureSet>> possibleStructureSets;
    @Shadow @Final private Map<Structure, List<StructurePlacement>> placementsForStructure;

    @Inject(method="hasBiomesForStructureSet", at=@At("RETURN"), cancellable=true, require=1)
    private static void terrainDiffusion$lushRuinsSet(StructureSet set, BiomeSource source,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && (LushCaveRuinsCompatibility.retainsSet(set, source)
                || InstanceStructureCompatibility.retainsSet(set, source)))
            cir.setReturnValue(true);
    }

    @Inject(method="generatePositions", at=@At("TAIL"), require=1)
    private void terrainDiffusion$lushRuinsPlacements(CallbackInfo ci) {
        LushCaveRuinsCompatibility.addPlacements(biomeSource, possibleStructureSets, placementsForStructure);
        InstanceStructureCompatibility.addPlacements(biomeSource, possibleStructureSets, placementsForStructure);
    }

    @Inject(method="<init>",at=@At("RETURN"))
    private void terrainDiffusion$unifiedVillages(CallbackInfo ci) {
        possibleStructureSets=UnifiedVillageSets.rewrite(biomeSource,possibleStructureSets);
    }

    // The invocation lives in an async synthetic lambda. Match its unique callsite
    // rather than a lambda name that changes between Mojang and Fabric mappings.
    @Redirect(method = "*", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/biome/BiomeSource;findBiomeHorizontal(IIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/biome/Climate$Sampler;)Lcom/mojang/datafixers/util/Pair;"), require = 1, allow = 1)
    private Pair<BlockPos, Holder<Biome>> terrainDiffusion$findRingBiome(BiomeSource source,
            int x, int y, int z, int radius, Predicate<Holder<Biome>> preferred,
            RandomSource random, Climate.Sampler sampler) {
        if (RingBiomeSourcePolicy.skipRelocation(source)) {
            // Null retains the original ring position in Minecraft's caller. Do
            // not query terrain here: even bounded searches generate distant AI
            // terrain during world startup. Ordinary biome searches bypass this
            // callsite and remain available, including /locate biome.
            return null;
        }
        return source.findBiomeHorizontal(x, y, z, radius, preferred, random, sampler);
    }
}
