package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.VillageDistribution;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runtime-only placement. Native locate scans the same versioned microcell origins as STARTS. */
public final class UnifiedVillagePlacement extends RandomSpreadStructurePlacement {
    private final VillageDistribution settings;
    private final boolean epicLoaded;

    public UnifiedVillagePlacement(VillageDistribution settings,boolean epicLoaded) {
        super(VillageDistributionGeometry.microSpacing(settings.targetMeanNearestNeighborBlocks(),settings.algorithmVersion()),
                0, RandomSpreadType.LINEAR, 0x54445631);
        this.settings=settings;
        this.epicLoaded=epicLoaded;
    }

    public VillageDistribution settings() { return settings; }
    public boolean epicLoaded() { return epicLoaded; }
    public int cellX(ChunkPos pos) { return VillageDistributionGeometry.cell(pos.x,spacing(),settings.algorithmVersion()); }
    public int cellZ(ChunkPos pos) { return VillageDistributionGeometry.cell(pos.z,spacing(),settings.algorithmVersion()); }

    /** Fixed seeded hop order per cell, shared by speculative assembly and real generation. */
    public List<ChunkPos> candidates(long seed,ChunkPos owner) {
        List<ChunkPos> result=new ArrayList<>(VillageDistributionGeometry.HOPS);
        for (var microcell:VillageDistributionGeometry.microcells(owner.x,owner.z,spacing(),settings.algorithmVersion()))
            result.add(getPotentialStructureChunk(seed,microcell.x(),microcell.z()));
        result.sort(Comparator.comparingLong(pos -> PlacementGeometry.priority(seed,
                VillageDistributionGeometry.SET_ID+"/hop",pos.x,pos.z)));
        return List.copyOf(result);
    }
}
