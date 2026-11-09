package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Policy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.BiConsumer;

/** A fully sampled, bounded plan. Failure rejects the start before any block writes. */
public final class StructureTerrain {
    public record Column(int original, int target, boolean foundation, boolean slopeChecked) {}
    public record Plan(Map<Long, Column> columns) {}
    public record ApplyResult(int changed, int heightSkipped, int materialSkipped, int occupiedSkipped) {}
    private record Pad(PlacementGeometry.Rect rect, int y, StructurePiece source) {}
    private StructureTerrain() {}

    public static boolean shapes(StructureStart start, Policy policy, RegistryAccess access) {
        if (policy.surfaceMode().name().equals("PRESERVE")) return false;
        if (policy.surfaceMode().name().equals("SURFACE")) return true;
        // A surface decoration step alone does not imply a surface building: mods also
        // put underground and airborne jigsaws there. Only explicit ground projection
        // with zero offset is automatic; uncertain/custom providers preserve semantics.
        if (!StructureSurfaceMetadata.projectsToGround(start.getStructure(),access)) return false;
        return start.getStructure().step() == GenerationStep.Decoration.SURFACE_STRUCTURES
                && start.getStructure().terrainAdaptation() != TerrainAdjustment.NONE
                && start.getPieces().stream().allMatch(p -> p.getClass()==PoolElementStructurePiece.class);
    }

    public static PlacementGeometry.Rect rect(BoundingBox b) {
        return new PlacementGeometry.Rect(b.minX(), b.minZ(), b.maxX(), b.maxZ());
    }
    public static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    public static Plan plan(StructureStart start, Policy policy, ChunkGenerator generator,
                            RandomState random, LevelHeightAccessor height) {
        return plan(start,policy,generator,random,height,new HashMap<>());
    }
    public static Plan plan(StructureStart start, Policy policy, ChunkGenerator generator,
                            RandomState random, LevelHeightAccessor height, Map<Long,int[]> sampledHeights) {
        return plan(start,policy,generator,random,height,sampledHeights,(reason,detail) -> {});
    }
    public static Plan plan(StructureStart start, Policy policy, ChunkGenerator generator,
                            RandomState random, LevelHeightAccessor height, Map<Long,int[]> sampledHeights,
                            BiConsumer<String,String> rejected) {
        return plan(start,policy,generator,random,height,sampledHeights,null,rejected);
    }
    /**
     * {@code sampledHeights} remains local to one assembly because its third entry caches
     * policy-dependent material support. {@code sharedHeights} may only memoize raw heights.
     */
    public static Plan plan(StructureStart start, Policy policy, ChunkGenerator generator,
                            RandomState random, LevelHeightAccessor height, Map<Long,int[]> sampledHeights,
                            StructureHeightCache sharedHeights, BiConsumer<String,String> rejected) {
        List<Pad> pads = new ArrayList<>();
        Set<StructurePiece> auditedFoundations=Collections.newSetFromMap(new IdentityHashMap<>());
        int terrainVersion = ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion();
        boolean rounded = terrainVersion >= 2;
        List<PlacementGeometry.Rect> paths = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (rounded ? !StructurePieceOccupancy.requiresTerrainSupport(piece) : !StructurePieceOccupancy.hasLegacyTerrainSupport(piece)) continue;
            if (rounded && StructurePieceOccupancy.buriedWellBottom(piece,start.getPieces())) continue;
            BoundingBox box = piece.getBoundingBox();
            if (piece instanceof PoolElementStructurePiece pool) {
                // Terrain-matching paths follow the terrain; buildings need a rigid foundation.
                if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                    paths.add(rect(box));
                    continue;
                }
                int grade=box.minY()+pool.getGroundLevelDelta()-1;
                var mask=rounded ? StructurePieceOccupancy.groundMask(pool) : null;
                if (mask==null) pads.add(new Pad(rect(box),grade,piece));
                else {
                    auditedFoundations.add(piece);
                    for (var contact:mask) pads.add(new Pad(contact,grade,piece));
                }
            } else pads.add(new Pad(rect(box), box.minY() - 1,piece));
        }
        Map<Long, Column> columns = new HashMap<>();
        List<PlacementGeometry.Rect> samplingAreas=new ArrayList<>();
        // Policy 5's contour can reach radius+2; retain two untouched validation cells beyond it.
        for (Pad pad:pads) samplingAreas.add(pad.rect.inflate(policy.blendRadiusBlocks()+(terrainVersion>=5 ? 4 : 2)));
        for (var path:paths) samplingAreas.add(path.inflate(2));
        for (var area:samplingAreas) {
            long areaSize = ((long)area.maxX() - area.minX() + 1) * ((long)area.maxZ() - area.minZ() + 1);
            if (areaSize > policy.maxTerrainSamples()) return reject(rejected,"sample budget","single area="+areaSize);
            for (int x = area.minX(); x <= area.maxX(); x++) for (int z = area.minZ(); z <= area.maxZ(); z++) {
                long key = key(x, z);
                if (columns.containsKey(key)) continue;
                if (columns.size() >= policy.maxTerrainSamples()) return reject(rejected,"sample budget","combined columns="+columns.size());
                int[] sample=sampledHeights.get(key);
                if (sample==null) {
                    StructureHeightCache.Heights raw=sharedHeights==null ? null : sharedHeights.get(key);
                    if (raw==null) {
                        if (sharedHeights!=null) sharedHeights.recordHeightCall();
                        int oceanFloor=generator.getFirstOccupiedHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,height,random);
                        if (sharedHeights!=null) sharedHeights.recordHeightCall();
                        int worldSurface=generator.getFirstOccupiedHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,height,random);
                        raw=new StructureHeightCache.Heights(oceanFloor,worldSurface);
                        if (sharedHeights!=null) sharedHeights.put(key,raw);
                    }
                    sample=new int[] {raw.oceanFloor(),raw.worldSurface(),0};
                    sampledHeights.put(key,sample);
                }
                int original = sample[0];
                int surface = sample[1];
                int nearest = Integer.MAX_VALUE;
                int lower=Integer.MIN_VALUE,upper=Integer.MAX_VALUE;
                Pad lowerPad=null,upperPad=null;
                for (Pad candidate : pads) {
                    int distance = PlacementGeometry.terrainBlendDistance(candidate.rect,x,z,
                            policy.blendRadiusBlocks(),terrainVersion);
                    if (distance == Integer.MAX_VALUE) continue;
                    int limit = PlacementGeometry.gradeAllowance(distance, terrainVersion);
                    if (candidate.y-limit>lower) lowerPad=candidate;
                    if (candidate.y+limit<upper) upperPad=candidate;
                    lower=Math.max(lower,candidate.y-limit);
                    upper=Math.min(upper,candidate.y+limit);
                    nearest=Math.min(nearest,distance);
                }
                if (lower>upper) return reject(rejected,"incompatible foundation levels",x+","+z+" lower="+lower+" upper="+upper
                        +" lowerPad="+describePad(lowerPad)+" upperPad="+describePad(upperPad));
                int target=PlacementGeometry.constrainedHeight(original,lower,upper);
                boolean pathColumn=false;
                for (var path:paths) if (path.distance(x,z)==0) { pathColumn=true; break; }
                if (!PlacementGeometry.boundedEarthwork(original, target, policy.maxCutBlocks(), policy.maxFillBlocks())) {
                    return reject(rejected,"cut/fill budget",x+","+z+" original="+original+" target="+target);
                }
                if (nearest==0 || original!=target || pathColumn) {
                    // Fluids in the unchanged outer rim are allowed, but foundations and
                    // terrain-matching village corridors must have dry natural support.
                    if (surface!=original || original<height.getMinBuildHeight()+policy.maxCutBlocks()+2) {
                        return reject(rejected,"water or empty support",x+","+z+" ground="+original+" surface="+surface);
                    }
                    if (sample[2]==0) {
                        var nativeColumn=generator.getBaseColumn(x,z,height,random);
                        boolean safe=true;
                        // Validate all possible bounded adjustments once, then reuse across alternatives.
                        for (int y=original-policy.maxCutBlocks()-1;y<=original;y++) {
                            if (!natural(nativeColumn.getBlock(y))) { safe=false; break; }
                        }
                        for (int y=original+1;safe && y<=original+policy.maxFillBlocks();y++) {
                            if (!nativeColumn.getBlock(y).isAir()) safe=false;
                        }
                        sample[2]=safe?1:-1;
                    }
                    if (sample[2]<0) return reject(rejected,"non-natural support",x+","+z+" original="+original);
                }
                columns.put(key, new Column(original, target, nearest==0, nearest==0 || pathColumn));
            }
        }
        if (terrainVersion >= 5) {
            // Ground masks partition actual contact cells into disjoint rectangles.
            // Do not score whole-box fallbacks: their roof overhangs and air are unaudited.
            Map<StructurePiece,long[]> earthwork=new IdentityHashMap<>();
            for (Pad pad:pads) {
                if (!auditedFoundations.contains(pad.source)) continue;
                long[] counts=earthwork.computeIfAbsent(pad.source,ignored -> new long[2]);
                for (int x=pad.rect.minX();x<=pad.rect.maxX();x++) for (int z=pad.rect.minZ();z<=pad.rect.maxZ();z++) {
                    Column column=columns.get(key(x,z));
                    counts[0]++;
                    if (Math.abs((long)column.target-column.original)>=2) counts[1]++;
                }
            }
            for (long[] counts:earthwork.values()) {
                if (!PlacementGeometry.naturalFoundation(counts[0],counts[1],terrainVersion)) {
                    return reject(rejected,"foundation earthwork coverage",
                            "contactColumns="+counts[0]+" deepCutOrFillColumns="+counts[1]);
                }
            }
        }
        // Check every one- and two-column neighborhood (including unchanged outer rim).
        for (var entry : columns.entrySet()) {
            int x = (int)(entry.getKey() >> 32), z = (int)(long)entry.getKey();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                int distance = Math.abs(dx) + Math.abs(dz);
                if (distance == 0 || distance > 2) continue;
                Column other = columns.get(key(x + dx, z + dz));
                Column current=entry.getValue();
                if (other != null && (current.slopeChecked || other.slopeChecked || current.original!=current.target || other.original!=other.target)
                        && !PlacementGeometry.slope(current.target, other.target, distance)) {
                    return reject(rejected,"slope",x+","+z+" target="+current.target+" neighbor="+other.target+" distance="+distance);
                }
            }
        }
        // Packed X/Z keys share many Long hashes. Map.copyOf uses linear-probing MapN,
        // making neighborhood lookups quadratic on a grid; HashMap treeifies those bins.
        return new Plan(Collections.unmodifiableMap(new HashMap<>(columns)));
    }
    /** Every endpoint participating in the accepted local grade constraints. */
    public static boolean protectsGrade(Plan plan,int x,int z) {
        Column column=plan.columns().get(key(x,z));
        if (column==null) return false;
        if (column.slopeChecked() || column.original()!=column.target()) return true;
        for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
            int distance=Math.abs(dx)+Math.abs(dz);
            if (distance==0 || distance>2) continue;
            Column neighbor=plan.columns().get(key(x+dx,z+dz));
            if (neighbor!=null && (neighbor.slopeChecked() || neighbor.original()!=neighbor.target())) return true;
        }
        return false;
    }

    private static String describePad(Pad pad) {
        if (pad==null) return "none";
        return (pad.source instanceof PoolElementStructurePiece pool ? pool.getElement() : pad.source.getClass().getName())
                +" y="+pad.y+" rect="+pad.rect;
    }

    private static Plan reject(BiConsumer<String,String> rejected,String reason,String detail) {
        rejected.accept(reason,detail);
        return null;
    }

    static boolean natural(BlockState state) {
        return state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                    || state.is(Blocks.SNOW_BLOCK));
    }

    public static ApplyResult apply(Plan plan, ChunkAccess chunk, Policy policy) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int changed=0, heightSkipped=0, materialSkipped=0, occupiedSkipped=0;
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            Column column = plan.columns.get(key(x, z));
            if (column == null || (column.target == column.original && !column.foundation)) continue;
            int actual = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            if (!PlacementGeometry.boundedEarthwork(actual, column.target, policy.maxCutBlocks(), policy.maxFillBlocks())) {
                heightSkipped++; continue;
            }
            BlockState top = chunk.getBlockState(cursor.set(x, actual, z));
            if (!natural(top)) { materialSkipped++; continue; }
            BlockState under = chunk.getBlockState(cursor.set(x, actual - 1, z));
            if (!natural(under)) { materialSkipped++; continue; }
            boolean safe = true;
            for (int y = Math.min(actual, column.target); y <= Math.max(actual, column.target); y++) {
                BlockState state = chunk.getBlockState(cursor.set(x, y, z));
                if (!(state.isAir() || natural(state))) { safe = false; break; }
            }
            if (!safe) { occupiedSkipped++; continue; }
            if (actual==column.target) continue;
            if (column.target < actual) {
                for (int y = actual; y > column.target; y--) chunk.setBlockState(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), false);
            } else {
                for (int y = actual; y < column.target; y++) chunk.setBlockState(cursor.set(x, y, z), under, false);
            }
            chunk.setBlockState(cursor.set(x, column.target, z), top, false);
            changed++;
        }
        return new ApplyResult(changed,heightSkipped,materialSkipped,occupiedSkipped);
    }
}
