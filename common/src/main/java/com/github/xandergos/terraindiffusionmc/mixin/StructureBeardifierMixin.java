package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import net.minecraft.core.registries.Registries;
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
    private static final String STRUCTORY_SWAMP_RUIN = "structory:swamp_ruin";

    @Redirect(method="forStructuresInChunk", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<StructureStart> terrainDiffusion$boundedTerrain(StructureManager manager, ChunkPos pos,
            Predicate<Structure> predicate) {
        LevelAccessor accessor=((StructureManagerAccess)manager).terrainDiffusion$level();
        ServerLevel level=accessor instanceof WorldGenRegion region?region.getLevel():accessor instanceof ServerLevel server?server:null;
        List<StructureStart> starts=manager.startsForStructure(pos,predicate);
        if (level==null || !TerrainDiffusionBiomeSources.isTerrainDiffusion(level.getChunkSource().getGenerator().getBiomeSource())) return starts;
        var access=manager.registryAccess();
        var structures=access.registryOrThrow(Registries.STRUCTURE);
        return starts.stream().filter(start -> !StructurePlacementRuntime.shapes(start,access)
                // Structory's original fixed stone basements remain in its templates. Its
                // native beard creates the visible broad halo around TD mangrove swamp ruins.
                && !STRUCTORY_SWAMP_RUIN.equals(String.valueOf(structures.getKey(start.getStructure())))).toList();
    }
}
