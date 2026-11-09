package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Policy;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureFoundationSupport;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePieceOccupancy;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureTerrain;
import com.github.xandergos.terraindiffusionmc.world.structure.PolicyStructureStart;
import com.mojang.datafixers.util.Either;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Exercises the post-placement repair against native pool/template/start types.
 * The world is a write-recording WorldGenLevel proxy: no fixture chunk is changed.
 */
final class FoundationSupportChecks {
    private static final Policy TWO_BLOCK_FILL = new Policy(0, 0, 0, 0, 2, 0, 1, 1, 1.0,
            com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.SurfaceMode.AUTO);

    private FoundationSupportChecks() { }

    static Map<String, Object> run(ServerLevel level, List<StructureStart> generated) {
        if (generated.isEmpty()) throw new AssertionError("Foundation audit needs a registered generated structure");
        var context = StructurePieceSerializationContext.fromLevel(level);
        StructureTemplate grassTemplate = soilTemplate(level, "minecraft:grass_block");
        StructureTemplate coarseDirtTemplate = soilTemplate(level, "minecraft:coarse_dirt");
        StructurePoolElement rigid = element(SinglePoolElement.class, grassTemplate,
                StructureTemplatePool.Projection.RIGID);
        StructurePoolElement coarseRigid = element(SinglePoolElement.class, coarseDirtTemplate,
                StructureTemplatePool.Projection.RIGID);
        int positive = 0;
        int negative = 0;

        positive += repair(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), 2, false, false);
        positive += repair(level, context, generated.getFirst(), coarseRigid, Blocks.COARSE_DIRT.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState()), 1, false, false);
        // The saved Structory ruin has short_grass between the soil floor and its dry anchor.
        positive += repair(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.SHORT_GRASS.defaultBlockState(), Blocks.AIR.defaultBlockState()), 2, false, false);

        negative += reject(level, context, generated.getFirst(), rigid, Blocks.STONE.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState()), false, false);
        negative += reject(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.WATER.defaultBlockState()), false, false);
        negative += reject(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), false, false);
        // A terrain slab is not a native y=0 soil state, so it must never become a foundation target.
        negative += reject(level, context, generated.getFirst(), rigid, Blocks.STONE_SLAB.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState()), false, false);
        negative += reject(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState()), true, false);
        negative += reject(level, context, generated.getFirst(), rigid, Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(Blocks.AIR.defaultBlockState()), false, true);

        PoolElementStructurePiece sample = piece(context, rigid, new BlockPos(2, 64, 2));
        if (StructurePieceOccupancy.soilFloor(sample).size() != 1)
            throw new AssertionError("Synthetic native SinglePoolElement did not expose its y=0 soil floor");
        if (!StructurePieceOccupancy.soilFloor(piece(context,
                element(LegacySinglePoolElement.class, grassTemplate, StructureTemplatePool.Projection.RIGID),
                new BlockPos(2, 64, 2))).isEmpty())
            throw new AssertionError("Legacy pool element became eligible for post-placement repair");
        if (!StructurePieceOccupancy.soilFloor(piece(context,
                element(SinglePoolElement.class, grassTemplate, StructureTemplatePool.Projection.TERRAIN_MATCHING),
                new BlockPos(2, 64, 2))).isEmpty())
            throw new AssertionError("Terrain-matching pool element became eligible for post-placement repair");
        if (positive != 5 || negative != 6)
            throw new AssertionError("Foundation repair coverage mismatch: restored=" + positive + " rejected=" + negative);
        return Map.of("status", "passed", "restored", positive, "rejected", negative,
                "naturalTerrainChecks", naturalTerrainChecks(level, context, generated.getFirst()),
                "template", "native_inline_single_pool_element",
                "fixtureWrites", false,
                "execution", "direct production restore on a virtual WorldGenLevel",
                "hookEvidence", "not_observed_by_this_check",
                "nativePlacementBox", "ChunkGenerator.getWritableArea: chunk x/z, minBuildHeight+1 through maxBuildHeight-1");
    }

    /**
     * Policy five is intentionally tested with cached, synthetic terrain samples.
     * This proves planner policy boundaries without mutating or claiming to survey fixture terrain.
     */
    private static Map<String, Object> naturalTerrainChecks(ServerLevel level,
                                                              StructurePieceSerializationContext context,
                                                              StructureStart owner) {
        StructurePoolElement broad = element(SinglePoolElement.class,
                soilTemplate(level, "minecraft:dirt", 4, 4), StructureTemplatePool.Projection.RIGID);
        StructurePoolElement small = element(SinglePoolElement.class,
                soilTemplate(level, "minecraft:dirt", 1, 1), StructureTemplatePool.Projection.RIGID);
        requireAuditedMask(piece(context, broad, new BlockPos(0, 64, 0)), 16, "4x4 dirt floor");
        requireAuditedMask(piece(context, small, new BlockPos(0, 64, 0)), 1, "1x1 dirt floor");
        Policy policy = new Policy(0, 0, 0, 2, 3, 12, 65536, 1, 1.0,
                com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.SurfaceMode.AUTO);
        List<Map<String, Object>> cases = new java.util.ArrayList<>();

        expectPlan(level, owner, List.of(piece(context, broad, new BlockPos(0, 64, 0))), policy, 4,
                samples((x, z) -> 62), true, null, "policy4_broad_two_fill", cases);
        expectPlan(level, owner, List.of(piece(context, broad, new BlockPos(0, 64, 0))), policy, 5,
                samples((x, z) -> 62), false, "foundation earthwork coverage", "policy5_broad_two_fill", cases);
        expectPlan(level, owner, List.of(piece(context, broad, new BlockPos(0, 64, 0))), policy, 5,
                samples((x, z) -> 63), true, null, "policy5_broad_one_fill", cases);
        expectPlan(level, owner, List.of(piece(context, small, new BlockPos(0, 64, 0))), policy, 5,
                samples((x, z) -> 62), true, null, "policy5_small_two_fill", cases);
        // The flat remote component cannot hide the first component's 16 deep-contact cells.
        expectPlan(level, owner, List.of(piece(context, broad, new BlockPos(0, 64, 0)),
                        piece(context, broad, new BlockPos(48, 64, 0))), policy, 5,
                samples((x, z) -> x >= 48 && x < 52 && z >= 0 && z < 4 ? 64 : 62), false,
                "foundation earthwork coverage", "policy5_per_piece_not_global_average", cases);
        return Map.of("status", "passed", "cases", cases,
                "inputs", "cached synthetic [oceanFloor,worldSurface,natural]=[original,original,1]",
                "fixtureWrites", false,
                "limitation", "planner policy test only; native height/material sampling and actual terrain acceptance are audited separately");
    }

    private static void requireAuditedMask(PoolElementStructurePiece piece, long expectedColumns, String name) {
        var mask = StructurePieceOccupancy.groundMask(piece);
        long columns = mask == null ? -1 : mask.stream().mapToLong(rect ->
                (long) (rect.maxX() - rect.minX() + 1) * (rect.maxZ() - rect.minZ() + 1)).sum();
        if (columns != expectedColumns)
            throw new AssertionError(name + " audited ground mask expected " + expectedColumns + ", got " + columns);
    }

    private static void expectPlan(ServerLevel level, StructureStart owner,
                                   List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces,
                                   Policy policy, int version, Map<Long, int[]> sampled, boolean accepted,
                                   String expectedReason, String name, List<Map<String, Object>> cases) {
        StructureStart start = new StructureStart(owner.getStructure(), new ChunkPos(0, 0), 0,
                new PiecesContainer(pieces));
        ((PolicyStructureStart) (Object) start).terrainDiffusion$policyVersion(version);
        List<String> reasons = new java.util.ArrayList<>();
        StructureTerrain.Plan plan = StructureTerrain.plan(start, policy, level.getChunkSource().getGenerator(),
                level.getChunkSource().randomState(), level, sampled,
                (reason, detail) -> reasons.add(reason + ": " + detail));
        if (accepted ? plan == null : plan != null)
            throw new AssertionError(name + " expected accepted=" + accepted + " but reasons=" + reasons);
        if (expectedReason != null && reasons.stream().noneMatch(reason -> reason.startsWith(expectedReason + ":")))
            throw new AssertionError(name + " expected rejection " + expectedReason + ", got " + reasons);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("name", name);
        row.put("policyVersion", version);
        row.put("accepted", accepted);
        row.put("pieces", pieces.size());
        if (plan != null) row.put("columns", plan.columns().size());
        if (!reasons.isEmpty()) row.put("reasons", reasons);
        cases.add(row);
    }

    private interface HeightSource { int original(int x, int z); }

    private static Map<Long, int[]> samples(HeightSource source) {
        Map<Long, int[]> result = new HashMap<>();
        for (int x = -40; x <= 100; x++) for (int z = -40; z <= 40; z++) {
            int original = source.original(x, z);
            result.put(StructureTerrain.key(x, z), new int[]{original, original, 1});
        }
        return result;
    }

    private static int repair(ServerLevel level, StructurePieceSerializationContext context, StructureStart owner,
                              StructurePoolElement element, BlockState floor, List<BlockState> gap, int expected,
                              boolean otherPiece, boolean foreignChunk) {
        int restored = invoke(level, context, owner, element, floor, gap, otherPiece, foreignChunk);
        if (restored != expected) throw new AssertionError("Expected " + expected + " foundation blocks, got " + restored);
        return restored;
    }

    private static int reject(ServerLevel level, StructurePieceSerializationContext context, StructureStart owner,
                              StructurePoolElement element, BlockState floor, List<BlockState> gap,
                              boolean otherPiece, boolean foreignChunk) {
        int restored = invoke(level, context, owner, element, floor, gap, otherPiece, foreignChunk);
        if (restored != 0) throw new AssertionError("Unsafe foundation case wrote " + restored + " blocks");
        return 1;
    }

    private static int invoke(ServerLevel level, StructurePieceSerializationContext context, StructureStart owner,
                              StructurePoolElement element, BlockState floor, List<BlockState> gap,
                              boolean otherPiece, boolean foreignChunk) {
        BlockPos floorPos = new BlockPos(2, 64, 2);
        PoolElementStructurePiece primary = piece(context, element, floorPos);
        List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = new java.util.ArrayList<>();
        pieces.add(primary);
        if (otherPiece) {
            // It owns the first support voxel. Its own y=0 soil check sees AIR and cannot write.
            pieces.add(piece(context, element, floorPos.below()));
        }
        StructureStart start = new StructureStart(owner.getStructure(), new ChunkPos(floorPos), 0,
                new PiecesContainer(pieces));
        VirtualLevel virtual = new VirtualLevel(level);
        virtual.blocks.put(floorPos, floor);
        for (int d = 1; d <= gap.size(); d++) virtual.blocks.put(floorPos.below(d), gap.get(d - 1));
        virtual.blocks.put(floorPos.below(gap.size() + 1), Blocks.DIRT.defaultBlockState());
        virtual.blocks.put(floorPos.below(gap.size() + 2), Blocks.DIRT.defaultBlockState());
        var plan = new StructureTerrain.Plan(Map.of(StructureTerrain.key(floorPos.getX(), floorPos.getZ()),
                new StructureTerrain.Column(64, floorPos.getY(), true, true)));
        BoundingBox placement = new BoundingBox(0, 0, 0, 15, 255, 15);
        ChunkPos target = foreignChunk ? new ChunkPos(1, 0) : new ChunkPos(floorPos);
        int restored = StructureFoundationSupport.restore(virtual.world, start, placement, target, plan, TWO_BLOCK_FILL);
        if (!foreignChunk && restored > 0) {
            for (int d = 1; d <= restored; d++) {
                if (!virtual.blocks.get(floorPos.below(d)).is(Blocks.DIRT))
                    throw new AssertionError("Foundation material was not written through native restore");
            }
        }
        return restored;
    }

    private static PoolElementStructurePiece piece(StructurePieceSerializationContext context,
                                                    StructurePoolElement element, BlockPos position) {
        return new PoolElementStructurePiece(context.structureTemplateManager(), element, position, 1, Rotation.NONE,
                new BoundingBox(position), LiquidSettings.APPLY_WATERLOGGING);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StructurePoolElement element(Class<? extends StructurePoolElement> type, StructureTemplate template,
                                                StructureTemplatePool.Projection projection) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(Either.class, Holder.class,
                    StructureTemplatePool.Projection.class, Optional.class);
            constructor.setAccessible(true);
            return (StructurePoolElement) constructor.newInstance(Either.right(template),
                    Holder.direct(new StructureProcessorList(List.of())), projection, Optional.empty());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct native " + type.getSimpleName() + " audit element", failure);
        }
    }

    private static StructureTemplate soilTemplate(ServerLevel level, String stateId) {
        return soilTemplate(level, stateId, 1, 1);
    }

    private static StructureTemplate soilTemplate(ServerLevel level, String stateId, int width, int depth) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(width, 1, depth));
        CompoundTag state = new CompoundTag();
        state.putString("Name", stateId);
        ListTag palette = new ListTag();
        palette.add(state);
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(x, 0, z));
            block.putInt("state", 0);
            blocks.add(block);
        }
        tag.put("blocks", blocks);
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(lookup, tag);
        return template;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }

    private static final class VirtualLevel {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final WorldGenLevel world;

        VirtualLevel(ServerLevel delegate) {
            world = (WorldGenLevel) Proxy.newProxyInstance(FoundationSupportChecks.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("getMinBuildHeight")) return 0;
                        if (name.equals("ensureCanWrite") || name.equals("hasChunk") || name.equals("hasChunkAt")) return true;
                        if (name.equals("getBlockState")) return blocks.getOrDefault((BlockPos) args[0],
                                Blocks.DIRT.defaultBlockState());
                        if (name.equals("getFluidState")) return blocks.getOrDefault((BlockPos) args[0],
                                Blocks.DIRT.defaultBlockState()).getFluidState();
                        if (name.equals("setBlock")) {
                            blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                            return true;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
        }
    }
}
