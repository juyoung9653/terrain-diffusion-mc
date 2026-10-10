package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class StructurePlacementWorldMixin {
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void terrainDiffusion$pinStructureRules(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        LevelStem overworld = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        if (overworld != null && overworld.generator().getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            StructurePlacementConfig.bindWorld(server.registryAccess().registryOrThrow(Registries.STRUCTURE),
                    server.getWorldPath(LevelResource.ROOT));
            var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
            com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex.load(registry,
                    server.getWorldPath(LevelResource.ROOT), StructurePlacementConfig.snapshotFor(registry));
        }
    }
}
