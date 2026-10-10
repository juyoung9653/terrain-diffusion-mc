package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.List;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorStructurePlacementMixin {
    @Unique private final ThreadLocal<ChunkGeneratorStructureState> terrainDiffusion$structureState=new ThreadLocal<>();
    @Unique private final ThreadLocal<StructureSet> terrainDiffusion$currentSet=new ThreadLocal<>();

    @WrapOperation(method="createStructures",at=@At(value="INVOKE",target="Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void terrainDiffusion$bindSet(List<Holder<StructureSet>> sets, Consumer<Holder<StructureSet>> action,
            Operation<Void> original, @Local(argsOnly=true) ChunkGeneratorStructureState state) {
        original.call(sets,(Consumer<Holder<StructureSet>>)holder -> {
            StructureSet previous=terrainDiffusion$currentSet.get();
            ChunkGeneratorStructureState previousState=terrainDiffusion$structureState.get();
            try {
                terrainDiffusion$structureState.set(state);
                terrainDiffusion$currentSet.set(holder.value());
                action.accept(holder);
            }
            finally {
                if (previousState==null) terrainDiffusion$structureState.remove(); else terrainDiffusion$structureState.set(previousState);
                if (previous==null) terrainDiffusion$currentSet.remove(); else terrainDiffusion$currentSet.set(previous);
            }
        });
    }

    @Redirect(method="tryGenerateStructure", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/levelgen/structure/Structure;generate(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"))
    private StructureStart terrainDiffusion$place(Structure structure, RegistryAccess access, ChunkGenerator generator,
            BiomeSource biomes, RandomState random, StructureTemplateManager templates, long seed, ChunkPos owner,
            int references, LevelHeightAccessor height, Predicate<Holder<Biome>> validBiome) {
        return StructurePlacementRuntime.generate(structure,access,generator,biomes,random,templates,seed,owner,
                references,height,validBiome,terrainDiffusion$structureState.get(),terrainDiffusion$currentSet.get());
    }
}
