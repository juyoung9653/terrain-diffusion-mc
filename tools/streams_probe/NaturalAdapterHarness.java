package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalConfig;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsWatershedNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverProfile;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small native-JAR smoke harness; it has no Minecraft or NeoForge dependency. */
public final class NaturalAdapterHarness {
    public record LocalRecord(int known) { }
    private static final String[] CONFIG_ACCESSORS = {
            "seaLevel", "plateScale", "plateJitter", "gridSpacing", "terrainSampleSpacing", "valleySnapRadius",
            "minRiverAccumulation", "baseWidth", "maxWidth", "widthScale", "streamDepth", "stepDeltaY",
            "bankWidth", "bankSlope", "bankNoise", "bankSteepFactor", "ditchInset", "aRiver", "borderMargin",
            "smoothingPasses", "meanderStrength", "seaLevelExtension", "reconnectRadius", "wetnessDryCutoff",
            "wetnessWetReference", "connectNearbyWater", "connectRadius", "sinkRooted", "growFromWater",
            "growDensity", "confineToBanks", "containmentBerm", "wetnessMultiplier", "wetnessDryFloor",
            "minSeaChainNodes", "minLengthScope", "lakeMinArea", "lakeMaxArea", "outletBiomes", "hydraulics"
    };
    private static final String[] COLUMN_ACCESSORS = {
            "inChannel", "inCorridor", "waterSurfaceY", "bedY", "bankBlend", "distance", "width", "gradeY",
            "slope", "flowDirX", "flowDirZ", "terminal", "source", "upstreamSlope", "fluidFlowDirX",
            "fluidFlowDirZ", "distToOutlet", "curvature", "lateral", "distToJunction", "flatRun", "speed"
    };
    private NaturalAdapterHarness() { }

    public static void main(String[] args) throws Exception {
        Class<?> configClass = Class.forName("dev.streamsreflowing.core.config.StreamsConfig");
        Method defaults = configClass.getMethod("defaults");
        Object original = defaults.invoke(null);
        verifyNativeSamplingSettings(original);
        for (int scale : new int[]{2, 5}) verifyVersionDispatch(original, scale);
        verifyNetworkRefinement(original);
        verifyLakeGuard(original);
        verifyWatershedSelection(original);
        verifyColumnPlanRoundTrip(original);
        require(StreamsRecordAccess.copy(new LocalRecord(1), Map.of("known", 2)).equals(new LocalRecord(2)),
                "real Java record layout supports known replacements");
        boolean failedClosed = false;
        try { StreamsRecordAccess.copy(new LocalRecord(1), Map.of("notAStreamsField", 1)); }
        catch (IllegalStateException expected) { failedClosed = true; }
        require(failedClosed, "unknown record replacement must fail closed");
        System.out.println("NaturalAdapterHarness: PASS " + configClass.getProtectionDomain().getCodeSource().getLocation());
    }

    private static void verifyNativeSamplingSettings(Object original) throws Exception {
        try { original.getClass().getMethod("terrainFit"); }
        catch (NoSuchMethodException legacy) { return; }
        Map<String, Object> settings = Map.of("terrainFit", 3, "lakeSampleSpacing", 24,
                "legacyShaping", false, "adaptiveGridRead", true);
        Object selected = StreamsRecordAccess.copy(original, settings);
        for (int version = 1; version <= 10; version++) {
            Object adapted = StreamsNaturalConfig.apply(selected, 2, version);
            for (var entry : settings.entrySet()) {
                require(Objects.equals(StreamsRecordAccess.read(adapted, entry.getKey()), entry.getValue()),
                        "v" + version + " preserves native " + entry.getKey());
            }
        }
    }

    private static void verifyVersionDispatch(Object original, int scale) {
        Map<String, Object> originalValues = configValues(original);
        Object legacy = StreamsNaturalConfig.apply(original, scale);
        Object v1 = StreamsNaturalConfig.apply(original, scale, 1);
        require(v1 != original, "v1 adapter must return a copied config");
        require(v1.equals(legacy), "explicit v1 must equal the legacy two-argument profile");
        require(configValues(v1).equals(configValues(legacy)), "explicit v1 must preserve every config accessor");
        require(configValues(original).equals(originalValues), "v1 must not mutate the native source config");

        double originalAccumulation = number(original, "minRiverAccumulation");
        require(number(v1, "minRiverAccumulation") == Math.max(24.0, originalAccumulation), "v1 min accumulation");
        int grid = (int) number(original, "gridSpacing");
        int requiredBlocks = 256 * Math.max(2, Math.min(6, scale)) / 2;
        require((int) number(v1, "minSeaChainNodes") == (int) Math.ceil(requiredBlocks / (double) grid), "v1 coastal length");
        require((int) number(v1, "minLengthScope") == 1, "v1 scope");
        require(Boolean.TRUE.equals(StreamsRecordAccess.read(v1, "confineToBanks")), "v1 bank confinement");
        Object hydraulics = StreamsRecordAccess.read(v1, "hydraulics");
        require(number(hydraulics, "bendAsymmetry") == .80, "bend asymmetry");
        require(number(hydraulics, "channelBars") == .15, "channel bars");
        for (String name : new String[]{"seaLevel", "gridSpacing", "lakeMinArea", "lakeMaxArea", "baseWidth", "maxWidth", "widthScale"}) {
            require(StreamsRecordAccess.read(original, name).equals(StreamsRecordAccess.read(v1, name)), "v1 unchanged " + name);
        }
        require(NaturalRiverProfile.minimumCoastalNodes(grid, scale) * grid >= requiredBlocks, "node conversion rounds up");

        Object v2 = StreamsNaturalConfig.apply(original, scale, 2);
        require(v2 != original, "v2 adapter must return a copied config");
        require(number(v2, "minRiverAccumulation") > number(v1, "minRiverAccumulation"), "v2 raises effective threshold");
        require(number(v2, "growDensity") > number(v1, "growDensity"), "v2 raises growth density");
        require(number(v2, "widthScale") < number(v1, "widthScale"), "v2 lowers accumulation width coefficient");
        for (String name : new String[]{"maxWidth", "baseWidth", "gridSpacing", "wetnessDryCutoff", "wetnessWetReference",
                "seaLevel", "lakeMinArea", "lakeMaxArea", "minLengthScope"}) {
            require(Objects.equals(StreamsRecordAccess.read(v2, name), StreamsRecordAccess.read(v1, name)), "v2 retains " + name);
        }
        require(number(StreamsRecordAccess.read(v2, "hydraulics"), "widthSlope") == .25, "v2 hydraulic width slope");
        require(number(StreamsRecordAccess.read(v2, "hydraulics"), "deltaMouths") == .02, "v2 delta mouths");
        require(configValues(original).equals(originalValues), "v2 must not mutate the native source config");

        Object custom = StreamsRecordAccess.copy(original, Map.of("minRiverAccumulation", 500.0, "growDensity", 3.0));
        Object customV2 = StreamsNaturalConfig.apply(custom, scale, 2);
        require(number(customV2, "minRiverAccumulation") == 500.0, "v2 retains a larger native threshold");
        require(number(customV2, "growDensity") == 3.0, "v2 retains a larger native growth density");
        boolean rejectedUnknownVersion = false;
        try { StreamsNaturalConfig.apply(original, scale, NaturalRiverProfile.VERSION + 1); }
        catch (IllegalArgumentException expected) { rejectedUnknownVersion = true; }
        require(rejectedUnknownVersion, "profile dispatch must reject an unknown version");
        Object v3 = StreamsNaturalConfig.apply(original, scale, 3);
        require(number(v3, "plateScale") == NaturalRiverProfile.watershedPlateV3((int) number(original, "plateScale"), scale), "v3 expands routing domain");
        require(number(v3, "minRiverAccumulation") == Math.max(6, originalAccumulation), "v3 restores small headwaters");
        require(number(v3, "growDensity") == Math.max(1, number(original, "growDensity")), "v3 growth does not suppress headwaters");
        require(number(v3, "widthScale") == number(original, "widthScale") * .45, "v3 fine channel coefficient");
        require(number(v3, "minSeaChainNodes") == 1, "v3 whole-basin policy owns coastal suppression");
        require(configValues(original).equals(originalValues), "v3 must not mutate original config");
        Object v4 = StreamsNaturalConfig.apply(original, scale, 4);
        require(configValues(v4).equals(configValues(v3)), "v4 keeps the frozen native routing settings; hierarchy/width response is separate");
        Object v5 = StreamsNaturalConfig.apply(original, scale, 5);
        require(configValues(v5).equals(configValues(v4)), "v5 bank finishing must not change routing, widths, water grades or lake settings");
        Object v6 = StreamsNaturalConfig.apply(original, scale, 6);
        require(configValues(v6).equals(configValues(v5)), "v6 regrades the selected graph without changing native routing settings");
        Object v7 = StreamsNaturalConfig.apply(original, scale, 7);
        require(configValues(v7).equals(configValues(v6)), "v7 refines retained widths and dry mud without changing native routing settings");
        Object v8 = StreamsNaturalConfig.apply(original, scale, 8);
        require(number(v8, "minRiverAccumulation") == Math.max(24.0, originalAccumulation),
                "v8 admits native sources at the v1 threshold, not v2's 128");
        for (String accessor : CONFIG_ACCESSORS) {
            if (accessor.equals("minRiverAccumulation")) continue;
            require(Objects.equals(StreamsRecordAccess.read(v8, accessor), StreamsRecordAccess.read(v7, accessor)),
                    "v8 must preserve v7 " + accessor);
        }
        require(number(v7, "minRiverAccumulation") == Math.max(6.0, originalAccumulation),
                "v7 remains frozen when v8 is selected");
        Object customV8 = StreamsNaturalConfig.apply(custom, scale, 8);
        require(number(customV8, "minRiverAccumulation") == 500.0,
                "v8 must preserve a stronger native source threshold");
        double previous = 0;
        for (int i = 0; i <= 3200; i++) {
            double width = i / 100.0, narrowed = NaturalRiverProfile.narrowHalfWidthV4(width);
            require(narrowed >= previous && narrowed <= width + 1e-10, "v4 width response must be monotone and non-widening");
            previous = narrowed;
        }
        require(NaturalRiverProfile.narrowHalfWidthV4(0) == 0 && NaturalRiverProfile.narrowHalfWidthV4(12) == 12,
                "v4 preserves source taper and larger river widths");
    }

    private static Map<String, Object> configValues(Object config) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (String accessor : CONFIG_ACCESSORS) values.put(accessor, StreamsRecordAccess.read(config, accessor));
        return values;
    }

    private static void verifyColumnPlanRoundTrip(Object config) throws Exception {
        ClassLoader loader = config.getClass().getClassLoader();
        Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan", false, loader);
        Class<?>[] signature = new Class<?>[COLUMN_ACCESSORS.length];
        Object[] values = new Object[COLUMN_ACCESSORS.length];
        for (int i = 0; i < COLUMN_ACCESSORS.length; i++) {
            Class<?> type = column.getMethod(COLUMN_ACCESSORS[i]).getReturnType();
            signature[i] = type;
            if (type == boolean.class) values[i] = i % 2 == 0;
            else if (type == int.class) values[i] = 100 + i;
            else values[i] = i + .125D;
        }
        Object original = column.getConstructor(signature).newInstance(values);
        Object copied = StreamsRecordAccess.copy(original, Map.of());
        for (String name : COLUMN_ACCESSORS) {
            require(Objects.equals(StreamsRecordAccess.read(original, name), StreamsRecordAccess.read(copied, name)),
                    "ColumnPlan copy preserves " + name);
        }
        Object narrow = StreamsRecordAccess.copy(original, Map.of("width", .71D, "inChannel", true,
                "lateral", number(original, "lateral") * number(original, "width") / .71D));
        require(number(narrow, "width") == .71D && Boolean.TRUE.equals(StreamsRecordAccess.read(narrow, "inChannel")),
                "raster replacement uses .71 channel layout");
        require(number(narrow, "lateral") == number(original, "lateral") * number(original, "width") / .71D,
                "raster replacement rescales lateral distance");
        for (String name : COLUMN_ACCESSORS) {
            if (name.equals("width") || name.equals("inChannel") || name.equals("lateral")) continue;
            require(Objects.equals(StreamsRecordAccess.read(original, name), StreamsRecordAccess.read(narrow, name)),
                    "raster replacement preserves " + name);
        }
    }

    /** Exercises the native assembly boundary without Minecraft terrain or world generation. */
    private static void verifyNetworkRefinement(Object config) throws Exception {
        ClassLoader loader = config.getClass().getClassLoader();
        Class<?> keyType = Class.forName("dev.streamsreflowing.core.plate.PlateKey", false, loader);
        Class<?> segmentType = Class.forName("dev.streamsreflowing.core.river.RiverSegment", false, loader);
        Class<?> tracerType = Class.forName("dev.streamsreflowing.core.river.NetworkTracer", false, loader);
        Object key = keyType.getConstructor(int.class, int.class).newInstance(0, 0);
        var segment = segmentType.getConstructor(double.class, double.class, double.class, double.class,
                double.class, double.class, double.class, double.class, double.class, double.class);
        List<Object> segments = List.of(segment.newInstance(0D, 0D, 80D, 2D, 10D, 0D, 79.8D, 2D, 1D, 1D),
                segment.newInstance(10D, 0D, 79.8D, 2D, 20D, 0D, 79D, 2D, 1D, 1D));
        Object network = assemble(tracerType, key, segments, config, loader);
        Class<?> terrainType = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false, loader);
        Object terrain = Proxy.newProxyInstance(loader, new Class<?>[]{terrainType}, (proxy, method, args) -> {
            if (method.getReturnType() == double.class) return 80D;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
        Object refined = StreamsNaturalNetwork.refine(network, terrain, config);
        require(refined.getClass() == network.getClass(), "refinement must return a native RiverNetwork");
        List<?> output = (List<?>) refined.getClass().getMethod("segments").invoke(refined);
        require(output.size() == 2, "segment count retained");
        require(number(output.get(0), "y2") == number(output.get(1), "y1"), "shared node grade corrected together");
        require(refined != network && number(output.get(0), "y2") == 79.5D,
                "gentle kink must actually be lowered and reassembled");
        require(refined.getClass().getMethod("lakes").invoke(refined).equals(network.getClass().getMethod("lakes").invoke(network)), "lakes retained");
        // Newer Streams has these metadata accessors; if present, their values must survive reassembly.
        for (String name : new String[]{"sinks", "relief"}) try {
            require(Objects.equals(refined.getClass().getMethod(name).invoke(refined), network.getClass().getMethod(name).invoke(network)), name + " retained");
        } catch (NoSuchMethodException ignored) { }
    }

    private static Object assemble(Class<?> tracer, Object key, List<Object> segments, Object config, ClassLoader loader) throws Exception {
        return assemble(tracer, key, segments, List.of(), config, loader);
    }

    private static Object assemble(Class<?> tracer, Object key, List<Object> segments, List<Object> lakes, Object config, ClassLoader loader) throws Exception {
        // Prefer the current full metadata assembly contract when it exists.
        for (Method method : tracer.getMethods()) {
            if (!method.getName().equals("assemble")) continue;
            if (method.getParameterCount() == 6) {
                Class<?> sink = Class.forName("dev.streamsreflowing.core.api.Sink", false, loader);
                Object oneSink = sink.getConstructor(double.class, double.class, double.class, double.class, double.class, double.class)
                        .newInstance(0D, 0D, 80D, 1D, 1D, 1D);
                Class<?> relief = Class.forName("dev.streamsreflowing.core.Relief", false, loader);
                var reliefConstructor = relief.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, float[].class);
                reliefConstructor.setAccessible(true);
                Object oneCellRelief = reliefConstructor.newInstance(0, 0, 1, 1, 1, new float[]{0F});
                return method.invoke(null, key, segments, lakes, List.of(oneSink), oneCellRelief, config);
            }
        }
        for (Method method : tracer.getMethods()) {
            if (method.getName().equals("assemble") && method.getParameterCount() == 4)
                return method.invoke(null, key, segments, lakes, config);
        }
        throw new AssertionError("no compatible native NetworkTracer.assemble method");
    }

    private static void verifyLakeGuard(Object config) throws Exception {
        ClassLoader loader = config.getClass().getClassLoader();
        Class<?> keyType = Class.forName("dev.streamsreflowing.core.plate.PlateKey", false, loader);
        Class<?> segmentType = Class.forName("dev.streamsreflowing.core.river.RiverSegment", false, loader);
        Class<?> tracerType = Class.forName("dev.streamsreflowing.core.river.NetworkTracer", false, loader);
        Class<?> lakeType = Class.forName("dev.streamsreflowing.core.river.Lake", false, loader);
        Object key = keyType.getConstructor(int.class, int.class).newInstance(0, 0);
        var segment = segmentType.getConstructor(double.class, double.class, double.class, double.class,
                double.class, double.class, double.class, double.class, double.class, double.class);
        List<Object> segments = List.of(segment.newInstance(0D, 0D, 80D, 2D, 10D, 0D, 79.8D, 2D, 1D, 1D),
                segment.newInstance(10D, 0D, 79.8D, 2D, 20D, 0D, 79D, 2D, 1D, 1D));
        // level, dig depth, spacing, cell origin X/Z, one cell wide/high, occupied mask.
        Object lake = lakeType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class, int.class, long[].class)
                .newInstance(70, 1, 1, 10, 0, 1, 1, new long[]{1L});
        Object network = assemble(tracerType, key, segments, List.of(lake), config, loader);
        Class<?> terrainType = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false, loader);
        Object terrain = Proxy.newProxyInstance(loader, new Class<?>[]{terrainType}, (proxy, method, args) ->
                method.getReturnType() == double.class ? 80D : method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null);
        Object refined = StreamsNaturalNetwork.refine(network, terrain, config);
        List<?> output = (List<?>) refined.getClass().getMethod("segments").invoke(refined);
        require(number(output.get(0), "y2") == 79.8D && number(output.get(1), "y1") == 79.8D,
                "lake guard must retain the degree-one interior grade exactly");
        require(refined.getClass().getMethod("lakes").invoke(refined).equals(List.of(lake)), "lake guard retains lake metadata");
    }

    private static void verifyWatershedSelection(Object original) throws Exception {
        Object config = StreamsNaturalConfig.apply(original, 5, 3);
        ClassLoader loader = config.getClass().getClassLoader();
        Class<?> keyType = Class.forName("dev.streamsreflowing.core.plate.PlateKey", false, loader);
        Class<?> latticeType = Class.forName("dev.streamsreflowing.core.plate.PlateLattice", false, loader);
        Class<?> domainType = Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false, loader);
        Class<?> segmentType = Class.forName("dev.streamsreflowing.core.river.RiverSegment", false, loader);
        Class<?> tracer = Class.forName("dev.streamsreflowing.core.river.NetworkTracer", false, loader);
        Object key = keyType.getConstructor(int.class, int.class).newInstance(0, 0);
        Object lattice = latticeType.getConstructor(long.class, double.class, double.class).newInstance(1L, 5120D, 0D);
        Object domain = domainType.getConstructor(latticeType, keyType).newInstance(lattice, key);
        var segment = segmentType.getConstructor(double.class, double.class, double.class, double.class,
                double.class, double.class, double.class, double.class, double.class, double.class);
        List<Object> segments = List.of(
                segment.newInstance(1000D,2560D,80D,1D,2000D,2560D,70D,2D,1D,1D),
                segment.newInstance(2000D,3500D,80D,1D,2000D,2560D,70D,2D,1D,1D),
                segment.newInstance(2000D,2560D,70D,3D,4000D,2560D,62D,5D,1D,1D),
                segment.newInstance(4000D,2560D,62D,5D,4000.001D,2560D,62D,5D,1D,1D),
                segment.newInstance(3000D,3500D,90D,1D,3000D,3500D,90D,1D,1D,1D),
                segment.newInstance(2400D,3000D,63D,1D,2500D,3000D,62D,1D,1D,1D));
        Object network = assemble(tracer, key, segments, config, loader);
        // Verify both a surviving terminal's sink and a discarded terminal's sink.
        try {
            List<?> oldSinks = (List<?>) network.getClass().getMethod("sinks").invoke(network);
            Object sink = oldSinks.getFirst();
            for (Method method : tracer.getMethods()) if (method.getName().equals("assemble") && method.getParameterCount() == 6) {
                network = method.invoke(null, key, segments, List.of(), List.of(
                        sink.getClass().getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                                .newInstance(4000D,2560D,62D,5D,100D,3000D),
                        sink.getClass().getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                                .newInstance(2000D,2560D,70D,2D,50D,1000D),
                        sink.getClass().getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                                .newInstance(3000D,3500D,90D,1D,6D,0D),
                        sink.getClass().getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                                .newInstance(2500D,3000D,62D,1D,6D,100D)),
                        network.getClass().getMethod("relief").invoke(network), config);
                break;
            }
        } catch (NoSuchMethodException ignored) { }
        Class<?> terrainType = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false, loader);
        Object terrain = Proxy.newProxyInstance(loader, new Class<?>[]{terrainType}, (proxy, method, args) -> {
            if (method.getReturnType() == double.class) return 80D;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
        Object selected = StreamsWatershedNetwork.select(network, domain, terrain, config, (x,z) -> true);
        List<?> retained = (List<?>) selected.getClass().getMethod("segments").invoke(selected);
        require(retained.equals(segments.subList(0,5)), "v3 retains long merging hierarchy and removes small complete marine basin");
        for (int i=0;i<5;i++) require(retained.get(i) == segments.get(i), "selection preserves native geometry object");
        require(selected != network, "changed subset must rebuild native index");
        try {
            List<?> sinks = (List<?>) selected.getClass().getMethod("sinks").invoke(selected);
            List<?> originalSinks = (List<?>) network.getClass().getMethod("sinks").invoke(network);
            require(sinks.size()==3 && sinks.equals(originalSinks.subList(0,3)),
                    "retain original junction, terminal-self-edge and unknown self-only placeholder sinks in order");
            require(selected.getClass().getMethod("relief").invoke(selected)==network.getClass().getMethod("relief").invoke(network), "v3 preserves relief");
        } catch (NoSuchMethodException ignored) { }
        require(StreamsWatershedNetwork.select(network,domain,terrain,config,(x,z)->false)==network, "unknown inland terminals fail open");
        Object border = segment.newInstance(10D,1000D,63D,1D,100D,1000D,62D,1D,1D,1D);
        Object borderNetwork = assemble(tracer,key,List.of(border),config,loader);
        require(StreamsWatershedNetwork.select(borderNetwork,domain,terrain,config,(x,z)->true)==borderNetwork, "plate boundary basin stays available for native cross-plate joining");
        Class<?> lakeType = Class.forName("dev.streamsreflowing.core.river.Lake", false, loader);
        Object lake = lakeType.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class,int.class,long[].class)
                .newInstance(62,1,1,2450,3000,1,1,new long[]{1L});
        Object lakeNetwork = assemble(tracer,key,segments,List.of(lake),config,loader);
        require(StreamsWatershedNetwork.select(lakeNetwork,domain,terrain,config,(x,z)->true)==lakeNetwork, "lake crossing protects full component before coastal filtering");
        List<Object> lakeTree = List.of(
                segment.newInstance(1000D,2000D,80D,1D,2000D,2000D,70D,2D,1D,1D),
                segment.newInstance(2000D,2100D,75D,1D,2000D,2000D,70D,2D,1D,1D),
                segment.newInstance(2000D,2000D,70D,3D,4000D,2000D,62D,5D,1D,1D));
        Object downstreamLake=lakeType.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class,int.class,long[].class)
                .newInstance(66,1,1,3000,2000,1,1,new long[]{1L});
        Object protectedTree=assemble(tracer,key,lakeTree,List.of(downstreamLake),config,loader);
        Object trimmedTree=StreamsWatershedNetwork.select(protectedTree,domain,terrain,config,(x,z)->true);
        require(trimmedTree.getClass().getMethod("segments").invoke(trimmedTree).equals(List.of(lakeTree.get(0),lakeTree.get(2))),
                "native lake-protected downstream reach permits safe short tributary pruning");
        require(trimmedTree.getClass().getMethod("lakes").invoke(trimmedTree).equals(List.of(downstreamLake)),
                "native lake metadata remains after twig pruning");
    }

    private static double number(Object record, String field) { return StreamsRecordAccess.number(record, field); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
