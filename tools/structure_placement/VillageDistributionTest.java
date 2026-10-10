import com.github.xandergos.terraindiffusionmc.world.structure.VillageDistributionGeometry;
import com.github.xandergos.terraindiffusionmc.world.structure.VillageDistributionGeometry.Group;

/** Pure regression boundary for seed decisions used by real STARTS and locate placements. */
public final class VillageDistributionTest {
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(VillageDistributionGeometry.microSpacing(500)==7,"500-block target uses a 336-block common cell with expanded search");
        check(VillageDistributionGeometry.HOPS==9,"nine bounded candidate origins per common cell");
        check(VillageDistributionGeometry.microSpacing(500,1)==11,"version one retains the 352-block common cell");
        check(VillageDistributionGeometry.microcells(-1,-1,11,1).equals(java.util.List.of(
                new VillageDistributionGeometry.Microcell(-22,-22),new VillageDistributionGeometry.Microcell(-22,-11),
                new VillageDistributionGeometry.Microcell(-11,-22),new VillageDistributionGeometry.Microcell(-11,-11))),
                "version one retains exact negative-cell candidate enumeration");
        int previous=0;
        for (int request=256;request<=4096;request++) {
            int spacing=VillageDistributionGeometry.microSpacing(request);
            check(spacing>=previous && spacing>=2,"monotonic bounded density grid");
            check(Math.abs(spacing*VillageDistributionGeometry.MICROCELLS_PER_SIDE*16-request*.7)<=24,
                    "expanded candidates use approximately 0.7-target common-cell width");
            previous=spacing;
            check(VillageDistributionGeometry.microSpacing(request,1)==Math.max(2,(int)Math.round(request*.7/32.0)),
                    "version one retains the full legacy spacing contract");
        }
        for (int invalid:new int[]{-1,0,255,4097,Integer.MAX_VALUE}) {
            try { VillageDistributionGeometry.microSpacing(invalid); throw new AssertionError("invalid spacing accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        for (int chunk=-10000;chunk<=10000;chunk++) {
            int cell=VillageDistributionGeometry.cell(chunk,13);
            check(chunk>=cell*39 && chunk<(cell+1)*39,"negative-coordinate floor division");
        }
        check(VillageDistributionGeometry.cell(-1,13)==-1,"negative one belongs to previous cell");
        check(VillageDistributionGeometry.cell(-39,13)==-1,"negative cell boundary");
        check(VillageDistributionGeometry.cell(-40,13)==-2,"negative boundary predecessor");
        check(VillageDistributionGeometry.cell(-1,13,1)==-1 && VillageDistributionGeometry.cell(-26,13,1)==-1
                        && VillageDistributionGeometry.cell(-27,13,1)==-2,"version one retains negative boundaries");
        for (int x=-400;x<=400;x+=7) for (int z=-400;z<=400;z+=11) {
            var hops=VillageDistributionGeometry.microcells(x,z,13);
            check(hops.size()==9 && new java.util.HashSet<>(hops).size()==9,"exactly nine distinct bounded hops");
            for (var hop:hops) {
                check(VillageDistributionGeometry.microcells(hop.x()+12,hop.z()+12,13).equals(hops),
                        "every potential origin in each locate microcell reproduces same common-cell search");
                check(Math.abs(hop.x()-x)<=38 && Math.abs(hop.z()-z)<=38,"hop owner query remains bounded");
                check(Math.floorMod(hop.x(),13)==0 && Math.floorMod(hop.z(),13)==0,"native locate query mapping");
            }
        }
        candidateCoverage();
        for (String id:new String[]{"minecraft:village_plains","mmv:forest_village","t_and_t:village_savanna",
                "terralith:village/desert","terrain-diffusion-mc:basic_village_taiga"})
            check(VillageDistributionGeometry.isVillage(id,false),"all known living village shapes are suppressed natively");
        check(VillageDistributionGeometry.isVillage("custom:settlement",true),"tagged custom villages join shared population");
        check(!VillageDistributionGeometry.isVillage("custom:ruined_village_house",false),"unrelated village ruins keep native placement");
        check(!VillageDistributionGeometry.isVillage("minecraft:pillager_outpost",false),"native non-village remains independent");
        check(!VillageDistributionGeometry.isVillage("cataclysm:abandoned_village",true),"Cataclysm abandoned ruin remains independent");
        check(!VillageDistributionGeometry.isVillage("cataclysm:desert_occupied_village",false),"Cataclysm occupied dungeon remains independent");
        check(VillageDistributionGeometry.group("minecraft:village_plains",false)==Group.BASIC,"original vanilla basic");
        check(VillageDistributionGeometry.group("minecraft:village_plains",true)==Group.DISTINCTIVE,"Epic namespace override distinctive");
        check(VillageDistributionGeometry.group("terrain-diffusion-mc:basic_village_plains",true)==Group.BASIC,"original aliases basic even with Epic");
        check(VillageDistributionGeometry.group("mmv:desert_village",true)==Group.BASIC,"MMV basic");
        check(VillageDistributionGeometry.group("t_and_t:village_plains",false)==Group.DISTINCTIVE,"Towns and Towers distinctive");
        int basic=0;
        int densityQuarter=0;
        for (int x=-150;x<150;x++) for (int z=-150;z<150;z++) {
            Group actual=VillageDistributionGeometry.choose(7823,x,z,70,true,true);
            check(actual==VillageDistributionGeometry.choose(7823,x,z,70,true,true),"repeat seed deterministic");
            if (actual==Group.BASIC) basic++;
            check(VillageDistributionGeometry.densityEligible(7823,"mmv:village",x,z,1),"unthinned member eligibility");
            if (VillageDistributionGeometry.densityEligible(7823,"mmv:village",x,z,2)) densityQuarter++;
            check(VillageDistributionGeometry.choose(7823,x,z,70,true,false)==Group.BASIC,"missing distinctive fallback");
            check(VillageDistributionGeometry.choose(7823,x,z,70,false,true)==Group.DISTINCTIVE,"missing basic fallback");
            check(VillageDistributionGeometry.choose(7823,x,z,70,false,false)==null,"no viable groups reject");
            check(VillageDistributionGeometry.choose(7823,x,z,0,true,true)==Group.DISTINCTIVE,"zero share endpoint");
            check(VillageDistributionGeometry.choose(7823,x,z,100,true,true)==Group.BASIC,"full share endpoint");
            check(VillageDistributionGeometry.choose(7823,x,z,0,true,false)==null,"zero share never falls back to basic");
            check(VillageDistributionGeometry.choose(7823,x,z,100,false,true)==null,"full share never falls back to distinctive");
        }
        check(Math.abs(basic/90000.0-.7)<.01,"70 percent among mutually viable groups over spatial sample");
        check(Math.abs(densityQuarter/90000.0-.25)<.01,"per-structure multiplier retains inverse-square member thinning");
        System.out.println("VillageDistributionTest: "+assertions+" assertions passed; basic fraction="+basic/90000.0);
    }

    /** Exhaust the native spread offset range, including negative common-cell boundaries. */
    private static void candidateCoverage() {
        for (int version:new int[]{1,2}) for (int spacing:new int[]{4,7,13,60}) for (int cellX=-2;cellX<=2;cellX++)
            for (int cellZ=-2;cellZ<=2;cellZ++) {
                int side=version==1?2:3;
                int width=side*spacing;
                int minX=cellX*width,minZ=cellZ*width;
                var hops=VillageDistributionGeometry.microcells(minX,minZ,spacing,version);
                check(hops.size()==side*side,"version selects the bounded origin count");
                var covered=new java.util.HashSet<VillageDistributionGeometry.Microcell>();
                var reversed=new java.util.ArrayList<>(hops);
                java.util.Collections.reverse(reversed);
                for (var hop:reversed) {
                    check(hop.x()>=minX && hop.x()<minX+width && hop.z()>=minZ && hop.z()<minZ+width,
                            "every native region belongs to its common cell");
                    for (int dx=0;dx<spacing;dx++) for (int dz=0;dz<spacing;dz++) {
                        int candidateX=hop.x()+dx,candidateZ=hop.z()+dz;
                        check(covered.add(new VillageDistributionGeometry.Microcell(candidateX,candidateZ)),
                                "candidate regions do not overlap");
                        check(VillageDistributionGeometry.cell(candidateX,spacing,version)==cellX
                                        && VillageDistributionGeometry.cell(candidateZ,spacing,version)==cellZ,
                                "all native spread offsets retain the same owner");
                    }
                    check(VillageDistributionGeometry.microcells(hop.x(),hop.z(),spacing,version).equals(hops)
                                    && VillageDistributionGeometry.microcells(hop.x()+spacing-1,hop.z()+spacing-1,spacing,version).equals(hops),
                            "reverse visitation of native locate regions preserves candidate enumeration");
                }
                check(covered.size()==width*width,"candidate regions cover the entire common cell without holes");
                for (int invalid:new int[]{1,129}) {
                    try {
                        VillageDistributionGeometry.microcells(minX,minZ,invalid,version);
                        throw new AssertionError("invalid microcell spacing accepted");
                    } catch (IllegalArgumentException expected) { assertions++; }
                }
            }
        for (int version:new int[]{-1,0,3,Integer.MAX_VALUE}) {
            try {
                VillageDistributionGeometry.microSpacing(500,version);
                throw new AssertionError("unsupported algorithm version accepted");
            } catch (IllegalArgumentException expected) { assertions++; }
        }
    }
}
