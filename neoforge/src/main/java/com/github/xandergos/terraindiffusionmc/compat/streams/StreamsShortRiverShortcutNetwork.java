package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverShortcutPlanner;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

/** V12: replace only eligible short unbranched reach geometry before native indexing. */
public final class StreamsShortRiverShortcutNetwork {
    private StreamsShortRiverShortcutNetwork() { }

    public static Object refine(Object network, Object domain, NaturalRiverMorphology.Ground ground,
                                Object config, int profile) {
        if (profile < 12) return network;
        if (!Streams2162NetworkAccess.applies(network)) {
            throw new IllegalStateException("Natural river V12 requires Streams 2.16.2");
        }
        List<?> original = Streams2162NetworkAccess.segments(network);
        List<NaturalRiverMorphology.Reach> input = StreamsNaturalNetwork.reaches(original);
        NaturalRiverPlanform.Corridor corridor = corridor(network,domain,config);
        ShortRiverShortcutPlanner.Result shortcut = ShortRiverShortcutPlanner.plan(input, ground, corridor,
                (int) StreamsRecordAccess.number(config, "seaLevel"),
                (int) Math.round(StreamsRecordAccess.number(config, "ditchInset")));
        List<ShortRiverShortcutPlanner.Change> admitted = new ArrayList<>();
        Object acceptedNetwork=network;
        for(var proposal:shortcut.changes()) {
            var trial=new ArrayList<>(admitted);
            trial.add(proposal);
            var composed=ShortRiverShortcutPlanner.compose(input,trial);
            Object candidate=Streams2162NetworkAccess.reassemble(network,map(original,input,composed.reaches()),config,false);
            // Never publish a changed graph whose complete roof was rejected.
            if(preflight(candidate,ground,corridor(candidate,domain,config),composed.tunnels(),trial,config)) {
                admitted.add(proposal); acceptedNetwork=candidate;
            }
        }
        if(!shortcut.changes().isEmpty()) org.slf4j.LoggerFactory.getLogger("TerrainDiffusion/ShortRiverTunnels").info(
                "Accepted {} of {} short river routes before native graph persistence",admitted.size(),shortcut.changes().size());
        return acceptedNetwork;
    }

    static NaturalRiverPlanform.Corridor corridor(Object network, Object domain, Object config) {
        double spacing=StreamsRecordAccess.number(config,"gridSpacing");
        return (x,z,radius) -> Streams2162NetworkAccess.contains(domain,x,z)
                && Streams2162NetworkAccess.borderDistance(domain,x,z)>radius+8
                && Streams2162NetworkAccess.lakeNear(network,(int)Math.floor(x),(int)Math.floor(z),
                        (int)Math.ceil(radius/Math.max(1,spacing))+1)==null;
    }

    private static boolean preflight(Object network, NaturalRiverMorphology.Ground ground,
                                     NaturalRiverPlanform.Corridor corridor,
                                     List<ShortRiverTunnelPlanner.Tunnel> expected, Object config) {
        return preflight(network, ground, corridor, expected, List.of(), config);
    }

    private static boolean preflight(Object network, NaturalRiverMorphology.Ground ground,
                                      NaturalRiverPlanform.Corridor corridor,
                                      List<ShortRiverTunnelPlanner.Tunnel> expected,
                                      List<ShortRiverShortcutPlanner.Change> changes, Object config) {
        try {
            if (expected.isEmpty()) return false;
            List<ShortRiverTunnelPlanner.Tunnel> masks = approvedMasks(network, ground, corridor, config);
            if (!sameMasks(expected, masks) || !sameShortcutCoverage(changes, masks)) {
                org.slf4j.LoggerFactory.getLogger("TerrainDiffusion/ShortRiverTunnels").info(
                        "Shortcut masks changed after graph subdivision: expected={}, reconstructed={}", expected.size(), masks.size());
                return false;
            }
            return true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams 2.16.2 shortcut preflight contract changed", error);
        }
    }

    /** Shared by graph admission and carver/restart planning; never depends on an engine cache. */
    static List<ShortRiverTunnelPlanner.Tunnel> approvedMasks(Object network, NaturalRiverMorphology.Ground ground,
            NaturalRiverPlanform.Corridor corridor, Object config) throws ReflectiveOperationException {
        var geometry = ShortRiverTunnelPlanner.plan(
                StreamsNaturalNetwork.reaches(Streams2162NetworkAccess.segments(network)), ground, corridor,
                (int) StreamsRecordAccess.number(config,"seaLevel"),
                (int) Math.round(StreamsRecordAccess.number(config,"ditchInset")));
        var columns = NativeSample.of(network,config);
        List<ShortRiverTunnelPlanner.Tunnel> accepted = new ArrayList<>();
        for (var mask : geometry) {
            var fitted=columns.approve(mask,ground);
            if(fitted!=null) accepted.add(fitted);
        }
        return List.copyOf(accepted);
    }

    private static boolean sameMasks(List<ShortRiverTunnelPlanner.Tunnel> expected,
                                     List<ShortRiverTunnelPlanner.Tunnel> actual) {
        for (ShortRiverTunnelPlanner.Tunnel mask : expected) {
            boolean matched = false;
            for (ShortRiverTunnelPlanner.Tunnel candidate : actual) {
                if (Math.abs(mask.length() - candidate.length()) > 1.0) continue;
                if (sameFootprint(mask, candidate)) { matched = true; break; }
            }
            if (!matched) return false;
        }
        return true;
    }

    /** Native-column fitting may trim a portal by up to sixteen blocks, but it
     * may not turn the already admitted shortcut into a longer exposed apron. */
    private static boolean sameShortcutCoverage(List<ShortRiverShortcutPlanner.Change> changes,
                                                 List<ShortRiverTunnelPlanner.Tunnel> actual) {
        for (ShortRiverShortcutPlanner.Change change : changes) {
            if (change.chord().isEmpty()) return false;
            NaturalRiverMorphology.Reach first = change.chord().getFirst();
            NaturalRiverMorphology.Reach last = change.chord().getLast();
            NaturalRiverMorphology.Reach chord = new NaturalRiverMorphology.Reach(
                    first.x1(), first.z1(), first.y1(), first.w1(),
                    last.x2(), last.z2(), last.y2(), last.w2(), first.speed1(), last.speed2());
            for (ShortRiverTunnelPlanner.Tunnel expected : change.tunnels()) {
                ShortRiverTunnelPlanner.Tunnel matched = null;
                for (ShortRiverTunnelPlanner.Tunnel candidate : actual) {
                    if (Math.abs(expected.length() - candidate.length()) <= 1.0
                            && sameFootprint(expected, candidate)) { matched = candidate; break; }
                }
                if (matched == null || !ShortRiverShortcutPlanner.roofCoversChord(chord, matched)) return false;
            }
        }
        return true;
    }

    private static boolean sameFootprint(ShortRiverTunnelPlanner.Tunnel expected,
                                         ShortRiverTunnelPlanner.Tunnel actual) {
        var entry=expected.centreline().getFirst(); var exit=expected.centreline().getLast();
        var actualEntry=actual.centreline().getFirst(); var actualExit=actual.centreline().getLast();
        if(Math.hypot(entry.x()-actualEntry.x(),entry.z()-actualEntry.z())>1
                || Math.hypot(exit.x()-actualExit.x(),exit.z()-actualExit.z())>1
                || actual.coveredStart()<expected.coveredStart()-1 || actual.coveredEnd()>expected.coveredEnd()+1
                || actual.coveredStart()-expected.coveredStart()>17 || expected.coveredEnd()-actual.coveredEnd()>17) return false;
        return ShortRiverTunnelPlanner.sameRoofFootprint(expected, actual);
    }

    /** Direct graph sampler: no engine cache, no column-plan construction during graph building. */
    record NativeSample(Object network, Object config, Method sample, Method distance, Method width,
                                Method grade, Method insetFor, int waterFloor) {
        static NativeSample of(Object network, Object config) throws ReflectiveOperationException {
            Method sample = network.getClass().getMethod("proprietary1", double.class, double.class, double.class);
            Class<?> type = sample.getReturnType();
            Class<?> engine = Class.forName("dev.streamsreflowing.core.RiverEngine", false, network.getClass().getClassLoader());
            return new NativeSample(network, config, sample, type.getMethod("proprietary1"), type.getMethod("niceJohn1"),
                    type.getMethod("seeAiNotice1"), engine.getMethod("insetFor", double.class, config.getClass()),
                    (int) StreamsRecordAccess.number(config, "seaLevel") - 1);
        }
        int water(int x, int z, boolean channelOnly) throws ReflectiveOperationException {
            Object value = sample.invoke(network, x + .5, z + .5, 64.0);
            double distanceValue = value == null ? Double.NaN : ((Number) distance.invoke(value)).doubleValue();
            if (!Double.isFinite(distanceValue)) return Integer.MIN_VALUE;
            double widthValue = ((Number) width.invoke(value)).doubleValue();
            double gradeValue = ((Number) grade.invoke(value)).doubleValue();
            if (!(widthValue > 0) || !Double.isFinite(gradeValue)) return Integer.MIN_VALUE;
            double limit = channelOnly ? widthValue
                    : widthValue + Math.max(5, widthValue * StreamsRecordAccess.number(config, "bankWidth"));
            if (distanceValue > limit) return Integer.MIN_VALUE;
            int insetValue = ((Number) insetFor.invoke(null, widthValue, config)).intValue();
            return Math.max(waterFloor, (int) Math.floor(gradeValue) - insetValue);
        }
        ShortRiverTunnelPlanner.Tunnel approve(ShortRiverTunnelPlanner.Tunnel mask, NaturalRiverMorphology.Ground ground)
                throws ReflectiveOperationException {
            var entry=mask.centreline().getFirst(); var exit=mask.centreline().getLast();
            int top=water((int)Math.floor(entry.x()),(int)Math.floor(entry.z()),true);
            int bottom=water((int)Math.floor(exit.x()),(int)Math.floor(exit.z()),true);
            if(top==Integer.MIN_VALUE || bottom==Integer.MIN_VALUE || top<=bottom) return null;
            Map<Long,Integer> waters=new java.util.HashMap<>();
            Map<Long,Double> heights=new java.util.HashMap<>();
            return ShortRiverTunnelPlanner.fitRoof(mask,
                    (x,z)->heights.computeIfAbsent(((long)x<<32)^(z&0xffffffffL),ignored->ground.height(x,z)),
                    (x,z)->waters.computeIfAbsent(((long)x<<32)^(z&0xffffffffL),ignored->{
                        try { return waterFor(mask,x,z); }
                        catch(ReflectiveOperationException error) { throw new IllegalStateException("Native roof water sampling failed",error); }
                    }));
        }
        int waterFor(ShortRiverTunnelPlanner.Tunnel mask, int x, int z) throws ReflectiveOperationException {
            int water = water(x,z,false);
            if (water != Integer.MIN_VALUE) return water;
            ShortRiverTunnelPlanner.Point nearest = null;
            double best = Double.POSITIVE_INFINITY;
            for (var point : mask.centreline()) {
                double distance = Math.hypot(point.x()-x,point.z()-z);
                if (distance < best) { best=distance; nearest=point; }
            }
            return nearest == null ? Integer.MIN_VALUE
                    : water((int)Math.floor(nearest.x()),(int)Math.floor(nearest.z()),true);
        }
        int roof(ShortRiverTunnelPlanner.Tunnel mask, int x, int z) throws ReflectiveOperationException {
            if (mask.roofFloorY(x,z,0)==Integer.MIN_VALUE) return Integer.MIN_VALUE;
            int water=waterFor(mask,x,z);
            if (water==Integer.MIN_VALUE) throw new IllegalStateException("Approved river roof lost its native water sample");
            return mask.roofFloorY(x,z,water);
        }
    }

    /** The audited 2.16.2 record has exactly these ten coordinates/width/speed fields. */
    static List<Object> map(List<?> original, List<NaturalRiverMorphology.Reach> input,
                            List<NaturalRiverMorphology.Reach> output) {
        Map<NaturalRiverMorphology.Reach, Object> unchanged = new java.util.HashMap<>();
        for (int i = 0; i < input.size(); i++) unchanged.put(input.get(i), original.get(i));
        List<Object> result = new ArrayList<>(output.size());
        for (NaturalRiverMorphology.Reach reach : output) {
            Object raw = unchanged.get(reach);
            result.add(raw != null ? raw : StreamsRecordAccess.copy(original.getFirst(),
                    Map.of("x1", reach.x1(), "z1", reach.z1(), "y1", reach.y1(), "w1", reach.w1(),
                            "x2", reach.x2(), "z2", reach.z2(), "y2", reach.y2(), "w2", reach.w2(),
                            "sp1", reach.speed1(), "sp2", reach.speed2())));
        }
        return List.copyOf(result);
    }

}
