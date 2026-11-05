package com.github.xandergos.terraindiffusionmc.world.geology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Cave rivers are carved after native carvers so their bed cannot be perforated by that pass. */
public final class UndergroundRivers {
    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private UndergroundRivers() { }

    public static void apply(WorldGenRegion region, StructureManager structures,
                             NoiseBasedChunkGenerator generator, ChunkAccess chunk) {
        if (!UndergroundGeneration.isEnabled(generator) || chunk.getMinBuildHeight() >= -64) return;
        if (com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle.isPreviewServer(region.getLevel().getServer())) return;
        if (!TerrainDiffusionBiomeSources.find(generator.getBiomeSource()).undergroundRivers()) return;
        int scale = TerrainDiffusionBiomeSources.find(generator.getBiomeSource()).boundUndergroundScale();
        var rivers = new UndergroundRiverModel(region.getSeed(), scale,
                TerrainDiffusionBiomeSources.find(generator.getBiomeSource()).undergroundRiverVersion());
        boolean compact = TerrainDiffusionBiomeSources.find(generator.getBiomeSource()).undergroundRiverVersion() >= 2;
        var geology = new GeologyModel(region.getSeed(), chunk.getMinBuildHeight(), scale,
                TerrainDiffusionBiomeSources.find(generator.getBiomeSource()).undergroundProfileVersion());
        List<net.minecraft.world.level.levelgen.structure.BoundingBox> protectedPieces = new ArrayList<>();
        for (var start : structures.startsForStructure(chunk.getPos(), ignored -> true)) {
            for (var piece : start.getPieces()) {
                var b = piece.getBoundingBox();
                protectedPieces.add(new net.minecraft.world.level.levelgen.structure.BoundingBox(
                        b.minX() - 4, b.minY() - 8, b.minZ() - 4, b.maxX() + 4, b.maxY() + 4, b.maxZ() + 4));
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = startX + x, wz = startZ + z;
            var channel = rivers.column(wx, wz);
            if (channel == null) continue;
            int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            if (!channel.fits(chunk.getMinBuildHeight(), ground)) continue;
            boolean blocked = false;
            for (var b : protectedPieces) {
                if (wx >= b.minX() && wx <= b.maxX() && wz >= b.minZ() && wz <= b.maxZ()
                        && channel.roof() >= b.minY() && channel.liningBottom() <= b.maxY()) { blocked = true; break; }
            }
            if (blocked) continue;
            // Compact streams cut into a real floor. Never bridge a pre-existing void
            // with a tall artificial retaining wall or a source-water column.
            int bottom = compact ? channel.bed() : channel.liningBottom();
            BlockState existingBed = chunk.getBlockState(pos.set(wx, bottom, wz));
            // Water crossing a lava pocket cools just the contact layer. Air gaps
            // still fail closed; this never builds a foundation down into a void.
            boolean cooledBed = compact && channel.wet() && existingBed.is(Blocks.LAVA);
            if (compact && !existingBed.isSolid() && !cooledBed) continue;
            // Preserve unknown mod blocks; never leave half a cut through an already placed object.
            for (int y = bottom; y <= channel.roof(); y++) {
                if (!replaceable(chunk.getBlockState(pos.set(wx, y, wz)))) { blocked = true; break; }
            }
            if (blocked) continue;
            var rock = geology.column(wx, wz, ground);
            for (int y = bottom; y <= channel.roof(); y++) {
                if (compact && y == bottom && !channel.wet()) continue;
                BlockState state = y <= channel.bed() ? bedMaterial(rock.rock(y))
                        : channel.wet() && y <= channel.water() ? WATER : AIR;
                if (y == channel.bed() && channel.wet()) {
                    double patch = compact ? sedimentPatch(region.getSeed(), wx, wz)
                            : Math.sin(wx / (7.0 * scale)) + Math.cos(wz / (11.0 * scale));
                    if (channel.lake() && patch > .2) state = Blocks.CLAY.defaultBlockState();
                    else if (Math.hypot(channel.flowX(), channel.flowZ()) < .65 && patch > .6
                            && (!compact || chunk.getBlockState(pos.set(wx, y - 1, wz)).isSolid()))
                        state = Blocks.GRAVEL.defaultBlockState();
                    if (cooledBed) state = Blocks.OBSIDIAN.defaultBlockState();
                }
                chunk.setBlockState(pos.set(wx, y, wz), state, false);
                if (state == WATER) chunk.markPosForPostprocessing(pos);
            }
            if (channel.wet()) RiverChannelData.record(chunk, x, z, new RiverChannelData.Entry(
                    channel.bed(), channel.water(), channel.roof(), channel.flowX(), channel.flowZ(), channel.lake()));
        }
    }

    private static double sedimentPatch(long seed, int x, int z) {
        // Small irregular sediment patches, independent of the TD distance multiplier.
        long h = seed ^ Math.floorDiv(x, 3) * 0x9E3779B97F4A7C15L
                ^ Math.floorDiv(z, 3) * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ h >>> 30) * 0xBF58476D1CE4E5B9L;
        h = (h ^ h >>> 27) * 0x94D049BB133111EBL;
        return ((h ^ h >>> 31) >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    private static boolean replaceable(BlockState state) {
        return state.isAir() || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.WATER) || state.is(Blocks.LAVA) || state.is(Blocks.CALCITE)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.GRAVEL)
                || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES);
    }

    private static BlockState bedMaterial(int rock) {
        return switch (rock) {
            case GeologyModel.ROCK_CALCITE -> Blocks.CALCITE.defaultBlockState();
            case GeologyModel.ROCK_SANDSTONE -> Blocks.SANDSTONE.defaultBlockState();
            case GeologyModel.ROCK_GRANITE -> Blocks.GRANITE.defaultBlockState();
            case GeologyModel.ROCK_DIORITE -> Blocks.DIORITE.defaultBlockState();
            case GeologyModel.ROCK_TUFF -> Blocks.TUFF.defaultBlockState();
            case GeologyModel.ROCK_DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    /** Server-authoritative current in our generated channel footprint; never requests a chunk. */
    public static Vec3 flow(BlockGetter view, BlockPos pos, FluidState fluid, Vec3 vanilla) {
        // A scale-one spring can be above Y=-48. Persisted carved identity,
        // not a fixed world-height cutoff, determines whether this is our water.
        if (!(view instanceof ServerLevel level) || !fluid.is(FluidTags.WATER)) return vanilla;
        if (com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle.isPreviewServer(level.getServer())) return vanilla;
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise) || !UndergroundGeneration.isEnabled(noise)) return vanilla;
        if (!TerrainDiffusionBiomeSources.find(noise.getBiomeSource()).undergroundRivers()) return vanilla;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return vanilla;
        var channel = RiverChannelData.get(chunk, pos.getX() & 15, pos.getZ() & 15);
        if (channel == null || channel.lake() || pos.getY() <= channel.bed() || pos.getY() > channel.water()) return vanilla;
        // Only successfully carved, persisted columns can receive this current.
        if (!chunk.getBlockState(new BlockPos(pos.getX(), channel.bed(), pos.getZ())).isSolid()) return vanilla;
        return new Vec3(channel.flowX(), vanilla.y, channel.flowZ());
    }
}
