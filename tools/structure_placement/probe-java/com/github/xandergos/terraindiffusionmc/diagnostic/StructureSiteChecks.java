package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureSiteCompatibility;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Uses the shipped monument start template; it neither locates nor generates terrain. */
final class StructureSiteChecks {
    private static final String ID = "betteroceanmonuments:ocean_monument";
    private static final ResourceLocation START_TEMPLATE = ResourceLocation.parse("betteroceanmonuments:start");
    private static final String MARKER = "verify-structure-sites.txt";

    private StructureSiteChecks() { }

    static Map<String, Object> verify(ServerLevel level) {
        if (!Files.exists(level.getServer().getServerDirectory().resolve(MARKER)))
            return Map.of("status", "skipped", "reason", "missing " + MARKER);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        if (registry.get(ResourceLocation.parse(ID)) == null)
            throw new AssertionError("Structure-site verification requested but Better Ocean Monuments is not installed");
        StructureTemplate template = level.getServer().getStructureManager().getOrCreate(START_TEMPLATE);
        var policy = StructurePlacementConfig.snapshotFor(registry).resolve(ID);
        int sea = level.getSeaLevel();
        BlockPos origin = new BlockPos(0, sea - 48, 0);
        Map<String, Object> rotations = new LinkedHashMap<>();
        for (Rotation rotation : Rotation.values()) {
            BoundingBox box = template.getBoundingBox(new StructurePlaceSettings().setRotation(rotation), origin);
            var bounds = new StructureSiteCompatibility.Bounds(box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());
            var deep = StructureSiteCompatibility.check(ID, bounds, policy.maxCutBlocks(),
                    policy.maxTerrainSamples(), sea, (x, z) -> new StructureSiteCompatibility.Heights(bounds.minY(), sea - 1));
            if (!deep.applicable() || !deep.accepted())
                throw new AssertionError("Controlled deep monument template site was rejected for " + rotation + ": " + deep);
            int edgeX = bounds.maxX(), edgeZ = bounds.maxZ();
            int shallowFloor = Math.addExact(bounds.minY(), policy.maxCutBlocks() + 1);
            var shallow = StructureSiteCompatibility.check(ID, bounds, policy.maxCutBlocks(),
                    policy.maxTerrainSamples(), sea, (x, z) -> x == edgeX && z == edgeZ
                            ? new StructureSiteCompatibility.Heights(shallowFloor, sea - 1)
                            : new StructureSiteCompatibility.Heights(bounds.minY(), sea - 1));
            if (shallow.accepted() || !shallow.applicable()
                    || !shallow.reason().startsWith("shallow floor at " + edgeX + "," + edgeZ))
                throw new AssertionError("Single shallow monument edge was not rejected for " + rotation + ": " + shallow);
            rotations.put(rotation.name(), Map.of("bounds", java.util.List.of(box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()), "columns", bounds.columnCount(),
                    "deep", deep.reason(), "shallowEdge", shallow.reason()));
        }
        return Map.of("status", "passed", "id", ID, "template", START_TEMPLATE.toString(),
                "sampleSource", "controlled deep water with one shallow final footprint cell", "rotations", rotations);
    }
}
