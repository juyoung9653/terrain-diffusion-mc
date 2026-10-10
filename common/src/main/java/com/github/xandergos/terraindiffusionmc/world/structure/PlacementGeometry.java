package com.github.xandergos.terraindiffusionmc.world.structure;

/** Minecraft-independent geometry shared by the planner and adversarial checks. */
public final class PlacementGeometry {
    private PlacementGeometry() {}

    /** Alternate native jigsaw layouts; never used for terrain or population-grid seeds. */
    public static long layoutSeed(long seed,String id,int chunkX,int chunkZ,int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("Negative assembly attempt");
        return attempt == 0 ? seed : priority(seed,"td-layout/"+id+"/"+attempt,chunkX,chunkZ);
    }

    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(Rect other) {
            return minX <= other.minX && minZ <= other.minZ && maxX >= other.maxX && maxZ >= other.maxZ;
        }
        public Rect union(Rect other) {
            return new Rect(Math.min(minX,other.minX),Math.min(minZ,other.minZ),
                    Math.max(maxX,other.maxX),Math.max(maxZ,other.maxZ));
        }
        public Rect inflate(int amount) {
            return new Rect(minX - amount, minZ - amount, maxX + amount, maxZ + amount);
        }
        public boolean conflicts(Rect other, int gap) {
            long dx = Math.max(0L, Math.max((long)minX - other.maxX - 1, (long)other.minX - maxX - 1));
            long dz = Math.max(0L, Math.max((long)minZ - other.maxZ - 1, (long)other.minZ - maxZ - 1));
            boolean overlap=minX<=other.maxX && maxX>=other.minX && minZ<=other.maxZ && maxZ>=other.minZ;
            return dx * dx + dz * dz < (long)gap * gap || overlap;
        }
        public int distance(int x, int z) {
            return Math.max(Math.max(0, Math.max(minX - x, x - maxX)), Math.max(0, Math.max(minZ - z, z - maxZ)));
        }

        /** Rounded terraces with gentle, world-anchored contour variation; foundations stay exact. */
        public int terrainDistance(int x, int z) {
            double dx = Math.max(0L, Math.max((long)minX - x, (long)x - maxX));
            double dz = Math.max(0L, Math.max((long)minZ - z, (long)z - maxZ));
            double distance = Math.hypot(dx, dz);
            if (distance == 0) return 0;
            double contour = Math.sin(x * 0.11 + Math.sin(z * 0.071))
                    * Math.cos(z * 0.097 + Math.sin(x * 0.053));
            return Math.max(1, (int)Math.floor(distance + 1.25 * contour * Math.min(1, distance / 6)));
        }
    }

    public record Candidate(String id, int chunkX, int chunkZ, int radius, int gap, long priority, int tier) {
        public Candidate(String id,int chunkX,int chunkZ,int radius,int gap,long priority) {
            this(id,chunkX,chunkZ,radius,gap,priority,1);
        }
        public Candidate withTier(int value) { return new Candidate(id,chunkX,chunkZ,radius,gap,priority,value); }
        public Rect envelope() {
            int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
            return new Rect(x - radius, z - radius, x + radius, z + radius);
        }
        public int comparePriority(Candidate other) {
            int order = Integer.compare(tier,other.tier);
            if (order == 0) order = Long.compareUnsigned(priority, other.priority);
            if (order == 0) order = id.compareTo(other.id);
            if (order == 0) order = Integer.compare(chunkX, other.chunkX);
            if (order == 0) order = Integer.compare(chunkZ, other.chunkZ);
            return order;
        }
        public boolean defeats(Candidate other) {
            return comparePriority(other)<0 && envelope().conflicts(other.envelope(), Math.max(gap, other.gap));
        }
    }

    public record Voxel(int x,int y,int z) {
        public Voxel rotate(int quarterTurns) {
            return switch (Math.floorMod(quarterTurns,4)) {
                case 1 -> new Voxel(-z,y,x);
                case 2 -> new Voxel(-x,y,-z);
                case 3 -> new Voxel(z,y,-x);
                default -> this;
            };
        }
        public Voxel offset(int dx,int dy,int dz) { return new Voxel(x+dx,y+dy,z+dz); }
    }
    public record Occupied(java.util.Set<Voxel> blocks) {
        public Occupied { blocks=java.util.Set.copyOf(blocks); }
        public Occupied at(int rotation,int x,int y,int z) {
            var world=new java.util.HashSet<Voxel>();
            for (Voxel block:blocks) world.add(block.rotate(rotation).offset(x,y,z));
            return new Occupied(world);
        }
        public boolean overlaps(Occupied other) {
            return blocks.stream().anyMatch(other.blocks::contains);
        }
    }
    /** Unknown replacement states retain occupancy; only native no-write final states are ignored. */
    public static boolean jigsawWritesBlock(String finalState) {
        return finalState==null || !java.util.Set.of("minecraft:air","air","minecraft:structure_void","structure_void").contains(finalState);
    }

    /** Only locally viable candidates participate; arbitration never follows winner chains. */
    public record Viable(Candidate candidate, Rect bounds, Rect influence) {
        public boolean defeats(Viable other) {
            return candidate.comparePriority(other.candidate)<0 &&
                    (bounds.conflicts(other.bounds,Math.max(candidate.gap,other.candidate.gap))
                            || influence.conflicts(other.influence,0));
        }
    }

    /** Bounded memoization with no locks held during worldgen and no waiting on in-flight work. */
    public static final class PreflightCache<K,V> {
        private final int capacity;
        private final java.util.LinkedHashMap<K,V> values=new java.util.LinkedHashMap<>(16,0.75f,true);
        public PreflightCache(int capacity) {
            if (capacity<1) throw new IllegalArgumentException("Positive capacity required");
            this.capacity=capacity;
        }
        public V get(K key,java.util.function.Supplier<V> factory) {
            synchronized(values) { V found=values.get(key); if (found!=null) return found; }
            V computed=java.util.Objects.requireNonNull(factory.get());
            synchronized(values) {
                V found=values.get(key);
                if (found!=null) return found;
                values.put(key,computed);
                if (values.size()>capacity) values.remove(values.keySet().iterator().next());
                return computed;
            }
        }
        public int size() { synchronized(values) { return values.size(); } }
    }

    /**
     * Final safety gate, scoped to one dimension. Only accepted starts are retained;
     * speculative results must never be inserted or read back into preflight eligibility.
     * No assembly, terrain sampling, or other callbacks execute while holding this lock.
     */
    public static final class AcceptedIndex<K,D> {
        private record Entry<D>(D descriptor,Rect bounds,Rect influence,int gap) {
            Rect area() { return bounds.inflate(gap).union(influence); }
            boolean conflicts(Entry<?> other) {
                return bounds.conflicts(other.bounds,Math.max(gap,other.gap))
                        || influence.conflicts(other.influence,0);
            }
        }
        private final java.util.Map<K,Entry<D>> accepted=new java.util.HashMap<>();
        private final java.util.Map<Long,java.util.List<Entry<D>>> cells=new java.util.HashMap<>();
        private final java.util.List<Entry<D>> oversized=new java.util.ArrayList<>();

        public synchronized boolean accept(K key,D descriptor,Rect bounds,Rect influence,int gap) {
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(descriptor);
            if (gap<0) throw new IllegalArgumentException("Negative structure gap");
            var entry=new Entry<>(descriptor,bounds,influence,gap);
            var previous=accepted.get(key);
            // Same-owner retries may reproduce a start, but may never replace its reservation.
            if (previous!=null) return previous.equals(entry);
            Rect area=entry.area();
            int minX=Math.floorDiv(area.minX,512),maxX=Math.floorDiv(area.maxX,512);
            int minZ=Math.floorDiv(area.minZ,512),maxZ=Math.floorDiv(area.maxZ,512);
            boolean large=((long)maxX-minX+1)*((long)maxZ-minZ+1)>4096;
            if (large) {
                for (var other:accepted.values()) if (entry.conflicts(other)) return false;
            } else {
                for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++)
                    for (var other:cells.getOrDefault(cell(x,z),java.util.List.of()))
                        if (entry.conflicts(other)) return false;
                for (var other:oversized) if (entry.conflicts(other)) return false;
            }
            accepted.put(key,entry);
            if (large) oversized.add(entry);
            else for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++)
                cells.computeIfAbsent(cell(x,z),ignored->new java.util.ArrayList<>()).add(entry);
            return true;
        }
        public synchronized int size() { return accepted.size(); }
        private static long cell(int x,int z) { return ((long)x<<32) ^ (z&0xffffffffL); }
    }

    public static long priority(long seed, String id, int x, int z) {
        long h = seed;
        for (int i = 0; i < id.length(); i++) h = mix(h ^ id.charAt(i));
        return mix(h ^ ((long)x << 32) ^ (z & 0xffffffffL));
    }
    public static double densityFraction(long seed,String id,int x,int z) {
        return (priority(seed,id+"#density",x,z)>>>11)*0x1.0p-53;
    }
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
    public static boolean boundedEarthwork(int original, int target, int cut, int fill) {
        return target - original <= fill && original - target <= cut;
    }
    /** Lowest natural block checked before accepting a terrain plan. */
    public static int supportFloor(int original,int maxCut) { return original-maxCut-1; }
    public static int constrainedHeight(int original,int lower,int upper) {
        if (lower>upper) throw new IllegalArgumentException("Incompatible height intervals");
        return Math.max(lower,Math.min(upper,original));
    }
    /** Keep retries local; at a chunk edge use the opposite half-offset instead of duplicating zero. */
    public static int localOffset(int position, int delta, int chunkMin) {
        if (position<chunkMin || position>chunkMin+15) return position;
        int proposed=position+delta;
        if (proposed>=chunkMin && proposed<=chunkMin+15) return proposed;
        return Math.max(chunkMin,Math.min(chunkMin+15,position-delta/2));
    }
    /** Preserve saved plans; new plans use the same voxel rounding as final slope validation. */
    public static int gradeAllowance(int distance, int terrainVersion) {
        return terrainVersion >= 3 ? (distance + 1) / 2 : distance / 2;
    }

    /** Block stairs approximate a 1:2 grade: at most one rise over any two columns. */
    public static boolean slope(int a, int b, int distance) {
        return Math.abs((long)a - b) <= (distance + 1) / 2;
    }
}
