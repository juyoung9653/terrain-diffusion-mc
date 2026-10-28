package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
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
    private record HeightCase(String name,StructurePlacementConfig.Policy policy) { }
    private record HeightCaseResult(String name,StructureTerrain.Plan plan,List<Map<String,String>> rejected) { }
    private record HeightSequence(String name,List<HeightCaseResult> cases,
                                  StructureHeightCache.Snapshot cache,long wallNanos,long cpuNanos) { }
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
            if(!server.getWorldData().worldGenOptions().generateStructures())
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
            boolean shoreAudit=Boolean.getBoolean("td.shoreAudit");
            if(shoreAudit) ShoreFrequencyTrace.begin(minX*16,(maxX+1)*16,minZ*16,(maxZ+1)*16);
            long phaseBegan=System.nanoTime();
            Set<ChunkPos> owners=new HashSet<>();
            boolean villagesOnly=Files.exists(server.getServerDirectory().resolve("village-census-only.txt"));
            report.put("villageCandidateOwnersOnly",villagesOnly);
            report.put("requestBatchSize",villagesOnly ? densityPrefetch : 1);
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
            for(var p:ordered) {
                if(villagesOnly && prefetched.isEmpty()) {
                    var batch=ordered.subList(attempted,Math.min(attempted+densityPrefetch,ordered.size()));
                    int distance=33-ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);
                    try {
                        long stepBegan=System.nanoTime();
                        server.submit(() -> batch.forEach(pos ->
                                level.getChunkSource().addRegionTicket(CENSUS_TICKET,pos,distance,pos)))
                                .get(180,TimeUnit.SECONDS);
                        // On the server thread getChunkFuture managed-blocks before returning.
                        // The public off-thread path schedules admission on that thread and
                        // returns immediately, so this worker can actually submit the batch.
                        var futures=batch.stream().map(pos -> level.getChunkSource()
                                .getChunkFuture(pos.x,pos.z,ChunkStatus.STRUCTURE_STARTS,true)).toList();
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
                var chunk=villagesOnly ? prefetched.remove(p) : AsyncStructureLocate.getChunk(level,p.x,p.z);
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
