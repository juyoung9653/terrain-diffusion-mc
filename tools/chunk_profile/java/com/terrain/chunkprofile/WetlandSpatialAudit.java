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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Full terrain-pipeline ecology census; no replay and no generated-block claims. */
public final class WetlandSpatialAudit {
    private static final String[] NAMES = {"reed", "mangrove", "freshwater", "riparian"};
    private WetlandSpatialAudit() { }

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
