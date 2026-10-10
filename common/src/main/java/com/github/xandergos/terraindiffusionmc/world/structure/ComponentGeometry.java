package com.github.xandergos.terraindiffusionmc.world.structure;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Solid support and destructive AIR writes are different component contracts. */
public final class ComponentGeometry {
    private ComponentGeometry() {}
    public enum Write { NONE, AIR, SOLID }

    /** The native 4x3x4 well shaft attaches below another village piece, not at surface grade. */
    public static boolean buriedWell(String id,PlacementGeometry.Rect shaft,int minY,int maxY,
                                     PlacementGeometry.Rect parent,int parentMinY) {
        return (id.equals("minecraft:village/common/well_bottom")
                || id.equals("terrain-diffusion-mc:basic_village/common/well_bottom"))
                && (long)shaft.maxX()-shaft.minX()==3 && (long)shaft.maxZ()-shaft.minZ()==3
                && (long)maxY-minY==2 && (long)maxY+1==parentMinY && parent.contains(shaft);
    }

    /** Audited installed village street families; projection alone cannot identify a road. */
    public static boolean villagePath(String id) {
        String path;
        if (id.startsWith("minecraft:village/")) path=id.substring("minecraft:village/".length());
        else if (id.startsWith("terrain-diffusion-mc:basic_village/")) path=id.substring("terrain-diffusion-mc:basic_village/".length());
        else if (id.startsWith("mmv:1_21_0/")) path=id.substring("mmv:1_21_0/".length());
        else return false;
        String[] parts=path.split("/");
        int family = parts.length==4 && parts[1].equals("zombie") ? 2 : 1;
        return parts.length==family+2 && Set.of("plains","desert","savanna","snowy","taiga","swamp","jungle","dark_oak").contains(parts[0])
                && (parts[family].equals("streets") || parts[family].equals("terminators"));
    }

    public static Write write(String state,String finalState,boolean legacy) {
        if (state.equals("minecraft:structure_block")) return Write.NONE;
        if (state.equals("minecraft:jigsaw")) {
            if ("minecraft:structure_void".equals(finalState) || "structure_void".equals(finalState)) return Write.NONE;
            if ("minecraft:air".equals(finalState) || "air".equals(finalState)) return legacy ? Write.NONE : Write.AIR;
            return Write.SOLID; // Missing, invalid or unfamiliar final states stay conservative.
        }
        if (state.equals("minecraft:air")) return legacy ? Write.NONE : Write.AIR;
        return Write.SOLID;
    }

    /**
     * Ground-band contacts only: a roof overhang is not a request to fill the column below it.
     * Equal runs on adjacent rows merge, so a solid rectangular floor still produces one pad.
     * Null retains the conservative envelope for an unaudited size/ground band or no known contact.
     */
    public static List<PlacementGeometry.Rect> groundMask(PlacementGeometry.Occupied solid,int groundBand) {
        if (groundBand<0 || groundBand>16 || solid.blocks().size()>65536) return null;
        var rows=new TreeMap<Integer,TreeSet<Integer>>();
        for (var block:solid.blocks()) if (block.y()>=0 && block.y()<=groundBand)
            rows.computeIfAbsent(block.z(),ignored -> new TreeSet<>()).add(block.x());
        if (rows.isEmpty()) return null;
        record Span(int minX,int maxX) {}
        List<PlacementGeometry.Rect> result=new ArrayList<>();
        Map<Span,Integer> active=new java.util.HashMap<>();
        int previousZ=Integer.MIN_VALUE;
        for (var row:rows.entrySet()) {
            int z=row.getKey();
            Map<Span,Integer> next=new java.util.HashMap<>();
            List<Span> spans=new ArrayList<>();
            int first=row.getValue().first(),last=first;
            for (int x:row.getValue().tailSet(first,false)) {
                if (x!=last+1) { spans.add(new Span(first,last)); first=x; }
                last=x;
            }
            spans.add(new Span(first,last));
            for (Span span:spans) {
                Integer index=z==previousZ+1 ? active.get(span) : null;
                if (index==null) {
                    index=result.size();
                    result.add(new PlacementGeometry.Rect(span.minX(),z,span.maxX(),z));
                    if (result.size()>4096) return null;
                } else {
                    var old=result.get(index);
                    result.set(index,new PlacementGeometry.Rect(old.minX(),old.minZ(),old.maxX(),z));
                }
                next.put(span,index);
            }
            active=next;
            previousZ=z;
        }
        return List.copyOf(result);
    }

    public static PlacementGeometry.Rect transform(PlacementGeometry.Rect rect,int rotation,int x,int z) {
        var a=new PlacementGeometry.Voxel(rect.minX(),0,rect.minZ()).rotate(rotation);
        var b=new PlacementGeometry.Voxel(rect.maxX(),0,rect.maxZ()).rotate(rotation);
        return new PlacementGeometry.Rect(x+Math.min(a.x(),b.x()),z+Math.min(a.z(),b.z()),
                x+Math.max(a.x(),b.x()),z+Math.max(a.z(),b.z()));
    }

    public record Footprint(PlacementGeometry.Occupied solid,PlacementGeometry.Occupied air) {
        public Footprint(Set<PlacementGeometry.Voxel> solid,Set<PlacementGeometry.Voxel> air) {
            this(new PlacementGeometry.Occupied(solid),new PlacementGeometry.Occupied(air));
        }
        public boolean hasWrites() { return !solid.blocks().isEmpty() || !air.blocks().isEmpty(); }
        public boolean needsSupport() { return !solid.blocks().isEmpty(); }
        public boolean conflicts(Footprint other) {
            return solid.overlaps(other.solid) || air.overlaps(other.solid) || solid.overlaps(other.air);
        }
        public Footprint at(int rotation,int x,int y,int z) {
            return new Footprint(solid.at(rotation,x,y,z),air.at(rotation,x,y,z));
        }
    }
}
