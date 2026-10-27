package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StructureShieldGeometry;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retains individual Better Mineshaft entrance pieces while dropping its over-broad aggregate shield. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsStructureShieldMixin {
    private static final Logger LOG = LoggerFactory.getLogger("terrain-diffusion-mc/streams-shield");
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final int SHIELD_PADDING = 3;

    @Inject(method = "a(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/ChunkPos;)[I",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void terrainDiffusion$replaceMineshaftAggregateShield(WorldGenLevel level, ChunkPos chunkPos,
            CallbackInfoReturnable<int[]> callback) {
        int[] original = callback.getReturnValue();
        if (!terrainDiffusion$usesTerrainDiffusionBiomes(level) || original == null) return;

        List<StructureShieldGeometry.Replacement> replacements = terrainDiffusion$targetReplacements(level, chunkPos);
        int[] filtered = StructureShieldGeometry.replaceAggregates(original, replacements, SHIELD_PADDING);
        if (filtered != original) {
            callback.setReturnValue(filtered);
            if (REPORTED.compareAndSet(false, true)) {
                LOG.info("Streams: replaced Better Mineshaft aggregate structure shields with piece shields for Terrain Diffusion");
            }
        }
    }

    private static boolean terrainDiffusion$usesTerrainDiffusionBiomes(WorldGenLevel level) {
        return TerrainDiffusionBiomeSources.isTerrainDiffusion(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
    }

    private static List<StructureShieldGeometry.Replacement> terrainDiffusion$targetReplacements(WorldGenLevel level, ChunkPos chunkPos) {
        ChunkAccess current = level.getChunk(chunkPos.x, chunkPos.z);
        int expandedMinX = chunkPos.getMinBlockX() - SHIELD_PADDING;
        int expandedMaxX = chunkPos.getMaxBlockX() + SHIELD_PADDING;
        int expandedMinZ = chunkPos.getMinBlockZ() - SHIELD_PADDING;
        int expandedMaxZ = chunkPos.getMaxBlockZ() + SHIELD_PADDING;
        List<StructureShieldGeometry.Replacement> replacements = new ArrayList<>();

        for (Map.Entry<Structure, LongSet> entry : current.getAllReferences().entrySet()) {
            ResourceLocation id = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(entry.getKey());
            if (id == null || !"bettermineshafts".equals(id.getNamespace())) continue;
            for (long packedReference : entry.getValue()) {
                int referenceX = ChunkPos.getX(packedReference);
                int referenceZ = ChunkPos.getZ(packedReference);
                ChunkAccess structureChunk = referenceX == chunkPos.x && referenceZ == chunkPos.z
                        ? current : level.getChunk(referenceX, referenceZ, ChunkStatus.STRUCTURE_STARTS, false);
                if (structureChunk == null) continue;
                StructureStart start = structureChunk.getStartForStructure(entry.getKey());
                if (start == null || !start.isValid()) continue;
                BoundingBox aggregate = start.getBoundingBox();
                StructureShieldGeometry.Box aggregateBox = terrainDiffusion$box(aggregate);
                if (!aggregateBox.intersects(expandedMinX, expandedMinZ, expandedMaxX, expandedMaxZ)) continue;

                List<StructureShieldGeometry.Box> pieces = new ArrayList<>();
                for (StructurePiece piece : start.getPieces()) {
                    StructureShieldGeometry.Box pieceBox = terrainDiffusion$box(piece.getBoundingBox());
                    if (pieceBox.intersects(expandedMinX, expandedMinZ, expandedMaxX, expandedMaxZ)) pieces.add(pieceBox);
                }
                // A broad aggregate can reach this chunk while none of its real pieces does.
                // In that case remove the aggregate and add no local shield tuple.
                replacements.add(new StructureShieldGeometry.Replacement(aggregateBox, pieces));
            }
        }
        return replacements;
    }

    private static StructureShieldGeometry.Box terrainDiffusion$box(BoundingBox box) {
        return new StructureShieldGeometry.Box(box.minX(), box.minZ(), box.maxX(), box.maxZ(), box.maxY());
    }
}
