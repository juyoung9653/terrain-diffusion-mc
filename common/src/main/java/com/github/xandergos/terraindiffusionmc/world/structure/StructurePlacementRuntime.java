package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.mojang.logging.LogUtils;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

public final class StructurePlacementRuntime {
    public static final int CURRENT_TERRAIN_POLICY = 5;
    private static final Map<ChunkGeneratorStructureState, StructureReservations> PLANNERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<StructureStart, StructureTerrain.Plan> PLANS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final Map<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Attempt> ATTEMPT = new ThreadLocal<>();
    private static final int[][] OFFSETS = {{0,0},{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final class Attempt {
        final int x,z; final ChunkPos owner; boolean used, foreignOrigin;
        Attempt(int x,int z,ChunkPos owner) { this.x=x; this.z=z; this.owner=owner; }
    }
    private StructurePlacementRuntime() {}

    public static BlockPos offsetJigsaw(BlockPos pos) {
        Attempt attempt = ATTEMPT.get();
        if (attempt == null) return pos;
        // The JigsawStructure local hook runs before vanilla's addPieces fallback hook.
        if (attempt.used) return pos;
        int minX = attempt.owner.getMinBlockX(), minZ = attempt.owner.getMinBlockZ();
        if (pos.getX()<minX || pos.getX()>minX+15 || pos.getZ()<minZ || pos.getZ()>minZ+15) {
            attempt.foreignOrigin=true;
            return pos;
        }
        attempt.used = true;
        // The unmodified first attempt preserves mod-specific initial origin semantics.
        if (attempt.x==0 && attempt.z==0) return pos;
        return new BlockPos(PlacementGeometry.localOffset(pos.getX(),attempt.x,minX), pos.getY(),
                PlacementGeometry.localOffset(pos.getZ(),attempt.z,minZ));
    }

    private static void diagnostic(String id, String reason) {
        count(id,reason);
        if (DIAGNOSTICS.add(id + ":" + reason)) LogUtils.getLogger().info("TD structure policy {}: {}", id, reason);
    }
    static void count(String id,String reason) { COUNTERS.computeIfAbsent(id+" | "+reason,k -> new LongAdder()).increment(); }
    static void recordHeightCache(StructureHeightCache.Snapshot snapshot) {
        String prefix="terrain-diffusion-mc:height_cache | ";
        COUNTERS.computeIfAbsent(prefix+"choices",k -> new LongAdder()).increment();
        COUNTERS.computeIfAbsent(prefix+"hits",k -> new LongAdder()).add(snapshot.hits());
        COUNTERS.computeIfAbsent(prefix+"misses",k -> new LongAdder()).add(snapshot.misses());
        COUNTERS.computeIfAbsent(prefix+"native height calls",k -> new LongAdder()).add(snapshot.nativeHeightCalls());
        COUNTERS.computeIfAbsent(prefix+"cap drops",k -> new LongAdder()).add(snapshot.capDrops());
    }
    public static Map<String,Long> diagnostics() {
        Map<String,Long> result=new TreeMap<>();
        COUNTERS.forEach((key,value) -> result.put(key,value.sum()));
        return Map.copyOf(result);
    }
    public static void clear() { PLANNERS.clear(); PLANS.clear(); DIAGNOSTICS.clear(); COUNTERS.clear(); StructureSurfaceMetadata.clear(); StructurePieceOccupancy.clear(); }

    public static StructureStart generate(Structure structure, RegistryAccess access, ChunkGenerator generator,
            BiomeSource biomes, RandomState random, StructureTemplateManager templates, long seed, ChunkPos owner,
            int references, LevelHeightAccessor height, Predicate<Holder<Biome>> validBiome, ChunkGeneratorStructureState state,
            StructureSet generatingSet) {
        if (!TerrainDiffusionBiomeSources.isTerrainDiffusion(biomes)) return structure.generate(access,generator,biomes,random,templates,seed,owner,references,height,validBiome);
        var registry = access.registryOrThrow(Registries.STRUCTURE);
        var config = StructurePlacementConfig.snapshotFor(registry);
        if (!config.enabled()) return structure.generate(access,generator,biomes,random,templates,seed,owner,references,height,validBiome);
        String id = String.valueOf(registry.getKey(structure));
        if (state == null || generatingSet==null) { diagnostic(id,"missing structure state/set context; rejected"); return StructureStart.INVALID_START; }
        StructureReservations planner;
        synchronized (PLANNERS) { planner = PLANNERS.computeIfAbsent(state,s -> new StructureReservations(s,registry,config)); }
        // Preflight sees only immutable height limits, never a neighbor ChunkAccess.
        LevelHeightAccessor limits=LevelHeightAccessor.create(height.getMinBuildHeight(),height.getHeight());
        var reservation = planner.winner(structure,owner,generatingSet,new StructureReservations.Preflight() {
            @Override public StructureReservations.Descriptor assemble(Structure selected,ChunkPos pos) {
                return assemble(selected,pos,null);
            }
            @Override public StructureReservations.Descriptor assemble(Structure selected,ChunkPos pos,StructureHeightCache heights) {
                Assembly assembled=StructurePlacementRuntime.assemble(selected,access,generator,biomes,random,templates,seed,pos,0,limits,
                        selected.biomes()::contains,config.resolve(String.valueOf(registry.getKey(selected))),true,heights);
                if (assembled==null) return null;
                var footprint=descriptor(assembled);
                if (SavedStructureIndex.conflicts(registry,footprint.bounds(),footprint.gap())
                        || SavedStructureIndex.overlaps(registry,footprint.influence())) {
                    count(String.valueOf(registry.getKey(selected)),"preflight saved footprint conflict");
                    return null;
                }
                return footprint;
            }
        });
        if (reservation == null) { count(id,"reservation rejected"); return StructureStart.INVALID_START; }
        Assembly assembled=assemble(structure,access,generator,biomes,random,templates,seed,owner,references,
                height,validBiome,config.resolve(id),false,null);
        if (assembled==null) return StructureStart.INVALID_START;
        var actual=descriptor(assembled);
        if (!reservation.descriptor().conservative() && !reservation.descriptor().equals(actual)) {
            diagnostic(id,"preflight reproduction mismatch; rejected");
            return StructureStart.INVALID_START;
        }
        if (SavedStructureIndex.conflicts(registry,actual.bounds(),reservation.candidate().gap())
                || SavedStructureIndex.overlaps(registry,actual.influence())) {
            diagnostic(id,"saved structure footprint conflicts with accepted bounds; rejected");
            return StructureStart.INVALID_START;
        }
        StructureStart start=assembled.start();
        if (assembled.plan()!=null) PLANS.put(start,assembled.plan());
        ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion(CURRENT_TERRAIN_POLICY);
        // Native/modded assembly can drift after bounded preflight cache eviction. Commit
        // actual geometry atomically; an unsuccessful later chunk write leaves a conservative
        // session reservation until lifecycle clear, rather than risking a conflicting start.
        if (!planner.accept(owner,actual)) {
            diagnostic(id,"accepted session footprint conflict or changed owner replay; rejected");
            return StructureStart.INVALID_START;
        }
        count(id,"accepted");
        if (generatingSet.placement() instanceof UnifiedVillagePlacement villagePlacement)
            count(VillageDistributionGeometry.SET_ID,"accepted "+VillageDistributionGeometry.group(id,villagePlacement.epicLoaded())
                    .name().toLowerCase(java.util.Locale.ROOT));
        if (assembled.attempt()>0) count(id,"alternate accepted");
        return start;
    }

    private record Assembly(StructureStart start, int attempt, StructureTerrain.Plan plan, int gap, boolean surface) {}

    /** Assembly, bounds, component and terrain checks shared by preflight and real STARTS. */
    private static Assembly assemble(Structure structure, RegistryAccess access, ChunkGenerator generator,
            BiomeSource biomes, RandomState random, StructureTemplateManager templates, long seed, ChunkPos owner,
            int references, LevelHeightAccessor height, Predicate<Holder<Biome>> validBiome,
            StructurePlacementConfig.Policy policy, boolean preflight, StructureHeightCache sharedHeights) {
        String id=String.valueOf(access.registryOrThrow(Registries.STRUCTURE).getKey(structure));
        int radius=policy.reservationRadiusBlocks();
        var footprintEnvelope=new PlacementGeometry.Rect(owner.getMiddleBlockX()-radius,owner.getMiddleBlockZ()-radius,
                owner.getMiddleBlockX()+radius,owner.getMiddleBlockZ()+radius);
        var terrainDependency=new PlacementGeometry.Rect(owner.getMinBlockX()-128,owner.getMinBlockZ()-128,
                owner.getMaxBlockX()+128,owner.getMaxBlockZ()+128);
        Map<Long,int[]> sampledHeights=new HashMap<>();
        for (int attemptIndex=0; attemptIndex<Math.min(OFFSETS.length,policy.maxCandidateAttempts()); attemptIndex++) {
            count(id,preflight ? "preflight candidate attempts" : "candidate attempts");
            if (attemptIndex>0) count(id,"alternate attempts");
            Attempt attempt = new Attempt(OFFSETS[attemptIndex][0]*policy.maxCandidateOffsetBlocks(), OFFSETS[attemptIndex][1]*policy.maxCandidateOffsetBlocks(),owner);
            Attempt previous = ATTEMPT.get();
            StructureStart start;
            try {
                ATTEMPT.set(attempt);
                long layoutSeed=structure.getClass()==net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.class
                        ? PlacementGeometry.layoutSeed(seed,id,owner.x,owner.z,attemptIndex) : seed;
                start = structure.generate(access,generator,biomes,random,templates,layoutSeed,owner,references,height,validBiome);
            } finally { if (previous == null) ATTEMPT.remove(); else ATTEMPT.set(previous); }
            if (attempt.used) count(id,"standard jigsaw assembly hook observed");
            else if (structure instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure) {
                diagnostic(id,"standard jigsaw hook not reached; alternate assembly unavailable");
            }
            if (attempt.foreignOrigin) diagnostic(id,"foreign jigsaw origin preserved; alternate assembly unavailable");
            if (!start.isValid()) count(id,"native generation returned invalid start");
            if (start.isValid()) {
                ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion(CURRENT_TERRAIN_POLICY);
                StructureTerrain.Plan acceptedPlan=null;
                boolean shaped=StructureTerrain.shapes(start,policy,access);
                boolean accepted = footprintEnvelope.contains(StructureTerrain.rect(start.getBoundingBox()))
                        && (!shaped || terrainDependency.contains(StructureTerrain.rect(start.getBoundingBox())));
                if (!accepted) diagnostic(id,"footprint exceeds bounded reservation; rejected");
                List<StructurePiece> pieces = start.getPieces();
                for (StructurePiece piece:pieces) {
                    if (!footprintEnvelope.contains(StructureTerrain.rect(piece.getBoundingBox()))
                            || (shaped && !terrainDependency.contains(StructureTerrain.rect(piece.getBoundingBox())))) {
                        accepted=false; diagnostic(id,"component footprint exceeds bounded reservation; rejected"); break;
                    }
                }
                // Underground/native assemblies can intentionally share corridors and rooms.
                // House-overlap protection belongs to the opted-in ground adapter; external
                // reservation separation applies equally to every supported namespace.
                var collisions=new StructurePieceOccupancy.CollisionContext();
                for (int i=0; accepted && shaped && i<pieces.size(); i++) for (int j=i+1; j<pieces.size(); j++) {
                    if (!collisions.hasBlocks(pieces.get(i)) || !collisions.hasBlocks(pieces.get(j))) continue;
                    boolean stackedBuildings=pieces.get(i) instanceof PoolElementStructurePiece a
                            && pieces.get(j) instanceof PoolElementStructurePiece b
                            && a.getElement().getProjection()==net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID
                            && b.getElement().getProjection()==net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID
                            && shaped
                            && a.getBoundingBox().intersects(b.getBoundingBox().minX(),b.getBoundingBox().minZ(),b.getBoundingBox().maxX(),b.getBoundingBox().maxZ());
                    if (collisions.conflicts(pieces.get(i),pieces.get(j),
                            stackedBuildings || pieces.get(i).getBoundingBox().intersects(pieces.get(j).getBoundingBox()))) {
                        if (DIAGNOSTICS.add(id+":first-component-conflict")) {
                            LogUtils.getLogger().info("TD component conflict {} owner={} horizontal={} first={} box={} second={} box={}",
                                    id,owner,stackedBuildings,describe(pieces.get(i)),pieces.get(i).getBoundingBox(),
                                    describe(pieces.get(j)),pieces.get(j).getBoundingBox());
                        }
                        accepted=false; diagnostic(id,"intersecting component envelopes; rejected"); break;
                    }
                }
                if (accepted && shaped) {
                    StructureTerrain.Plan plan;
                    try {
                        // Native height/base-column scans revisit one tile for many Y values.
                        // Reuse that tile only during planning; keep native material checks intact.
                        var provider=LocalTerrainProvider.getInstance();
                        plan = provider.withRequestOrigin(LocalTerrainProvider.RequestOrigin.STRUCTURE, () ->
                                provider.withSamplingBinding(() -> StructureTerrain.plan(start,policy,generator,random,height,
                                        sampledHeights,sharedHeights,(reason,detail) -> {
                                    count(id,"terrain rejected: "+reason);
                                    if (DIAGNOSTICS.add(id+":terrain:"+reason)) {
                                        LogUtils.getLogger().info("TD terrain rejection {} owner={} reason={} {}",id,owner,reason,detail);
                                    }
                                })));
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IllegalStateException("Structure terrain sampling failed for "+id, failure);
                    }
                    if (plan == null) { accepted=false; diagnostic(id,"terrain support, slope or sample budget rejected candidate"); }
                    else {
                        for (var column:plan.columns().entrySet()) {
                            int x=(int)(column.getKey()>>32),z=(int)(long)column.getKey();
                            boolean changed=column.getValue().original()!=column.getValue().target();
                            if (!changed && !StructureTerrain.protectsGrade(plan,x,z)) continue;
                            var columnRect=new PlacementGeometry.Rect(x,z,x,z);
                            if ((changed && !footprintEnvelope.contains(columnRect)) || !terrainDependency.contains(columnRect)) {
                                accepted=false; diagnostic(id,"terrain blend exceeds bounded reservation; rejected"); break;
                            }
                        }
                        if (accepted) acceptedPlan=plan;
                    }
                }
                if (accepted) {
                    if (preflight) count(id,"preflight viable");
                    if (!shaped) diagnostic(id,"native terrain preserved; no generic surface adapter");
                    return new Assembly(start,attemptIndex,acceptedPlan,policy.minGapBlocks(),
                            structure.step()==net.minecraft.world.level.levelgen.GenerationStep.Decoration.SURFACE_STRUCTURES
                                    || StructureSurfaceMetadata.projectsToGround(structure,access));
                }
            }
            if (!attempt.used || attempt.foreignOrigin || policy.maxCandidateOffsetBlocks()==0) break;
        }
        return null;
    }

    private static StructureReservations.Descriptor descriptor(Assembly assembly) {
        StructureStart start=assembly.start();
        var bounds=StructureTerrain.rect(start.getBoundingBox());
        var influence=bounds;
        List<String> pieces=new ArrayList<>();
        for (StructurePiece piece:start.getPieces()) {
            String signature=piece.getClass().getName()+":"+piece.getBoundingBox()+":"+piece.getOrientation();
            if (piece instanceof PoolElementStructurePiece pool)
                signature+=":"+pool.getElement()+":"+pool.getPosition()+":"+pool.getRotation()+":"+pool.getGroundLevelDelta();
            pieces.add(signature);
        }
        String terrainFingerprint="";
        if (assembly.plan()!=null) {
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                for (var entry:new TreeMap<>(assembly.plan().columns()).entrySet()) {
                    int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
                    var column=entry.getValue();
                    if (column.original()!=column.target() || StructureTerrain.protectsGrade(assembly.plan(),x,z))
                        influence=influence.union(new PlacementGeometry.Rect(x,z,x,z));
                    digest.update((entry.getKey()+":"+column+";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                terrainFingerprint=HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        return new StructureReservations.Descriptor(start.getStructure(),assembly.attempt(),bounds,influence,
                List.copyOf(pieces),terrainFingerprint,assembly.gap(),assembly.surface(),false);
    }

    public static boolean shapes(StructureStart start, RegistryAccess access) {
        if (!managedTerrain(start)) return false;
        var registry=access.registryOrThrow(Registries.STRUCTURE);
        var config=StructurePlacementConfig.snapshotFor(registry);
        return config.enabled() && StructureTerrain.shapes(start,config.resolve(String.valueOf(registry.getKey(start.getStructure()))),access);
    }

    private static boolean managedTerrain(StructureStart start) {
        int version = ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion();
        return version >= 1 && version <= CURRENT_TERRAIN_POLICY;
    }

    public static void finishFoundation(WorldGenLevel level,ChunkGenerator generator,StructureStart start,
                                        BoundingBox box,ChunkPos chunk) {
        if (!(level instanceof WorldGenRegion)
                || ((PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion()<4
                || !TerrainDiffusionBiomeSources.isTerrainDiffusion(generator.getBiomeSource())
                || !shapes(start,level.registryAccess())) return;
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        String id=String.valueOf(registry.getKey(start.getStructure()));
        var policy=StructurePlacementConfig.snapshotFor(registry).resolve(id);
        var plan=PLANS.get(start);
        if (plan==null) {
            plan=StructureTerrain.plan(start,policy,generator,level.getLevel().getChunkSource().randomState(),level);
            if (plan==null) return;
            PLANS.put(start,plan);
        }
        int filled=StructureFoundationSupport.restore(level,start,box,chunk,plan,policy);
        if (filled>0) add(id,"post-placement soil support blocks",filled);
    }

    public static void surface(WorldGenRegion region, ChunkGenerator generator, RandomState random, ChunkAccess chunk) {
        forEachTerrainPlan(region,generator,random,chunk,(start,plan) -> {
            var registry=region.registryAccess().registryOrThrow(Registries.STRUCTURE);
            String id=String.valueOf(registry.getKey(start.getStructure()));
            var policy=StructurePlacementConfig.snapshotFor(registry).resolve(id);
            var applied=StructureTerrain.apply(plan,chunk,policy);
            add(id,"terrain columns changed",applied.changed());
            add(id,"terrain height mismatch skips",applied.heightSkipped());
            add(id,"terrain protected material skips",applied.materialSkipped());
            add(id,"terrain occupied column skips",applied.occupiedSkipped());
            if (applied.heightSkipped()+applied.materialSkipped()+applied.occupiedSkipped()>0) {
                diagnostic(id,"native terrain changed before bounded surface pass; adapter incomplete");
            }
        });
    }

    /** Keep the verified soil band intact through carving; this never restores or writes terrain. */
    public static void protectCarving(WorldGenRegion region, ChunkGenerator generator, RandomState random,
            ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving step) {
        if (!(chunk instanceof ProtoChunk proto)) return;
        int[] floors=new int[256], tops=new int[256];
        Arrays.fill(floors,Integer.MAX_VALUE);
        Arrays.fill(tops,Integer.MIN_VALUE);
        forEachTerrainPlan(region,generator,random,chunk,(start,plan) -> {
            var registry=region.registryAccess().registryOrThrow(Registries.STRUCTURE);
            String id=String.valueOf(registry.getKey(start.getStructure()));
            int maxCut=StructurePlacementConfig.snapshotFor(registry).resolve(id).maxCutBlocks();
            int protectedColumns=0;
            for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                long key=((long)(chunk.getPos().getMinBlockX()+x)<<32)
                        | ((chunk.getPos().getMinBlockZ()+z)&0xffffffffL);
                var column=plan.columns().get(key);
                if (column==null) continue;
                // The accepted slope also constrains the sampled, unchanged endpoint
                // beside a pad/path/blend. Keep both endpoints of that constraint intact.
                if (!StructureTerrain.protectsGrade(plan,chunk.getPos().getMinBlockX()+x,chunk.getPos().getMinBlockZ()+z)) continue;
                int index=z*16+x;
                floors[index]=Math.min(floors[index],PlacementGeometry.supportFloor(column.original(),maxCut));
                tops[index]=Math.max(tops[index],column.target());
                protectedColumns++;
            }
            add(id,"carver protected columns",protectedColumns);
        });
        if (Arrays.stream(tops).allMatch(top -> top==Integer.MIN_VALUE)) return;
        CarvingMask mask=proto.getOrCreateCarvingMask(step);
        var previous=((com.github.xandergos.terraindiffusionmc.mixin.CarvingMaskAccess)(Object)mask)
                .terrainDiffusion$additionalMask();
        mask.setAdditionalMask((x,y,z) -> {
            int index=(z&15)*16+(x&15);
            return (y>=floors[index] && y<=tops[index]) || previous.test(x,y,z);
        });
    }

    private static void forEachTerrainPlan(WorldGenRegion region, ChunkGenerator generator, RandomState random,
            ChunkAccess chunk, java.util.function.BiConsumer<StructureStart,StructureTerrain.Plan> action) {
        if (!TerrainDiffusionBiomeSources.isTerrainDiffusion(generator.getBiomeSource())) return;
        var registry=region.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var config=StructurePlacementConfig.snapshotFor(registry);
        if (!config.enabled()) return;
        // SURFACE and CARVERS already depend on radius-eight STARTS. Never request neighbor REFERENCES here.
        int cx=chunk.getPos().x, cz=chunk.getPos().z;
        var chunkRect=new PlacementGeometry.Rect(cx*16,cz*16,cx*16+15,cz*16+15);
        for (int x=cx-8;x<=cx+8;x++) for (int z=cz-8;z<=cz+8;z++) {
            ChunkAccess source=region.getChunk(x,z,ChunkStatus.STRUCTURE_STARTS);
            for (StructureStart start:source.getAllStarts().values()) {
                if (!start.isValid() || !managedTerrain(start)) continue;
                var policy=config.resolve(String.valueOf(registry.getKey(start.getStructure())));
                if (!StructureTerrain.shapes(start,policy,region.registryAccess()) || !StructureTerrain.rect(start.getBoundingBox()).inflate(policy.blendRadiusBlocks()+2).conflicts(chunkRect,1)) continue;
                StructureTerrain.Plan plan=PLANS.get(start);
                if (plan==null) {
                    plan=StructureTerrain.plan(start,policy,generator,random,chunk);
                    if (plan!=null) PLANS.put(start,plan);
                }
                if (plan!=null) action.accept(start,plan);
            }
        }
    }
    private static void add(String id,String reason,int value) {
        if (value>0) COUNTERS.computeIfAbsent(id+" | "+reason,key -> new LongAdder()).add(value);
    }
    private static String describe(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pool) return pool.getElement()+" projection="+pool.getElement().getProjection();
        return piece.getClass().getName();
    }
}
