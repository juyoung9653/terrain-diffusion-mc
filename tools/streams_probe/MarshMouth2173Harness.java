package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import dev.streamsreflowing.core.plate.PlateKey;
import dev.streamsreflowing.core.plate.PlateLattice;
import dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001;
import dev.streamsreflowing.core.river.RiverNetwork;
import dev.streamsreflowing.core.river.Segment;
import dev.streamsreflowing.core.river.Sink;
import java.util.ArrayList;
import java.util.List;

/** World-free contract for bounded, unbranched marsh-mouth truncation. */
public final class MarshMouth2173Harness {
    public static final class LoadedEngine {
        public Object config() { return new Object(); }
        public PlateLattice lattice() { return new PlateLattice(); }
    }

    private static Segment segment(double from, double to) {
        return new Segment(from, 0, 64, 5.4, to, 0, 64, 5.4);
    }

    private static RiverNetwork network(Segment... segments) {
        return new RiverNetwork(new ArrayList<>(List.of(segments)));
    }

    @SuppressWarnings("unchecked")
    private static List<Segment> segments(Object network) {
        return (List<Segment>) ((RiverNetwork) network).sourceGivenToAuthorizedOnly1();
    }

    private static void pond(int min, int max) {
        LocalTerrainProvider.clearWetlandWater();
        LocalTerrainProvider.addWetlandWater(min, max, 65);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void unchanged(Object before, Object after, String message) {
        require(segments(before).equals(segments(after)), message);
    }

    public static void main(String[] args) {
        Object domain = new ProprietaryNiceJohn_001(new PlateLattice(), new PlateKey());

        // A two-piece, twelve-block dry stub is removed and its old sink is filtered.
        pond(880, 901);
        RiverNetwork twoPiece = network(segment(880, 900), segment(900, 906), segment(906, 912));
        StreamsDryGrade2173.reset();
        Object fresh = StreamsMarshMouthNetwork2173.adaptFresh(twoPiece, domain, new Object());
        require(StreamsDryGrade2173.calls == 1, "fresh path skipped or repeated dry grading");
        require(segments(fresh).size() == 2 && segments(fresh).getLast().x2 == 900,
                "two-piece dry tail did not stop at its modeled pond");
        require(((RiverNetwork) fresh).sinks().equals(List.of(new Sink(777, 777))),
                "reassembly removed an unmatched original sink or kept the displaced sink");
        require(segments(fresh).getFirst().w1 == 5.4 && segments(fresh).getFirst().w2 == 5.4,
                "surviving river width changed");

        // A tail beginning inside a longer native segment is cut at the pond interior.
        pond(880, 901);
        RiverNetwork interior = network(segment(870, 890), segment(890, 912));
        Object cutInterior = StreamsMarshMouthNetwork2173.adaptFresh(interior, domain, new Object());
        require(segments(cutInterior).size() == 2 && segments(cutInterior).get(1).x2 == 900,
                "pond crossing was not rebound at the interior endpoint");
        unchanged(cutInterior, StreamsMarshMouthNetwork2173.adaptFresh(cutInterior, domain, new Object()),
                "interior cut was not idempotent");

        // Loaded and fresh graph paths use the same selection.
        Object loaded = StreamsMarshMouthNetwork2173.adaptLoaded(new LoadedEngine(), new PlateKey(), twoPiece);
        require(StreamsDryGrade2173.calls == 4, "loaded path skipped or repeated dry grading");
        require(segments(loaded).size() == 2 && segments(loaded).getLast().x2 == 900,
                "loaded graph did not truncate the dry tail");

        // Controls: a long tail, thin pond, source-only line, and a joined tail remain unchanged.
        RiverNetwork longTail = network(segment(870, 900), segment(900, 925));
        StreamsDryGrade2173.reset();
        unchanged(longTail, StreamsMarshMouthNetwork2173.adaptFresh(longTail, domain, new Object()),
                "tail longer than twenty blocks changed");
        require(StreamsDryGrade2173.calls == 1, "unchanged mouth skipped dry grading");
        pond(900, 900);
        RiverNetwork thinPond = network(segment(880, 900), segment(900, 912));
        unchanged(thinPond, StreamsMarshMouthNetwork2173.adaptFresh(thinPond, domain, new Object()),
                "thin wet shoulder changed");
        pond(880, 901);
        RiverNetwork sourceOnly = network(segment(900, 912));
        unchanged(sourceOnly, StreamsMarshMouthNetwork2173.adaptFresh(sourceOnly, domain, new Object()),
                "source-only terminal changed");
        RiverNetwork joined = network(segment(880, 900), segment(890, 900), segment(900, 912));
        unchanged(joined, StreamsMarshMouthNetwork2173.adaptFresh(joined, domain, new Object()),
                "joined terminal changed");

        System.out.println("MarshMouth2173Harness: PASS bounded truncation, sink filtering, controls");
    }
}






