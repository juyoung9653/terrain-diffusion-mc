package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverProfile;
import java.util.HashMap;
import java.util.Map;

/** Adapts the audited native configuration before its engine and cache are created. */
public final class StreamsNaturalConfig {
    private StreamsNaturalConfig() { }

    /** Frozen v1 behavior for already generated worlds. */
    public static Object apply(Object config, int scale) {
        Object hydraulics = StreamsRecordAccess.read(config, "hydraulics");
        Object shaped = StreamsRecordAccess.copy(hydraulics, Map.of(
                "widthSlope", 0.85, "widthClimate", 0.55, "widthVariation", 0.30,
                "depthRatio", 0.65, "bendAsymmetry", 0.80, "channelBars", 0.15, "deltaMouths", 0.10));
        Map<String, Object> changes = new HashMap<>();
        changes.put("minRiverAccumulation", NaturalRiverProfile.minimumAccumulation(
                StreamsRecordAccess.number(config, "minRiverAccumulation")));
        changes.put("growDensity", Math.max(1.0, StreamsRecordAccess.number(config, "growDensity")));
        changes.put("minSeaChainNodes", NaturalRiverProfile.minimumCoastalNodes(
                (int) StreamsRecordAccess.number(config, "gridSpacing"), scale));
        changes.put("minLengthScope", 1); // Native scope 1: ocean outlets, not tributary or lake joins.
        changes.put("confineToBanks", true);
        changes.put("meanderStrength", Math.min(0.85, StreamsRecordAccess.number(config, "meanderStrength")));
        changes.put("streamDepth", Math.min(0.12, StreamsRecordAccess.number(config, "streamDepth")));
        changes.put("bankNoise", Math.min(0.12, StreamsRecordAccess.number(config, "bankNoise")));
        changes.put("hydraulics", shaped);
        return StreamsRecordAccess.copy(config, changes);
    }

    public static Object apply(Object config, int scale, int version) {
        if (version == 1) return apply(config, scale);
        if (version == 3) return watershedProfile(config, scale);
        if (version == 4) return watershedProfile(config, scale);
        // V5 only finishes dry banks after carving; native routing and water grades stay frozen.
        if (version == 5) return watershedProfile(config, scale);
        if (version == 6) return watershedProfile(config, scale);
        if (version == 7) return watershedProfile(config, scale);
        if (version == 8 || version == 9 || version == 10) {
            // Admit fewer weak native sources before the conservative border and
            // connector guards run. Keep every other v7 routing setting frozen.
            return StreamsRecordAccess.copy(watershedProfile(config, scale), Map.of(
                    "minRiverAccumulation", NaturalRiverProfile.minimumAccumulation(
                            StreamsRecordAccess.number(config, "minRiverAccumulation"))));
        }
        if (version != 2) throw new IllegalArgumentException("Unsupported natural river profile " + version);
        Object legacy = apply(config, scale);
        Object hydraulics = StreamsRecordAccess.copy(StreamsRecordAccess.read(legacy, "hydraulics"), Map.of(
                "widthSlope", 0.25, "widthClimate", 0.35, "widthVariation", 0.20, "deltaMouths", 0.02));
        // Native accumulation is upstream wetness summed over grid cells. Raise the
        // routing threshold, rather than deleting already connected river segments.
        // Width remains proportional to sqrt(accumulation * gridSpacing^2), with a
        // smaller coefficient; merely lowering maxWidth would flatten river hierarchy.
        return StreamsRecordAccess.copy(legacy, Map.of(
                "minRiverAccumulation", Math.max(128.0, StreamsRecordAccess.number(config, "minRiverAccumulation")),
                "growDensity", Math.max(2.0, StreamsRecordAccess.number(config, "growDensity")),
                "minSeaChainNodes", NaturalRiverProfile.coastalNodesV2(
                        (int) StreamsRecordAccess.number(config, "gridSpacing"), scale),
                "widthScale", StreamsRecordAccess.number(config, "widthScale") * 0.50,
                "hydraulics", hydraulics));
    }

    private static Object watershedProfile(Object config, int scale) {
        Object base = apply(config, scale);
        Object hydraulics = StreamsRecordAccess.copy(StreamsRecordAccess.read(base, "hydraulics"), Map.of(
                "widthSlope", 0.20, "widthClimate", 0.25, "widthVariation", 0.20, "deltaMouths", 0.02));
        // V3 separates basin selection from headwater initiation. V2's threshold
        // started every visible stream far downstream, after it was already wide.
        return StreamsRecordAccess.copy(base, Map.of(
                "plateScale", NaturalRiverProfile.watershedPlateV3((int) StreamsRecordAccess.number(config, "plateScale"), scale),
                "minRiverAccumulation", Math.max(6.0, StreamsRecordAccess.number(config, "minRiverAccumulation")),
                "growDensity", Math.max(1.0, StreamsRecordAccess.number(config, "growDensity")),
                "minSeaChainNodes", 1,
                "widthScale", StreamsRecordAccess.number(config, "widthScale") * 0.45,
                "hydraulics", hydraulics));
    }
}
