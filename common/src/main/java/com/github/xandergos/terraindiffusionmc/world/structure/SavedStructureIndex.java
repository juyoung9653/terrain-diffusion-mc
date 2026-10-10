package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Snapshot;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.*;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only startup index: generation never waits for a neighboring chunk to load. */
public final class SavedStructureIndex {
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Map<Object, SavedStructureIndex> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private record Entry(PlacementGeometry.Rect box, int gap) {}
    private final Map<Long, List<Entry>> cells = new HashMap<>();
    private final List<Entry> oversized = new ArrayList<>();
    private int count;

    public static void load(Object identity, Path worldRoot, Snapshot config) {
        SavedStructureIndex index = new SavedStructureIndex();
        Path regionDir = worldRoot.resolve("region");
        if (config.enabled() && Files.isDirectory(regionDir)) {
            try (var files = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
                for (Path file : files) {
                    try { index.readRegion(file, config); }
                    catch (IOException error) { throw new IOException("Cannot read saved structure region: " + file, error); }
                }
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Cannot index saved structures before TD generation: " + regionDir, error);
            }
        }
        WORLDS.put(identity, index);
        LogUtils.getLogger().info("TD indexed {} saved structures for existing/new terrain separation", index.count);
    }

    public static int size(Object identity) { return required(identity).count; }
    public static boolean conflicts(Object identity, PlacementGeometry.Rect candidate, int gap) {
        return conflicts(identity,candidate,gap,false);
    }
    /** Terrain influence must not overlap old structures; its rim does not add another edge gap. */
    public static boolean overlaps(Object identity,PlacementGeometry.Rect candidate) {
        return conflicts(identity,candidate,0,true);
    }
    private static boolean conflicts(Object identity,PlacementGeometry.Rect candidate,int gap,boolean overlapOnly) {
        SavedStructureIndex index = required(identity);
        // Every entry is indexed with its own gap, and the query with its requested gap.
        // This overfetches but the final Euclidean edge-distance predicate is exact.
        var area = candidate.inflate(gap);
        for (int x = Math.floorDiv(area.minX(), 512); x <= Math.floorDiv(area.maxX(), 512); x++) {
            for (int z = Math.floorDiv(area.minZ(), 512); z <= Math.floorDiv(area.maxZ(), 512); z++) {
                for (Entry entry : index.cells.getOrDefault(key(x,z), List.of())) {
                    if (candidate.conflicts(entry.box, overlapOnly ? 0 : Math.max(gap,entry.gap))) return true;
                }
            }
        }
        for (Entry entry : index.oversized) if (candidate.conflicts(entry.box,overlapOnly ? 0 : Math.max(gap,entry.gap))) return true;
        return false;
    }
    public static void clear() { WORLDS.clear(); }
    private static SavedStructureIndex required(Object identity) {
        SavedStructureIndex index = WORLDS.get(identity);
        if (index == null) throw new IllegalStateException("Saved structures were not indexed before generation");
        return index;
    }
    private static long key(int x,int z) { return ((long)x<<32) ^ (z&0xffffffffL); }

    private void add(CompoundTag root, Snapshot config) throws IOException {
        if (root.contains("Level",Tag.TAG_COMPOUND)) root=root.getCompound("Level");
        CompoundTag structures=root.contains("structures",Tag.TAG_COMPOUND)
                ? root.getCompound("structures") : root.getCompound("Structures");
        CompoundTag starts=structures.contains("starts",Tag.TAG_COMPOUND)
                ? structures.getCompound("starts") : structures.getCompound("Starts");
        for (String name:starts.getAllKeys()) {
            CompoundTag start=starts.getCompound(name);
            String id=start.getString("id");
            if (id.isEmpty() || id.equals("INVALID")) continue;
            ListTag pieces=start.getList("Children",Tag.TAG_COMPOUND);
            PlacementGeometry.Rect box=null;
            for (int i=0;i<pieces.size();i++) {
                int[] b=pieces.getCompound(i).getIntArray("BB");
                if (b.length!=6 || b[0]>b[3] || b[2]>b[5]) throw new IOException("Invalid saved structure bounds: "+id);
                box=box==null ? new PlacementGeometry.Rect(b[0],b[2],b[3],b[5])
                        : new PlacementGeometry.Rect(Math.min(box.minX(),b[0]),Math.min(box.minZ(),b[2]),
                            Math.max(box.maxX(),b[3]),Math.max(box.maxZ(),b[5]));
            }
            if (box==null) throw new IOException("Saved structure has no component bounds: "+id);
            // Includes the largest vanilla terrain-adaptation bounding expansion.
            box=box.inflate(12);
            int gap=config.resolve(id).minGapBlocks();
            Entry entry=new Entry(box,gap);
            var area=box.inflate(gap);
            int minX=Math.floorDiv(area.minX(),512),maxX=Math.floorDiv(area.maxX(),512);
            int minZ=Math.floorDiv(area.minZ(),512),maxZ=Math.floorDiv(area.maxZ(),512);
            if ((long)(maxX-minX+1)*(maxZ-minZ+1)>4096) oversized.add(entry);
            else for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++)
                cells.computeIfAbsent(key(x,z),ignored->new ArrayList<>()).add(entry);
            count++;
        }
    }

    private void readRegion(Path file, Snapshot config) throws IOException {
        var match=REGION.matcher(file.getFileName().toString());
        if (!match.matches()) return;
        int regionX=Integer.parseInt(match.group(1)),regionZ=Integer.parseInt(match.group(2));
        try (FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)) {
            // Vanilla may create an empty region on a read; its header is not written
            // until the first chunk save. Zero bytes means no saved chunks.
            if (channel.size()==0) return;
            ByteBuffer header=read(channel,0,4096);
            for (int slot=0;slot<1024;slot++) {
                int location=header.getInt(slot*4);
                if (location==0) continue;
                long offset=(long)(location>>>8)*4096;
                int sectors=location&255;
                if (offset<8192 || sectors==0) throw new IOException("Invalid region location in "+file);
                ByteBuffer prefix=read(channel,offset,5);
                int length=prefix.getInt();int compression=prefix.get()&255;
                boolean external=(compression&128)!=0;
                RegionFileVersion version=RegionFileVersion.fromId(compression&127);
                if (version==null || (compression&127)==127 || length<1 || length>sectors*4096-4)
                    throw new IOException("Unsupported or corrupt chunk record in "+file);
                InputStream payload;
                if (external) {
                    int x=regionX*32+(slot&31),z=regionZ*32+(slot>>5);
                    payload=Files.newInputStream(file.resolveSibling("c."+x+"."+z+".mcc"));
                } else payload=new ByteArrayInputStream(read(channel,offset+5,length-1).array());
                try (payload; var input=new DataInputStream(version.wrap(payload))) {
                    add(NbtIo.read(input,NbtAccounter.create(256L*1024*1024)),config);
                }
            }
        }
    }
    private static ByteBuffer read(FileChannel channel,long offset,int length) throws IOException {
        ByteBuffer result=ByteBuffer.allocate(length);
        while (result.hasRemaining()) {
            int n=channel.read(result,offset+result.position());
            if (n<0) throw new EOFException("Truncated region file");
        }
        return result.flip();
    }
}
