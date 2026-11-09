package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Real-world evidence for V12's deliberately small river roof masks.
 *
 * <p>The planner may run on a native graph before chunks exist.  This audit first
 * obtains one bounded plan, then drives every chunk touching it to FULL through
 * {@link ServerLevel#getChunk(int, int)} and reads those blocks.  It therefore
 * cannot pass from a planner result, a write counter, or a proxy WorldGenLevel.
 * A fixture with no qualified plan fails deliberately: this is an acceptance
 * audit, not a census.</p>
 */
final class ShortRiverTunnelAudit {
    private static final String RUNTIME =
            "com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShortRiverTunnels";

    private ShortRiverTunnelAudit() { }

    static Prepared prepare(ServerLevel level) throws Exception {
        require(WorldNaturalRiverState.enabled(level) && WorldNaturalRiverState.version(level) >= 12,
                "short tunnel audit requires an enabled V12 natural-river world");
        require(level.getMinBuildHeight() == -64,
                "short tunnel fixture must use the default surface height range");
        int centerX = integer("td.streamsProbe.shortTunnelCenterX", level.getSharedSpawnPos().getX());
        int centerZ = integer("td.streamsProbe.shortTunnelCenterZ", level.getSharedSpawnPos().getZ());
        int radius = bounded("td.streamsProbe.shortTunnelRadius", 768, 96, 2048);
        List<?> plans = plans(level, centerX, centerZ, radius);
        if (plans.isEmpty()) {
            Path exported = exportRejectedInputs(level, centerX, centerZ, radius);
            throw new IllegalStateException("no qualified bounded short river tunnel in audit region; inputs=" + exported);
        }
        Object tunnel = plans.stream().min(Comparator.comparingDouble(ShortRiverTunnelAudit::length)).orElseThrow();
        List<?> points = points(tunnel);
        require(points.size() >= 3, "tunnel has no interior samples");
        double length = length(tunnel);
        require(length > 0.0 && length <= maxTunnelLength() + 0.000001,
                "invalid/unbounded tunnel length " + length);

        // This is a real FULL-generation request.  The selected plan is small,
        // but allow a one-chunk margin for native carver and bank passes.
        int minChunkX = Integer.MAX_VALUE, minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE, maxChunkZ = Integer.MIN_VALUE;
        for (Object point : points) {
            int x = floor(number(point, "x")), z = floor(number(point, "z"));
            minChunkX = Math.min(minChunkX, (x >> 4) - 1); maxChunkX = Math.max(maxChunkX, (x >> 4) + 1);
            minChunkZ = Math.min(minChunkZ, (z >> 4) - 1); maxChunkZ = Math.max(maxChunkZ, (z >> 4) + 1);
        }
        boolean reverseChunkOrder = Boolean.getBoolean("td.streamsProbe.shortTunnelReverseChunkOrder");
        int generated = 0;
        for (int zi = 0; zi <= maxChunkZ - minChunkZ; zi++) for (int xi = 0; xi <= maxChunkX - minChunkX; xi++) {
            int z = reverseChunkOrder ? maxChunkZ - zi : minChunkZ + zi;
            int x = reverseChunkOrder ? maxChunkX - xi : minChunkX + xi;
            LevelChunk chunk = level.getChunk(x, z);
            require(chunk.getPos().equals(new ChunkPos(x, z)), "wrong FULL chunk " + x + "," + z);
            generated++;
        }
        List<ChunkPos> chunks = new ArrayList<>();
        for (int z = minChunkZ; z <= maxChunkZ; z++) for (int x = minChunkX; x <= maxChunkX; x++)
            chunks.add(new ChunkPos(x, z));
        return new Prepared(tunnel, points, plans.size(), length, generated, reverseChunkOrder,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ, List.copyOf(chunks));
    }

    static String inspect(ServerLevel level, Prepared prepared, long settledTicks) throws Exception {
        Object tunnel = prepared.tunnel;
        List<?> points = prepared.points;
        int planCount = prepared.planCount;
        double length = prepared.length;
        int generated = prepared.generated;
        boolean reverseChunkOrder = prepared.reverseChunkOrder;

        int portalEntryX = floor(number(points.getFirst(), "x")), portalEntryZ = floor(number(points.getFirst(), "z"));
        int portalExitX = floor(number(points.getLast(), "x")), portalExitZ = floor(number(points.getLast(), "z"));
        WaterColumn entry = waterNear(level, portalEntryX, portalEntryZ, floor(number(points.getFirst(), "water")));
        WaterColumn exit = waterNear(level, portalExitX, portalExitZ, floor(number(points.getLast(), "water")));
        require(!entry.ambiguous && !exit.ambiguous, "ambiguous portal water column");
        int entryWater = entry.surfaceY, exitWater = exit.surfaceY;
        require(entryWater != Integer.MIN_VALUE && exitWater != Integer.MIN_VALUE,
                "both native tunnel portals must contain water");
        require(level.canSeeSky(new BlockPos(portalEntryX, entryWater + 1, portalEntryZ))
                        && level.canSeeSky(new BlockPos(portalExitX, exitWater + 1, portalExitZ)),
                "tunnel portals must remain surface-open");
        require(entryWater > exitWater, "actual downstream portal is not lower: " + entryWater + " <= " + exitWater);

        int maskMinX = Integer.MAX_VALUE, maskMinZ = Integer.MAX_VALUE;
        int maskMaxX = Integer.MIN_VALUE, maskMaxZ = Integer.MIN_VALUE;
        for (Object point : points) {
            int reach = (int) Math.ceil(number(point, "halfWidth")) + 10;
            int x = floor(number(point, "x")), z = floor(number(point, "z"));
            maskMinX = Math.min(maskMinX, x - reach); maskMaxX = Math.max(maskMaxX, x + reach);
            maskMinZ = Math.min(maskMinZ, z - reach); maskMaxZ = Math.max(maskMaxZ, z + reach);
        }
        int interior = 0, maskedColumns = 0;
        Object nativeColumns = nativeColumns(level,portalEntryX,portalEntryZ);
        Method nativeRoof = nativeColumns.getClass().getDeclaredMethod("roof",ShortRiverTunnelPlanner.Tunnel.class,int.class,int.class);
        nativeRoof.setAccessible(true);
        Object engine=Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                .getMethod("engine",net.minecraft.world.level.WorldGenLevel.class).invoke(null,level);
        Method columnPlan=engine.getClass().getMethod("plan",int.class,int.class);
        Path output = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-short-river-tunnel-audit.csv");
        List<String> csv = new ArrayList<>();
        csv.add("kind,x,z,plannedWaterY,actualWaterY,roofY,wet,headroom,roofSolid,skyVisible");
        csv.add("portal_entry," + portalEntryX + ',' + portalEntryZ + ',' + floor(number(points.getFirst(), "water")) + ','
                + entryWater + ",open,true,true,false,true");
        csv.add("portal_exit," + portalExitX + ',' + portalExitZ + ',' + floor(number(points.getLast(), "water")) + ','
                + exitWater + ",open,true,true,false,true");
        for (int z = maskMinZ; z <= maskMaxZ; z++) for (int x = maskMinX; x <= maskMaxX; x++) {
            int estimatedWater = projectedWater(points, x, z);
            int plannedRoof = ((Number)nativeRoof.invoke(nativeColumns,tunnel,x,z)).intValue();
            if (plannedRoof == Integer.MIN_VALUE) continue; // intentional open portal/transition
            WaterColumn column = waterNear(level, x, z, estimatedWater);
            int waterY = column.surfaceY;
            int roofY = plannedRoof;
            maskedColumns++;
            boolean wet = waterY != Integer.MIN_VALUE;
            boolean headroom = wet && roofY-waterY>=4 && headroom(level, x, z, waterY, roofY);
            boolean roofSolid = level.getBlockState(new BlockPos(x, roofY, z)).isSolid();
            boolean sky = wet && level.canSeeSky(new BlockPos(x, waterY + 1, z));
            Object live=columnPlan.invoke(engine,x,z);
            // Native bowl edges can be dry even inside inChannel: rounding leaves zero depth there.
            // Test the central half of the actual native channel; the complete entry/exit water path
            // is independently checked with a six-connected block flood fill below.
            boolean core = (Boolean)StreamsRecordAccess.read(live,"inChannel")
                    && StreamsRecordAccess.number(live,"distance")<=StreamsRecordAccess.number(live,"width")*.5;
            csv.add((core ? "mask_core" : "mask_apron") + ',' + x + ',' + z + ',' + estimatedWater + ',' + waterY + ',' + roofY + ','
                    + wet + ',' + headroom + ',' + roofSolid + ',' + sky);
            // Every model mask column, including dry-bank apron, must retain a
            // solid roof.  Only the enclosed channel core must be wet/traversable.
            if(!roofSolid || (core && (!wet || !headroom || sky || column.ambiguous))) Files.write(output,csv);
            require(roofSolid, "roof was not retained at " + x + "," + z + " roofY=" + roofY
                    + " block=" + level.getBlockState(new BlockPos(x,roofY,z)) + " water=" + waterY + " retained=" + retainedWrites());
            if (!core) continue;
            require(!column.ambiguous, "ambiguous core water column at " + x + "," + z);
            interior++;
            require(wet && headroom && roofSolid && !sky,
                    "physical roof-mask failure at " + x + "," + z + " wet=" + wet
                            + " headroom=" + headroom + " roof=" + roofSolid + " sky=" + sky);
        }
        require(maskedColumns > interior && interior >= 2,
                "missing full mask/apron or enclosed channel-core cross section");
        Connectivity connectivity = connectedWater(level, portalEntryX, entryWater, portalEntryZ,
                portalExitX, exitWater, portalExitZ, maskMinX, maskMaxX, maskMinZ, maskMaxZ);
        csv.add("connectivity," + connectivity.connected + ",visited=" + connectivity.visited
                + ",closestBoundary=" + connectivity.closestBoundary);
        // Persist the complete current mask before any connectivity assertion so a failed
        // acceptance run cannot leave a stale partial CSV from an earlier failure.
        Files.write(output, csv);
        require(connectivity.connected, "water path is not face-connected from entry to exit; visited="
                + connectivity.visited + " closestBoundary=" + connectivity.closestBoundary);
        long retained = retainedWrites();
        String planHash = sha256(planText(tunnel));
        String sampleHash = sha256(String.join("\n", csv));
        Path baseline = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-short-river-tunnel-audit-baseline.txt");
        boolean first = !Files.exists(baseline);
        if (first) {
            require(retained > 0, "first generation retained no roof writes; an existing cave cannot prove the feature");
            Files.writeString(baseline, planHash + "\n" + sampleHash + "\n", StandardCharsets.UTF_8);
        } else {
            List<String> prior = Files.readAllLines(baseline, StandardCharsets.UTF_8);
            require(prior.size() == 2 && prior.get(0).equals(planHash) && prior.get(1).equals(sampleHash),
                    "restart tunnel plan or physical samples changed");
        }
        return json("passed", planCount, length, generated, interior, output.getFileName().toString(),
                first, retained, planHash, sampleHash, reverseChunkOrder, settledTicks);
    }

    /** Captures every protected native-water column before the probe permits fluid ticks. */
    static String capturePreTick(ServerLevel level, Prepared prepared) throws Exception {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object point : prepared.points) {
            int reach = (int) Math.ceil(number(point, "halfWidth")) + 10;
            int x = floor(number(point, "x")), z = floor(number(point, "z"));
            minX = Math.min(minX, x - reach); maxX = Math.max(maxX, x + reach);
            minZ = Math.min(minZ, z - reach); maxZ = Math.max(maxZ, z + reach);
        }
        Object columns = nativeColumns(level, floor(number(prepared.points.getFirst(), "x")),
                floor(number(prepared.points.getFirst(), "z")));
        Method roof = columns.getClass().getDeclaredMethod("roof", ShortRiverTunnelPlanner.Tunnel.class, int.class, int.class);
        roof.setAccessible(true);
        List<String> rows = new ArrayList<>();
        rows.add("kind,x,z,plannedWaterY,actualWaterY,roofY,wet,skyVisible");
        int protectedColumns = 0, wetColumns = 0;
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            int roofY = ((Number) roof.invoke(columns, prepared.tunnel, x, z)).intValue();
            if (roofY == Integer.MIN_VALUE) continue;
            protectedColumns++;
            WaterColumn water = waterNear(level, x, z, projectedWater(prepared.points, x, z));
            boolean wet = water.surfaceY != Integer.MIN_VALUE;
            if (wet) wetColumns++;
            rows.add("mask," + x + ',' + z + ',' + projectedWater(prepared.points, x, z) + ','
                    + water.surfaceY + ',' + roofY + ',' + wet + ','
                    + (wet && level.canSeeSky(new BlockPos(x, water.surfaceY + 1, z))));
        }
        int sx = floor(number(prepared.points.getFirst(), "x")), sz = floor(number(prepared.points.getFirst(), "z"));
        int ex = floor(number(prepared.points.getLast(), "x")), ez = floor(number(prepared.points.getLast(), "z"));
        WaterColumn entry = waterNear(level, sx, sz, floor(number(prepared.points.getFirst(), "water")));
        WaterColumn exit = waterNear(level, ex, ez, floor(number(prepared.points.getLast(), "water")));
        Connectivity path = entry.surfaceY == Integer.MIN_VALUE || exit.surfaceY == Integer.MIN_VALUE
                ? new Connectivity(false, 0, "missing_portal_water")
                : connectedWater(level, sx, entry.surfaceY, sz, ex, exit.surfaceY, ez, minX, maxX, minZ, maxZ);
        rows.add("connectivity,,,,,,," + path.connected + ";visited=" + path.visited + ";closest=" + path.closestBoundary);
        Path output = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-short-river-tunnel-before-ticks.csv");
        Files.write(output, rows);
        Files.writeString(output.resolveSibling("td-short-river-tunnel-plan.txt"),
                planText(prepared.tunnel), StandardCharsets.UTF_8);
        return "{\"event\":\"short_river_tunnel_pre_ticks\",\"protectedColumns\":\"" + protectedColumns
                + "\",\"wetColumns\":\"" + wetColumns + "\",\"connected\":\"" + path.connected
                + "\",\"visited\":\"" + path.visited + "\",\"closestBoundary\":\""
                + path.closestBoundary + "\",\"csv\":\"" + output.getFileName() + "\"}";
    }

    private static List<?> plans(ServerLevel level, int x, int z, int radius) throws Exception {
        Class<?> type = Class.forName(RUNTIME);
        Method method = type.getMethod("plans", ServerLevel.class, int.class, int.class, int.class);
        Object value = method.invoke(null, level, x, z, radius);
        if (!(value instanceof List<?> list)) throw new IllegalStateException("short tunnel plans must be a List");
        return list;
    }

    private static Object nativeColumns(ServerLevel level,int x,int z) throws Exception {
        Object engine=Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                .getMethod("engine",net.minecraft.world.level.WorldGenLevel.class).invoke(null,level);
        Object config=engine.getClass().getMethod("config").invoke(engine);
        Method plateAt=engine.getClass().getMethod("plateAt",int.class,int.class);
        Object network=engine.getClass().getMethod("networkFor",plateAt.getReturnType()).invoke(engine,plateAt.invoke(engine,x,z));
        Class<?> type=Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShortRiverShortcutNetwork$NativeSample");
        Method factory=type.getDeclaredMethod("of",Object.class,Object.class);
        factory.setAccessible(true);
        return factory.invoke(null,network,config);
    }

    /**
     * Exports the exact bounded 2.16.2 inputs used by the runtime when no plan
     * qualified.  This deliberately does not loosen a predicate or generate a
     * chunk: it gives the next pure-planner iteration raw reaches plus every
     * queried ground/corridor value.
     */
    private static Path exportRejectedInputs(ServerLevel level, int centerX, int centerZ, int radius) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Object engine = worldgen.getMethod("engine", net.minecraft.world.level.WorldGenLevel.class).invoke(null, level);
        Object config = engine.getClass().getMethod("config").invoke(engine);
        Method plateAt = engine.getClass().getMethod("plateAt", int.class, int.class);
        double sea = StreamsRecordAccess.number(config, "seaLevel");
        double inset = StreamsRecordAccess.number(config, "ditchInset");
        Path root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("short-tunnel-inputs-" + centerX + "-" + centerZ + "-r" + radius);
        Files.createDirectories(root);
        IdentityHashMap<Object, Integer> networks = new IdentityHashMap<>();
        int next = 0;
        for (int x = centerX - radius; x <= centerX + radius; x += 64) for (int z = centerZ - radius; z <= centerZ + radius; z += 64) {
            Object plate = plateAt.invoke(engine, x, z);
            Method networkFor = engine.getClass().getMethod("networkFor", plate.getClass());
            Object network = networkFor.invoke(engine, plate);
            if (network != null && !networks.containsKey(network)) networks.put(network, next++);
        }
        int totalReaches = 0, usableWidths = 0, aboveSea = 0, plans = 0;
        StringBuilder summary = new StringBuilder("centerX=" + centerX + "\ncenterZ=" + centerZ + "\nradius=" + radius
                + "\nseaLevel=" + sea + "\nditchInset=" + inset + "\nnetworks=" + networks.size() + "\n");
        for (var entry : networks.entrySet()) {
            int index = entry.getValue(); Object network = entry.getKey();
            @SuppressWarnings("unchecked")
            List<Object> segments = (List<Object>) network.getClass().getMethod("sourceGivenToAuthorizedOnly1").invoke(network);
            List<NaturalRiverMorphology.Reach> reaches = new ArrayList<>();
            StringBuilder reachCsv = new StringBuilder("x1,z1,y1,w1,x2,z2,y2,w2,sp1,sp2\n");
            for (Object segment : segments) {
                NaturalRiverMorphology.Reach reach = new NaturalRiverMorphology.Reach(
                        StreamsRecordAccess.number(segment, "x1"), StreamsRecordAccess.number(segment, "z1"),
                        StreamsRecordAccess.number(segment, "y1"), StreamsRecordAccess.number(segment, "w1"),
                        StreamsRecordAccess.number(segment, "x2"), StreamsRecordAccess.number(segment, "z2"),
                        StreamsRecordAccess.number(segment, "y2"), StreamsRecordAccess.number(segment, "w2"),
                        StreamsRecordAccess.number(segment, "sp1"), StreamsRecordAccess.number(segment, "sp2"));
                reaches.add(reach); totalReaches++;
                if (reach.w1() >= .71 && reach.w2() >= .71 && reach.w1() <= 5 && reach.w2() <= 5) usableWidths++;
                if (reach.y1() - inset > sea && reach.y2() - inset > sea) aboveSea++;
                reachCsv.append(reach.x1()).append(',').append(reach.z1()).append(',').append(reach.y1()).append(',').append(reach.w1()).append(',')
                        .append(reach.x2()).append(',').append(reach.z2()).append(',').append(reach.y2()).append(',').append(reach.w2()).append(',')
                        .append(reach.speed1()).append(',').append(reach.speed2()).append('\n');
            }
            Map<String, Double> groundValues = new LinkedHashMap<>();
            Map<String, Boolean> corridorValues = new LinkedHashMap<>();
            NaturalRiverMorphology.Ground originalGround = StreamsNaturalRiverAdapter.originalTerrain(config);
            NaturalRiverMorphology.Ground ground = (gx, gz) -> {
                double value = originalGround.height(gx, gz);
                groundValues.put(gx + "," + gz, value); return value;
            };
            Method makeDomain = Class.forName(RUNTIME).getDeclaredMethod("domain", Object.class, Object.class);
            makeDomain.setAccessible(true);
            Object domain = makeDomain.invoke(null, engine, network);
            Method contains = domain.getClass().getMethod("proprietary1", double.class, double.class);
            Method borderDistance = domain.getClass().getMethod("seeAiNotice1", double.class, double.class);
            double spacing = StreamsRecordAccess.number(config, "gridSpacing");
            Method lakeNear = network.getClass().getMethod("proprietary1", int.class, int.class, int.class);
            NaturalRiverPlanform.Corridor corridor = (px, pz, bankRadius) -> {
                try {
                    int gx = (int) Math.floor(px), gz = (int) Math.floor(pz);
                    int gr = (int) Math.ceil(bankRadius / Math.max(1.0, spacing)) + 1;
                    boolean allowed = (Boolean) contains.invoke(domain, px, pz)
                            && ((Number) borderDistance.invoke(domain, px, pz)).doubleValue() > bankRadius + 8.0
                            && lakeNear.invoke(network, gx, gz, gr) == null;
                    corridorValues.put(px + "," + pz + "," + bankRadius, allowed); return allowed;
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native lake lookup failed", failure); }
            };
            List<ShortRiverTunnelPlanner.Tunnel> calculated = ShortRiverTunnelPlanner.plan(reaches, ground, corridor,
                    (int) sea, (int) Math.round(inset));
            var shortcuts = com.github.xandergos.terraindiffusionmc.world.river.ShortRiverShortcutPlanner.plan(
                    reaches, ground, corridor, (int)sea, (int)Math.round(inset));
            plans += calculated.size();
            Files.writeString(root.resolve("network-" + index + "-reaches.csv"), reachCsv, StandardCharsets.UTF_8);
            StringBuilder groundCsv = new StringBuilder("x,z,height\n");
            groundValues.forEach((key, value) -> groundCsv.append(key).append(',').append(value).append('\n'));
            Files.writeString(root.resolve("network-" + index + "-ground.csv"), groundCsv, StandardCharsets.UTF_8);
            StringBuilder corridorCsv = new StringBuilder("x,z,radius,allowed\n");
            corridorValues.forEach((key, value) -> corridorCsv.append(key).append(',').append(value).append('\n'));
            Files.writeString(root.resolve("network-" + index + "-corridor.csv"), corridorCsv, StandardCharsets.UTF_8);
            summary.append("network.").append(index).append(".segments=").append(reaches.size())
                    .append("\nnetwork.").append(index).append(".qualifiedPlans=").append(calculated.size())
                    .append("\nnetwork.").append(index).append(".shortcutMasks=").append(shortcuts.tunnels().size()).append('\n');
        }
        summary.append("totalSegments=").append(totalReaches).append("\nusableWidthSegments=").append(usableWidths)
                .append("\naboveSeaSegments=").append(aboveSea).append("\nqualifiedPlans=").append(plans).append('\n');
        Files.writeString(root.resolve("summary.properties"), summary, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("summary.json"), "{\"centerX\":" + centerX + ",\"centerZ\":" + centerZ
                + ",\"radius\":" + radius + ",\"seaLevel\":" + sea + ",\"ditchInset\":" + inset
                + ",\"networks\":" + networks.size() + ",\"totalSegments\":" + totalReaches
                + ",\"usableWidthSegments\":" + usableWidths + ",\"aboveSeaSegments\":" + aboveSea
                + ",\"qualifiedPlans\":" + plans + "}\n", StandardCharsets.UTF_8);
        return root;
    }

    private static List<?> points(Object tunnel) throws Exception {
        Object value = tunnel.getClass().getMethod("centreline").invoke(tunnel);
        if (!(value instanceof List<?> list)) throw new IllegalStateException("tunnel centreline must be a List");
        return list;
    }

    private static double length(Object tunnel) {
        return number(tunnel, "length");
    }

    private static int roof(Object tunnel, int x, int z, int waterY) throws Exception {
        if (waterY == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return ((Number) tunnel.getClass().getMethod("roofFloorY", int.class, int.class, int.class)
                .invoke(tunnel, x, z, waterY)).intValue();
    }

    private static boolean headroom(ServerLevel level, int x, int z, int waterY, int roofY) {
        if (roofY <= waterY + 2) return false;
        for (int y = waterY + 1; y < roofY; y++) if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return false;
        return true;
    }

    /** Finds the exposed top of one water layer; separate layers are rejected. */
    private static WaterColumn waterNear(ServerLevel level, int x, int z, int estimated) {
        int surface = Integer.MIN_VALUE;
        boolean inLayer = false, gapAfterLayer = false, ambiguous = false;
        for (int delta = -4; delta <= 4; delta++) {
            int y = estimated + delta;
            boolean water = y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()
                    && level.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER);
            if (water) {
                if (gapAfterLayer) ambiguous = true;
                inLayer = true;
                surface = y;
            } else if (inLayer) gapAfterLayer = true;
        }
        return new WaterColumn(surface, ambiguous);
    }

    private static int projectedWater(List<?> points, int x, int z) {
        Projection projection = project(points, x, z);
        return floor(projection.water);
    }

    private static boolean channelCore(List<?> points, int x, int z) {
        Projection projection = project(points, x, z);
        return projection.distance <= Math.max(.5, projection.halfWidth);
    }

    /** Exact nearest polyline projection, not the nearest sampled node. */
    private static Projection project(List<?> points, int x, int z) {
        double best = Double.POSITIVE_INFINITY, water = 0.0, width = 0.0;
        for (int i = 1; i < points.size(); i++) {
            Object a = points.get(i - 1), b = points.get(i);
            double ax = number(a, "x"), az = number(a, "z"), bx = number(b, "x"), bz = number(b, "z");
            double dx = bx - ax, dz = bz - az, squared = dx * dx + dz * dz;
            if (squared <= 1.0e-7) continue;
            double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / squared));
            double distance = Math.hypot(x - (ax + dx * t), z - (az + dz * t));
            if (distance < best) {
                best = distance;
                water = number(a, "water") + (number(b, "water") - number(a, "water")) * t;
                width = number(a, "halfWidth") + (number(b, "halfWidth") - number(a, "halfWidth")) * t;
            }
        }
        if (!Double.isFinite(best)) throw new IllegalStateException("invalid tunnel polyline");
        return new Projection(best, water, width);
    }

    private static double maxTunnelLength() {
        try {
            Class<?> planner = Class.forName("com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner");
            return planner.getField("DEFAULT_MAX_LENGTH").getDouble(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("short tunnel planner maximum is unavailable", failure);
        }
    }

    /** Six-connected real water blocks within the bounded planned footprint. */
    /** Six-connected wet path plus a bounded failure witness for the probe receipt. */
    private static Connectivity connectedWater(ServerLevel level, int sx, int sy, int sz, int ex, int ey, int ez,
                                          int minX, int maxX, int minZ, int maxZ) {
        BlockPos start = new BlockPos(sx, sy, sz), target = new BlockPos(ex, ey, ez);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start); seen.add(start);
        int minY = Math.min(sy, ey) - 8, maxY = Math.max(sy, ey) + 8;
        String closest = "none";
        int closestDistance = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(target) || pos.getX() == ex && pos.getZ() == ez && Math.abs(pos.getY() - ey) <= 1)
                return new Connectivity(true, seen.size(), "reached");
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (next.getX() < minX || next.getX() > maxX || next.getZ() < minZ || next.getZ() > maxZ
                        || next.getY() < minY || next.getY() > maxY || seen.contains(next)) continue;
                if (level.getBlockState(next).getFluidState().is(FluidTags.WATER)) {
                    seen.add(next); queue.addLast(next);
                } else {
                    int distance = Math.abs(next.getX() - ex) + Math.abs(next.getY() - ey) + Math.abs(next.getZ() - ez);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closest = next.getX() + "," + next.getY() + "," + next.getZ() + ":"
                                + level.getBlockState(next).toString();
                    }
                }
            }
        }
        return new Connectivity(false, seen.size(), closest);
    }

    private static long retainedWrites() throws Exception {
        Class<?> type = Class.forName(RUNTIME);
        return ((Number) type.getMethod("retainedWrites").invoke(null)).longValue();
    }

    private static String planText(Object tunnel) throws Exception {
        StringBuilder text = new StringBuilder(Double.toString(length(tunnel))).append('\n');
        text.append(number(tunnel, "coveredStart")).append(',')
                .append(number(tunnel, "coveredEnd")).append('\n');
        for (Object point : points(tunnel)) text.append(number(point, "x")).append(',')
                .append(number(point, "z")).append(',').append(number(point, "water")).append(',')
                .append(number(point, "halfWidth")).append('\n');
        return text.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static double number(Object value, String method) {
        try { return ((Number) value.getClass().getMethod(method).invoke(value)).doubleValue(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("unsupported short tunnel " + method, failure); }
    }
    private static int floor(double value) { return (int) Math.floor(value); }
    static final class Prepared {
        final Object tunnel;
        final List<?> points;
        final int planCount;
        final double length;
        final int generated;
        final boolean reverseChunkOrder;
        final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        final List<ChunkPos> chunks;
        Prepared(Object tunnel, List<?> points, int planCount, double length, int generated, boolean reverseChunkOrder,
                 int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, List<ChunkPos> chunks) {
            this.tunnel = tunnel; this.points = List.copyOf(points); this.planCount = planCount;
            this.length = length; this.generated = generated; this.reverseChunkOrder = reverseChunkOrder;
            this.minChunkX = minChunkX; this.maxChunkX = maxChunkX; this.minChunkZ = minChunkZ; this.maxChunkZ = maxChunkZ;
            this.chunks = chunks;
        }
    }
    private record Connectivity(boolean connected, int visited, String closestBoundary) { }
    private record WaterColumn(int surfaceY, boolean ambiguous) { }
    private record Projection(double distance, double water, double halfWidth) { }
    private static int integer(String name, int fallback) { try { return Integer.parseInt(System.getProperty(name, String.valueOf(fallback))); } catch (NumberFormatException ignored) { return fallback; } }
    private static int bounded(String name, int fallback, int min, int max) { return Math.max(min, Math.min(max, integer(name, fallback))); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static String json(String status, int plans, double length, int chunks, int interior, String csv,
                               boolean first, long retained, String planHash, String sampleHash,
                               boolean reverseChunkOrder, long settledTicks) {
        return "{\"event\":\"short_river_tunnel_audit\",\"status\":\"" + status
                + "\",\"plansInBounds\":\"" + plans + "\",\"selectedLength\":\"" + length
                + "\",\"fullChunks\":\"" + chunks + "\",\"interiorColumns\":\"" + interior
                + "\",\"csv\":\"" + csv + "\",\"firstGeneration\":\"" + first
                + "\",\"retainedWrites\":\"" + retained + "\",\"planHash\":\"" + planHash
                + "\",\"physicalSampleHash\":\"" + sampleHash
                + "\",\"reverseChunkOrder\":\"" + reverseChunkOrder
                + "\",\"settledTicks\":\"" + settledTicks
                + "\",\"method\":\"actual_full_chunks_full_mask_connected_water_roof_and_portals\"}";
    }
}
