package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.mixin.StructureTemplatePaletteAccess;
import com.github.xandergos.terraindiffusionmc.world.structure.ChapelTerrainProcessor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Runs the real Structory chapel palettes through the placement processor without writing a
 * chunk.  The virtual level supplies sand below the chosen shoreline and air above it.
 */
final class ChapelTerrainChecks {
    private static final List<String> TEMPLATES = List.of(
            "harvest/chapel/church", "harvest/chapel/graveyard1", "harvest/chapel/graveyard2");
    private static final int SHORE_Y = 64;

    private ChapelTerrainChecks() { }

    static Map<String, Object> run(ServerLevel level) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (String path : TEMPLATES) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("structory", path);
            if (!ChapelTerrainProcessor.handles(id)) throw new AssertionError("Chapel processor misses " + id);
            StructureTemplate template = level.getServer().getStructureManager().getOrCreate(id);
            for (Rotation rotation : List.of(Rotation.NONE, Rotation.CLOCKWISE_90)) {
                for (Mirror mirror : List.of(Mirror.NONE, Mirror.FRONT_BACK)) {
                    cases.add(check(level, id, template, rotation, mirror));
                }
            }
        }
        if (ChapelTerrainProcessor.handles(ResourceLocation.fromNamespaceAndPath("structory", "harvest/chapel/not_a_template"))
                || ChapelTerrainProcessor.handles(ResourceLocation.fromNamespaceAndPath("minecraft", "village/plains/houses/plains_small_house_1")))
            throw new AssertionError("Chapel processor accepted an unknown template");
        return Map.of("status", "passed", "templates", TEMPLATES, "cases", cases,
                "fixtureWrites", false,
                "execution", "native palette processBlockInfos with a read-only ServerLevelAccessor proxy",
                "shoreline", "sand at y <= " + SHORE_Y + ", air above");
    }

    private static Map<String, Object> check(ServerLevel level, ResourceLocation id,
                                              StructureTemplate template, Rotation rotation, Mirror mirror) {
        BlockPos origin = new BlockPos(160, SHORE_Y, 160);
        List<StructureTemplate.StructureBlockInfo> source = palette(template);
        StructurePlaceSettings baselineSettings = settings(rotation, mirror, null);
        VirtualLevel virtual = new VirtualLevel(level);
        List<StructureTemplate.StructureBlockInfo> baseline = template.processBlockInfos(virtual.level, origin,
                BlockPos.ZERO, baselineSettings, source);
        StructurePlaceSettings filteredSettings = settings(rotation, mirror, null)
                .addProcessor(new ChapelTerrainProcessor(template));
        List<StructureTemplate.StructureBlockInfo> filtered = template.processBlockInfos(virtual.level, origin,
                BlockPos.ZERO, filteredSettings, source);
        Map<BlockPos, StructureTemplate.StructureBlockInfo> after = byPosition(filtered);
        int architecture = 0, landscaping = 0, omitted = 0, soilOmitted = 0, soilPreserved = 0;
        for (StructureTemplate.StructureBlockInfo info : baseline) {
            if (landscaping(info.state())) {
                landscaping++;
                if (!after.containsKey(info.pos())) {
                    omitted++;
                    if (soil(info.state())) soilOmitted++;
                }
                if (soil(info.state()) && after.containsKey(info.pos()) && after.get(info.pos()).state().is(Blocks.SAND))
                    soilPreserved++;
            } else {
                architecture++;
                StructureTemplate.StructureBlockInfo result = after.get(info.pos());
                if (result == null || !result.state().equals(info.state()) || !java.util.Objects.equals(result.nbt(), info.nbt()))
                    throw new AssertionError(id + " changed architecture at " + info.pos());
            }
        }
        if (architecture == 0 || landscaping == 0 || omitted == 0 || soilOmitted == 0 || soilPreserved == 0)
            throw new AssertionError(id + " did not preserve architecture and remove exterior landscaping: architecture="
                    + architecture + " landscaping=" + landscaping + " omitted=" + omitted
                    + " soilOmitted=" + soilOmitted + " sand=" + soilPreserved);

        // A narrow clip around an actual native footing must never cause a processor terrain
        // read outside it.  Choose it after transformation so every rotation/mirror is covered.
        if (virtual.readPositions.isEmpty()) throw new AssertionError(id + " never read native footing");
        BlockPos clippedFooting = virtual.readPositions.iterator().next();
        BoundingBox clip = new BoundingBox(clippedFooting.getX(), 0, clippedFooting.getZ(),
                clippedFooting.getX(), 255, clippedFooting.getZ());
        VirtualLevel clipped = new VirtualLevel(level);
        template.processBlockInfos(clipped.level, origin, BlockPos.ZERO,
                settings(rotation, mirror, clip).addProcessor(new ChapelTerrainProcessor(template)), source);
        boolean outside = clipped.readPositions.stream().anyMatch(pos -> !clip.isInside(pos));
        if (outside || clipped.reads == 0)
            throw new AssertionError(id + " violated placement clip: reads=" + clipped.reads
                    + " outside=" + outside);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template", id.toString());
        result.put("rotation", rotation.name());
        result.put("mirror", mirror.name());
        result.put("architecturePreserved", architecture);
        result.put("landscapingInput", landscaping);
        result.put("landscapingOmitted", omitted);
        result.put("soilOmitted", soilOmitted);
        result.put("soilStatesKeptAsSand", soilPreserved);
        result.put("clippedReads", clipped.reads);
        return result;
    }

    private static StructurePlaceSettings settings(Rotation rotation, Mirror mirror, BoundingBox clip) {
        StructurePlaceSettings result = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror);
        result.addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor.STRUCTURE_BLOCK);
        result.addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor.INSTANCE);
        if (clip != null) result.setBoundingBox(clip);
        return result;
    }

    private static List<StructureTemplate.StructureBlockInfo> palette(StructureTemplate template) {
        List<StructureTemplate.Palette> palettes = ((StructureTemplatePaletteAccess) template).terrainDiffusion$palettes();
        if (palettes.isEmpty()) throw new AssertionError("Native chapel template has no palettes");
        return palettes.getFirst().blocks();
    }

    private static Map<BlockPos, StructureTemplate.StructureBlockInfo> byPosition(
            List<StructureTemplate.StructureBlockInfo> infos) {
        Map<BlockPos, StructureTemplate.StructureBlockInfo> result = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : infos) {
            if (result.put(info.pos(), info) != null) throw new AssertionError("Template emitted duplicate placement " + info.pos());
        }
        return result;
    }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
    }

    private static boolean landscaping(BlockState state) {
        return state.isAir() || soil(state) || state.is(net.minecraft.tags.BlockTags.LEAVES)
                || (state.canBeReplaced() && state.getFluidState().isEmpty() && !state.hasBlockEntity())
                || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK);
    }

    private static final class VirtualLevel {
        final ServerLevelAccessor level;
        int reads;
        final java.util.Set<BlockPos> readPositions = new java.util.LinkedHashSet<>();

        VirtualLevel(ServerLevel delegate) {
            level = (ServerLevelAccessor) Proxy.newProxyInstance(ChapelTerrainChecks.class.getClassLoader(),
                    new Class<?>[]{ServerLevelAccessor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getBlockState")) {
                            BlockPos pos = (BlockPos) args[0];
                            reads++;
                            readPositions.add(pos.immutable());
                            return pos.getY() <= SHORE_Y ? Blocks.SAND.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        }
                        if (method.getName().equals("getFluidState")) return Blocks.AIR.defaultBlockState().getFluidState();
                        // JigsawReplacementProcessor parses final_state through the block registry.
                        if (method.getName().equals("holderLookup") || method.getName().equals("registryAccess"))
                            return method.invoke(delegate, args);
                        if (method.getName().equals("setBlock") || method.getName().equals("setBlockState"))
                            throw new AssertionError("Chapel template audit attempted a fixture write");
                        throw new AssertionError("Unexpected native terrain query: " + method.getName());
                    });
        }
    }
}
