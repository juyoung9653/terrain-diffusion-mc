package com.github.xandergos.terraindiffusionmc.compat.streams;

import dev.streamsreflowing.core.api.Sink;
import dev.streamsreflowing.core.config.StreamsConfig;
import dev.streamsreflowing.core.plate.PlateKey;
import dev.streamsreflowing.core.river.Lake;
import dev.streamsreflowing.core.river.LicensedCodeNotForAnalysis_001;
import dev.streamsreflowing.core.river.RiverNetwork;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exercises the actual 2.16.2 native graph reconstruction boundary. */
public final class Streams2162NetworkHarness {
    private Streams2162NetworkHarness() { }

    public static void main(String[] args) {
        StreamsConfig config = StreamsConfig.proprietary1();
        verifySegmentRecordAccess();
        LicensedCodeNotForAnalysis_001 retained = segment(0, 0, 10, 0);
        LicensedCodeNotForAnalysis_001 removed = segment(10, 0, 20, 0);
        Lake lake = new Lake(62, 1, 1, 0, 0, 1, 1, new long[] {1L});
        Sink retainedSink = new Sink(10, 0, 0, 0, 0, 0);
        Sink removedSink = new Sink(20, 0, 0, 0, 0, 0);
        Sink unrelatedSink = new Sink(999, 0, 0, 0, 0, 0);

        // The public constructor permits a null relief object.  This makes the
        // preservation assertion independent of private relief construction.
        RiverNetwork original = new RiverNetwork(new PlateKey(0, 0),
                List.of(retained, removed), null, List.of(lake),
                List.of(retainedSink, removedSink, unrelatedSink), null);
        require(Streams2162NetworkAccess.applies(original), "2.16.2 graph was not recognized");

        RiverNetwork rebuilt = (RiverNetwork) StreamsNaturalNetwork.reassemble(
                original, List.of(retained), config, true);
        RiverNetwork unfiltered = (RiverNetwork) StreamsNaturalNetwork.reassemble(
                original, List.of(retained), config, false);
        require(rebuilt.sourceGivenToAuthorizedOnly1().equals(List.of(retained)),
                "reassembly changed retained segments");
        require(rebuilt.allRightsReserved1().equals(List.of(lake)), "reassembly lost lakes");
        require(rebuilt.niceJohn1() == original.niceJohn1(), "reassembly changed relief");
        require(rebuilt.sinks().equals(List.of(retainedSink)),
                "sink filter did not retain only matching retained endpoints");
        require(unfiltered.sinks().equals(List.of(retainedSink, removedSink, unrelatedSink)),
                "unfiltered graph reassembly did not retain all native sinks");
        System.out.println("Streams2162NetworkHarness: PASS native graph reassembly lakes sinks null-relief");
    }

    private static void verifySegmentRecordAccess() {
        List<String> names = List.of("x1", "z1", "y1", "w1", "x2", "z2", "y2", "w2", "sp1", "sp2");
        double[] forwardValues = {11, 22, 33, 44, 55, 66, 77, 88, 99, 111};
        double[] reversedValues = {55, 66, 77, 88, 11, 22, 33, 44, 99, 111};
        for (double[] expectedValues : List.of(forwardValues, reversedValues)) {
            LicensedCodeNotForAnalysis_001 value = new LicensedCodeNotForAnalysis_001(
                    expectedValues[0], expectedValues[1], expectedValues[2], expectedValues[3],
                    expectedValues[4], expectedValues[5], expectedValues[6], expectedValues[7],
                    expectedValues[8], expectedValues[9]);
            Map<String, Object> baseline = new LinkedHashMap<>();
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                Object actual = StreamsRecordAccess.read(value, name);
                require(Double.valueOf(expectedValues[index]).equals(actual),
                        "segment accessor mapped " + name + " to the wrong native component");
                baseline.put(name, actual);
            }
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                double replacement = -1000.0 - index;
                LicensedCodeNotForAnalysis_001 copy = (LicensedCodeNotForAnalysis_001)
                        StreamsRecordAccess.copy(value, Map.of(name, replacement));
                for (String other : names) {
                    Object expected = other.equals(name) ? replacement : baseline.get(other);
                    require(expected.equals(StreamsRecordAccess.read(copy, other)),
                            "segment record alias/order error replacing " + name + " changed " + other);
                }
            }
        }
    }

    private static LicensedCodeNotForAnalysis_001 segment(double x1, double z1, double x2, double z2) {
        return new LicensedCodeNotForAnalysis_001(x1, z1, 70, 4, x2, z2, 69, 4, .2, .3);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
