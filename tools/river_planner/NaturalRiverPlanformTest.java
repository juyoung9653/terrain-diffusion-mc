import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform.Piece;
import java.util.List;

public final class NaturalRiverPlanformTest {
    private static final NaturalRiverPlanform.Corridor OPEN = (x, z, radius) -> true;
    private static final com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground HIGH = (x, z) -> 100;

    public static void main(String[] args) {
        bendsLongStraightReachAndPinsEndpoints();
        bendsAChainAndInterpolatesAttributes();
        bendsStraightRunInsideCurvedPathWithoutReordering();
        pinsAConfluenceWhileBendingItsLongReach();
        deterministicForNegativeCoordinates();
        preservesUnsuitableAndBranchedPaths();
        rejectsBlockedTerrainAndCorridor();
        triesOppositeBankBeforeGivingUp();
        rejectsAnUnrelatedCrossing();
        handlesNativeHalfBlockTerracesAndZeroWidthSources();
        System.out.println("NaturalRiverPlanformTest: passed");
    }

    private static void bendsLongStraightReachAndPinsEndpoints() {
        Reach source = r(0, 0, 76, 3, 56, 0, 74, 4, 2, 4);
        List<Piece> out = NaturalRiverPlanform.refine(List.of(source), HIGH, OPEN, 19, 63);
        check(out.size() > 2, "long reach must be sampled");
        check(same(out.get(0).reach().x1(), 0) && same(out.get(0).reach().z1(), 0), "source pinned");
        Reach end = out.get(out.size() - 1).reach();
        check(same(end.x2(), 56) && same(end.z2(), 0), "outlet pinned");
        check(out.stream().anyMatch(p -> Math.abs(p.reach().z1()) > .2 || Math.abs(p.reach().z2()) > .2), "must visibly bend");
        check(out.stream().allMatch(p -> p.sourceIndex() == 0), "source metadata retained");
        for (int i=1;i<out.size();i++) check(out.get(i-1).reach().x2() == out.get(i).reach().x1()
                && out.get(i-1).reach().z2() == out.get(i).reach().z1(), "pieces join exactly");
    }

    private static void bendsAChainAndInterpolatesAttributes() {
        List<Reach> input = List.of(r(0,0,80,2,20,0,79,3,1,2), r(20,0,79,3,40,0,78,4,2,3), r(40,0,78,4,60,0,77,5,3,4));
        List<Piece> out = NaturalRiverPlanform.refine(input, HIGH, OPEN, 7, 63);
        check(out.size() > input.size(), "chain should split");
        check(out.stream().allMatch(p -> p.reach().y1() >= p.reach().y2()), "no uphill pieces");
        check(out.stream().allMatch(p -> p.reach().w1() <= 5 && p.reach().w2() <= 5), "width interpolated");
        check(same(out.get(0).reach().y1(), 80) && same(out.get(out.size()-1).reach().y2(), 77), "native grades pinned");
    }

    private static void deterministicForNegativeCoordinates() {
        List<Reach> input = List.of(r(-60,-20,80,3,-4,-20,78,3,1,1));
        List<Piece> a = NaturalRiverPlanform.refine(input, HIGH, OPEN, 99, 63);
        List<Piece> b = NaturalRiverPlanform.refine(input, HIGH, OPEN, 99, 63);
        check(a.equals(b), "same seed and coordinates must repeat");
    }

    private static void pinsAConfluenceWhileBendingItsLongReach() {
        List<Reach> input = List.of(r(0,0,80,3,60,0,78,3,1,1), r(0,-20,82,3,0,0,80,3,1,1));
        List<Piece> out = NaturalRiverPlanform.refine(input, HIGH, OPEN, 15, 63);
        check(out.size() > input.size(), "long mainstem at confluence should bend");
        check(same(out.get(0).reach().x1(), 0) && same(out.get(0).reach().z1(), 0), "confluence fixed");
        check(out.get(out.size()-1).sourceIndex() == 1 && out.get(out.size()-1).reach().equals(input.get(1)), "tributary exact and ordered");
    }

    private static void bendsStraightRunInsideCurvedPathWithoutReordering() {
        List<Reach> input = List.of(
                r(-20,-20,81,3,0,0,80,3,1,1), r(0,0,80,3,56,0,78,3,1,1),
                r(56,0,78,3,76,20,77,3,1,1));
        List<Piece> out = NaturalRiverPlanform.refine(input, HIGH, OPEN, 11, 63);
        check(out.size() > input.size(), "embedded straight run should bend");
        check(out.get(0).sourceIndex() == 0 && out.get(0).reach().equals(input.get(0)), "upstream bend preserved and order pinned");
        check(out.get(out.size()-1).sourceIndex() == 2 && out.get(out.size()-1).reach().equals(input.get(2)), "downstream bend preserved and order pinned");
        check(out.stream().filter(p -> p.sourceIndex() == 1).anyMatch(p -> Math.abs(p.reach().z1()) > .2 || Math.abs(p.reach().z2()) > .2), "middle run curved");
        check(same(out.get(0).reach().x2(), 0) && same(out.get(0).reach().z2(), 0), "incoming junction exact");
    }

    private static void preservesUnsuitableAndBranchedPaths() {
        List<Reach> shortOne = List.of(r(0,0,80,3,20,0,79,3,1,1));
        List<Reach> wide = List.of(r(0,0,80,6,60,0,79,6,1,1));
        List<Reach> curved = List.of(r(0,0,80,3,30,5,79,3,1,1), r(30,5,79,3,60,0,78,3,1,1));
        List<Reach> branch = List.of(r(0,0,80,3,30,0,79,3,1,1), r(30,0,79,3,60,0,78,3,1,1), r(30,0,79,3,30,20,78,3,1,1));
        List<Reach> steep = List.of(r(0,0,90,3,60,0,70,3,1,1));
        List<Reach> nearSea = List.of(r(0,0,64,3,60,0,63,3,1,1));
        List<Reach> localUphill = List.of(r(0,0,80,3,20,0,79,3,1,1), r(20,0,79,3,40,0,80,3,1,1), r(40,0,80,3,60,0,78,3,1,1));
        for (List<Reach> input : List.of(shortOne, wide, curved, branch, steep, nearSea, localUphill)) check(identity(input).equals(NaturalRiverPlanform.refine(input,HIGH,OPEN,1,63)), "unsafe graph unchanged");
    }

    private static void triesOppositeBankBeforeGivingUp() {
        List<Reach> input = List.of(r(0,0,80,3,60,0,78,3,1,1));
        List<Piece> out = NaturalRiverPlanform.refine(input, HIGH, (x,z,r) -> z <= 0.01, 2, 63);
        check(out.size() > 1 && out.stream().allMatch(p -> p.reach().z1() <= .01 && p.reach().z2() <= .01),
                "blocked preferred side should try the opposite bank");
    }

    private static void rejectsBlockedTerrainAndCorridor() {
        List<Reach> input = List.of(r(0,0,80,3,60,0,78,3,1,1));
        check(identity(input).equals(NaturalRiverPlanform.refine(input, (x,z)->70, OPEN, 2,63)), "low bank must reject");
        check(identity(input).equals(NaturalRiverPlanform.refine(input, HIGH, (x,z,r)->false, 2,63)), "lake/domain must reject");
        check(identity(input).equals(NaturalRiverPlanform.refine(input, (x,z)->Math.abs(z) > 1 ? 70 : 100, OPEN, 2,63)), "eroded bank wall must reject");
    }

    private static void rejectsAnUnrelatedCrossing() {
        List<Reach> input = List.of(r(0,0,80,3,60,0,78,3,1,1), r(30,-20,80,3,30,20,78,3,1,1));
        check(identity(input).equals(NaturalRiverPlanform.refine(input,HIGH,OPEN,2,63)), "unrelated crossing must reject");
    }

    private static void handlesNativeHalfBlockTerracesAndZeroWidthSources() {
        var input = new java.util.ArrayList<Reach>();
        // A separate zero-width headwater is valid native data, not a reason
        // to abandon all other rivers in the plate.
        input.add(r(1000,1000,80,0,1001,1000,80,.01,1,1));
        for (int i = 0; i < 20; i++) {
            double a = 10 - i * .5, b = a - .5;
            input.add(r(56+a,a,77,2,56+b,b,77,2,1,1));
        }
        for (int i = 0; i < 112; i++) {
            double a=56-i*.5,b=a-.5;
            double ya=74+Math.floor(a/16),yb=74+Math.floor(b/16);
            input.add(r(a,0,ya,2,b,0,yb,2,1,1));
        }
        for (int i=0;i<20;i++) {
            double a=-i*.5,b=a-.5;
            input.add(r(a,a,74,2,b,b,74,2,1,1));
        }
        // Boundary 76 contains physical water 75, although the graph grade is
        // 77. The native planner applies its two-block inset when rasterizing.
        var out=NaturalRiverPlanform.refine(input,(x,z)->76,OPEN,19,63,2);
        check(out.get(0).reach().equals(input.get(0)),"zero-width source preserved");
        check(out.stream().anyMatch(p->p.sourceIndex()>=21 && p.sourceIndex()<133
                && Math.abs(p.reach().z1())>1),"native half-block terrace run must bend");
        for(int i=1;i<out.size();i++) check(out.get(i-1).sourceIndex()<=out.get(i).sourceIndex(),"native record order stable");
        check(out.stream().allMatch(p->p.reach().y1()>=p.reach().y2()),"native terrace grades remain downstream");
    }

    private static List<Piece> identity(List<Reach> input) { java.util.ArrayList<Piece> out=new java.util.ArrayList<>(); for(int i=0;i<input.size();i++) out.add(new Piece(i,input.get(i))); return List.copyOf(out); }
    private static Reach r(double x1,double z1,double y1,double w1,double x2,double z2,double y2,double w2,double s1,double s2){ return new Reach(x1,z1,y1,w1,x2,z2,y2,w2,s1,s2); }
    private static boolean same(double a,double b){return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
