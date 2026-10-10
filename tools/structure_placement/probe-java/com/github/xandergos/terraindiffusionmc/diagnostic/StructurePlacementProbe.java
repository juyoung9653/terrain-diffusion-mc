package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureReservations;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureTerrain;
import com.github.xandergos.terraindiffusionmc.world.structure.PolicyStructureStart;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber(modid="terrain_diffusion_mc", bus=EventBusSubscriber.Bus.GAME)
public final class StructurePlacementProbe {
    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (Boolean.getBoolean("td.structureProbe") && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, level.getServer());
        }
    }
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("td.structureProbe")) return;
        Thread worker = new Thread(() -> run(event.getServer()), "td-structure-audit");
        worker.setDaemon(true); worker.start();
    }
    private static void run(MinecraftServer server) {
        Map<String,Object> report = new LinkedHashMap<>();
        try {
            report.put("savedIndexChecks",SavedStructureIndexChecks.run(server.getServerDirectory()));
            var level=server.overworld();
            var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var config=StructurePlacementConfig.snapshotFor(registry);
            var externalIndexFile=server.getServerDirectory().resolve("index-audit-world.txt");
            if(Files.exists(externalIndexFile)) {
                Object indexIdentity=new Object();long before=System.nanoTime();
                com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex.load(indexIdentity,
                        java.nio.file.Path.of(Files.readString(externalIndexFile).trim()),config);
                report.put("existingWorldIndexMillis",(System.nanoTime()-before)/1_000_000);
                report.put("existingWorldStructuresIndexed",com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex.size(indexIdentity));
            }
            var state=level.getChunkSource().getGeneratorState();
            var planner=new StructureReservations(state,registry,config);
            var candidates=new ArrayList<>(planner.nearbyWinners(new ChunkPos(0,0),256));
            if(Files.exists(server.getServerDirectory().resolve("wide-search.txt"))) {
                candidates.addAll(planner.nearbyWinners(new ChunkPos(512,0),256));
                candidates.addAll(planner.nearbyWinners(new ChunkPos(0,512),256));
                candidates.addAll(planner.nearbyWinners(new ChunkPos(512,512),256));
                candidates=new ArrayList<>(new java.util.LinkedHashSet<>(candidates));
            }
            Set<String> preferred=Set.of();
            var preferredFile=server.getServerDirectory().resolve("preferred-chunks.txt");
            if(Files.exists(preferredFile)) preferred=Set.copyOf(Files.readAllLines(preferredFile));
            final Set<String> preferredChunks=preferred;
            candidates.sort(Comparator.<com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Candidate>comparingInt(c -> preferredChunks.contains(c.chunkX()+","+c.chunkZ()) ? -1 : c.id().startsWith("td_test_") ? 0 : 1)
                    .thenComparingLong(c -> (long)c.chunkX()*c.chunkX()+(long)c.chunkZ()*c.chunkZ()));
            report.put("registeredStructures",registry.size());
            report.put("savedStructuresIndexed",com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex.size(registry));
            report.put("winnerCount",candidates.size());
            for(int i=0;i<candidates.size();i++) for(int j=i+1;j<candidates.size();j++) {
                var a=candidates.get(i);var b=candidates.get(j);
                if(a.envelope().conflicts(b.envelope(),Math.max(a.gap(),b.gap())))
                    throw new AssertionError("Conflicting reservations: "+a+" / "+b);
            }
            List<Map<String,Object>> starts=new ArrayList<>();
            List<StructureStart> actualStarts=new ArrayList<>();
            int attempted=0;
            int adapted=0;
            int villages=0;
            boolean requireVillage=Files.exists(server.getServerDirectory().resolve("require-village.txt"));
            for(var candidate:candidates) {
                if(attempted>=100 || (starts.size()>=3 && adapted>0 && (!requireVillage || villages>0))) break;
                attempted++;
                var chunk=AsyncStructureLocate.getChunk(level,candidate.chunkX(),candidate.chunkZ());
                for(StructureStart start:chunk.getAllStarts().values()) if(start.isValid()) {
                    for(StructureStart previous:actualStarts) {
                        int gap=Math.max(config.resolve(String.valueOf(registry.getKey(previous.getStructure()))).minGapBlocks(),
                                config.resolve(String.valueOf(registry.getKey(start.getStructure()))).minGapBlocks());
                        if(StructureTerrain.rect(previous.getBoundingBox()).conflicts(StructureTerrain.rect(start.getBoundingBox()),gap))
                            throw new AssertionError("Actual independent structures violate gap: "+previous.getChunkPos()+" / "+start.getChunkPos());
                    }
                    actualStarts.add(start);
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",String.valueOf(registry.getKey(start.getStructure())));
                    row.put("owner",start.getChunkPos().toString());
                    row.put("box",start.getBoundingBox().toString());
                    row.put("pieces",start.getPieces().size());
                    row.put("surfaceAdapted",StructurePlacementRuntime.shapes(start,level.registryAccess()));
                    var context=StructurePieceSerializationContext.fromLevel(level);
                    var reloaded=StructureStart.loadStaticStart(context,start.createTag(context,start.getChunkPos()),level.getSeed());
                    if(reloaded==null || ((PolicyStructureStart)(Object)reloaded).terrainDiffusion$policyVersion()!=1)
                        throw new AssertionError("Placement marker did not survive NBT roundtrip");
                    if(StructurePlacementRuntime.shapes(start,level.registryAccess()) && (adapted==0 || start.getPieces().size()>1)) {
                        var policy=config.resolve(String.valueOf(registry.getKey(start.getStructure())));
                        var plan=StructureTerrain.plan(start,policy,level.getChunkSource().getGenerator(),
                                level.getChunkSource().randomState(),level);
                        if(plan==null) throw new AssertionError("Accepted terrain plan cannot be reconstructed");
                        Map<Long,net.minecraft.world.level.chunk.ChunkAccess> generated=new HashMap<>();
                        int checked=0;
                        int loadedFeatureSupport=0;
                        for(var column:plan.columns().entrySet()) {
                            if(!column.getValue().slopeChecked() && column.getValue().original()==column.getValue().target()) continue;
                            int x=(int)(column.getKey()>>32),z=(int)(long)column.getKey();
                            long packed=ChunkPos.asLong(x>>4,z>>4);
                            var surfaceChunk=generated.get(packed);
                            if(surfaceChunk==null) {
                                surfaceChunk=server.submit(() -> level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.CARVERS,true)).get(120,TimeUnit.SECONDS);
                                generated.put(packed,surfaceChunk);
                            }
                            if(surfaceChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                                // After actual construction, roofs/interiors are no longer terrain heightmaps.
                                // Verify physical foundation support instead of comparing a building top to its ground plan.
                                if(column.getValue().foundation()) {
                                    if(surfaceChunk.getBlockState(new BlockPos(x,column.getValue().target()-1,z)).isAir())
                                        throw new AssertionError("Loaded building has missing support at "+x+","+z);
                                    loadedFeatureSupport++;
                                }
                                continue;
                            }
                            int actual=surfaceChunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x&15,z&15);
                            if(actual!=column.getValue().target() || surfaceChunk.getBlockState(new BlockPos(x,actual,z)).isAir())
                                throw new AssertionError("Foundation mismatch at "+x+","+z+": actual="+actual+" column="+column.getValue()
                                        +" status="+surfaceChunk.getPersistedStatus()+" targetBlock="+surfaceChunk.getBlockState(new BlockPos(x,column.getValue().target(),z))
                                        +" underBlock="+surfaceChunk.getBlockState(new BlockPos(x,column.getValue().target()-1,z)));
                            checked++;
                        }
                        row.put("carvedTerrainColumnsVerified",checked);row.put("terrainChunks",generated.size());
                        row.put("loadedFeatureSupportColumns",loadedFeatureSupport);adapted++;
                        var first=start.getPieces().getFirst().getBoundingBox();
                        int fx=(first.minX()+first.maxX())/2,fz=(first.minZ()+first.maxZ())/2;
                        var featureChunk=server.submit(() -> level.getChunkSource().getChunk(fx>>4,fz>>4,ChunkStatus.FEATURES,true)).get(180,TimeUnit.SECONDS);
                        int postFeatureSupport=0;
                        for(var column:plan.columns().entrySet()) {
                            int x=(int)(column.getKey()>>32),z=(int)(long)column.getKey();
                            if(!column.getValue().foundation() || (x>>4)!=(fx>>4) || (z>>4)!=(fz>>4)) continue;
                            if(featureChunk.getBlockState(new BlockPos(x,column.getValue().target()-1,z)).isAir())
                                throw new AssertionError("Support missing after FEATURES at "+x+","+z);
                            postFeatureSupport++;
                        }
                        row.put("postFeatureSupportColumns",postFeatureSupport);
                        if(start.getPieces().size()>1) villages++;
                    }
                    starts.add(row);
                }
                System.out.println("[TD-STRUCTURE-AUDIT] attempted="+attempted+" accepted="+starts.size());
            }
            report.put("attempted",attempted);report.put("starts",starts);
            report.put("diagnostics",StructurePlacementRuntime.diagnostics());
            report.put("adaptedVerified",adapted);
            report.put("villagesVerified",villages);
            report.put("success",!starts.isEmpty() && adapted>0 && (!requireVillage || villages>0));
        } catch(Throwable error) { report.put("success",false);report.put("error",error.toString());error.printStackTrace(); }
        try {
            Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("structure-audit.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(Exception error) {error.printStackTrace();}
        System.out.println("[TD-STRUCTURE-AUDIT] result="+report);
        server.execute(() -> server.halt(false));
    }
}
