package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;
import java.util.function.Predicate;

@Mixin(Beardifier.class)
public abstract class StructureBeardifierMixin {
    @Redirect(method="forStructuresInChunk", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<StructureStart> terrainDiffusion$boundedTerrain(StructureManager manager, ChunkPos pos,
            Predicate<Structure> predicate) {
        LevelAccessor accessor=((StructureManagerAccess)manager).terrainDiffusion$level();
        ServerLevel level=accessor instanceof WorldGenRegion region?region.getLevel():accessor instanceof ServerLevel server?server:null;
        List<StructureStart> starts=manager.startsForStructure(pos,predicate);
        if (level==null || !(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return starts;
        return starts.stream().filter(start -> !StructurePlacementRuntime.shapes(start,manager.registryAccess())).toList();
    }
}
