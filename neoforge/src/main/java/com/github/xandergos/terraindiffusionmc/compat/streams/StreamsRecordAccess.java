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
                String[] names = type.isRecord()
                        ? java.util.Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new)
                        : auditedComponents(type);
                Component[] components = new Component[names.length];
                Class<?>[] signature = new Class<?>[names.length];
                for (int i = 0; i < names.length; i++) {
                    Method accessor = type.getMethod(names[i]);
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
            case "dev.streamsreflowing.core.river.RiverSegment" -> "x1 z1 y1 w1 x2 z2 y2 w2 sp1 sp2";
            case "dev.streamsreflowing.core.compose.ColumnPlan" ->
                    "inChannel inCorridor waterSurfaceY bedY bankBlend distance width gradeY slope flowDirX flowDirZ "
                    + "terminal source upstreamSlope fluidFlowDirX fluidFlowDirZ distToOutlet curvature lateral distToJunction flatRun speed";
            default -> throw new IllegalStateException("Unsupported Streams record: " + type.getName());
        };
        return fields.split(" ");
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
