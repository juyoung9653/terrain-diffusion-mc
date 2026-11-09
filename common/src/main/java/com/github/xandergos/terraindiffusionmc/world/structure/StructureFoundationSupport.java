package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Policy;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Finish bounded foundations after native templates have written their actual soil floors. */
public final class StructureFoundationSupport {
    private StructureFoundationSupport() {}

    public static int restore(WorldGenLevel level, StructureStart start, BoundingBox placementBox,
                              ChunkPos chunk, StructureTerrain.Plan plan, Policy policy) {
        int filled=0;
        for (var piece:start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)) continue;
            for (var floor:StructurePieceOccupancy.soilFloor(pool)) {
                BlockPos pos=new BlockPos(floor.x(),0,floor.z()).rotate(pool.getRotation()).offset(pool.getPosition());
                if ((pos.getX()>>4)!=chunk.x || (pos.getZ()>>4)!=chunk.z || !placementBox.isInside(pos)) continue;
                var column=plan.columns().get(StructureTerrain.key(pos.getX(),pos.getZ()));
                if (column==null || !column.foundation() || column.target()!=pos.getY()
                        || pos.getY()-policy.maxFillBlocks()-2<level.getMinBuildHeight()) continue;
                var actual=level.getBlockState(pos);
                // A processor, another piece, or a feature may have changed the floor. Do not guess.
                if (!floor.states().contains(actual)) continue;
                int depth=FoundationSupport.repairDepth(policy.maxFillBlocks(),
                        d -> clearable(level.getBlockState(pos.below(d))),
                        d -> StructureTerrain.natural(level.getBlockState(pos.below(d))));
                if (depth==0) continue;
                boolean safe=true;
                for (int d=1;d<=depth && safe;d++) {
                    BlockPos support=pos.below(d);
                    if (!placementBox.isInside(support) || !level.ensureCanWrite(support)) { safe=false; break; }
                    // A cellar, buried well or other component owns its interior even when it is AIR.
                    for (var other:start.getPieces()) if (other!=piece && other.getBoundingBox().isInside(support)) {
                        safe=false; break;
                    }
                }
                if (!safe) continue;
                var material=actual.is(BlockTags.DIRT) ? Blocks.DIRT.defaultBlockState() : actual;
                for (int d=1;d<=depth;d++) {
                    if (level.setBlock(pos.below(d),material,2)) filled++;
                }
            }
        }
        return filled;
    }

    private static boolean clearable(BlockState state) {
        return state.isAir() || (state.getBlock() instanceof BushBlock && state.canBeReplaced()
                && !state.hasBlockEntity() && state.getFluidState().isEmpty());
    }
}
