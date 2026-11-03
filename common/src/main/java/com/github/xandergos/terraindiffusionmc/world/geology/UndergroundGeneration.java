package com.github.xandergos.terraindiffusionmc.world.geology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** New-world-only geology; no terrain lookups, neighbor writes or global mutable state. */
public final class UndergroundGeneration {
    public static final ResourceKey<NoiseGeneratorSettings> SETTINGS = ResourceKey.create(
            Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "underground"));
    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState[] MATERIALS = new BlockState[32];
    static {
        MATERIALS[GeologyModel.ROCK_STONE] = Blocks.STONE.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_SANDSTONE] = Blocks.SANDSTONE.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_CALCITE] = Blocks.CALCITE.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_TUFF] = Blocks.TUFF.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_GRANITE] = Blocks.GRANITE.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_DIORITE] = Blocks.DIORITE.defaultBlockState();
        MATERIALS[GeologyModel.ROCK_DEEPSLATE] = Blocks.DEEPSLATE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_COAL] = Blocks.COAL_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_COPPER] = Blocks.COPPER_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_IRON] = Blocks.IRON_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_GOLD] = Blocks.GOLD_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_REDSTONE] = Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_LAPIS] = Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState();
        MATERIALS[GeologyModel.ORE_DIAMOND] = Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
    }

    private UndergroundGeneration() {}

    public static boolean isEnabled(NoiseBasedChunkGenerator generator) {
        return generator.generatorSettings().is(SETTINGS)
                && TerrainDiffusionBiomeSources.isTerrainDiffusion(generator.getBiomeSource());
    }

    public static void apply(WorldGenRegion region, StructureManager structures,
                             NoiseBasedChunkGenerator generator, ChunkAccess chunk) {
        if (!isEnabled(generator)) return;
        if (com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle.isPreviewServer(region.getLevel().getServer())) return;
        int minY = chunk.getMinBuildHeight();
        if (minY >= -64) return;
        var terrainSource = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        GeologyModel geology = new GeologyModel(region.getSeed(), minY, terrainSource.boundUndergroundScale());
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        // Starts/references already exist at SURFACE. Protect each piece plus its support,
        // rather than masking an entire settlement's aggregate bounding box.
        List<BoundingBox> protectedPieces = new ArrayList<>();
        for (var start : structures.startsForStructure(chunk.getPos(), ignored -> true)) {
            for (var piece : start.getPieces()) {
                var b = piece.getBoundingBox();
                if (b.maxX() + 4 < startX || b.minX() - 4 > startX + 15
                        || b.maxZ() + 4 < startZ || b.minZ() - 4 > startZ + 15) continue;
                protectedPieces.add(new BoundingBox(b.minX() - 4, b.minY() - 8, b.minZ() - 4,
                        b.maxX() + 4, b.maxY() + 4, b.maxZ() + 4));
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int worldX = startX + x, worldZ = startZ + z;
            int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int top = Math.min(chunk.getMaxBuildHeight() - 1, ground - GeologyModel.MIN_ROOF - 1);
            var column = geology.column(worldX, worldZ, ground);
            List<BoundingBox> columnPieces = protectedPieces.stream()
                    .filter(b -> worldX >= b.minX() && worldX <= b.maxX()
                            && worldZ >= b.minZ() && worldZ <= b.maxZ()).toList();
            for (int y = minY + 6; y <= top; y++) {
                pos.set(worldX, y, worldZ);
                BlockState old = chunk.getBlockState(pos);
                if (!old.is(Blocks.STONE) && !old.is(Blocks.DEEPSLATE)) continue;
                int cavity = column.cavity(y);
                if (cavity != 0 && protectedAt(columnPieces, y)) cavity = 0;
                BlockState state = cavity == 1 ? AIR : cavity == 2 ? WATER : MATERIALS[column.material(y)];
                if (state != old) {
                    chunk.setBlockState(pos, state, false);
                    if (cavity == 2) chunk.markPosForPostprocessing(pos);
                }
            }
        }
    }

    private static boolean protectedAt(List<BoundingBox> boxes, int y) {
        for (var box : boxes) if (y >= box.minY() && y <= box.maxY()) return true;
        return false;
    }
}
