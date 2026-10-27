import com.github.xandergos.terraindiffusionmc.world.structure.ComponentGeometry;
import com.github.xandergos.terraindiffusionmc.world.structure.ComponentGeometry.Footprint;
import com.github.xandergos.terraindiffusionmc.world.structure.ComponentGeometry.Write;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Voxel;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.List;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry;

public final class ComponentGeometryTest {
    private static void wellShaft() {
        var shaft=new PlacementGeometry.Rect(-877,1383,-874,1386);
        var town=new PlacementGeometry.Rect(-880,1383,-871,1392);
        String id="terrain-diffusion-mc:basic_village/common/well_bottom";
        check(ComponentGeometry.buriedWell(id,shaft,64,66,town,67),"native buried well connects to town center");
        check(!ComponentGeometry.buriedWell(id,shaft,64,66,town,68),"detached buried piece retained");
        check(!ComponentGeometry.buriedWell(id,shaft,67,69,town,67),"surface/aboveground well retained");
        check(!ComponentGeometry.buriedWell("other:well_bottom",shaft,64,66,town,67),"unknown well retained");
        check(!ComponentGeometry.buriedWell(id,new PlacementGeometry.Rect(-881,1383,-878,1386),64,66,town,67),"uncovered shaft retained");
        check(!ComponentGeometry.buriedWell(id,shaft,63,66,town,67),"changed template height retained");
    }
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static Set<Voxel> maskCells(List<PlacementGeometry.Rect> mask) {
        var result=new HashSet<Voxel>();
        for(var rect:mask) for(int x=rect.minX();x<=rect.maxX();x++) for(int z=rect.minZ();z<=rect.maxZ();z++)
            check(result.add(new Voxel(x,0,z)),"compressed support rectangles do not overlap");
        return result;
    }
    private static void assemblyFootprints() {
        record Piece(int id) {}
        var first=new Piece(1);
        var equalButDistinct=new Piece(1);
        var unknown=new Piece(2);
        var calls=new int[1];
        var local=new Footprint(Set.of(new Voxel(0,0,0)),Set.of(new Voxel(1,0,0)));
        var cache=new ComponentGeometry.FootprintCache<Piece>(piece -> {
            calls[0]++;
            return piece==unknown ? null : local.at(1,-33,70,-17);
        });
        var world=cache.get(first);
        for(int i=0;i<20;i++) check(cache.get(first)==world,"one immutable transform per piece identity");
        check(calls[0]==1,"repeated pair checks do not repeat coordinate transforms");
        check(cache.get(equalButDistinct).equals(world) && calls[0]==2,
                "equal but distinct pieces have independent cached transforms");
        check(cache.get(unknown)==null && cache.get(unknown)==null && calls[0]==3,
                "unknown footprint remains conservative and is resolved only once");
        var wall=new Footprint(Set.of(new Voxel(-33,70,-16)),Set.of());
        check(world.conflicts(wall) && wall.conflicts(world),"cached rotated AIR still conflicts with solid walls");
        var cavity=new Footprint(Set.of(),Set.of(new Voxel(-33,70,-16)));
        check(!world.conflicts(cavity),"cached AIR against AIR remains allowed");
        var next=new ComponentGeometry.FootprintCache<Piece>(piece -> local.at(2,80,-64,90));
        check(!next.get(first).equals(world),"new assembly never reuses a previous position or rotation");
    }
    private static void supportMasks(String fixture) throws Exception {
        var solids=new java.util.HashMap<String,Set<Voxel>>();
        for(String row:java.nio.file.Files.readAllLines(java.nio.file.Path.of(fixture))) {
            String[] field=row.split("\\t",-1);
            if(ComponentGeometry.write(field[4],field[5].isEmpty()?null:field[5],true)==Write.SOLID)
                solids.computeIfAbsent(field[0],ignored->new HashSet<>()).add(new Voxel(
                        Integer.parseInt(field[1]),Integer.parseInt(field[2]),Integer.parseInt(field[3])));
        }
        var cartographer=new PlacementGeometry.Occupied(solids.get("savanna_cartographer_1"));
        var mask=ComponentGeometry.groundMask(cartographer,1);
        var contacts=maskCells(mask);
        check(contacts.size()==17,"real cartographer has 17 contact columns including the final-state entrance stair, not its 72-column envelope");
        check(contacts.contains(new Voxel(0,0,4)),"jigsaw's final physical entrance stair retains foundation support");
        check(!contacts.contains(new Voxel(3,0,3)),"raised slab floor does not invent a full underlying earth pad");
        check(!contacts.contains(new Voxel(7,0,8)),"roof/air-only envelope corner is not a foundation");
        check(contacts.contains(new Voxel(6,0,7)),"real sparse fence contact keeps support");
        var stairs=ComponentGeometry.groundMask(new PlacementGeometry.Occupied(solids.get("taiga_decoration_4")),1);
        check(maskCells(stairs).equals(Set.of(new Voxel(0,0,0),new Voxel(0,0,1))),"both real taiga stair columns retain support");
        for(int rotation=0;rotation<4;rotation++) {
            final int turn=rotation;
            var transformed=mask.stream().map(rect->ComponentGeometry.transform(rect,turn,-33,-17)).toList();
            var expected=new HashSet<Voxel>();
            for(var cell:contacts) expected.add(cell.rotate(rotation).offset(-33,0,-17));
            check(maskCells(transformed).equals(expected),"support mask respects native rotation and negative world translation");
        }
        var rectangle=new HashSet<Voxel>();
        for(int x=0;x<20;x++) for(int z=0;z<30;z++) rectangle.add(new Voxel(x,0,z));
        check(ComponentGeometry.groundMask(new PlacementGeometry.Occupied(rectangle),1).equals(
                List.of(new PlacementGeometry.Rect(0,0,19,29))),"solid rectangular floor compresses to one pad");
        check(ComponentGeometry.groundMask(new PlacementGeometry.Occupied(Set.of(new Voxel(0,8,0))),1)==null,
                "roof-only/unknown suspended template conservatively falls back, never silently drops support");
        check(ComponentGeometry.groundMask(cartographer,-1)==null && ComponentGeometry.groundMask(cartographer,17)==null,
                "unsupported ground bands fall back to conservative rectangle");
        var large=new HashSet<Voxel>();
        for(int x=0;x<65537;x++) large.add(new Voxel(x,0,0));
        check(ComponentGeometry.groundMask(new PlacementGeometry.Occupied(large),1)==null,"bounded mask compilation rejects excessive voxel count");
        var checker=new HashSet<Voxel>();
        for(int x=0;x<4097;x++) checker.add(new Voxel(x*2,0,0));
        check(ComponentGeometry.groundMask(new PlacementGeometry.Occupied(checker),1)==null,"bounded compressed pad count");
    }
    public static void main(String[] args) throws Exception {
        assemblyFootprints();
        wellShaft();
        supportMasks(args[0]);
        for (String id:new String[]{"minecraft:village/savanna/streets/straight_04",
                "terrain-diffusion-mc:basic_village/desert/streets/straight_01",
                "minecraft:village/plains/zombie/streets/straight_03",
                "terrain-diffusion-mc:basic_village/taiga/zombie/streets/straight_03",
                "minecraft:village/desert/zombie/terminators/terminator_01",
                "mmv:1_21_0/swamp/terminators/terminator_01"})
            check(ComponentGeometry.villagePath(id),"audited native village road family");
        for (String id:new String[]{"custom:village/plains/streets/house","minecraft:village/plains/houses/house_1",
                "minecraft:village/plains/zombie/houses/plains_small_house_5",
                "minecraft:village/plains/other/streets/straight_03",
                "minecraft:village/plains/zombie/streets/deeper/straight_03",
                "mmv:1_21_0/swamp/houses/tower","minecraft:other/streets/straight","mmv:1_21_5/swamp/streets/new"})
            check(!ComponentGeometry.villagePath(id),"unknown or building template keeps conservative envelope");
        check(ComponentGeometry.write("minecraft:air",null,true)==Write.NONE,"legacy ignores literal AIR");
        check(ComponentGeometry.write("minecraft:air",null,false)==Write.AIR,"single AIR is a destructive write");
        check(ComponentGeometry.write("minecraft:jigsaw","minecraft:air",true)==Write.NONE,"legacy ignores replaced AIR");
        check(ComponentGeometry.write("minecraft:jigsaw","minecraft:air",false)==Write.AIR,"single replaced AIR is a destructive write");
        for (boolean legacy:new boolean[]{true,false}) {
            check(ComponentGeometry.write("minecraft:jigsaw","minecraft:structure_void",legacy)==Write.NONE,"jigsaw void never writes");
            check(ComponentGeometry.write("minecraft:structure_block",null,legacy)==Write.NONE,"metadata marker never writes");
            check(ComponentGeometry.write("minecraft:jigsaw","minecraft:hay_block",legacy)==Write.SOLID,"physical horse marker retains support");
            check(ComponentGeometry.write("minecraft:jigsaw",null,legacy)==Write.SOLID,"unknown jigsaw remains conservative");
            check(ComponentGeometry.write("mod:unknown",null,legacy)==Write.SOLID,"unrecognized block remains solid");
        }
        var shell=new HashSet<Voxel>();
        for(int x=0;x<=4;x++) for(int z=0;z<=4;z++) for(int y=0;y<=4;y++)
            if(x==0 || x==4 || z==0 || z==4 || y==0 || y==4) shell.add(new Voxel(x,y,z));
        var house=new Footprint(shell,Set.of());
        // Actual MMV villager template dimensions: 1x3x1, two AIR blocks and one jigsaw->AIR.
        var villager=new Footprint(Set.of(),Set.of(new Voxel(0,0,0),new Voxel(0,1,0),new Voxel(0,2,0)));
        check(villager.hasWrites() && !villager.needsSupport(),"AIR marker participates in collision without inventing a foundation");
        check(!villager.at(0,2,1,2).conflicts(house),"villager marker inside real room cavity allowed despite envelope overlap");
        check(villager.at(0,2,2,2).conflicts(house),"marker that would clear the roof rejected");
        check(villager.at(0,0,1,2).conflicts(house),"marker that would clear a wall rejected");
        check(house.conflicts(villager.at(0,0,1,2)),"destructive write check independent of component order");
        check(!villager.conflicts(villager),"AIR against AIR is not a building collision");
        check(house.conflicts(house),"overlapping physical buildings still rejected");
        check(!house.at(0,5,0,0).conflicts(house),"disjoint physical buildings accepted");
        var legacyMarker=new Footprint(Set.of(),Set.of());
        check(!legacyMarker.hasWrites() && !legacyMarker.needsSupport(),"legacy entity marker has neither writes nor a foundation");
        var hay=new Footprint(Set.of(new Voxel(0,0,0)),Set.of());
        check(hay.needsSupport() && hay.at(0,0,0,0).conflicts(house),"solid decoration retains support and collision checks");
        for(int rotation=0;rotation<4;rotation++) for(int x=-48;x<=48;x+=8) for(int z=-48;z<=48;z+=8) {
            var worldHouse=house.at(rotation,x,-64,z);
            check(!worldHouse.conflicts(villager.at(0,2,1,2).at(rotation,x,-64,z)),"room marker safe after rotations and negative translations");
            check(worldHouse.conflicts(villager.at(0,0,1,2).at(rotation,x,-64,z)),"wall clearing rejected after rotations and negative translations");
            check(worldHouse.conflicts(house.at(rotation,x,-64,z)),"building-building collision preserved in every transform");
        }
        System.out.println("ComponentGeometryTest: "+assertions+" assertions passed");
    }
}
