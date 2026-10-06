import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverConfluenceWidths;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public final class NaturalRiverConfluenceWidthsTest {
    public static void main(String[] args) {
        joinsRespondToTributarySize();
        sourceTaperDoesNotDisappear();
        segmentedAreaAndJoinMemory();
        protectedTransition();
        invalidComponentDoesNotDisableAnotherRiver();
        reorderAndCapacity();
        System.out.println("NaturalRiverConfluenceWidthsTest: passed");
    }

    private static Reach edge(double x, double z, double w, double X, double Z, double W) {
        return new Reach(x, z, 90, w, X, Z, 80, W, .6, .7);
    }
    private static List<Reach> yJoin(double tributary) {
        return List.of(edge(-300,-30,2,0,0,2), edge(-300,30,tributary,0,0,tributary),
                edge(0,0,4,4,0,4), edge(4,0,4,32,0,4), edge(32,0,4,512,0,4));
    }
    private static void joinsRespondToTributarySize() {
        var large = NaturalRiverConfluenceWidths.refine(yJoin(2), r -> false);
        var small = NaturalRiverConfluenceWidths.refine(yJoin(.65), r -> false);
        near(large.get(2).w1(), 2, "outgoing join starts at the dominant incoming stream");
        near(large.get(2).w2(), 2.08, "large join begins a gradual transition");
        check(large.get(3).w2() > 2.5, "join contribution must persist beyond its first segment");
        check(small.get(3).w2() < 2.12, "a tiny tributary must not reset width to native capacity");
        check(large.get(3).w2() > small.get(3).w2(), "larger tributary contributes more");
    }
    private static void sourceTaperDoesNotDisappear() {
        var input = List.of(edge(0,0,0,1,0,.8), edge(1,0,.8,501,0,3));
        var result = NaturalRiverConfluenceWidths.refine(input, r -> false);
        near(result.get(0).w1(), 0, "source tip stays zero");
        check(result.get(0).w2() >= .65, "short source reaches must establish a usable creek");
        var zero = List.of(edge(0,0,0,300,0,0));
        check(NaturalRiverConfluenceWidths.refine(zero, r -> false).equals(zero), "zero native taper unchanged");
    }
    private static List<Reach> chain(int pieces, double endWidth) {
        List<Reach> result = new ArrayList<>();
        for (int i=0; i<pieces; i++) {
            double a = (double)i/pieces, b = (double)(i+1)/pieces;
            result.add(edge(a*1000,0,.65+(endWidth-.65)*a,b*1000,0,.65+(endWidth-.65)*b));
        }
        result.add(edge(1000,0,endWidth,1500,0,endWidth));
        return result;
    }
    private static void segmentedAreaAndJoinMemory() {
        for (double width : new double[]{.75,20}) {
            var coarse = NaturalRiverConfluenceWidths.refine(chain(1,width), r -> false);
            var fine = NaturalRiverConfluenceWidths.refine(chain(100,width), r -> false);
            near(coarse.get(0).w2(), fine.get(99).w2(), "area and rate limited straight reaches must not re-add deficits per segment");
        }
        var mixedCoarse = NaturalRiverConfluenceWidths.refine(chain(1,4.65), r -> false);
        var mixedFine = NaturalRiverConfluenceWidths.refine(chain(100,4.65), r -> false);
        check(Math.abs(mixedCoarse.get(0).w2()-mixedFine.get(99).w2()) < .1,
                "mixed area/rate clamps have bounded subdivision sensitivity");
    }
    private static void protectedTransition() {
        var input = List.of(edge(0,0,.65,300,0,4), edge(300,0,4,364,0,4),
                edge(364,0,4,428,0,4), edge(428,0,4,600,0,4));
        var output = NaturalRiverConfluenceWidths.refine(input, r -> r.x1()==428);
        check(output.get(3).equals(input.get(3)), "protected reach exact");
        near(output.get(2).w2(), 4, "approach meets protected width exactly");
        check(output.get(1).w2() > output.get(1).w1(), "restore width over the approach corridor");
        check(output.get(1).w2() < 4, "restoration must not jump at corridor entry");
    }
    private static void invalidComponentDoesNotDisableAnotherRiver() {
        var cyclic = List.of(edge(-200,0,1,0,0,2), edge(0,0,2,10,0,2), edge(10,0,2,0,0,2));
        List<Reach> input = new ArrayList<>(cyclic);
        input.add(edge(1000,0,.65,1300,0,4));
        input.add(edge(1300,0,4,1800,0,4));
        var output = NaturalRiverConfluenceWidths.refine(input, r -> false);
        check(output.subList(0,3).equals(cyclic), "whole cyclic component including its feeder stays native");
        check(output.get(3).w2() < 4, "unrelated DAG still improves");
    }
    private static void reorderAndCapacity() {
        List<Reach> input = new ArrayList<>(yJoin(1.1));
        input.addAll(chain(20,3).stream().map(r -> edge(r.x1(),r.z1()+100,r.w1(),r.x2(),r.z2()+100,r.w2())).toList());
        var output = NaturalRiverConfluenceWidths.refine(input, r -> false);
        var mapping = new HashMap<Reach,Reach>();
        for (int i=0;i<input.size();i++) {
            Reach a=input.get(i), b=output.get(i);
            check(b.w1()>=0 && b.w2()>=0 && b.w1()<=a.w1() && b.w2()<=a.w2(), "native capacity");
            check(edgeIdentity(a,b), "width policy changes neither geometry nor grade/speed");
            mapping.put(a,b);
        }
        Collections.shuffle(input,new Random(513));
        var shuffled = NaturalRiverConfluenceWidths.refine(input,r->false);
        for(int i=0;i<input.size();i++) check(mapping.get(input.get(i)).equals(shuffled.get(i)), "deterministic under edge reorder");
    }
    private static boolean edgeIdentity(Reach a, Reach b) {
        return a.x1()==b.x1() && a.z1()==b.z1() && a.y1()==b.y1() && a.x2()==b.x2() && a.z2()==b.z2()
                && a.y2()==b.y2() && a.speed1()==b.speed1() && a.speed2()==b.speed2();
    }
    private static void near(double a,double b,String message) { check(Math.abs(a-b)<1e-9,message+": "+a+" != "+b); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
