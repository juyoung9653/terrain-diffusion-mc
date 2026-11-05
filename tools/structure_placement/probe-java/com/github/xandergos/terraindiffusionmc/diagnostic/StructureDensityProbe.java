package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelData;
import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRivers;
import com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureHeightCache;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureTerrain;
import com.github.xandergos.terraindiffusionmc.world.structure.UnifiedVillageSets;
import com.github.xandergos.terraindiffusionmc.world.structure.VillageDistributionGeometry;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Fixed-area census of all native candidate owners, never filtered to TD winners. */
@EventBusSubscriber(modid="terrain_diffusion_mc", bus=EventBusSubscriber.Bus.GAME)
public final class StructureDensityProbe {
    private static final TicketType<ChunkPos> CENSUS_TICKET=TicketType.create("td-census",Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> RIVER_AUDIT_TICKET=TicketType.create("td-river-audit",Comparator.comparingLong(ChunkPos::toLong));
    private record HeightCase(String name,StructurePlacementConfig.Policy policy) { }
    private record HeightCaseResult(String name,StructureTerrain.Plan plan,List<Map<String,String>> rejected) { }
    private record HeightSequence(String name,List<HeightCaseResult> cases,
                                  StructureHeightCache.Snapshot cache,long wallNanos,long cpuNanos) { }
    private record MarineColumn(int x,int z,int localX,int localZ,ChunkAccess chunk,
                                int baseHeight,int oceanFloorHeight) { }
    private record ScoutCandidate(int x,int z,float signedMeters) { }
    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (Boolean.getBoolean("td.densityProbe") && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
            level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,level.getServer());
    }
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("td.densityProbe")) return;
        Thread worker=new Thread(() -> run(event.getServer()),"td-density-audit");
        worker.setDaemon(true);worker.start();
    }
    private static void run(MinecraftServer server) {
        Map<String,Object> report=new LinkedHashMap<>();
        long began=System.nanoTime();
        Long beganCpu=DensityProbeTelemetry.processCpuNanos();
        int availableProcessors=Runtime.getRuntime().availableProcessors();
        boolean undergroundAudit=Boolean.getBoolean("td.density.underground")
                || Files.exists(server.getServerDirectory().resolve("underground-audit.txt"));
        report.put("availableProcessors",availableProcessors);
        report.put("processCpuScope","whole JVM process (all threads and native work)");
        Map<String,Double> phaseSeconds=new LinkedHashMap<>();
        report.put("phaseSeconds",phaseSeconds);
        try {
            report.put("terrainMode",TerrainReplayStore.mode());
            int densityPrefetch=DensityProbeTelemetry.prefetch(System.getProperty("td.densityPrefetch"));
            report.put("savedIndexChecks",SavedStructureIndexChecks.run(server.getServerDirectory()));
            var level=server.overworld();
            var source=level.getChunkSource().getGenerator().getBiomeSource();
            if(source instanceof com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource td) {
                var terralith=td.getClass().getDeclaredField("terralith");
                terralith.setAccessible(true);
                int scale=com.github.xandergos.terraindiffusionmc.world.WorldScaleManager.getCurrentScale();
                report.put("fixture",Map.of("naturalBiomes",td.naturalBiomes(),"terralith",terralith.getBoolean(td),
                        "showcase",td.ecologyShowcase(),"scale",scale,"seed",level.getSeed(),
                        "riverProfile",com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level)));
                if(Files.exists(server.getServerDirectory().resolve("require-ecology-scale1.txt"))
                        && (!td.naturalBiomes() || !terralith.getBoolean(td) || !td.ecologyShowcase().isEmpty() || scale!=1))
                    throw new AssertionError("Fixture must match natural-biome, Terralith, scale-one play world: "+report.get("fixture"));
                System.out.println("[TD-DENSITY] fixture="+report.get("fixture"));
            }
            boolean generateStructures=server.getWorldData().worldGenOptions().generateStructures();
            if(!generateStructures && !undergroundAudit)
                throw new AssertionError("Density census requires generate-structures=true in the saved world");
            var state=level.getChunkSource().getGeneratorState();
            var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var config=StructurePlacementConfig.snapshotFor(registry);
            boolean repeatNative=Files.exists(server.getServerDirectory().resolve("repeat-native.txt"));
            List<Map<String,Object>> nativeRepeats=new ArrayList<>();
            if(repeatNative) {
                nativeRepeats.add(nativeSnapshot(server));
                nativeRepeats.add(nativeSnapshot(server));
                report.put("nativeRepeats",nativeRepeats);
            }
            int radius=96;
            var radiusFile=server.getServerDirectory().resolve("density-radius.txt");
            if(Files.exists(radiusFile)) radius=Integer.parseInt(Files.readString(radiusFile).trim());
            int centerX=0,centerZ=0;
            var centerFile=server.getServerDirectory().resolve("density-center.txt");
            if(Files.exists(centerFile)) {
                String[] center=Files.readString(centerFile).trim().split(",");
                centerX=Integer.parseInt(center[0]);centerZ=Integer.parseInt(center[1]);
            }
            int minX=centerX-radius,maxX=centerX+radius-1,minZ=centerZ-radius,maxZ=centerZ+radius-1;
            ChunkStatus censusStatus=undergroundAudit ? ChunkStatus.FULL : ChunkStatus.STRUCTURE_STARTS;
            report.put("chunkCensusStatus",censusStatus.getName());
            boolean shoreAudit=Boolean.getBoolean("td.shoreAudit");
            if(shoreAudit) ShoreFrequencyTrace.begin(minX*16,(maxX+1)*16,minZ*16,(maxZ+1)*16);
            long phaseBegan=System.nanoTime();
            Set<ChunkPos> owners=new HashSet<>();
            boolean villagesOnly=Files.exists(server.getServerDirectory().resolve("village-census-only.txt"));
            report.put("villageCandidateOwnersOnly",villagesOnly);
            report.put("requestBatchSize",(villagesOnly || undergroundAudit) ? densityPrefetch : 1);
            var villageTag=net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("village"));
            report.put("taggedVillages",registry.holders().filter(holder -> holder.is(villageTag))
                    .map(holder -> holder.key().location().toString()).sorted().toList());
            Map<String,Integer> nativeCandidates=new TreeMap<>();
            for(var holder:state.possibleStructureSets()) {
                String id=holder.unwrapKey().map(k -> k.location().toString()).orElseGet(() -> UnifiedVillageSets.syntheticId(holder.value()));
                if(id==null) throw new AssertionError("Unknown unregistered structure set");
                if(villagesOnly && !id.equals(VillageDistributionGeometry.SET_ID)) continue;
                var placement=holder.value().placement();
                int count=0;
                if(placement instanceof RandomSpreadStructurePlacement spread) {
                    int spacing=spread.spacing();
                    for(int x=Math.floorDiv(minX,spacing);x<=Math.floorDiv(maxX,spacing);x++)
                        for(int z=Math.floorDiv(minZ,spacing);z<=Math.floorDiv(maxZ,spacing);z++) {
                            ChunkPos p=spread.getPotentialStructureChunk(level.getSeed(),x*spacing,z*spacing);
                            if(p.x>=minX && p.x<=maxX && p.z>=minZ && p.z<=maxZ
                                    && placement.applyAdditionalChunkRestrictions(p.x,p.z,level.getSeed())) {
                                owners.add(p);count++;
                            }
                        }
                } else if(placement instanceof ConcentricRingsStructurePlacement rings) {
                    var positions=state.getRingPositionsFor(rings);
                    if(positions!=null) for(var p:positions)
                        if(p.x>=minX && p.x<=maxX && p.z>=minZ && p.z<=maxZ) {owners.add(p);count++;}
                }
                nativeCandidates.put(id,count);
            }
            List<ChunkPos> ordered=new ArrayList<>(owners);
            ordered.sort(Comparator.comparingInt((ChunkPos p)->p.x).thenComparingInt(p->p.z));
            // A terrain-only fixture intentionally disables structures.  It still
            // needs a fixed square of chunks so its depth timing is meaningful.
            if(undergroundAudit && !generateStructures) {
                ordered.clear();
                for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) ordered.add(new ChunkPos(x,z));
            }
            if(Files.exists(server.getServerDirectory().resolve("reverse-order.txt"))) Collections.reverse(ordered);
            phaseSeconds.put("candidateEnumeration",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            report.put("seed",level.getSeed());report.put("radiusChunks",radius);
            report.put("centerChunk",List.of(centerX,centerZ));
            report.put("policy",config.document());
            Map<String,String> types=new TreeMap<>();
            Map<String,List<String>> villageBiomes=new TreeMap<>();
            for(var structure:registry) {
                String id=registry.getKey(structure).toString();types.put(id,structure.getClass().getName());
                if(VillageDistributionGeometry.isVillage(id,registry.wrapAsHolder(structure).is(villageTag)))
                    villageBiomes.put(id,structure.biomes().stream().map(b->b.unwrapKey().orElseThrow().location().toString()).sorted().toList());
            }
            report.put("structureTypes",types);report.put("villageBiomes",villageBiomes);
            report.put("registeredStructures",registry.size());
            report.put("nativeCandidates",nativeCandidates);report.put("candidateOwners",ordered.size());
            phaseBegan=System.nanoTime();
            var inferenceBefore=inferenceCounters();
            long submissionRoundtripNanos=0,waitingFuturesNanos=0,ticketCleanupNanos=0;
            long singleChunkFetchNanos=0,scanningNanos=0;
            List<StructureStart> actual=new ArrayList<>();
            List<Map<String,Object>> rows=new ArrayList<>();
            Map<String,Integer> accepted=new TreeMap<>();
            List<Map<String,Object>> villages=new ArrayList<>();
            int attempted=0;
            Map<ChunkPos,ChunkAccess> prefetched=new HashMap<>();
            // Keep exact census ownership/order separate from the optional material audit.
            // The later material audit reads the FULL chunks from this timed census.
            List<ChunkPos> censusChunks=new ArrayList<>();
            Map<ChunkPos,ChunkAccess> censusChunkData=new HashMap<>();
            for(var p:ordered) {
                if((villagesOnly || undergroundAudit) && prefetched.isEmpty()) {
                    var batch=ordered.subList(attempted,Math.min(attempted+densityPrefetch,ordered.size()));
                    int distance=33-ChunkLevel.byStatus(censusStatus);
                    try {
                        long stepBegan=System.nanoTime();
                        server.submit(() -> batch.forEach(pos ->
                                level.getChunkSource().addRegionTicket(CENSUS_TICKET,pos,distance,pos)))
                                .get(180,TimeUnit.SECONDS);
                        // On the server thread getChunkFuture managed-blocks before returning.
                        // The public off-thread path schedules admission on that thread and
                        // returns immediately, so this worker can actually submit the batch.
                        var futures=batch.stream().map(pos -> level.getChunkSource()
                                .getChunkFuture(pos.x,pos.z,censusStatus,true)).toList();
                        submissionRoundtripNanos+=System.nanoTime()-stepBegan;
                        stepBegan=System.nanoTime();
                        for(int index=0;index<batch.size();index++) prefetched.put(batch.get(index),
                                futures.get(index).get(300,TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("Census chunk unavailable")));
                        waitingFuturesNanos+=System.nanoTime()-stepBegan;
                    } finally {
                        long cleanupBegan=System.nanoTime();
                        server.submit(() -> batch.forEach(pos -> level.getChunkSource().removeRegionTicket(CENSUS_TICKET,pos,distance,pos)))
                                .get(180,TimeUnit.SECONDS);
                        ticketCleanupNanos+=System.nanoTime()-cleanupBegan;
                    }
                }
                long stepBegan=System.nanoTime();
                var chunk=(villagesOnly || undergroundAudit) ? prefetched.remove(p) : AsyncStructureLocate.getChunk(level,p.x,p.z);
                censusChunks.add(p);
                if(undergroundAudit) censusChunkData.put(p,chunk);
                singleChunkFetchNanos+=System.nanoTime()-stepBegan;
                stepBegan=System.nanoTime();
                for(var start:chunk.getAllStarts().values()) if(start.isValid()) {
                    String id=registry.getKey(start.getStructure()).toString();
                    for(var prior:actual) {
                        int gap=Math.max(config.resolve(id).minGapBlocks(),config.resolve(registry.getKey(prior.getStructure()).toString()).minGapBlocks());
                        if(StructureTerrain.rect(start.getBoundingBox()).conflicts(StructureTerrain.rect(prior.getBoundingBox()),gap))
                            throw new AssertionError("Gap violated: "+start.getChunkPos()+" / "+prior.getChunkPos());
                    }
                    actual.add(start);accepted.merge(id,1,Integer::sum);
                    if(VillageDistributionGeometry.isVillage(id,registry.wrapAsHolder(start.getStructure()).is(villageTag))) {
                        var box=start.getBoundingBox();
                        villages.add(Map.of("id",id,"x",(box.minX()+box.maxX())/2.0,"z",(box.minZ()+box.maxZ())/2.0,
                                "basic",id.startsWith("terrain-diffusion-mc:basic_village_") || id.startsWith("mmv:")));
                    }
                    rows.add(Map.of("id",id,"x",p.x,"z",p.z,"box",start.getBoundingBox().toString(),"pieces",start.getPieces().size(),"adapted",StructurePlacementRuntime.shapes(start,level.registryAccess())));
                }
                attempted++;
                if(attempted%25==0) System.out.println("[TD-DENSITY] "+attempted+"/"+ordered.size()+" accepted="+actual.size());
                scanningNanos+=System.nanoTime()-stepBegan;
            }
            var inferenceDelta=DensityProbeTelemetry.inferenceDelta(inferenceBefore,inferenceCounters());
            Map<String,Object> censusTiming=new LinkedHashMap<>();
            censusTiming.put("submissionRoundtripSeconds",DensityProbeTelemetry.seconds(submissionRoundtripNanos));
            censusTiming.put("waitingFuturesSeconds",DensityProbeTelemetry.seconds(waitingFuturesNanos));
            censusTiming.put("ticketCleanupSeconds",DensityProbeTelemetry.seconds(ticketCleanupNanos));
            censusTiming.put("singleChunkFetchSeconds",DensityProbeTelemetry.seconds(singleChunkFetchNanos));
            censusTiming.put("scanningSeconds",DensityProbeTelemetry.seconds(scanningNanos));
            report.put("chunkCensusObserverSeconds",censusTiming);
            Map<String,Object> inferenceMetrics=new LinkedHashMap<>();
            inferenceMetrics.put("submittedDelta",inferenceDelta.submitted());
            inferenceMetrics.put("completedDelta",inferenceDelta.completed());
            inferenceMetrics.put("cumulativeQueueSecondsDelta",inferenceDelta.queueSeconds());
            inferenceMetrics.put("cumulativeExecutionSecondsDelta",inferenceDelta.executionSeconds());
            report.put("chunkCensusInference",inferenceMetrics);
            report.put("attempted",attempted);report.put("accepted",accepted);report.put("starts",rows);
            phaseSeconds.put("chunkCensus",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            report.put("totalStarts",actual.size());report.put("diagnostics",StructurePlacementRuntime.diagnostics());
            report.put("villages",villages);
            if(undergroundAudit) {
                phaseBegan=System.nanoTime();
                report.put("underground",auditUnderground(level,censusChunks,censusChunkData));
                // Block-state inspection follows the timed FULL census and does
                // not advance or load another chunk.
                phaseSeconds.put("undergroundAudit",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            }
            phaseBegan=System.nanoTime();
            int guard=config.villages().targetMeanNearestNeighborBlocks();
            List<Double> nearest=new ArrayList<>();
            for(var village:villages) {
                double x=((Number)village.get("x")).doubleValue(),z=((Number)village.get("z")).doubleValue();
                double boundary=Math.min(Math.min(x-minX*16,maxX*16+15-x),Math.min(z-minZ*16,maxZ*16+15-z));
                if(boundary<guard) continue;
                double best=Double.POSITIVE_INFINITY;
                for(var other:villages) if(other!=village) best=Math.min(best,Math.hypot(x-((Number)other.get("x")).doubleValue(),z-((Number)other.get("z")).doubleValue()));
                // Only retain observations whose nearest center cannot be outside the census boundary.
                // An outside owner may move its bounding-box center inside the owner census.
                int centerReach=config.global().reservationRadiusBlocks();
                for (var entry:config.document().structures.keySet()) centerReach=Math.max(centerReach,config.resolve(entry).reservationRadiusBlocks());
                for (var entry:config.document().namespaces.keySet()) centerReach=Math.max(centerReach,config.resolve(entry+":probe").reservationRadiusBlocks());
                if(best<=boundary-centerReach) nearest.add(best);
            }
            report.put("villageCenterDistances",nearest);
            report.put("villageCenterDistanceSamples",nearest.size());
            if(!nearest.isEmpty()) report.put("meanNearestVillageCenterBlocks",nearest.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            report.put("basicVillageCount",villages.stream().filter(v -> Boolean.TRUE.equals(v.get("basic"))).count());
            // Fixed land samples complement village-centered NN: empty regions still count.
            // Heights are generator samples, not a claim that every surveyed chunk reached FULL.
            double area=(maxX-minX+1L)*(maxZ-minZ+1L)*256.0/1_000_000;
            report.put("surveyAreaSquareKm",area);
            report.put("villageStartsPerSurveySquareKm",villages.size()/area);
            List<Map<String,Object>> landSamples=new ArrayList<>();
            int reach=config.global().reservationRadiusBlocks();
            for(var id:registry.keySet()) reach=Math.max(reach,config.resolve(id.toString()).reservationRadiusBlocks());
            var generator=level.getChunkSource().getGenerator();
            var randomState=level.getChunkSource().randomState();
            for(int x=minX*16+64;x<(maxX+1)*16;x+=128) for(int z=minZ*16+64;z<(maxZ+1)*16;z+=128) {
                int y=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,randomState);
                if(y<=generator.getSeaLevel()) continue;
                double boundary=Math.min(Math.min(x-minX*16,(maxX+1)*16-x),Math.min(z-minZ*16,(maxZ+1)*16-z))-reach;
                if(boundary<500) continue;
                double best=Double.POSITIVE_INFINITY;
                for(var village:villages) best=Math.min(best,Math.hypot(x-((Number)village.get("x")).doubleValue(),z-((Number)village.get("z")).doubleValue()));
                landSamples.add(Map.of("x",x,"z",z,"y",y,"distance",Double.isFinite(best)?best:-1,
                        "censorDistance",boundary,"exact",best<=boundary,"within500",best<=500));
            }
            report.put("landCoverageSamples",landSamples);
            report.put("landCoverageWithin500Fraction",landSamples.isEmpty()?0:landSamples.stream().filter(s->Boolean.TRUE.equals(s.get("within500"))).count()/(double)landSamples.size());
            phaseSeconds.put("coverage",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            phaseBegan=System.nanoTime();
            List<Map<String,Object>> locateChecks=new ArrayList<>();
            for(var start:actual) {
                String id=registry.getKey(start.getStructure()).toString();
                if(!VillageDistributionGeometry.isVillage(id,registry.wrapAsHolder(start.getStructure()).is(villageTag))) continue;
                var owner=start.getChunkPos();
                var found=server.submit(()->generator.findNearestMapStructure(level,
                        net.minecraft.core.HolderSet.direct(registry.wrapAsHolder(start.getStructure())),
                        new BlockPos(owner.getMiddleBlockX(),64,owner.getMiddleBlockZ()),0,false)).get(60,TimeUnit.SECONDS);
                if(found==null || (found.getFirst().getX()>>4)!=owner.x || (found.getFirst().getZ()>>4)!=owner.z)
                    throw new AssertionError("Native locate lost accepted village "+id+" at "+owner+": "+found);
                locateChecks.add(Map.of("id",id,"owner",List.of(owner.x,owner.z),"found",found.getFirst().toShortString()));
                if(locateChecks.size()>=3) break;
            }
            report.put("nativeLocateChecks",locateChecks);
            phaseSeconds.put("nativeLocate",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            phaseBegan=System.nanoTime();
            int policyRoundTrips=0;
            var serialization=net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level);
            for(var start:actual) {
                var tag=start.createTag(serialization,start.getChunkPos());
                int stored=((com.github.xandergos.terraindiffusionmc.world.structure.PolicyStructureStart)(Object)start).terrainDiffusion$policyVersion();
                if(stored!=3) throw new AssertionError("Expected new/reloaded policy 3, got "+stored);
                for(int version=1;version<=3;version++) {
                    var copy=tag.copy();copy.putInt("TDPlacementPolicy",version);
                    var reloaded=StructureStart.loadStaticStart(serialization,copy,level.getSeed());
                    if(reloaded==null || ((com.github.xandergos.terraindiffusionmc.world.structure.PolicyStructureStart)(Object)reloaded).terrainDiffusion$policyVersion()!=version)
                        throw new AssertionError("Policy NBT round trip failed: "+version);
                    if(version==3 && StructurePlacementRuntime.shapes(start,level.registryAccess())) {
                        var policy=config.resolve(registry.getKey(start.getStructure()).toString());
                        var before=StructureTerrain.plan(start,policy,generator,randomState,level);
                        var after=StructureTerrain.plan(reloaded,policy,generator,randomState,level);
                        if(before==null || !before.equals(after)) throw new AssertionError("Reload changed terrain plan");
                    }
                    policyRoundTrips++;
                }
            }
            report.put("policyRoundTrips",policyRoundTrips);
            phaseSeconds.put("serialization",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            if(repeatNative) {
                nativeRepeats.add(nativeSnapshot(server));
                nativeRepeats.add(nativeSnapshot(server));
                if(nativeRepeats.stream().anyMatch(row -> !row.equals(nativeRepeats.getFirst())))
                    throw new AssertionError("Native assembly changed within one server lifetime: "+nativeRepeats);
            }
            phaseBegan=System.nanoTime();
            boolean wellSupport=Boolean.getBoolean("td.densityWellSupport")
                    || Files.exists(server.getServerDirectory().resolve("verify-well-support.txt"));
            boolean supportRequested=Boolean.getBoolean("td.densitySupport") || wellSupport
                    || Files.exists(server.getServerDirectory().resolve("verify-support.txt"));
            if(supportRequested) {
                var supportCandidates=new ArrayList<>(actual);
                supportCandidates.sort(Comparator.comparing(start -> !registry.getKey(start.getStructure()).toString().startsWith("terrain-diffusion-mc:basic_village_")));
                if(wellSupport) {
                    supportCandidates.removeIf(start -> !registry.getKey(start.getStructure()).toString().startsWith("terrain-diffusion-mc:basic_village_")
                            || start.getPieces().stream().noneMatch(piece -> com.github.xandergos.terraindiffusionmc.world.structure.StructurePieceOccupancy.buriedWellBottom(piece,start.getPieces())));
                    if(supportCandidates.isEmpty()) throw new AssertionError("No accepted original village with a buried well");
                }
                for(var start:supportCandidates) if(start.getPieces().size()>1 && StructurePlacementRuntime.shapes(start,level.registryAccess())) {
                    report.put("support",verifySupport(server,start,wellSupport));break;
                }
                if(!report.containsKey("support")) throw new AssertionError("Support verification requested but no eligible adapted structure start was found");
            }
            phaseSeconds.put("supportVerification",DensityProbeTelemetry.seconds(System.nanoTime()-phaseBegan));
            if(shoreAudit) {
                int shoreSamples=ShoreFrequencyTrace.write(server.getWorldPath(LevelResource.ROOT));
                report.put("shoreSamples",shoreSamples);
                if(shoreSamples==0) throw new AssertionError("Shore audit requested but no in-bounds samples were recorded");
            }
            var terrainReplay=TerrainReplayStore.report();
            report.put("terrainReplay",terrainReplay);
            if(!TerrainReplayStore.mode().equals("full")) {
                String counter=TerrainReplayStore.mode().equals("replay")?"replayedTiles":"recordedTiles";
                if(((Number)terrainReplay.get(counter)).longValue()<=0
                        || ((Number)terrainReplay.get("misses")).longValue()!=0
                        || terrainReplay.get("failure")!=null)
                    throw new AssertionError("Incomplete terrain snapshot evidence: "+terrainReplay);
                report.put("onnxMetrics",com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel.runtimeMetrics());
            }
            report.put("success",true);
        } catch(Throwable e) {report.put("success",false);report.put("error",e.toString());e.printStackTrace();}
        Long endedCpu=DensityProbeTelemetry.processCpuNanos();
        long wallNanos=System.nanoTime()-began;
        report.put("elapsedSeconds",DensityProbeTelemetry.seconds(wallNanos));
        var cpu=DensityProbeTelemetry.usage(beganCpu,endedCpu,wallNanos,availableProcessors);
        report.put("processCpuTimeDeltaSeconds",cpu.processCpuSeconds());
        report.put("averageUtilizedLogicalCores",cpu.averageLogicalCores());
        report.put("percentAvailableLogicalCapacity",cpu.percentAvailableLogicalCapacity());
        try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("density-audit.json"),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report)); }
        catch(Exception e) {e.printStackTrace();}
        System.out.println("[TD-DENSITY] success="+report.get("success")+" count="+report.get("totalStarts"));
        server.execute(() -> server.halt(false));
    }

    /**
     * Records compact material evidence for a deep experimental world.
     * Underground mode requests FULL during the timed census. This method
     * only inspects those retained chunk references afterwards.
     */
    private static Map<String,Object> auditUnderground(net.minecraft.server.level.ServerLevel level,
                                                        List<ChunkPos> censusChunks,
                                                        Map<ChunkPos,ChunkAccess> censusChunkData) throws Exception {
        int minY=level.getMinBuildHeight(), maxY=level.getMaxBuildHeight()-1;
        List<ChunkPos> sampled=censusChunks.stream().distinct().toList();
        if(sampled.isEmpty()) throw new AssertionError("Underground audit needs at least one census chunk");
        Map<String,long[]> bands=new LinkedHashMap<>();
        // Values are air, water, lava, bedrock, ore, rock in that order.
        Set<String> deepRocks=new TreeSet<>();
        long columns=0, floorBedrock=0, nearLegacyBedrock=0, deepAir=0, scanned=0;
        long marineColumns=0, landColumns=0, marineWaterColumns=0;
        long marineLavaAboveFloor=0;
        // These columns were already generated by the census; raw model sampling
        // below is bounded after retaining the lowest physical candidates.
        List<MarineColumn> marineCandidates=new ArrayList<>();
        int lowestOceanFloor=Integer.MAX_VALUE;
        var surfaceDigest=java.security.MessageDigest.getInstance("SHA-256");
        var oceanFloorDigest=java.security.MessageDigest.getInstance("SHA-256");
        var terrainDigest=java.security.MessageDigest.getInstance("SHA-256");
        var landTerrainDigest=java.security.MessageDigest.getInstance("SHA-256");
        var marineTerrainDigest=java.security.MessageDigest.getInstance("SHA-256");
        var generator=level.getChunkSource().getGenerator();
        var randomState=level.getChunkSource().randomState();
        int generated=0;
        for(var pos:sampled) {
            var chunk=censusChunkData.get(pos);
            if(chunk==null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL))
                throw new AssertionError("Underground audit chunk did not reach FULL in the census: "+pos);
            generated++;
            for(int localX=2;localX<16;localX+=4) for(int localZ=2;localZ<16;localZ+=4) {
                columns++;
                int x=pos.getMinBlockX()+localX, z=pos.getMinBlockZ()+localZ;
                int baseOceanFloor=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,randomState);
                byte[] terrainSample=java.nio.ByteBuffer.allocate(12).putInt(x).putInt(z).putInt(baseOceanFloor).array();
                terrainDigest.update(terrainSample);
                surfaceDigest.update(java.nio.ByteBuffer.allocate(12).putInt(x).putInt(z)
                        .putInt(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,localX,localZ)).array());
                int oceanFloor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,localX,localZ);
                oceanFloorDigest.update(java.nio.ByteBuffer.allocate(12).putInt(x).putInt(z)
                        .putInt(oceanFloor).array());
                if(oceanFloor<63) {
                    marineColumns++;
                    lowestOceanFloor=Math.min(lowestOceanFloor,oceanFloor);
                    if(chunk.getBlockState(new BlockPos(x,62,z)).is(net.minecraft.world.level.block.Blocks.WATER)) marineWaterColumns++;
                }
                if(baseOceanFloor<63) {
                    marineTerrainDigest.update(terrainSample);
                    marineCandidates.add(new MarineColumn(x,z,localX,localZ,chunk,
                            baseOceanFloor,oceanFloor));
                } else {
                    landColumns++;
                    landTerrainDigest.update(terrainSample);
                }
                for(int y=minY;y<=maxY;y++) {
                    var state=chunk.getBlockState(new BlockPos(x,y,z));
                    if(oceanFloor<63 && y>oceanFloor && y<63
                            && state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA))
                        marineLavaAboveFloor++;
                    long[] counts=bands.computeIfAbsent(depthBand(minY,y), ignored -> new long[6]);
                    scanned++;
                    if(state.isAir()) {
                        counts[0]++;
                        if(minY<-64 && y<=-128) deepAir++;
                    } else if(state.is(net.minecraft.world.level.block.Blocks.WATER)) counts[1]++;
                    else if(state.is(net.minecraft.world.level.block.Blocks.LAVA)) counts[2]++;
                    else if(state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                        counts[3]++;
                        if(y==minY) floorBedrock++;
                        if(minY<-64 && y>=-72 && y<=-56) nearLegacyBedrock++;
                    } else {
                        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        if(id.endsWith("_ore")) counts[4]++;
                        else {
                            counts[5]++;
                            if(minY<-64 && y<=-128) deepRocks.add(id);
                        }
                    }
                }
            }
        }
        List<Map<String,Object>> bandRows=new ArrayList<>();
        for(var entry:bands.entrySet()) {
            long[] c=entry.getValue();
            bandRows.add(Map.of("band",entry.getKey(),"air",c[0],"water",c[1],"lava",c[2],
                    "bedrock",c[3],"ore",c[4],"rock",c[5]));
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("minY",minY); result.put("maxY",maxY); result.put("sampledChunks",generated);
        var terrainSource=com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources
                .find(generator.getBiomeSource());
        if(minY<-64 && terrainSource!=null) {
            result.put("boundScale",terrainSource.boundUndergroundScale());
            result.put("undergroundProfileVersion",terrainSource.undergroundProfileVersion());
        }
        result.put("runtimeTerrainProfile",runtimeTerrainProfile(level,generator,randomState,
                terrainSource,marineCandidates));
        result.put("columns",columns); result.put("scannedBlocks",scanned);
        result.put("floorBedrock",floorBedrock); result.put("legacyShelfBedrock",nearLegacyBedrock);
        result.put("deepCaveAir",deepAir); result.put("deepRockTypes",deepRocks);
        result.put("deepCavesObserved",deepAir>0); result.put("variedDeepRocksObserved",deepRocks.size()>=2);
        result.put("worldSurfaceSha256",HexFormat.of().formatHex(surfaceDigest.digest()));
        result.put("oceanFloorSha256",HexFormat.of().formatHex(oceanFloorDigest.digest()));
        result.put("marineColumns",marineColumns);
        result.put("marineWaterColumns",marineWaterColumns);
        result.put("marineLavaAboveFloor",marineLavaAboveFloor);
        result.put("landColumns",landColumns);
        result.put("lowestOceanFloorY",marineColumns==0 ? null : lowestOceanFloor);
        result.put("baseTerrainSha256",HexFormat.of().formatHex(terrainDigest.digest()));
        result.put("landBaseTerrainSha256",HexFormat.of().formatHex(landTerrainDigest.digest()));
        result.put("marineBaseTerrainSha256",HexFormat.of().formatHex(marineTerrainDigest.digest()));
        result.put("bands",bandRows);
        if(Files.exists(level.getServer().getServerDirectory().resolve("underground-river-audit.txt"))) {
            if(terrainSource==null || !terrainSource.undergroundRivers()) throw new AssertionError("Cave river profile is disabled");
            var rivers=new com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRiverModel(
                    level.getSeed(),terrainSource.boundUndergroundScale(),terrainSource.undergroundRiverVersion());
            result.put("riverVersion",terrainSource.undergroundRiverVersion());
            long wetColumns=0, present=0, supported=0, airAbove=0, cooledBeds=0;
            List<BlockPos> flowSamples=new ArrayList<>();
            Map<ChunkPos,Integer> flowSamplesPerChunk=new LinkedHashMap<>();
            for(var cp:sampled) {
                var chunk=censusChunkData.get(cp);
                for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                    int wx=cp.getMinBlockX()+x,wz=cp.getMinBlockZ()+z;
                    var c=rivers.column(wx,wz);
                    if(c==null || !c.wet() || !c.fits(minY,chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x,z))) continue;
                    wetColumns++;
                    var p=new BlockPos(wx,c.water(),wz);
                    if(chunk.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER)) {
                        present++;
                        if(chunk.getBlockState(new BlockPos(wx,c.bed(),wz)).isSolid()) supported++;
                        if(chunk.getBlockState(new BlockPos(wx,c.bed(),wz)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) cooledBeds++;
                        if(chunk.getBlockState(p.above()).isAir()) airAbove++;
                        // Keep flow validation spatially diverse: at most four columns
                        // from each of up to eight independently loaded chunks.
                        if(!c.lake() && flowSamples.size()<32
                                && flowSamplesPerChunk.getOrDefault(cp,0)<4) {
                            flowSamples.add(p);
                            flowSamplesPerChunk.merge(cp,1,Integer::sum);
                        }
                    }
                }
            }
            List<ChunkPos> liveRiverChunks=new ArrayList<>(flowSamplesPerChunk.keySet());
            Set<ChunkPos> riverAuditTicketChunks=new LinkedHashSet<>();
            for(var cp:liveRiverChunks) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                riverAuditTicketChunks.add(new ChunkPos(cp.x+dx,cp.z+dz));
            int riverTicketDistance=33-ChunkLevel.byStatus(ChunkStatus.FULL);
            int currents;
            int markerRoundTrips;
            try {
                // Census tickets are intentionally gone before this audit. These
                // diagnostic-only tickets make the selected FULL chunks resident
                // so getChunkNow tests runtime data rather than cache eviction.
                level.getServer().submit(() -> riverAuditTicketChunks.forEach(cp ->
                        level.getChunkSource().addRegionTicket(RIVER_AUDIT_TICKET,cp,riverTicketDistance,cp)))
                        .get(30,TimeUnit.SECONDS);
                // Entity#updateFluidHeightAndDoFluidPushing exits before sampling
                // when its one-block-inflated AABB touches an unloaded neighbor.
                // A column on a chunk edge therefore needs the surrounding 3x3.
                var futures=riverAuditTicketChunks.stream().map(cp -> level.getChunkSource()
                        .getChunkFuture(cp.x,cp.z,ChunkStatus.FULL,true)).toList();
                for(var future:futures) future.get(180,TimeUnit.SECONDS)
                        .orElseThrow(() -> new AssertionError("River audit chunk unavailable"));
                level.getServer().submit(() -> {
                    for(var cp:riverAuditTicketChunks) {
                        level.getChunkSource().getChunk(cp.x,cp.z,ChunkStatus.FULL,true);
                        if(level.getChunkSource().getChunkNow(cp.x,cp.z)==null)
                            throw new AssertionError("River audit halo chunk did not become FULL resident: "+cp);
                    }
                }).get(60,TimeUnit.SECONDS);
                currents=level.getServer().submit(() -> {
                    int n=0;
                    for(var p:flowSamples) {
                        var loaded=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                        if(loaded==null || RiverChannelData.get(loaded,p.getX()&15,p.getZ()&15)==null)
                            throw new AssertionError("Full chunk lost explicit carved river identity");
                        var state=level.getFluidState(p);
                        var velocity=state.getFlow(level,p);
                        var channel=rivers.column(p.getX(),p.getZ());
                        double expectedSpeed=Math.hypot(channel.flowX(),channel.flowZ());
                        double actualSpeed=Math.hypot(velocity.x,velocity.z);
                        double alignment=velocity.x*channel.flowX()+velocity.z*channel.flowZ();
                        if(expectedSpeed>0 && actualSpeed>=expectedSpeed*.95 && actualSpeed<=expectedSpeed*1.05
                                && alignment>=expectedSpeed*actualSpeed*.99) n++;
                    }
                    return n;
                }).get(30,TimeUnit.SECONDS);
                // Scale-one springs can lie above the former fixed Y=-48 gate.
                // Exercise persisted identity at that height, then restore the fixture.
                level.getServer().submit(() -> {
                    if(flowSamples.isEmpty()) throw new AssertionError("No river sample for shallow-current check");
                    var p=flowSamples.getFirst();
                    var loaded=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                    var original=RiverChannelData.get(loaded,p.getX()&15,p.getZ()&15);
                    var shallow=new BlockPos(p.getX(),-32,p.getZ());
                    var shallowBed=shallow.below();
                    var waterBefore=loaded.getBlockState(shallow);
                    var bedBefore=loaded.getBlockState(shallowBed);
                    try {
                        loaded.setBlockState(shallowBed,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),false);
                        loaded.setBlockState(shallow,net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),false);
                        RiverChannelData.record(loaded,p.getX()&15,p.getZ()&15,
                                new RiverChannelData.Entry(-33,-32,-29,original.flowX(),original.flowZ(),false));
                        var actual=UndergroundRivers.flow(level,shallow,level.getFluidState(shallow),net.minecraft.world.phys.Vec3.ZERO);
                        if(Math.abs(actual.x-original.flowX())>1e-9 || Math.abs(actual.z-original.flowZ())>1e-9)
                            throw new AssertionError("Marked shallow river current was suppressed");
                    } finally {
                        RiverChannelData.record(loaded,p.getX()&15,p.getZ()&15,original);
                        loaded.setBlockState(shallow,waterBefore,false);
                        loaded.setBlockState(shallowBed,bedBefore,false);
                    }
                }).get(30,TimeUnit.SECONDS);
                result.put("shallowRiverCurrentChecked",true);
                markerRoundTrips=level.getServer().submit(() -> {
                    Set<ChunkPos> checked=new HashSet<>();
                    for(var p:flowSamples) {
                        var cp=new ChunkPos(p);
                        if(!checked.add(cp)) continue;
                        var loaded=level.getChunkSource().getChunkNow(cp.x,cp.z);
                        if(loaded==null) throw new AssertionError("FULL river audit chunk was evicted: "+cp);
                        var serialized=net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level,loaded);
                        var restored=net.minecraft.world.level.chunk.storage.ChunkSerializer.read(level,level.getPoiManager(),
                                new net.minecraft.world.level.chunk.storage.RegionStorageInfo("river-audit",level.dimension(),"chunk"),cp,serialized);
                        for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                            if(!Objects.equals(RiverChannelData.get(loaded,x,z),RiverChannelData.get(restored,x,z)))
                                throw new AssertionError("Serialized FULL river marker changed at "+cp+" "+x+","+z);
                        }
                        var marker=RiverChannelData.get(loaded,p.getX()&15,p.getZ()&15);
                        if(marker==null) throw new AssertionError("Resident river column lost explicit marker: "+p);
                        var floatingItem=new net.minecraft.world.entity.item.ItemEntity(level,
                                p.getX()+.5,p.getY()+.1,p.getZ()+.5,
                                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
                        floatingItem.setNoGravity(true);
                        // ItemEntity's convenience constructor assigns a random
                        // horizontal toss in [-.1,.1] and +.2Y.  Zero it so this
                        // is a current-isolation check rather than a toss test.
                        floatingItem.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        var fluidAtSample=level.getFluidState(p);
                        boolean pushedByWater=floatingItem.updateFluidHeightAndDoFluidPushing(
                                net.minecraft.tags.FluidTags.WATER,.014);
                        var pushed=floatingItem.getDeltaMovement();
                        if(pushed.x*marker.flowX()+pushed.z*marker.flowZ()<=0) {
                            throw new AssertionError("Native item fluid push was not downstream: marker="+marker
                                    +", position="+floatingItem.position()+", bbox="+floatingItem.getBoundingBox()
                                    +", touchingUnloaded="+floatingItem.touchingUnloadedChunk()
                                    +", pushedByFluid="+floatingItem.isPushedByFluid()
                                    +", waterReturn="+pushedByWater+", fluid="+fluidAtSample
                                    +", fluidHeight="+fluidAtSample.getHeight(level,p)
                                    +", flow="+fluidAtSample.getFlow(level,p)+", velocity="+pushed);
                        }
                        var markerSnapshot=new net.minecraft.nbt.CompoundTag();
                        RiverChannelData.write(loaded,markerSnapshot);
                        try {
                            RiverChannelData.read(loaded,new net.minecraft.nbt.CompoundTag());
                            if(!UndergroundRivers.flow(level,p,level.getFluidState(p),net.minecraft.world.phys.Vec3.ZERO)
                                    .equals(net.minecraft.world.phys.Vec3.ZERO))
                                throw new AssertionError("Unmarked water received invented current");
                        } finally { RiverChannelData.read(loaded,markerSnapshot); }
                        var bedPos=new BlockPos(p.getX(),marker.bed(),p.getZ());
                        var bed=loaded.getBlockState(bedPos);
                        try {
                            loaded.setBlockState(bedPos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),false);
                            if(!UndergroundRivers.flow(level,p,level.getFluidState(p),net.minecraft.world.phys.Vec3.ZERO)
                                    .equals(net.minecraft.world.phys.Vec3.ZERO))
                                throw new AssertionError("Removed river bed retained artificial current");
                        } finally { loaded.setBlockState(bedPos,bed,false); }
                    }
                    return checked.size();
                }).get(60,TimeUnit.SECONDS);
            } finally {
                level.getServer().submit(() -> riverAuditTicketChunks.forEach(cp ->
                        level.getChunkSource().removeRegionTicket(RIVER_AUDIT_TICKET,cp,riverTicketDistance,cp)))
                        .get(30,TimeUnit.SECONDS);
            }
            result.put("rivers",Map.of("plannedWetColumns",wetColumns,"actualWaterColumns",present,
                    "supportedWaterColumns",supported,"airAboveWaterColumns",airAbove,
                    "currentSamples",flowSamples.size(),"downstreamCurrents",currents,
                    "markerSerializationChunks",markerRoundTrips,"unmarkedAndRemovedBedChecks",markerRoundTrips,
                    "nativeItemPushChecks",markerRoundTrips,"cooledBedColumns",cooledBeds));
            boolean completeRequired=Files.exists(level.getServer().getServerDirectory().resolve("underground-river-complete.txt"));
            result.put("riverCompleteCoverageRequired",completeRequired);
            if(completeRequired && present!=wetColumns)
                throw new AssertionError("Required complete river fixture contains missing water: "+result.get("rivers"));
            if(present<16 || supported!=present || airAbove<present*.9 || currents==0 || currents!=flowSamples.size())
                throw new AssertionError("Cave river water/support/current evidence incomplete: "+result.get("rivers"));
        }
        if(minY<-64) {
            if(floorBedrock!=columns) throw new AssertionError("Deep world lacks bedrock at every sampled minimum-Y column: "+floorBedrock+"/"+columns);
            if(nearLegacyBedrock!=0) throw new AssertionError("Deep world retained a bedrock shelf near Y=-64: "+nearLegacyBedrock);
        }
        return result;
    }

    private static String depthBand(int minY,int y) {
        int start=minY+Math.floorDiv(y-minY,256)*256;
        return start+".."+(start+255);
    }

    /** Bounded proof of the density graph and physical surface already present in the census chunks. */
    private static Map<String,Object> runtimeTerrainProfile(net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource terrainSource,
            List<MarineColumn> candidates) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(!(generator instanceof NoiseBasedChunkGenerator noise)) {
            result.put("status","not_noise_generator"); return result;
        }
        result.put("settingsKey",noise.generatorSettings().unwrapKey()
                .map(key -> key.location().toString()).orElse("unkeyed"));
        NoiseRouter settingsRouter=noise.generatorSettings().value().noiseRouter();
        result.put("settingsInitialDensity",describeDensity(settingsRouter.initialDensityWithoutJaggedness()));
        result.put("settingsFinalDensity",describeDensity(settingsRouter.finalDensity()));
        try {
            Object raw=randomState.getClass().getMethod("router").invoke(randomState);
            if(raw instanceof NoiseRouter router) {
                result.put("randomStateInitialDensity",describeDensity(router.initialDensityWithoutJaggedness()));
                result.put("randomStateFinalDensity",describeDensity(router.finalDensity()));
            } else result.put("randomStateRouter","unexpected:"+raw.getClass().getName());
        } catch(ReflectiveOperationException failure) {
            result.put("randomStateRouter","unavailable:"+failure.getClass().getSimpleName());
        }
        if(Files.exists(level.getServer().getServerDirectory().resolve("deep-ocean-scout.txt")))
            result.put("deepOceanScout",deepOceanScout(level,terrainSource));
        if(terrainSource==null || candidates.isEmpty()) {
            result.put("marineSamples",List.of()); return result;
        }
        int scale=terrainSource.boundUndergroundScale();
        List<MarineColumn> deepest=new ArrayList<>(candidates);
        deepest.sort(Comparator.comparingInt(MarineColumn::baseHeight)
                .thenComparingInt(MarineColumn::x).thenComparingInt(MarineColumn::z));
        result.put("marineCandidateColumns",deepest.size());
        result.put("marineBaseHeightMin",deepest.getFirst().baseHeight());
        result.put("marineBaseHeightMax",deepest.getLast().baseHeight());
        if(deepest.size()>64) deepest=new ArrayList<>(deepest.subList(0,64));
        List<Map<String,Object>> raw=new ArrayList<>();
        // Cap raw model reads at the 64 lowest base-height columns already inspected in FULL chunks.
        for(MarineColumn column:deepest) {
            var tile=LocalTerrainProvider.getInstance().fetchGroundHeightmap(column.z(),column.x(),
                    column.z()+1,column.x()+1);
            if(tile==null || tile.heightmap==null || tile.width==0 || tile.height==0) continue;
            short meters=tile.heightmap[0][0];
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("x",column.x()); row.put("z",column.z()); row.put("rawMeters",(int)meters);
            row.put("expectedProfile1",com.github.xandergos.terraindiffusionmc.world.HeightConverter
                    .convertToMinecraftHeight(meters,scale,1));
            row.put("expectedProfile2",com.github.xandergos.terraindiffusionmc.world.HeightConverter
                    .convertToMinecraftHeight(meters,scale,2));
            row.put("generatorBaseHeight",column.baseHeight());
            row.put("chunkOceanFloorHeight",column.oceanFloorHeight());
            // ChunkAccess.getHeight returns the highest occupied Y, unlike the
            // generator's first-free base height.
            int floorY=column.oceanFloorHeight();
            row.put("actualFloorY",floorY);
            row.put("actualFloorBlock",net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(column.chunk().getBlockState(new BlockPos(column.x(),floorY,column.z())).getBlock()).toString());
            row.put("aboveFloorBlock",net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(column.chunk().getBlockState(new BlockPos(column.x(),floorY+1,column.z())).getBlock()).toString());
            raw.add(row);
        }
        raw.sort(Comparator.comparingInt(row -> (Integer)row.get("rawMeters")));
        List<Map<String,Object>> selected=new ArrayList<>();
        for(int i=0;i<Math.min(8,raw.size());i++) {
            int index=Math.min(raw.size()-1,(int)Math.floor(i*(raw.size()-1)/7.0));
            selected.add(raw.get(index));
        }
        result.put("marineSamplePool",raw.size());
        result.put("marineSamples",selected);
        return result;
    }

    /** Optional, no-chunk-request scout for a reproducible raw deep-ocean fixture. */
    private static Map<String,Object> deepOceanScout(net.minecraft.server.level.ServerLevel level,
            com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource terrainSource) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(terrainSource==null) { result.put("status","no_td_source"); return result; }
        final int bound=8_192;
        int scale=terrainSource.boundUndergroundScale();
        int blocksPerCell=32*WorldPipelineModelConfig.latentCompression()*scale;
        int minCell=Math.floorDiv(-bound,blocksPerCell);
        int maxCell=Math.floorDiv(bound-1,blocksPerCell)+1;
        FloatTensor coarse;
        try {
            coarse=LocalTerrainProvider.getPipelineCoarse(minCell,minCell,maxCell,maxCell);
        } catch(Exception failure) {
            result.put("status","coarse_error:"+failure.getClass().getSimpleName()); return result;
        }
        int height=maxCell-minCell,width=maxCell-minCell,plane=height*width;
        if(coarse.shape.length!=3 || coarse.shape[0]<7 || coarse.shape[1]!=height
                || coarse.shape[2]!=width || coarse.data.length<7*plane) {
            result.put("status","unexpected_coarse_shape"); return result;
        }
        List<ScoutCandidate> candidates=new ArrayList<>();
        for(int z=0;z<height;z++) for(int x=0;x<width;x++) {
            int index=z*width+x;
            float weight=coarse.data[6*plane+index];
            float root=weight>1e-6f ? coarse.data[index]/weight : 0f;
            candidates.add(new ScoutCandidate((minCell+x)*blocksPerCell+blocksPerCell/2,
                    (minCell+z)*blocksPerCell+blocksPerCell/2,root*Math.abs(root)));
        }
        candidates.sort(Comparator.comparingDouble(ScoutCandidate::signedMeters));
        int tileSize=LocalTerrainProvider.sampleTileSize(), rawTiles=0;
        Set<Long> visited=new HashSet<>();
        Map<String,Object> deepest=null;
        for(ScoutCandidate candidate:candidates) {
            int tileX=Math.floorDiv(candidate.x(),tileSize)*tileSize;
            int tileZ=Math.floorDiv(candidate.z(),tileSize)*tileSize;
            long key=((long)tileX<<32)^(tileZ&0xffffffffL);
            if(!visited.add(key)) continue;
            if(rawTiles>=8) break;
            rawTiles++;
            var tile=LocalTerrainProvider.getInstance().fetchGroundHeightmap(tileZ,tileX,
                    tileZ+tileSize,tileX+tileSize);
            if(tile==null || tile.heightmap==null) continue;
            for(int z=0;z<tile.height;z+=16) for(int x=0;x<tile.width;x+=16) {
                short meters=tile.heightmap[z][x];
                if(meters>=-1000 || (deepest!=null && meters>=((Number)deepest.get("rawMeters")).intValue())) continue;
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("x",tileX+x); row.put("z",tileZ+z); row.put("rawMeters",(int)meters);
                row.put("legacyHeight",com.github.xandergos.terraindiffusionmc.world.HeightConverter
                        .convertToMinecraftHeight(meters,scale,1));
                row.put("profile2Height",com.github.xandergos.terraindiffusionmc.world.HeightConverter
                        .convertToMinecraftHeight(meters,scale,2));
                deepest=row;
            }
        }
        result.put("status",deepest==null?"no_raw_below_minus_1000":"found");
        result.put("seed",level.getSeed()); result.put("scale",scale);
        result.put("rawTiles",rawTiles); result.put("selected",deepest);
        result.put("coarseCandidates",candidates.size());
        result.put("bounds",List.of(-bound,bound));
        return result;
    }

    private static Map<String,Object> describeDensity(DensityFunction density) {
        Map<String,Object> result=new LinkedHashMap<>();
        LinkedHashSet<String> classes=new LinkedHashSet<>();
        TreeSet<Integer> profiles=new TreeSet<>();
        density.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction value) {
                classes.add(value.getClass().getName());
                if(value instanceof com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction td)
                    profiles.add(td.terrainProfile());
                return value;
            }
        });
        result.put("rootClass",density.getClass().getName());
        result.put("visitedClasses",List.copyOf(classes));
        result.put("terrainProfiles",List.copyOf(profiles));
        return result;
    }

    private static Map<String,Object> nativeSnapshot(MinecraftServer server) throws Exception {
        var level=server.overworld();
        String[] request=Files.readString(server.getServerDirectory().resolve("repeat-native.txt")).trim().split(",");
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var structure=registry.get(net.minecraft.resources.ResourceLocation.parse(request[0]));
        var owner=new ChunkPos(Integer.parseInt(request[1]),Integer.parseInt(request[2]));
        var generator=level.getChunkSource().getGenerator();
        var terrainDigest=java.security.MessageDigest.getInstance("SHA-256");
        for(int x=owner.getMinBlockX()-128;x<=owner.getMinBlockX()+128;x+=4)
            for(int z=owner.getMinBlockZ()-160;z<=owner.getMinBlockZ()+160;z+=4) {
                int h=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,level.getChunkSource().randomState());
                terrainDigest.update(java.nio.ByteBuffer.allocate(4).putInt(h).array());
            }
        String terrainHash=HexFormat.of().formatHex(terrainDigest.digest());
        var start=structure.generate(level.registryAccess(),generator,generator.getBiomeSource(),
                level.getChunkSource().randomState(),server.getStructureManager(),level.getSeed(),owner,0,
                net.minecraft.world.level.LevelHeightAccessor.create(level.getMinBuildHeight(),level.getHeight()),structure.biomes()::contains);
        if(!start.isValid()) return Map.of("valid",false);
        var context=net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level);
        String tag=start.createTag(context,owner).toString();
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result=Map.<String,Object>of("valid",true,"pieces",start.getPieces().size(),"box",start.getBoundingBox().toString(),"nbtSha256",hash,"terrainSha256",terrainHash);
        System.out.println("[TD-NATIVE-REPEAT] "+result);
        return result;
    }

    private static DensityProbeTelemetry.InferenceCounters inferenceCounters() {
        try {
            var snapshot=LocalTerrainProvider.inferenceSnapshot();
            return new DensityProbeTelemetry.InferenceCounters(snapshot.submitted(),snapshot.completed(),
                    snapshot.queueNanos(),snapshot.executionNanos());
        } catch(RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static Map<String,Object> verifySupport(MinecraftServer server,StructureStart start,boolean wellSupport) throws Exception {
        var level=server.overworld();
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var config=StructurePlacementConfig.snapshotFor(registry);
        String id=registry.getKey(start.getStructure()).toString();
        var policy=config.resolve(id);
        var generator=level.getChunkSource().getGenerator();
        var random=level.getChunkSource().randomState();
        // The audit worker has no inherited sampling cursor. Compare independent
        // planning passes so the scoped fast path must retain every accepted
        // height, water, cave/material and rejection result.
        var plan=StructureTerrain.plan(start,policy,generator,random,level);
        var boundPlan=LocalTerrainProvider.getInstance().withSamplingBinding(() ->
                StructureTerrain.plan(start,policy,generator,random,level));
        if(!Objects.equals(plan,boundPlan))
            throw new AssertionError("Sampling-bound terrain plan changed: "+id);
        if(plan==null) throw new AssertionError("Accepted terrain plan cannot be reconstructed");
        var heightCacheAudit=reportHeightCacheAudit(start,policy,generator,random,level);
        Map<Long,net.minecraft.world.level.chunk.ChunkAccess> chunks=new HashMap<>();
        int checked=0,loaded=0;
        for(var entry:plan.columns().entrySet()) {
            var column=entry.getValue();
            if(!column.slopeChecked() && column.original()==column.target()) continue;
            int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
            long key=ChunkPos.asLong(x>>4,z>>4);
            var chunk=chunks.get(key);
            if(chunk==null) {
                chunk=server.submit(() -> level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.CARVERS,true)).get(180,TimeUnit.SECONDS);
                chunks.put(key,chunk);
            }
            if(chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                if(column.foundation()) {
                    if(chunk.getBlockState(new BlockPos(x,column.target()-1,z)).isAir()) throw new AssertionError("Loaded support missing: "+x+","+z);
                    loaded++;
                }
            } else {
                int actual=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x&15,z&15);
                if(actual!=column.target() || chunk.getBlockState(new BlockPos(x,actual,z)).isAir())
                    throw new AssertionError("Carved support mismatch: "+x+","+z+" actual="+actual+" expected="+column.target());
                checked++;
            }
        }
        var box=start.getPieces().getFirst().getBoundingBox();
        int fx=(box.minX()+box.maxX())/2,fz=(box.minZ()+box.maxZ())/2;
        var built=server.submit(() -> level.getChunkSource().getChunk(fx>>4,fz>>4,ChunkStatus.FEATURES,true)).get(180,TimeUnit.SECONDS);
        int featureSupport=0;
        for(var entry:plan.columns().entrySet()) {
            int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
            if(!entry.getValue().foundation() || (x>>4)!=(fx>>4) || (z>>4)!=(fz>>4)) continue;
            if(built.getBlockState(new BlockPos(x,entry.getValue().target()-1,z)).isAir()) throw new AssertionError("Built support missing: "+x+","+z);
            featureSupport++;
        }
        if(featureSupport==0) throw new AssertionError("No physical foundation columns checked");
        int wellFloorBlocks=0;
        if(wellSupport) {
            for(var piece:start.getPieces()) {
                if(!com.github.xandergos.terraindiffusionmc.world.structure.StructurePieceOccupancy.buriedWellBottom(piece,start.getPieces())) continue;
                var shaft=piece.getBoundingBox();
                for(int x=shaft.minX();x<=shaft.maxX();x++) for(int z=shaft.minZ();z<=shaft.maxZ();z++) {
                    final int cx=x>>4,cz=z>>4;
                    var wellChunk=server.submit(() -> level.getChunkSource().getChunk(cx,cz,ChunkStatus.FEATURES,true)).get(180,TimeUnit.SECONDS);
                    var floor=wellChunk.getBlockState(new BlockPos(x,shaft.minY(),z));
                    if(!floor.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                            && !floor.is(net.minecraft.world.level.block.Blocks.MOSSY_COBBLESTONE))
                        throw new AssertionError("Native well floor missing after surface-pad exemption: "+x+","+shaft.minY()+","+z+" "+floor);
                    wellFloorBlocks++;
                }
            }
            if(wellFloorBlocks==0) throw new AssertionError("No native well floor blocks verified");
        }
        return Map.of("id",id,"pieces",start.getPieces().size(),"carvedColumns",checked,"loadedColumns",loaded,
                "featureSupport",featureSupport,"wellFloorBlocks",wellFloorBlocks,"samplingPlanParity",1,
                "heightCacheAudit",heightCacheAudit);
    }

    /** Same-JVM parity/call-count evidence for the scoped raw-height cache, not a throughput benchmark. */
    private static Map<String,Object> reportHeightCacheAudit(StructureStart start,StructurePlacementConfig.Policy policy,
                                                net.minecraft.world.level.chunk.ChunkGenerator generator,
                                                net.minecraft.world.level.levelgen.RandomState random,
                                                net.minecraft.world.level.LevelHeightAccessor height) throws Exception {
        List<HeightCase> cases=List.of(
                new HeightCase("original-a",policy),
                new HeightCase("original-b",policy),
                new HeightCase("cut-fill-zero",withTerrainPolicy(policy,0,0,policy.maxTerrainSamples())),
                new HeightCase("cut-fill-expanded",withTerrainPolicy(policy,policy.maxCutBlocks()+2,
                        policy.maxFillBlocks()+2,policy.maxTerrainSamples())),
                new HeightCase("sample-budget-one",withTerrainPolicy(policy,policy.maxCutBlocks(),
                        policy.maxFillBlocks(),1)));
        // Warm the native path symmetrically before collecting counterbalanced timings.
        heightSequence("warm-baseline",cases,start,generator,random,height,new StructureHeightCache(0));
        heightSequence("warm-shared",cases,start,generator,random,height,new StructureHeightCache());
        var inferenceBefore=inferenceCounters();
        var baselineFirst=heightSequence("baseline-first",cases,start,generator,random,height,new StructureHeightCache(0));
        var sharedFirst=heightSequence("shared-after-baseline",cases,start,generator,random,height,new StructureHeightCache());
        var sharedSecond=heightSequence("shared-first",cases,start,generator,random,height,new StructureHeightCache());
        var baselineSecond=heightSequence("baseline-after-shared",cases,start,generator,random,height,new StructureHeightCache(0));
        assertHeightParity(baselineFirst,sharedFirst);
        assertHeightParity(baselineSecond,sharedSecond);
        assertHeightParity(baselineFirst,baselineSecond);
        boolean accepted=baselineFirst.cases().stream().anyMatch(result -> result.plan()!=null);
        boolean rejected=baselineFirst.cases().stream().anyMatch(result -> result.plan()==null);
        if(!accepted || !rejected) throw new AssertionError("Height-cache audit needs accepted and rejected plans");
        if(sharedFirst.cache().hits()<=0 || sharedSecond.cache().hits()<=0)
            throw new AssertionError("Shared height cache produced no cross-plan hits");
        if(sharedFirst.cache().nativeHeightCalls()>=baselineFirst.cache().nativeHeightCalls()
                || sharedSecond.cache().nativeHeightCalls()>=baselineSecond.cache().nativeHeightCalls())
            throw new AssertionError("Shared height cache did not reduce native height calls");
        var inferenceAfter=inferenceCounters();
        Map<String,Object> inference=heightInferenceDelta(inferenceBefore,inferenceAfter);
        boolean contaminated=Boolean.TRUE.equals(inference.get("contaminated"));
        Map<String,Object> audit=Map.of("scope","same-JVM synthetic plan-cache parity; not throughput estimate",
                "baselineFirst",heightSequenceReport(baselineFirst),"sharedFirst",heightSequenceReport(sharedFirst),
                "sharedSecond",heightSequenceReport(sharedSecond),"baselineSecond",heightSequenceReport(baselineSecond),
                "inference",inference,"benchmarkContaminated",contaminated,
                "cases",baselineFirst.cases().stream().map(StructureDensityProbe::heightCaseReport).toList());
        return audit;
    }

    private static StructurePlacementConfig.Policy withTerrainPolicy(StructurePlacementConfig.Policy base,
                                                                       int cut,int fill,int samples) {
        return new StructurePlacementConfig.Policy(base.minGapBlocks(),base.reservationRadiusBlocks(),
                base.maxCandidateOffsetBlocks(),cut,fill,base.blendRadiusBlocks(),samples,
                base.maxCandidateAttempts(),base.spacingMultiplier(),base.surfaceMode());
    }

    private static HeightSequence heightSequence(String name,List<HeightCase> cases,StructureStart start,
                                                  net.minecraft.world.level.chunk.ChunkGenerator generator,
                                                  net.minecraft.world.level.levelgen.RandomState random,
                                                  net.minecraft.world.level.LevelHeightAccessor height,
                                                  StructureHeightCache cache) throws Exception {
        long wall=System.nanoTime(),cpu=currentThreadCpuNanos();
        var provider=LocalTerrainProvider.getInstance();
        List<HeightCaseResult> results=new ArrayList<>();
        for(var current:cases) {
            List<Map<String,String>> rejected=new ArrayList<>();
            StructureTerrain.Plan plan=provider.withSamplingBinding(() -> StructureTerrain.plan(start,current.policy(),
                    generator,random,height,new HashMap<>(),cache,(reason,detail) ->
                            rejected.add(Map.of("reason",reason,"detail",detail))));
            results.add(new HeightCaseResult(current.name(),plan,List.copyOf(rejected)));
        }
        long endedCpu=currentThreadCpuNanos();
        return new HeightSequence(name,List.copyOf(results),cache.snapshot(),System.nanoTime()-wall,
                cpu<0 || endedCpu<0 ? -1 : endedCpu-cpu);
    }

    private static void assertHeightParity(HeightSequence baseline,HeightSequence shared) {
        if(baseline.cases().size()!=shared.cases().size()) throw new AssertionError("Height-cache case count changed");
        for(int index=0;index<baseline.cases().size();index++) {
            var before=baseline.cases().get(index); var after=shared.cases().get(index);
            if(!before.name().equals(after.name()) || !Objects.equals(before.plan(),after.plan())
                    || !before.rejected().equals(after.rejected()))
                throw new AssertionError("Height-cache parity changed: "+before.name());
        }
    }

    private static long currentThreadCpuNanos() {
        var bean=ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : -1;
    }

    private static Map<String,Object> heightSequenceReport(HeightSequence sequence) {
        return Map.of("wallSeconds",DensityProbeTelemetry.seconds(sequence.wallNanos()),
                "threadCpuAvailable",sequence.cpuNanos()>=0,
                "threadCpuSeconds",sequence.cpuNanos()<0 ? -1.0 : DensityProbeTelemetry.seconds(sequence.cpuNanos()),
                "hits",sequence.cache().hits(),"misses",sequence.cache().misses(),
                "nativeHeightCalls",sequence.cache().nativeHeightCalls(),"capDrops",sequence.cache().capDrops(),
                "entries",sequence.cache().entries(),"capacity",sequence.cache().capacity());
    }

    private static Map<String,Object> heightCaseReport(HeightCaseResult result) {
        return Map.of("name",result.name(),"accepted",result.plan()!=null,"rejections",result.rejected());
    }

    private static Map<String,Object> heightInferenceDelta(DensityProbeTelemetry.InferenceCounters before,
                                                             DensityProbeTelemetry.InferenceCounters after) {
        if(before==null || after==null) return Map.of("available",false,"contaminated",true);
        long submitted=after.submitted()-before.submitted(),completed=after.completed()-before.completed();
        return Map.of("available",true,"submittedDelta",submitted,"completedDelta",completed,
                "contaminated",submitted!=0 || completed!=0);
    }
}
