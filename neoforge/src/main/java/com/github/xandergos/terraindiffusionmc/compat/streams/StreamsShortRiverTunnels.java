package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.ShortRiverTunnelPlanner;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** V12 retains existing rock roof only; it never carves or places blocks. */
public final class StreamsShortRiverTunnels {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final Map<Object, List<ShortRiverTunnelPlanner.Tunnel>> PLANS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final AtomicLong PLANNED = new AtomicLong(), RETAINED = new AtomicLong();
    private StreamsShortRiverTunnels() { }

    public static <T> T place(WorldGenLevel level, ThrowingSupplier<T> placement) {
        if (level == null || WorldNaturalRiverState.version(level.getLevel()) < 12) return call(placement);
        Scope previous = ACTIVE.get();
        Scope scope = create(level);
        if (scope == null) return call(placement);
        ACTIVE.set(scope);
        try { return call(placement); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }

    /** Called before every native WorldGenRegion write while the river feature is active. */
    public static boolean retain(WorldGenLevel level, BlockPos pos) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.level != level || !scope.contains(pos.getX(), pos.getY(), pos.getZ())) return false;
        // Do not turn a native cave hole into an artificial roof.  This lookup is
        // deliberately after the exact feature/region scope gate.
        if (level.getBlockState(pos).isAir()) return false;
        RETAINED.incrementAndGet();
        return true;
    }

    /** Bounded pre-generation planning API for the native tunnel audit. */
    public static List<ShortRiverTunnelPlanner.Tunnel> plans(ServerLevel level, int centerX, int centerZ, int radius) {
        if (level == null || radius < 0 || WorldNaturalRiverState.version(level) < 12) return List.of();
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen").getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) < 12) return List.of();
            Method plateAt = engine.getClass().getMethod("plateAt", int.class, int.class);
            Method networkFor = engine.getClass().getMethod("networkFor", plateAt.getReturnType());
            java.util.LinkedHashSet<ShortRiverTunnelPlanner.Tunnel> result = new java.util.LinkedHashSet<>();
            for (int x = centerX - radius; x <= centerX + radius; x += 64)
                for (int z = centerZ - radius; z <= centerZ + radius; z += 64)
                    result.addAll(tunnels(engine, config, plateAt, networkFor, x, z).values);
            return List.copyOf(result);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Streams 2.16.2 short-tunnel contract changed", error); }
    }

    public static long plannedCount() { return PLANNED.get(); }
    public static long retainedWrites() { return RETAINED.get(); }
    public static void clear() { ACTIVE.remove(); PLANS.clear(); PLANNED.set(0); RETAINED.set(0); }

    private static Scope create(WorldGenLevel level) {
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen").getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) < 12) return null;
            Method plateAt = engine.getClass().getMethod("plateAt", int.class, int.class);
            return new Scope(level, engine, config, plateAt,
                    engine.getClass().getMethod("networkFor", plateAt.getReturnType()));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Streams 2.16.2 short-tunnel contract changed", error); }
    }

    private static Tunnels tunnels(Object engine, Object config, Method plateAt, Method networkFor, int x, int z) {
        try {
            Object network = networkFor.invoke(engine, plateAt.invoke(engine, x, z));
            if (!Streams2162NetworkAccess.applies(network)) return Tunnels.EMPTY;
            List<ShortRiverTunnelPlanner.Tunnel> value = PLANS.get(network);
            if (value == null) {
                // Base-height inference is potentially expensive.  Never hold
                // the identity-map monitor while it runs; duplicate first-use
                // work is harmless and the synchronized publish picks one plan.
                NaturalRiverMorphology.Ground ground = StreamsNaturalRiverAdapter.originalTerrain(config);
                Object domain = domain(engine, network);
                List<ShortRiverTunnelPlanner.Tunnel> made = StreamsShortRiverShortcutNetwork.approvedMasks(
                        network, ground,
                        StreamsShortRiverShortcutNetwork.corridor(network,domain,config),
                        config);
                synchronized (PLANS) {
                    value = PLANS.get(network);
                    if (value == null) {
                        // A world may visit indefinitely many plate networks;
                        // retain only a small recent identity cache until server cleanup.
                        if (PLANS.size() >= 64) PLANS.remove(PLANS.keySet().iterator().next());
                        PLANS.put(network, made); value = made; PLANNED.addAndGet(value.size());
                    }
                }
            }
            return value.isEmpty() ? Tunnels.EMPTY : new Tunnels(value, StreamsShortRiverShortcutNetwork.NativeSample.of(network,config));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Streams 2.16.2 short-tunnel network lookup changed", error); }
    }

    /** Exact 2.16.2 domain constructor used by the graph builder and V11 planform gate. */
    private static Object domain(Object engine, Object network) {
        try {
            Object lattice = engine.getClass().getMethod("lattice").invoke(engine);
            Class<?> type = Class.forName("dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001", false,
                    engine.getClass().getClassLoader());
            return type.getConstructor(lattice.getClass(), Streams2162NetworkAccess.key(network).getClass())
                    .newInstance(lattice, Streams2162NetworkAccess.key(network));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams 2.16.2 plate-domain contract changed", error);
        }
    }

    private static <T> T call(ThrowingSupplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Native Streams river placement failed", error); }
    }
    @FunctionalInterface public interface ThrowingSupplier<T> { T get() throws Exception; }

    /** Per-feature write scope over the same approved native-sample roofs used at graph admission. */
    private static final class Scope {
        final WorldGenLevel level;
        final Object engine, config;
        final Method plateAt, networkFor;
        final Map<Long,Tunnels> byChunk = new java.util.HashMap<>();
        final Map<Long,Integer> roofByColumn = new java.util.HashMap<>();
        Scope(WorldGenLevel level, Object engine, Object config, Method plateAt, Method networkFor) {
            this.level=level; this.engine=engine; this.config=config;
            this.plateAt=plateAt; this.networkFor=networkFor;
        }
        boolean contains(int x,int y,int z) {
            long chunk=((long)(x>>4)<<32)^((z>>4)&0xffffffffL);
            Tunnels mask=byChunk.get(chunk);
            if (mask==null) { mask=tunnels(engine,config,plateAt,networkFor,x,z); byChunk.put(chunk,mask); }
            if (mask.values.isEmpty()) return false;
            long column=((long)x<<32)^(z&0xffffffffL);
            Integer roof=roofByColumn.get(column);
            if (roof==null) { roof=mask.roof(x,z); roofByColumn.put(column,roof); }
            return roof!=Integer.MIN_VALUE && y>=roof;
        }
    }

    private record Tunnels(List<ShortRiverTunnelPlanner.Tunnel> values,
                           StreamsShortRiverShortcutNetwork.NativeSample columns) {
        static final Tunnels EMPTY = new Tunnels(List.of(),null);
        int roof(int x,int z) {
            try {
                for (var mask:values) {
                    int roof=columns.roof(mask,x,z);
                    if (roof!=Integer.MIN_VALUE) return roof;
                }
                return Integer.MIN_VALUE;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Streams 2.16.2 approved roof sampler changed",error);
            }
        }
    }
}
