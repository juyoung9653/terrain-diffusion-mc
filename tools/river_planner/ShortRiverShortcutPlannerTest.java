import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverShortcutPlanner;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner;
import java.util.List;

public final class ShortRiverShortcutPlannerTest {
    private static final NaturalRiverPlanform.Corridor OPEN=(x,z,r)->true;
    public static void main(String[] args){ shortcutsURoute(); shortcutsTwoBlockNativeRoute(); denseMouthNeighbors(); placeholderBoundaries(); rejectsUnsafe(); rejectsExposedShortcutApproaches(); rejectsCombinedPortalAndRoofGaps(); deterministicNegativeOrder(); System.out.println("ShortRiverShortcutPlannerTest: passed"); }
    private static void shortcutsURoute(){
        List<Reach> in=List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2),r(80,0,84,2,120,0,83,2));
        check(!ShortRiverTunnelPlanner.plan(List.of(r(0,0,90,2,80,0,84,2)),hill(),OPEN,63,0).isEmpty(),"base chord roof qualifies");
        var out=ShortRiverShortcutPlanner.refine(in,hill(),OPEN,63,0);
        check(out.size()>in.size(),"shortcut is two-block subdivided: "+out);
        check(out.get(0).x1()==0&&out.get(out.size()-1).x2()==120,"outer endpoints retained");
        check(out.get(out.size()-1).equals(in.get(3)),"outside reach remains byte-for-byte record-equal");
        check(out.stream().anyMatch(x->Math.abs(x.z1())<.01&&Math.abs(x.z2())<.01),"chord crosses hill");
    }
    private static void rejectsUnsafe(){
        List<Reach> branch=List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2),r(0,40,88,2,-20,40,87,2));
        List<Reach> uphill=List.of(r(0,0,80,2,0,40,82,2),r(0,40,82,2,80,40,80,2),r(80,40,80,2,80,0,78,2));
        check(ShortRiverShortcutPlanner.refine(branch,hill(),OPEN,63,0).equals(branch),"branch unchanged");
        List<Reach> wideBranch=List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2),r(0,40,88,6,-20,40,87,6));
        check(ShortRiverShortcutPlanner.refine(wideBranch,hill(),OPEN,63,0).equals(wideBranch),"unusable-width branch still protects topology");
        List<Reach> crossing=List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2),r(80,0,84,2,120,0,83,2),r(8,-30,89,2,8,30,88,2));
        check(ShortRiverShortcutPlanner.refine(crossing,hill(),OPEN,63,0).equals(crossing),"unrelated stream crossing near chord start is rejected");
        check(ShortRiverShortcutPlanner.refine(uphill,hill(),OPEN,63,0).equals(uphill),"uphill unchanged");
        check(ShortRiverShortcutPlanner.refine(List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2)),(x,z)->90,OPEN,63,0).size()==3,"no buried roof unchanged");
    }
    private static void shortcutsTwoBlockNativeRoute(){
        var in=new java.util.ArrayList<Reach>();
        for(int z=0;z<40;z+=2)in.add(r(0,z,90-z*.05,2,0,z+2,90-(z+2)*.05,2));
        for(int x=0;x<80;x+=2)in.add(r(x,40,88-x*.025,2,x+2,40,88-(x+2)*.025,2));
        for(int z=40;z>0;z-=2)in.add(r(80,z,86-(40-z)*.05,2,80,z-2,86-(40-(z-2))*.05,2));
        in.add(r(80,0,84,2,120,0,83,2));
        check(!ShortRiverTunnelPlanner.plan(List.of(r(0,0,90,2,80,0,84,2)),hill(),OPEN,63,0).isEmpty(),"segmented direct chord roof qualifies");
        var out=ShortRiverShortcutPlanner.plan(in,hill(),OPEN,63,0);
        check(out.reaches().size()<in.size()&&out.tunnels().size()==1,"two-block native records retain a shortcut candidate");
    }
    private static void placeholderBoundaries(){
        List<Reach> in=List.of(r(-2,0,90,0,0,0,90,0),r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2),r(80,0,84,2,120,0,83,2),r(120,0,83,0,122,0,83,0));
        var out=ShortRiverShortcutPlanner.plan(in,hill(),OPEN,63,0);
        check(!out.tunnels().isEmpty(),"zero-width records delimit an accepted chain without throwing");
        check(out.reaches().contains(in.get(0))&&out.reaches().contains(in.get(5)),"zero-width boundary records are retained");
    }
    private static void denseMouthNeighbors(){
        var in=new java.util.ArrayList<Reach>();
        for(int x=-32;x<0;x+=2)in.add(r(x,0,91+x*.03,2,x+2,0,91+(x+2)*.03,2));
        for(int z=0;z<40;z+=2)in.add(r(0,z,90-z*.05,2,0,z+2,90-(z+2)*.05,2));
        for(int x=0;x<80;x+=2)in.add(r(x,40,88-x*.025,2,x+2,40,88-(x+2)*.025,2));
        for(int z=40;z>0;z-=2)in.add(r(80,z,86-(40-z)*.05,2,80,z-2,86-(40-(z-2))*.05,2));
        for(int x=80;x<120;x+=2)in.add(r(x,0,84-(x-80)*.025,2,x+2,0,84-(x+2-80)*.025,2));
        var out=ShortRiverShortcutPlanner.plan(in,hill(),OPEN,63,0);
        check(out.reaches().equals(in),"covered middle cannot shortcut long exposed mouth approaches");
    }
    private static void deterministicNegativeOrder(){
        List<Reach> in=List.of(r(-80,0,90,2,-80,40,88,2),r(-80,40,88,2,0,40,86,2),r(0,40,86,2,0,0,84,2));
        var a=ShortRiverShortcutPlanner.refine(in,negativeHill(),OPEN,63,0); var b=ShortRiverShortcutPlanner.refine(in,negativeHill(),OPEN,63,0);
        check(a.equals(b),"negative repeat deterministic");
    }
    private static void rejectsExposedShortcutApproaches(){
        List<Reach> route=List.of(r(0,0,90,2,0,40,88,2),r(0,40,88,2,80,40,86,2),r(80,40,86,2,80,0,84,2));
        check(ShortRiverShortcutPlanner.refine(route,shortMiddleHill(),OPEN,63,0).equals(route),
                "short central roof cannot replace the rest with an exposed chord");
        check(ShortRiverShortcutPlanner.refine(route,separateHills(),OPEN,63,0).equals(route),
                "separate hills cannot stitch an exposed straight chord");
    }
    private static void rejectsCombinedPortalAndRoofGaps(){
        Reach chord=r(0,0,90,2,80,0,84,2);
        var legal=new ShortRiverTunnelPlanner.Tunnel(List.of(new ShortRiverTunnelPlanner.Point(8,0,90,2),new ShortRiverTunnelPlanner.Point(72,0,84,2)),64,8,56);
        var exposedStart=new ShortRiverTunnelPlanner.Tunnel(List.of(new ShortRiverTunnelPlanner.Point(8,0,90,2),new ShortRiverTunnelPlanner.Point(72,0,84,2)),64,9,56);
        var exposedEnd=new ShortRiverTunnelPlanner.Tunnel(List.of(new ShortRiverTunnelPlanner.Point(8,0,90,2),new ShortRiverTunnelPlanner.Point(72,0,84,2)),64,8,55);
        check(ShortRiverShortcutPlanner.roofCoversChord(chord,legal),"sixteen-block combined portal allowance remains valid");
        check(!ShortRiverShortcutPlanner.roofCoversChord(chord,exposedStart),"portal and roof start gaps share one allowance");
        check(!ShortRiverShortcutPlanner.roofCoversChord(chord,exposedEnd),"portal and roof end gaps share one allowance");
    }
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground hill(){return (x,z)->(x>=2&&x<=78&&Math.abs(z)<12)?105:(x==0?90:x==80?84:84);}
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground shortMiddleHill(){return (x,z)->(x>=30&&x<=50&&Math.abs(z)<12)?105:(x==0?90:x==80?84:84);}
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground separateHills(){return (x,z)->((x>=2&&x<=22)||(x>=58&&x<=78))&&Math.abs(z)<12?105:(x==0?90:x==80?84:84);}
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground negativeHill(){return (x,z)->(x>=-78&&x<=-2&&Math.abs(z)<12)?105:(x==-80?90:84);}
    private static Reach r(double x1,double z1,double y1,double w1,double x2,double z2,double y2,double w2){return new Reach(x1,z1,y1,w1,x2,z2,y2,w2,1,1);} private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
