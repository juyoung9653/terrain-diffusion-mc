package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.mixin.StructureTemplatePaletteAccess;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

/** Placement-only treatment of Structory's exposed chapel landscaping, not its buildings. */
public final class ChapelTerrainProcessor extends StructureProcessor {
    private static final Set<String> TEMPLATES = Set.of(
            "harvest/chapel/church", "harvest/chapel/graveyard1", "harvest/chapel/graveyard2",
            "harvest/chapel/graveyard3", "harvest/chapel/grave1", "harvest/chapel/connector_long",
            "harvest/chapel/connector_short", "harvest/chapel/bell");
    private final Set<Long> buildingColumns;

    public static boolean handles(ResourceLocation id) {
        return id.getNamespace().equals("structory") && TEMPLATES.contains(id.getPath());
    }

    public ChapelTerrainProcessor(StructureTemplate template) {
        Set<Long> columns = new HashSet<>();
        // Union palettes: a column containing architecture in ANY variant must remain intact.
        for (var palette : ((StructureTemplatePaletteAccess)template).terrainDiffusion$palettes()) {
            for (var block : palette.blocks()) {
                if (!landscaping(block.state())) columns.add(key(block.pos()));
            }
        }
        buildingColumns = columns;
    }

    private static long key(BlockPos pos) { return StructureTerrain.key(pos.getX(), pos.getZ()); }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
    }

    private static boolean landscaping(BlockState state) {
        return state.isAir() || soil(state) || state.is(BlockTags.LEAVES)
                || (state.canBeReplaced() && state.getFluidState().isEmpty() && !state.hasBlockEntity())
                || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK);
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
            StructureTemplate.StructureBlockInfo local, StructureTemplate.StructureBlockInfo placed,
            StructurePlaceSettings settings) {
        // Local coordinates survive every native rotation/mirror; world reads stay in the placement chunk.
        if (settings.getBoundingBox() != null && !settings.getBoundingBox().isInside(placed.pos())) return placed;
        if (!buildingColumns.contains(key(local.pos())) && landscaping(local.state())) return null;
        if (soil(local.state()) && soil(placed.state()) && placed.nbt() == null) {
            BlockState existing = level.getBlockState(placed.pos());
            // Keep the surrounding substrate even directly underneath walls and gravestones.
            if (StructureTerrain.natural(existing))
                return new StructureTemplate.StructureBlockInfo(placed.pos(), existing, null);
        }
        return placed;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        // Added only to a copied placement settings object; never stored in a pool or registry.
        throw new UnsupportedOperationException("Chapel terrain processor is placement-only");
    }
}
