package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/** Actual terrain-input samples at an eight-block lattice, with identical old/new inputs. */
public final class ShoreFrequencyTrace {
    private static final ConcurrentHashMap<Long,String> ROWS=new ConcurrentHashMap<>();
    private record Bounds(int minX,int maxX,int minZ,int maxZ) { }
    private static volatile Bounds bounds;
    public static void begin(int minX,int maxX,int minZ,int maxZ) {
        if(minX>=maxX || minZ>=maxZ) throw new IllegalArgumentException("Empty shore survey bounds");
        ROWS.clear();
        bounds=new Bounds(minX,maxX,minZ,maxZ);
    }
    public static void record(int x,int z,float elevation,float slope,float temperature,float moisture,
            float distance,float openWater,float relief,short result) {
        var area=bounds;
        if(area==null || ((x|z)&7)!=0 || x<area.minX || x>=area.maxX || z<area.minZ || z>=area.maxZ
                || elevation<0 || distance>24 || openWater<.25f) return;
        short old=LegacyShoreClassifier.select(x,z,elevation,slope,temperature,moisture,distance,openWater,relief);
        ROWS.putIfAbsent(((long)x<<32)^(z&0xffffffffL),x+","+z+","+elevation+","+slope+","+temperature+","+moisture+","+distance+","+openWater+","+relief+","+old+","+result);
    }
    public static int write(Path root) throws java.io.IOException {
        var rows=new java.util.ArrayList<String>();
        rows.add("x,z,elevation,slope,temperature,moisture,distance,openWater,relief,before,after");
        new java.util.TreeMap<>(ROWS).values().forEach(rows::add);
        Files.write(root.resolve("shore-frequency.csv"),rows);
        return rows.size()-1;
    }
}
