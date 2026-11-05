package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.DeepOceanFluidPolicy;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundGeneration;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Binds the profile-2 picker to each saved world's actual build floor. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class DeepOceanFluidPickerMixin {
    @Unique private boolean terrainDiffusion$deepOceanProfile;
    @Unique private int terrainDiffusion$seaLevel;
    @Unique private BlockState terrainDiffusion$defaultFluid;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrainDiffusion$detectDeepOceanProfile(BiomeSource biomeSource,
            Holder<NoiseGeneratorSettings> settings, CallbackInfo ci) {
        var source = TerrainDiffusionBiomeSources.find(biomeSource);
        this.terrainDiffusion$deepOceanProfile = source != null
                && source.undergroundProfileVersion() == 2
                && settings.is(UndergroundGeneration.V2_SETTINGS);
        if (this.terrainDiffusion$deepOceanProfile) {
            NoiseGeneratorSettings value = settings.value();
            this.terrainDiffusion$seaLevel = value.seaLevel();
            this.terrainDiffusion$defaultFluid = value.defaultFluid();
        }
    }

    @Redirect(method = "createNoiseChunk", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private Object terrainDiffusion$chunkFluidPicker(Supplier<Aquifer.FluidPicker> vanilla,
            ChunkAccess chunk, StructureManager structureManager, Blender blender, RandomState random) {
        return this.terrainDiffusion$picker(vanilla, chunk.getMinBuildHeight());
    }

    @Redirect(method = "iterateNoiseColumn", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private Object terrainDiffusion$columnFluidPicker(Supplier<Aquifer.FluidPicker> vanilla,
            LevelHeightAccessor level, RandomState random, int x, int z,
            MutableObject<?> column, Predicate<BlockState> stoppingState) {
        return this.terrainDiffusion$picker(vanilla, level.getMinBuildHeight());
    }

    @Unique
    private Aquifer.FluidPicker terrainDiffusion$picker(Supplier<Aquifer.FluidPicker> vanilla, int minY) {
        if (!this.terrainDiffusion$deepOceanProfile) return vanilla.get();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(minY + 8, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(this.terrainDiffusion$seaLevel,
                this.terrainDiffusion$defaultFluid);
        return (x, y, z) -> DeepOceanFluidPolicy.useLava(y, minY, this.terrainDiffusion$seaLevel)
                ? lava : water;
    }
}
