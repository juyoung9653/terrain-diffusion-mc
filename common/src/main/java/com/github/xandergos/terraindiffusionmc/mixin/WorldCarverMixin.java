package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.LowlandCarvingGuard;
import com.github.xandergos.terraindiffusionmc.world.LowlandCarvingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Snapshots the pre-carver terrain roof and protects only shallow TD lowland columns. */
@Mixin(WorldCarver.class)
public class WorldCarverMixin {
    @Unique private static final TagKey<Biome> terrainDiffusion$tidalRoof = TagKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "tidal_bank_roof"));
    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$protectLowlandRoof(CarvingContext context, CarverConfiguration config,
                                                     ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomeGetter,
                                                     CarvingMask carvingMask, BlockPos.MutableBlockPos pos,
                                                     BlockPos.MutableBlockPos checkPos, Aquifer aquifer,
                                                     MutableBoolean reachedSurface,
                                                     CallbackInfoReturnable<Boolean> cir) {
        LowlandCarvingContext carvingContext = (LowlandCarvingContext) context;
        if (!carvingContext.terrainDiffusion$isWorld()) {
            return;
        }
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int surfaceY = carvingContext.terrainDiffusion$originalSurfaceY(chunk, localX, localZ);
        int sea = carvingContext.terrainDiffusion$seaLevel();
        int water = carvingContext.terrainDiffusion$surfaceWaterLevel(chunk, localX, localZ);
        if (LowlandCarvingGuard.protects(true, sea, surfaceY, pos.getY())
                || LowlandCarvingGuard.protectsWaterBed(surfaceY, water, pos.getY())
                || (LowlandCarvingGuard.dryTidalRoof(sea, surfaceY, water, pos.getY())
                && biomeGetter.apply(pos.atY(surfaceY)).is(terrainDiffusion$tidalRoof))) {
            cir.setReturnValue(false);
        }
    }

}
