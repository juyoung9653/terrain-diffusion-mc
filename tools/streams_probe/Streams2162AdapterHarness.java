package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalConfig;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JDK-only regression for the audited, explicit 2.16.2 logical record map. */
public final class Streams2162AdapterHarness {
    private static final List<String> CONFIG = List.of(
            "seaLevel", "plateScale", "plateJitter", "gridSpacing", "terrainSampleSpacing",
            "valleySnapRadius", "terrainFit", "lakeSampleSpacing", "legacyShaping",
            "adaptiveGridRead", "minRiverAccumulation", "baseWidth", "maxWidth",
            "widthScale", "streamDepth", "stepDeltaY", "bankWidth", "bankSlope",
            "bankNoise", "bankSteepFactor", "ditchInset", "aRiver", "borderMargin",
            "smoothingPasses", "meanderStrength", "seaLevelExtension", "reconnectRadius",
            "wetnessDryCutoff", "wetnessWetReference", "connectNearbyWater", "connectRadius",
            "sinkRooted", "growFromWater", "growDensity", "confineToBanks",
            "containmentBerm", "wetnessMultiplier", "wetnessDryFloor", "minSeaChainNodes",
            "minLengthScope", "lakeMinArea", "lakeMaxArea", "outletBiomes", "hydraulics");
    private static final List<String> HYDRAULICS = List.of(
            "widthSlope", "widthClimate", "widthVariation", "depthRatio", "bendAsymmetry",
            "channelBars", "deltaMouths");
    private static final List<String> COLUMN = List.of(
            "inChannel", "inCorridor", "waterSurfaceY", "bedY", "bankBlend", "distance",
            "width", "gradeY", "slope", "flowDirX", "flowDirZ", "terminal", "source",
            "upstreamSlope", "fluidFlowDirX", "fluidFlowDirZ", "distToOutlet", "curvature",
            "lateral", "distToJunction", "flatRun", "speed", "nativeExtra");

    private Streams2162AdapterHarness() { }

    public static void main(String[] args) throws Exception {
        Class<?> configType = Class.forName("dev.streamsreflowing.core.config.StreamsConfig");
        Object config = configType.getMethod("proprietary1").invoke(null);
        Map<String, Object> before = values(config, CONFIG);
        require(before.size() == 44, "config must expose 44 logical components");
        roundTrip(config, CONFIG, "config");
        verifySingleFieldCopies(config, CONFIG, "config");

        Object hydraulics = StreamsRecordAccess.read(config, "hydraulics");
        require(values(hydraulics, HYDRAULICS).size() == 7, "hydraulics must expose seven components");
        roundTrip(hydraulics, HYDRAULICS, "hydraulics");
        verifySingleFieldCopies(hydraulics, HYDRAULICS, "hydraulics");

        Class<?> columnType = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        Object none = columnType.getField("NONE").get(null);
        require(values(none, COLUMN).size() == 23, "ColumnPlan must expose 23 components");
        roundTrip(none, COLUMN, "ColumnPlan");
        verifySingleFieldCopies(none, COLUMN, "ColumnPlan");

        for (int version = 1; version <= 10; version++) {
            Object adapted = StreamsNaturalConfig.apply(config, 2, version);
            for (String field : List.of("terrainFit", "lakeSampleSpacing", "legacyShaping", "adaptiveGridRead")) {
                require(Objects.equals(StreamsRecordAccess.read(adapted, field), before.get(field)),
                        "v" + version + " changed " + field);
            }
        }
        requireFails(() -> StreamsRecordAccess.copy(config, Map.of("notAStreamsField", 1)));
        requireFails(() -> StreamsRecordAccess.copy(hydraulics, Map.of("notAStreamsField", 1)));
        requireFails(() -> StreamsRecordAccess.copy(none, Map.of("notAStreamsField", 1)));
        System.out.println("Streams2162AdapterHarness: PASS config44 hydraulic7 column23 profiles1-10");
    }

    private static void roundTrip(Object value, List<String> fields, String label) {
        Map<String, Object> before = values(value, fields);
        Object copy = StreamsRecordAccess.copy(value, Map.of());
        require(copy.equals(value), label + " empty copy differs");
        require(values(copy, fields).equals(before), label + " copy changed a field");
    }

    private static Map<String, Object> values(Object record, List<String> names) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, StreamsRecordAccess.read(record, name));
        return result;
    }

    /** One distinct replacement at a time catches same-typed alias/order mistakes. */
    private static void verifySingleFieldCopies(Object original, List<String> fields, String label) {
        Map<String, Object> baseline = values(original, fields);
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            if (field.equals("hydraulics")) continue; // Its own seven-field pass covers it.
            Object replacement = sentinel(baseline.get(field), index);
            Object copy = StreamsRecordAccess.copy(original, Map.of(field, replacement));
            Map<String, Object> actual = values(copy, fields);
            for (String other : fields) {
                Object expected = other.equals(field) ? replacement : baseline.get(other);
                require(Objects.equals(actual.get(other), expected),
                        label + " alias/order error replacing " + field + " changed " + other);
            }
        }
    }

    private static Object sentinel(Object value, int index) {
        if (value instanceof Integer) return 1000 + index;
        if (value instanceof Double number) {
            // Native hydraulic constructors retain their normal range checks; a
            // small in-range delta still distinguishes every copied component.
            return number >= .5 ? number - .001 * (index + 1) : number + .001 * (index + 1);
        }
        if (value instanceof Boolean) return !((Boolean) value);
        if (value instanceof String) return "td-2162-sentinel-" + index;
        throw new AssertionError("unsupported sentinel type " + value.getClass().getName());
    }

    private static void requireFails(Runnable action) {
        try { action.run(); throw new AssertionError("unknown replacement did not fail closed"); }
        catch (IllegalStateException expected) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
