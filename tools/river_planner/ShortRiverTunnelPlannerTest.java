import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner;
import java.util.List;

public final class ShortRiverTunnelPlannerTest {
    private static final NaturalRiverPlanform.Corridor OPEN = (x,z,r) -> true;
    public static void main(String[] args) {
        findsSmoothShortHill(); acceptsWindowInsideLongBranchedRoute();
        deterministicNegativeCoordinatesAndCanonicalOrder(); acceptsGradualHillMouth(); boundedLongPathSampling(); rejectsUnsafeCases(); rejectsSubEightCoreWithoutThrowing();
        roofSpansBanksAndLeavesPortalsOpen();
        acceptsLongGradualMountain();
        trimsThinMouthsButRejectsInteriorHoles();
        rejectsReconstructedInteriorRoofHole();
        System.out.println("ShortRiverTunnelPlannerTest: passed");
    }
    private static void findsSmoothShortHill() {
        List<Reach> in = List.of(r(0,0,80,2,40,0,78,2));
        var out = ShortRiverTunnelPlanner.plan(in, hill(0,40,80,78), OPEN,63,0);
        check(out.size()==1,"short smooth hill selected");
        check(in.equals(List.of(r(0,0,80,2,40,0,78,2))),"graph unchanged");
        var t=out.get(0);
        check(t.roofFloorY(0,0,80)==Integer.MIN_VALUE,"entry open");
        check(t.roofFloorY(20,0,79)>=84,"five-air centre arch");
        check(t.roofFloorY(20,8,79)!=Integer.MIN_VALUE,"bank apron roof retained");
        check(t.roofFloorY(20,12,79)==Integer.MIN_VALUE,"outside apron open");
    }
    private static void acceptsWindowInsideLongBranchedRoute() {
        List<Reach> in=List.of(r(-120,0,86,2,0,0,80,2),r(0,0,80,2,40,0,78,2),
                r(40,0,78,2,80,0,76,2),r(80,0,76,2,80,20,75,2));
        var out=ShortRiverTunnelPlanner.plan(in, longHill(), OPEN,63,0);
        check(out.size()==1&&out.get(0).length()<=96,"local hill survives distant confluence");
    }
    private static void deterministicNegativeCoordinatesAndCanonicalOrder() {
        List<Reach> in=List.of(r(-80,-20,86,2,-40,-20,84,2),r(-40,-20,84,2,0,-20,82,2),
                r(0,-20,82,2,40,-20,80,2));
        var a=ShortRiverTunnelPlanner.plan(in, shiftedHill(),OPEN,63,0);
        var b=ShortRiverTunnelPlanner.plan(List.of(in.get(2),in.get(1),in.get(0)),shiftedHill(),OPEN,63,0);
        check(a.equals(b)&&a.size()==1,"negative coordinates and native record order stable: " + a + " / " + b);
    }
    private static void rejectsUnsafeCases() {
        List<Reach> ok=List.of(r(0,0,80,2,40,0,78,2));
        check(ShortRiverTunnelPlanner.plan(ok,(x,z)->84,OPEN,63,0).isEmpty(),"insufficient roof rejected");
        check(ShortRiverTunnelPlanner.plan(ok,hill(0,40,80,78),(x,z,r)->x<20,63,0).isEmpty(),"lake or border rejected");
        check(ShortRiverTunnelPlanner.plan(List.of(r(0,0,80,2,120,0,74,2)),(x,z)->x==0?80:x==120?74:100,OPEN,63,0,96).isEmpty(),"parameterized cap rejects long mountain");
        check(ShortRiverTunnelPlanner.plan(List.of(r(0,0,80,2,40,0,80,2)),hill(0,40,80,80),OPEN,63,0).isEmpty(),"no lower exit rejected");
        List<Reach> placeholder=List.of(r(-2,0,81,0,0,0,80,.1),r(0,0,80,2,40,0,78,2));
        check(ShortRiverTunnelPlanner.plan(placeholder,hill(0,40,80,78),OPEN,63,0).size()==1,"placeholder breaks path only");
    }
    private static void boundedLongPathSampling() {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        var ground=(com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground)(x,z)->{
            calls.incrementAndGet(); return x==2000?151:x==2040?150:x>2000&&x<2040?200:160; };
        var out=ShortRiverTunnelPlanner.plan(List.of(r(0,0,200,2,4096,0,100,2)),ground,OPEN,63,0);
        check(out.size()==1&&out.get(0).length()<=512,"long path uses one bounded hill window");
        check(calls.get()<5000,"cached linear sampling avoids cap-squared terrain work");
    }
    private static void acceptsGradualHillMouth() {
        var ground=(com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground)(x,z)->{
            if(x<=0)return 90; if(x<12)return 90+x/2.0; if(x<=48)return 105;
            if(x<60)return 87+(60-x)/2.0; return 87; };
        var t=ShortRiverTunnelPlanner.plan(List.of(r(0,0,90,2,60,0,87,2)),ground,OPEN,63,0).get(0);
        check(t.coveredStart()>=8&&t.coveredEnd()<=44,"roof starts only after gradual cover is thick enough");
        check(t.roofFloorY(4,0,90)==Integer.MIN_VALUE,"low gradual mouth is not blocked");
    }
    private static void roofSpansBanksAndLeavesPortalsOpen() {
        var t=ShortRiverTunnelPlanner.plan(List.of(r(0,0,80,2,40,0,78,2)),hill(0,40,80,78),OPEN,63,0).get(0);
        check(t.roofFloorY(4,0,80)!=Integer.MIN_VALUE,"covered entrance keeps minimum headroom");
        check(t.roofFloorY(39,0,78)==Integer.MIN_VALUE,"exit remains open");
    }
    private static void rejectsSubEightCoreWithoutThrowing() {
        var ground=(com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground)(x,z)->
                x==0?80:x==40?78:x>=16&&x<=22?96:82;
        check(ShortRiverTunnelPlanner.plan(List.of(r(0,0,80,2,40,0,78,2)),ground,OPEN,63,0).isEmpty(),
                "covered core below eight blocks is rejected before Tunnel construction");
    }
    private static void acceptsLongGradualMountain() {
        var ground=(com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground)(x,z)->{
            double water=90-4*Math.max(0,Math.min(180,x))/180.0;
            double cover=Math.max(0,Math.min(20,Math.min(x,180-x)/2.0));
            return water+cover;
        };
        var reaches=List.of(r(0,0,90,2,180,0,86,2));
        var tunnels=ShortRiverTunnelPlanner.plan(reaches,ground,OPEN,63,0);
        check(tunnels.size()==1 && tunnels.getFirst().length()>96,"long gradual mountain is not capped at 96");
        check(ShortRiverTunnelPlanner.plan(reaches,ground,OPEN,63,0,96).isEmpty(),"explicit short cap still rejects same mountain");
        check(ShortRiverTunnelPlanner.physicalWater(80.9,1,3)==78,"narrow stream uses native width-scaled inset");
        check(ShortRiverTunnelPlanner.physicalWater(80.9,4,3)==77,"wide stream uses full native inset");
    }
    private static void trimsThinMouthsButRejectsInteriorHoles() {
        var mask=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,2),
                new ShortRiverTunnelPlanner.Point(80,0,76,2)),80,10,70);
        var fitted=ShortRiverTunnelPlanner.fitRoof(mask,(x,z)->x<=12?87:100,(x,z)->80);
        check(fitted!=null && fitted.coveredStart()>12 && fitted.coveredStart()<=26,
                "thin integer mouth rows move portal inward within bounded apron");
        check(ShortRiverTunnelPlanner.fitRoof(mask,(x,z)->x==40?80:100,(x,z)->80)==null,
                "interior roof hole rejects whole tunnel");
        check(ShortRiverTunnelPlanner.fitRoof(mask,(x,z)->x<=30?87:100,(x,z)->80)==null,
                "mouth correction cannot consume more than sixteen blocks");
    }
    private static void rejectsReconstructedInteriorRoofHole() {
        var expected=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,2),
                new ShortRiverTunnelPlanner.Point(80,0,76,2)),80,10,70);
        var mouthTrim=new ShortRiverTunnelPlanner.Tunnel(expected.centreline(),80,26,70);
        check(ShortRiverTunnelPlanner.sameRoofFootprint(expected,mouthTrim),
                "bounded sixteen-block mouth trim remains admissible");
        var excessiveTrim=new ShortRiverTunnelPlanner.Tunnel(expected.centreline(),80,27,70);
        check(oldAsymmetricFootprint(expected,excessiveTrim),
                "old actual-only comparison accepts an omitted expected mouth column");
        check(!ShortRiverTunnelPlanner.sameRoofFootprint(expected,excessiveTrim),
                "more than sixteen blocks of omitted roof is rejected");
        var wideExpected=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,3),
                new ShortRiverTunnelPlanner.Point(80,0,76,3)),80,10,70);
        var narrower=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,2.99),
                new ShortRiverTunnelPlanner.Point(80,0,76,2.99)),80,10,70);
        check(oldAsymmetricFootprint(wideExpected,narrower),
                "old actual-only comparison misses an interior outer-apron omission");
        check(!ShortRiverTunnelPlanner.sameRoofFootprint(wideExpected,narrower),
                "reconstructed width cannot omit an interior apron roof column");
        var subdivided=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,2),
                new ShortRiverTunnelPlanner.Point(40,0,78,2),
                new ShortRiverTunnelPlanner.Point(80,0,76,2)),80,10,70);
        check(ShortRiverTunnelPlanner.sameRoofFootprint(expected,subdivided),
                "same roof survives native segment subdivision");
        var shiftedInterior=new ShortRiverTunnelPlanner.Tunnel(List.of(
                new ShortRiverTunnelPlanner.Point(0,0,80,2),
                new ShortRiverTunnelPlanner.Point(40,8,78,2),
                new ShortRiverTunnelPlanner.Point(80,0,76,2)),
                Math.hypot(40,8)*2,10,70);
        check(!ShortRiverTunnelPlanner.sameRoofFootprint(expected,shiftedInterior),
                "reconstructed interior cannot lose or add protected roof columns");
    }
    /** Previous adapter predicate: it only compared columns protected by actual. */
    private static boolean oldAsymmetricFootprint(ShortRiverTunnelPlanner.Tunnel expected,
                                                   ShortRiverTunnelPlanner.Tunnel actual) {
        for(int x=-16;x<=96;x++) for(int z=-16;z<=16;z++) {
            int got=actual.roofFloorY(x,z,0);
            if(got!=Integer.MIN_VALUE && expected.roofFloorY(x,z,0)!=got) return false;
        }
        return true;
    }
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground hill(int start,int end,double entry,double exit){return (x,z)->x<=start?entry:x>=end?exit:92;}
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground longHill(){return (x,z)->x<0?80:x>40?76:hill(0,40,80,78).height(x,z);}
    private static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground shiftedHill(){return (x,z)->x<=-40?84:x>=0?82:96;}
    private static Reach r(double x1,double z1,double y1,double w1,double x2,double z2,double y2,double w2){return new Reach(x1,z1,y1,w1,x2,z2,y2,w2,1,1);}
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
