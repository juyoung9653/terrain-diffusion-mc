package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Rect;
import net.minecraft.nbt.*;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;

final class SavedStructureIndexChecks {
    static int run(Path parent) throws Exception {
        Path root=Files.createTempDirectory(parent,"saved-index-check-");
        var config=StructurePlacementConfig.validate(new StructurePlacementConfig.Document());
        int checks=0;
        Path empty=root.resolve("empty");Files.createDirectories(empty.resolve("region"));
        Files.createFile(empty.resolve("region/r.0.0.mca"));
        Object emptyIdentity=new Object();SavedStructureIndex.load(emptyIdentity,empty,config);
        require(SavedStructureIndex.size(emptyIdentity)==0,"empty vanilla region rejected");checks++;
        for(int compression=1;compression<=4;compression++) for(boolean external:new boolean[]{false,true}) {
            Path world=root.resolve(compression+"-"+external);Files.createDirectories(world.resolve("region"));
            CompoundTag piece=new CompoundTag();piece.putIntArray("BB",new int[]{-10,60,-10,10,80,10});
            ListTag children=new ListTag();children.add(piece);
            CompoundTag start=new CompoundTag();start.putString("id","removed_mod:building");start.put("Children",children);
            start.putInt("TDPlacementPolicy",external?1:0);
            CompoundTag starts=new CompoundTag();starts.put("removed_mod:building",start);
            CompoundTag structures=new CompoundTag();structures.put("starts",starts);
            CompoundTag chunk=new CompoundTag();chunk.put("structures",structures);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(RegionFileVersion.fromId(compression).wrap(bytes))) {NbtIo.write(chunk,output);}
            byte[] payload=bytes.toByteArray();
            ByteBuffer region=ByteBuffer.allocate(12288);region.putInt(0,2<<8|1);region.position(8192);
            region.putInt(external?1:payload.length+1);region.put((byte)(compression|(external?128:0)));
            if(external)Files.write(world.resolve("region/c.-32.0.mcc"),payload);else region.put(payload);
            Path file=world.resolve("region/r.-1.0.mca");Files.write(file,region.array());
            Object identity=new Object();SavedStructureIndex.load(identity,world,config);
            require(SavedStructureIndex.size(identity)==1,"missing saved start");checks++;
            require(SavedStructureIndex.conflicts(identity,new Rect(122,0,130,8),100),"99-block gap allowed");checks++;
            require(!SavedStructureIndex.overlaps(identity,new Rect(23,0,30,8)),"terrain-only overlap query applied saved gap");checks++;
            require(SavedStructureIndex.overlaps(identity,new Rect(22,0,30,8)),"touching terrain envelope missed");checks++;
            require(!SavedStructureIndex.conflicts(identity,new Rect(123,0,130,8),100),"100-block gap rejected");checks++;
            require(SavedStructureIndex.conflicts(identity,new Rect(222,0,230,8),200),"larger new gap ignored");checks++;
            require(Arrays.equals(region.array(),Files.readAllBytes(file)),"region mutated during indexing");checks++;
            Files.write(file,new byte[]{0,1,2});
            boolean rejected=false;
            try{SavedStructureIndex.load(new Object(),world,config);}catch(IllegalStateException expected){rejected=true;}
            require(rejected,"corrupt region silently skipped");checks++;
        }
        return checks;
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
