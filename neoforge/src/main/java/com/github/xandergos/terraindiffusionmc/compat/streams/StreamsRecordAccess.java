package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The native record boundary is isolated here; no Streams classes ship inside TD. */
public final class StreamsRecordAccess {
    private StreamsRecordAccess() { }

    private record Component(String name, Method accessor) { }
    private record Layout(Component[] components, Constructor<?> constructor) { }
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> type) {
            try {
                // The distributed JAR strips the Record attribute. Never infer constructor order
                // from getDeclaredFields(); use an explicit audited API layout instead.
                String[] names = type.isRecord() && !is2162(type)
                        ? java.util.Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new)
                        : auditedComponents(type);
                Component[] components = new Component[names.length];
                Class<?>[] signature = new Class<?>[names.length];
                for (int i = 0; i < names.length; i++) {
                    Method accessor = nativeAccessor(type, names[i]);
                    components[i] = new Component(names[i], accessor);
                    signature[i] = accessor.getReturnType();
                }
                return new Layout(components, type.getConstructor(signature));
            } catch (ReflectiveOperationException e) {
                throw incompatible(type, e);
            }
        }
    };

    private static String[] auditedComponents(Class<?> type) {
        String fields = switch (type.getName()) {
            case "dev.streamsreflowing.core.config.StreamsConfig" ->
                    "seaLevel plateScale plateJitter gridSpacing terrainSampleSpacing valleySnapRadius minRiverAccumulation "
                    + "baseWidth maxWidth widthScale streamDepth stepDeltaY bankWidth bankSlope bankNoise bankSteepFactor ditchInset "
                    + "aRiver borderMargin smoothingPasses meanderStrength seaLevelExtension reconnectRadius wetnessDryCutoff "
                    + "wetnessWetReference connectNearbyWater connectRadius sinkRooted growFromWater growDensity confineToBanks "
                    + "containmentBerm wetnessMultiplier wetnessDryFloor minSeaChainNodes minLengthScope lakeMinArea lakeMaxArea outletBiomes hydraulics";
            case "dev.streamsreflowing.core.config.HydraulicGeometry" ->
                    "widthSlope widthClimate widthVariation depthRatio bendAsymmetry channelBars deltaMouths";
            case "dev.streamsreflowing.core.river.RiverSegment",
                    "dev.streamsreflowing.core.river.LicensedCodeNotForAnalysis_001" -> "x1 z1 y1 w1 x2 z2 y2 w2 sp1 sp2";
            case "dev.streamsreflowing.core.compose.ColumnPlan" ->
                    "inChannel inCorridor waterSurfaceY bedY bankBlend distance width gradeY slope flowDirX flowDirZ "
                    + "terminal source upstreamSlope fluidFlowDirX fluidFlowDirZ distToOutlet curvature lateral distToJunction flatRun speed";
            default -> throw new IllegalStateException("Unsupported Streams record: " + type.getName());
        };
        if (type.getName().equals("dev.streamsreflowing.core.config.StreamsConfig")) {
            try {
                nativeAccessor(type, "terrainFit");
                // Audited 2.15.2 constructor order. Retain native fit, lake and adaptive settings.
                fields = fields.replace("valleySnapRadius ",
                        "valleySnapRadius terrainFit lakeSampleSpacing legacyShaping adaptiveGridRead ");
            } catch (NoSuchMethodException legacyLayout) {
                // Audited 2.13.x constructor; the full signature is still checked above.
            }
        }
        if (type.getName().equals("dev.streamsreflowing.core.compose.ColumnPlan")
                && is2162(type)) {
            // New native trailing component is carried through unchanged by TD.
            fields += " nativeExtra";
        }
        return fields.split(" ");
    }

    /** Audited 2.16.2 accessor aliases, in constructor order; never field iteration order. */
    private static Method nativeAccessor(Class<?> type, String name) throws NoSuchMethodException {
        if (!is2162(type)) return type.getMethod(name);
        String logical;
        String nativeNames;
        switch (type.getName()) {
            case "dev.streamsreflowing.core.river.LicensedCodeNotForAnalysis_001" -> {
                logical = "x1 z1 y1 w1 x2 z2 y2 w2 sp1 sp2";
                nativeNames = "sourceGivenToAuthorizedOnly1 licensed1 allRightsReserved1 notForAnalysis1 proprietary2 "
                        + "seeAiNotice2 niceJohn2 noDecompilePermission2 sourceGivenToAuthorizedOnly2 licensed2";
            }
            case "dev.streamsreflowing.core.config.StreamsConfig" -> {
                logical = "seaLevel plateScale plateJitter gridSpacing terrainSampleSpacing valleySnapRadius "
                        + "terrainFit lakeSampleSpacing legacyShaping adaptiveGridRead minRiverAccumulation baseWidth "
                        + "maxWidth widthScale streamDepth stepDeltaY bankWidth bankSlope bankNoise bankSteepFactor "
                        + "ditchInset aRiver borderMargin smoothingPasses meanderStrength seaLevelExtension reconnectRadius "
                        + "wetnessDryCutoff wetnessWetReference connectNearbyWater connectRadius sinkRooted growFromWater "
                        + "growDensity confineToBanks containmentBerm wetnessMultiplier wetnessDryFloor minSeaChainNodes "
                        + "minLengthScope lakeMinArea lakeMaxArea outletBiomes hydraulics";
                nativeNames = "seeAiNotice1 niceJohn1 noDecompilePermission1 sourceGivenToAuthorizedOnly1 licensed1 "
                        + "allRightsReserved1 notForAnalysis1 proprietary2 seeAiNotice2 niceJohn2 noDecompilePermission2 "
                        + "sourceGivenToAuthorizedOnly2 maxWidth licensed2 streamDepth allRightsReserved2 bankWidth bankSlope "
                        + "notForAnalysis2 proprietary3 seeAiNotice3 niceJohn3 noDecompilePermission3 sourceGivenToAuthorizedOnly3 "
                        + "licensed3 allRightsReserved3 notForAnalysis3 proprietary4 seeAiNotice4 niceJohn4 noDecompilePermission4 "
                        + "sourceGivenToAuthorizedOnly4 licensed4 allRightsReserved4 notForAnalysis4 proprietary5 seeAiNotice5 "
                        + "niceJohn5 noDecompilePermission5 sourceGivenToAuthorizedOnly5 licensed5 allRightsReserved5 notForAnalysis5 hydraulics";
            }
            case "dev.streamsreflowing.core.config.HydraulicGeometry" -> {
                logical = "widthSlope widthClimate widthVariation depthRatio bendAsymmetry channelBars deltaMouths";
                nativeNames = "widthSlope widthClimate widthVariation niceJohn1 noDecompilePermission1 channelBars sourceGivenToAuthorizedOnly1";
            }
            case "dev.streamsreflowing.core.compose.ColumnPlan" -> {
                logical = "inChannel inCorridor waterSurfaceY bedY bankBlend distance width gradeY slope flowDirX flowDirZ "
                        + "terminal source upstreamSlope fluidFlowDirX fluidFlowDirZ distToOutlet curvature lateral distToJunction flatRun speed nativeExtra";
                nativeNames = "inChannel inCorridor waterSurfaceY bedY seeAiNotice1 niceJohn1 noDecompilePermission1 "
                        + "sourceGivenToAuthorizedOnly1 licensed1 flowDirX flowDirZ allRightsReserved1 notForAnalysis1 proprietary2 "
                        + "seeAiNotice2 niceJohn2 noDecompilePermission2 sourceGivenToAuthorizedOnly2 licensed2 allRightsReserved2 "
                        + "notForAnalysis2 proprietary3 seeAiNotice3";
            }
            default -> { return type.getMethod(name); }
        }
        String[] keys = logical.split(" "), values = nativeNames.split(" ");
        if (keys.length != values.length) throw new IllegalStateException("Invalid Streams accessor mapping");
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(name)) return type.getMethod(values[i]);
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean is2162(Class<?> type) {
        if (type.getName().equals("dev.streamsreflowing.core.river.LicensedCodeNotForAnalysis_001")) return true;
        String marker = switch (type.getName()) {
            case "dev.streamsreflowing.core.config.StreamsConfig" -> "seaLevel";
            case "dev.streamsreflowing.core.config.HydraulicGeometry" -> "depthRatio";
            case "dev.streamsreflowing.core.compose.ColumnPlan" -> "width";
            default -> null;
        };
        if (marker == null) return false;
        try { type.getMethod(marker); return false; }
        catch (NoSuchMethodException renamed) { return true; }
    }

    public static Method accessor(Class<?> type, String name) throws NoSuchMethodException {
        for (Component component : LAYOUTS.get(type).components) {
            if (component.name.equals(name)) return component.accessor;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    public static Object read(Object record, String name) {
        for (Component component : LAYOUTS.get(record.getClass()).components) {
            if (component.name.equals(name)) {
                try { return component.accessor.invoke(record); }
                catch (ReflectiveOperationException e) { throw incompatible(record.getClass(), e); }
            }
        }
        throw new IllegalStateException("Missing Streams record field " + record.getClass().getName() + "." + name);
    }

    public static double number(Object record, String name) { return ((Number) read(record, name)).doubleValue(); }

    public static Object copy(Object record, Map<String, ?> replacements) {
        Layout layout = LAYOUTS.get(record.getClass());
        Object[] arguments = new Object[layout.components.length];
        Set<String> missing = new HashSet<>(replacements.keySet());
        try {
            for (int i = 0; i < arguments.length; i++) {
                Component component = layout.components[i];
                arguments[i] = replacements.containsKey(component.name)
                        ? replacements.get(component.name) : component.accessor.invoke(record);
                missing.remove(component.name);
            }
            if (!missing.isEmpty()) throw new IllegalStateException("Missing Streams record fields " + missing);
            return layout.constructor.newInstance(arguments);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw incompatible(record.getClass(), e);
        }
    }

    private static IllegalStateException incompatible(Class<?> type, Exception cause) {
        return new IllegalStateException("Streams adapter layout changed for " + type.getName()
                + "; validate the TD natural-river adapter before generating this world", cause);
    }
}
