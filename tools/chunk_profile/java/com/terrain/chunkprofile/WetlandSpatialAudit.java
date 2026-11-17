package com.terrain.chunkprofile;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Full terrain-pipeline ecology census plus a bounded saved-block observation; no replay or attribution claims. */
public final class WetlandSpatialAudit {
    private static final String[] NAMES = {"reed", "mangrove", "freshwater", "riparian"};
    private WetlandSpatialAudit() { }

    /**
     * Called on the server thread while the profiler still holds the FULL ticket.
     * This observes saved blocks only; it never claims that a particular feature
     * created a pond or plant.
     */
    public static Map<String,Object> scanFullChunk(ServerLevel level, ChunkPos pos, ChunkAccess chunk) {
        Map<String,Integer> biomes = new TreeMap<>();
        int waterColumns = 0, sourceWaterColumns = 0, mudBedColumns = 0;
        int shallowMudBedColumns = 0, interiorShallowMudBedWaterColumns = 0;
        int logBlocks = 0, oakLogBlocks = 0, mangroveLogBlocks = 0;
        int lilyPads = 0, reeds = 0;
        List<Map<String,Object>> waterSamples = new ArrayList<>();
        int minY = level.getMinBuildHeight();
        for (int localX=0; localX<16; localX++) for (int localZ=0; localZ<16; localZ++) {
            int x = pos.getMinBlockX()+localX, z = pos.getMinBlockZ()+localZ;
            int scanTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
            int topWater = minY - 1;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            // Find only water reached from the surface. This excludes aquifers
            // under soil, while allowing lily pads and other replaceable cover.
            for (int y=scanTop; y>=minY; y--) {
                cursor.set(x, y, z);
                BlockState state = chunk.getBlockState(cursor);
                if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                    topWater = y;
                    break;
                }
                if (!state.getFluidState().isEmpty() || (!state.canBeReplaced()
                        && !surfaceCover(state) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS))) break;
            }
            if (topWater >= minY) {
                waterColumns++;
                BlockState topState = chunk.getBlockState(new BlockPos(x, topWater, z));
                if (topState.getFluidState().isSource()) sourceWaterColumns++;
                int depth = 0;
                while (topWater-depth >= minY && chunk.getBlockState(new BlockPos(x, topWater-depth, z))
                        .getFluidState().is(net.minecraft.tags.FluidTags.WATER)) depth++;
                BlockState bed = topWater-depth < minY ? Blocks.AIR.defaultBlockState()
                        : chunk.getBlockState(new BlockPos(x, topWater-depth, z));
                boolean mudBed = bed.is(Blocks.MUD);
                boolean shallow = depth <= 4;
                boolean interiorCardinalWater = localX > 0 && localX < 15 && localZ > 0 && localZ < 15
                        && cardinalWater(level, x, topWater, z);
                if (mudBed) mudBedColumns++;
                if (mudBed && shallow) shallowMudBedColumns++;
                if (mudBed && shallow && interiorCardinalWater) {
                    interiorShallowMudBedWaterColumns++;
                    if (waterSamples.size() < 16) waterSamples.add(Map.of(
                            "position", List.of(x, topWater, z), "depthBlocks", depth,
                            "sourceWater", topState.getFluidState().isSource(),
                            "interiorCardinalWaterNeighbors", true));
                }
            }
            for (int y=minY; y<=Math.min(level.getMaxBuildHeight()-1, scanTop+1); y++) {
                BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
                if (state.is(BlockTags.LOGS)) {
                    logBlocks++;
                    if (state.is(Blocks.OAK_LOG)) oakLogBlocks++;
                    if (state.is(Blocks.MANGROVE_LOG)) mangroveLogBlocks++;
                }
                if (state.is(Blocks.LILY_PAD)) lilyPads++;
                if (state.is(Blocks.SUGAR_CANE)) reeds++;
            }
            int biomeY = Math.max(minY, scanTop);
            String biome = level.getBiome(new BlockPos(x, biomeY, z)).unwrapKey()
                    .map(key -> key.location().toString()).orElse("unkeyed");
            biomes.merge(biome, 1, Integer::sum);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("phase", "post_full_chunk_generation_before_ticket_release");
        result.put("chunk", List.of(pos.x, pos.z));
        result.put("savedBiomeColumnCounts", biomes);
        result.put("waterColumns", waterColumns);
        result.put("sourceWaterColumns", sourceWaterColumns);
        result.put("mudBedWaterColumns", mudBedColumns);
        result.put("shallowMudBedWaterColumns", shallowMudBedColumns);
        result.put("interiorShallowMudBedWaterColumns", interiorShallowMudBedWaterColumns);
        result.put("waterSamples", waterSamples);
        result.put("logBlocksAllHeights", logBlocks);
        result.put("oakLogBlocksAllHeights", oakLogBlocks);
        result.put("mangroveLogBlocksAllHeights", mangroveLogBlocks);
        result.put("lilyPads", lilyPads);
        result.put("reeds", reeds);
        result.put("featureGenerationProven", false);
        result.put("scope", "Observed saved FULL-chunk blocks. Interior cardinal water neighbors describe local geometry only, not pool containment. A mud-bed water candidate may be a natural pool, river, or another mod's feature; this audit does not attribute ownership.");
        return result;
    }

    private static boolean surfaceCover(BlockState state) {
        return state.is(Blocks.LILY_PAD) || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT);
    }

    private static boolean cardinalWater(ServerLevel level, int x, int y, int z) {
        return waterAt(level, x-1, y, z) && waterAt(level, x+1, y, z)
                && waterAt(level, x, y, z-1) && waterAt(level, x, y, z+1);
    }

    private static boolean waterAt(ServerLevel level, int x, int y, int z) {
        return level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
    }

    /** Aggregate measurements that were captured before each requested FULL ticket was released. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> summarizePhysicalEvidence(List<Map<String,Object>> phases) {
        List<Map<String,Object>> chunks = new ArrayList<>();
        int requested = 0;
        for (Map<String,Object> phase : phases) {
            Object rows = phase.get("chunks");
            if (!(rows instanceof List<?> list)) continue;
            requested += list.size();
            for (Object row : list) if (row instanceof Map<?,?> raw) {
                Object physical = raw.get("wetlandPhysicalAudit");
                if (physical instanceof Map<?,?> measurement) chunks.add((Map<String,Object>) measurement);
            }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", requested == 0 ? "not_measured"
                : chunks.size() == requested ? "passed" : "incomplete");
        result.put("requestedFullChunkCount", requested);
        result.put("measuredFullChunkCount", chunks.size());
        result.put("chunks", chunks);
        result.put("phase", "per-chunk post-FULL generation before ticket release");
        result.put("featureGenerationProven", false);
        return result;
    }

    // Called on the diagnostic worker. NaturalEcology uses the thread-safe terrain
    // provider, just like concurrent worldgen; do not block server ticks on inference.
    public static Map<String,Object> run(ServerLevel level, Path output) throws Exception {
        Class<?> ecology = Class.forName("com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology");
        Class<?> surface = Class.forName(ecology.getName() + "$SurfaceSample");
        Class<?> sample = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier$Sample");
        Class<?> wetland = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology");
        Class<?> weight = Class.forName(wetland.getName() + "$Weights");
        Method lookup = ecology.getMethod("surfaceSample", ChunkGenerator.class, int.class, int.class);
        Method climate = surface.getMethod("climate"), elevation = surface.getMethod("elevation");
        Method weights = wetland.getMethod("weights", sample);
        String[] fields = {"biomeId","wetness","marineInfluence","treeCover","temperature","moisture","slope"};
        Method[] samples = new Method[fields.length];
        for (int i=0;i<fields.length;i++) samples[i] = sample.getMethod(fields[i]);
        String[] weightFields = {"reedMarsh","freshwaterSwamp","mangrove","riparian"};
        Method[] controls = new Method[weightFields.length];
        for (int i=0;i<controls.length;i++) controls[i] = weight.getMethod(weightFields[i]);
        int count=0;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        // Discover habitats in this world's seed, rather than reusing coordinates
        // from a different seed. The coarse grid selects sites, not area estimates.
        int[][] centers=new int[4][2]; double[] best={-1,-1,-1,-1};
        int[] targetIds={20,22,21,23}, controlIndices={0,2,1,3};
        Map<Integer,Integer> coarseCounts=new java.util.TreeMap<>();
        int originX=level.getSharedSpawnPos().getX(), originZ=level.getSharedSpawnPos().getZ();
        for (int z=originZ-2048;z<originZ+2048;z+=32) for (int x=originX-2048;x<originX+2048;x+=32) {
            Object result=lookup.invoke(null,generator,x,z);
            if (result==null) throw new IllegalStateException("Missing ecology in discovery");
            Object point=climate.invoke(result), blend=weights.invoke(null,point);
            int id=((Number)samples[0].invoke(point)).intValue();
            coarseCounts.merge(id,1,Integer::sum);
            for(int i=0;i<4;i++) {
                double score=((Number)controls[controlIndices[i]].invoke(blend)).doubleValue()+(id==targetIds[i]?1:0);
                if(score>best[i]) {best[i]=score;centers[i][0]=x;centers[i][1]=z;}
            }
        }
        String fixedSites=System.getProperty("chunkprofile.wetlandSites", "");
        boolean fixed=!fixedSites.isEmpty();
        if(fixed) {
            String[] sites=fixedSites.split(";",-1);
            if(sites.length!=4) throw new IllegalArgumentException("Expected four wetland sites");
            for(int i=0;i<4;i++) {
                String[] pair=sites[i].split(",",-1);
                if(pair.length!=2) throw new IllegalArgumentException("Expected x,z wetland site");
                centers[i][0]=Integer.parseInt(pair[0]); centers[i][1]=Integer.parseInt(pair[1]);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("window,x,z,biomeId,elevation,wetness,marineInfluence,treeCover,temperature,moisture,slope,reedWeight,swampWeight,mangroveWeight,riparianWeight\n");
            for (int window=0;window<centers.length;window++) {
                int minX=centers[window][0]-512, minZ=centers[window][1]-512;
                for (int z=minZ;z<minZ+1024;z+=4) for (int x=minX;x<minX+1024;x+=4) {
                    Object result=lookup.invoke(null,generator,x,z);
                    if (result==null) throw new IllegalStateException("Missing natural ecology at "+x+","+z);
                    Object point=climate.invoke(result), blend=weights.invoke(null,point);
                    writer.write(NAMES[window]+","+x+","+z+","+samples[0].invoke(point)+","+elevation.invoke(result));
                    for (int i=1;i<samples.length;i++) writer.write(","+samples[i].invoke(point));
                    for (Method control:controls) writer.write(","+control.invoke(blend));
                    writer.write('\n'); count++;
                }
                writer.flush();
            }
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (var stream=Files.newInputStream(output)) {
            byte[] buffer=new byte[65536]; int length;
            while ((length=stream.read(buffer))!=-1) digest.update(buffer,0,length);
        }
        Map<String,Object> receipt=new LinkedHashMap<>(Map.of("status","passed", "samples",count, "windows",4, "sideBlocks",1024,
                "stepBlocks",4, "seed",level.getSeed(), "file",output.getFileName().toString(),
                "csvSha256", HexFormat.of().formatHex(digest.digest()),
                "scope","full-pipeline surface ecology; potentially overlapping habitat windows; not a global frequency estimate or final placed blocks"));
        List<Map<String,Object>> sites=new ArrayList<>();
        for(int i=0;i<4;i++) sites.add(Map.of("name",NAMES[i],"center",List.of(centers[i][0],centers[i][1]),"discoveryBestScore",best[i],"discoveryTargetPresent",best[i]>=1,"fixedComparisonSite",fixed));
        receipt.put("sites",sites);
        receipt.put("discovery",Map.of("sideBlocks",4096,"stepBlocks",32,"samples",16384,"biomeCounts",coarseCounts,"center",List.of(originX,originZ)));
        return receipt;
    }
}
