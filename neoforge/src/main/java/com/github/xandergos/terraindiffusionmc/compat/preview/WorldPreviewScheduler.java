package com.github.xandergos.terraindiffusionmc.compat.preview;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A bounded bridge for World Preview 1.3.1.
 *
 * <p>The bridge deliberately creates World Preview's own work units and lets its own
 * {@code WorkBatch.process()} write PreviewStorage.  The only substituted behavior is the
 * unbounded random queue: at most a few tiles nearest the requested viewport are retained,
 * and one daemon worker admits one tile at a time.
 */
public final class WorldPreviewScheduler {
    private static final Logger LOG = LoggerFactory.getLogger("terrain-diffusion-mc/world-preview");
    private static final int MAX_PENDING_TILES = 9;
    private static final int CHUNKS_PER_BATCH_SIDE = 4;
    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, BatchGate> BATCH_GATES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private WorldPreviewScheduler() {
    }

    /** @return true only when this was a Terrain Diffusion request and vanilla queuing must be cancelled. */
    public static boolean queueRange(Object manager, BlockPos first, BlockPos second) {
        try {
            Adapter adapter = Adapter.forManager(manager);
            if (!(adapter.biomeSource(manager) instanceof TerrainDiffusionBiomeSource)) return false;
            stateFor(manager, adapter).queue(first, second);
            return true;
        } catch (Throwable failure) {
            LOG.error("World Preview 1.3.1 bridge disabled for this request; using its normal queue", failure);
            return false;
        }
    }

    /** Called before World Preview drops or replaces its world state. */
    public static void close(Object manager) {
        State state;
        synchronized (STATES) {
            state = STATES.remove(manager);
        }
        if (state != null) state.close();
    }

    /**
     * Invoked at WorkBatch.process' final publication call. For bounded batches this keeps the
     * generation lock until the original method returns, so a new viewport cannot invalidate a
     * token between its last check and PreviewStorage publication.
     */
    public static void publishBatch(Object batch, List<?> results) {
        BatchGate gate = BATCH_GATES.get(batch);
        if (gate == null) {
            Adapter.applyVanillaChunkResult(batch, results);
            return;
        }
        synchronized (gate.state.lock) {
            if (gate.state.isCurrentLocked(gate.token)) {
                gate.state.adapter.applyChunkResult(batch, results);
            }
        }
    }

    private static State stateFor(Object manager, Adapter adapter) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(manager, ignored -> new State(manager, adapter));
        }
    }

    private static final class State {
        private final Object manager;
        private final Adapter adapter;
        private final Object lock = new Object();
        private final ArrayDeque<TileTask> pending = new ArrayDeque<>();
        private final ExecutorService worker;
        private long token;
        private boolean draining;
        private boolean closed;
        private Object activeBatch;
        private LocalTerrainProvider.PreviewSession session;
        private long sessionSeed = Long.MIN_VALUE;
        private int sessionScale = Integer.MIN_VALUE;
        private TileCursor cursor;

        private State(Object manager, Adapter adapter) {
            this.manager = manager;
            this.adapter = adapter;
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "terrain-diffusion-world-preview");
                thread.setDaemon(true);
                return thread;
            };
            this.worker = Executors.newSingleThreadExecutor(factory);
        }

        private void queue(BlockPos first, BlockPos second) throws ReflectiveOperationException {
            long seed = adapter.seed(manager);
            int scale = adapter.hasActualServer(manager) ? WorldScaleManager.getCurrentScale()
                    : WorldScaleSelectionState.getPendingScaleOrDefault();
            int y = first.getY(); // World Preview supplies the selected layer on both endpoints.
            int minX = Math.min(first.getX(), second.getX());
            int maxX = Math.max(first.getX(), second.getX());
            int minZ = Math.min(first.getZ(), second.getZ());
            int maxZ = Math.max(first.getZ(), second.getZ());
            TileCursor requestedTiles = new TileCursor(minX, minZ, maxX, maxZ, TerrainDiffusionConfig.tileSize());

            synchronized (lock) {
                if (closed) return;
                token++;
                long requestToken = token;
                pending.clear();
                cancelActiveLocked();
                if (session == null || sessionSeed != seed || sessionScale != scale) {
                    closeSessionLocked();
                    session = LocalTerrainProvider.createPreviewSession(seed, scale);
                    sessionSeed = seed;
                    sessionScale = scale;
                }
                cursor = requestedTiles.withRequest(requestToken, seed, scale, y);
                refillLocked();
                if (!draining) {
                    draining = true;
                    worker.execute(this::drain);
                }
            }
        }

        private void drain() {
            while (true) {
                TileTask task;
                LocalTerrainProvider.PreviewSession taskSession;
                synchronized (lock) {
                    refillLocked();
                    if (closed || pending.isEmpty()) {
                        draining = false;
                        return;
                    }
                    task = pending.removeFirst();
                    taskSession = session;
                    if (task.token != token || taskSession == null) continue;
                }
                try {
                    processTile(task, taskSession);
                } catch (Throwable failure) {
                    if (isCurrent(task.token)) {
                        LOG.error("World Preview tile ({}, {}) failed for seed {} at scale {}; continuing with the next tile",
                                task.tile.x, task.tile.z, task.seed, task.scale, failure);
                    }
                }
            }
        }

        private void processTile(TileTask task, LocalTerrainProvider.PreviewSession taskSession) throws ReflectiveOperationException {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int minBlockX = Math.multiplyExact(task.tile.x, tileSize);
            int minBlockZ = Math.multiplyExact(task.tile.z, tileSize);
            int maxBlockX = Math.addExact(minBlockX, tileSize);
            int maxBlockZ = Math.addExact(minBlockZ, tileSize);
            int minChunkX = Math.floorDiv(minBlockX, 16);
            int minChunkZ = Math.floorDiv(minBlockZ, 16);
            int maxChunkX = Math.floorDiv(maxBlockX - 1, 16);
            int maxChunkZ = Math.floorDiv(maxBlockZ - 1, 16);

            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ += CHUNKS_PER_BATCH_SIDE) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX += CHUNKS_PER_BATCH_SIDE) {
                    if (!isCurrent(task.token)) return;
                    List<Object> units = new ArrayList<>(CHUNKS_PER_BATCH_SIDE * CHUNKS_PER_BATCH_SIDE);
                    int limitZ = Math.min(maxChunkZ, chunkZ + CHUNKS_PER_BATCH_SIDE - 1);
                    int limitX = Math.min(maxChunkX, chunkX + CHUNKS_PER_BATCH_SIDE - 1);
                    for (int z = chunkZ; z <= limitZ; z++) {
                        for (int x = chunkX; x <= limitX; x++) {
                            units.add(adapter.newWorkUnit(manager, new ChunkPos(x, z), task.y));
                        }
                    }
                    Object batch = adapter.newBatch(manager, units);
                    BatchGate gate = new BatchGate(this, task.token);
                    BATCH_GATES.put(batch, gate);
                    synchronized (lock) {
                        if (!isCurrentLocked(task.token)) {
                            adapter.cancelBatch(batch);
                            BATCH_GATES.remove(batch);
                            return;
                        }
                        activeBatch = batch;
                    }
                    try {
                        taskSession.run(() -> {
                            try {
                                adapter.processBatch(batch);
                            } catch (ReflectiveOperationException e) {
                                throw new PreviewReflectionException(e);
                            }
                        });
                    } finally {
                        synchronized (lock) {
                            if (activeBatch == batch) activeBatch = null;
                        }
                        BATCH_GATES.remove(batch);
                    }
                }
            }
        }

        private boolean isCurrent(long candidate) {
            synchronized (lock) {
                return isCurrentLocked(candidate);
            }
        }

        private boolean isCurrentLocked(long candidate) {
            return !closed && candidate == token;
        }

        private void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                token++;
                pending.clear();
                cursor = null;
                cancelActiveLocked();
                closeSessionLocked();
            }
            worker.shutdown();
        }

        private void cancelActiveLocked() {
            if (activeBatch != null) {
                try {
                    adapter.cancelBatch(activeBatch);
                } catch (ReflectiveOperationException failure) {
                    LOG.error("World Preview 1.3.1 bridge could not cancel a stale batch", failure);
                }
            }
        }

        private void closeSessionLocked() {
            if (session != null) {
                session.close();
                session = null;
            }
        }

        /** Keeps metadata bounded while eventually walking every requested tile center-out. */
        private void refillLocked() {
            while (!closed && pending.size() < MAX_PENDING_TILES && cursor != null) {
                TileKey tile = cursor.next();
                if (tile == null) {
                    cursor = null;
                    return;
                }
                pending.addLast(new TileTask(cursor.token, cursor.seed, cursor.scale, cursor.y, tile));
            }
        }
    }

    static record TileKey(int x, int z) { }
    private record TileTask(long token, long seed, int scale, int y, TileKey tile) { }
    private record BatchGate(State state, long token) { }

    private static int midpoint(int first, int second) {
        return (int) Math.floorDiv((long) first + second, 2L);
    }

    static final class TileCursor {
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int centerX;
        private final int centerZ;
        private final int maxRadius;
        private int radius;
        private int edge;
        private int edgeOffset;
        private long token;
        private long seed;
        private int scale;
        private int y;

        TileCursor(int firstX, int firstZ, int secondX, int secondZ, int tileSize) {
            minX = Math.floorDiv(firstX, tileSize);
            maxX = Math.floorDiv(secondX, tileSize);
            minZ = Math.floorDiv(firstZ, tileSize);
            maxZ = Math.floorDiv(secondZ, tileSize);
            centerX = Math.floorDiv(midpoint(firstX, secondX), tileSize);
            centerZ = Math.floorDiv(midpoint(firstZ, secondZ), tileSize);
            maxRadius = Math.max(Math.max(centerX - minX, maxX - centerX), Math.max(centerZ - minZ, maxZ - centerZ));
        }

        private TileCursor withRequest(long token, long seed, int scale, int y) {
            this.token = token;
            this.seed = seed;
            this.scale = scale;
            this.y = y;
            return this;
        }

        TileKey next() {
            if (radius == 0) {
                radius = 1;
                return new TileKey(centerX, centerZ);
            }
            while (radius <= maxRadius) {
                int edgeLength = switch (edge) {
                    case 0 -> radius * 2 + 1;       // top, including both corners
                    case 1, 2 -> radius * 2;        // right and bottom, each omits one shared corner
                    case 3 -> radius * 2 - 1;       // left omits both shared corners
                    default -> throw new IllegalStateException("invalid tile cursor edge");
                };
                if (edgeOffset == edgeLength) {
                    edgeOffset = 0;
                    edge++;
                    if (edge == 4) {
                        edge = 0;
                        radius++;
                    }
                    continue;
                }
                int offset = edgeOffset++;
                int x;
                int z;
                switch (edge) {
                    case 0 -> { x = centerX - radius + offset; z = centerZ - radius; }
                    case 1 -> { x = centerX + radius; z = centerZ - radius + 1 + offset; }
                    case 2 -> { x = centerX + radius - 1 - offset; z = centerZ + radius; }
                    case 3 -> { x = centerX - radius; z = centerZ + radius - 1 - offset; }
                    default -> throw new IllegalStateException("invalid tile cursor edge");
                }
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return new TileKey(x, z);
            }
            return null;
        }
    }

    private static final class Adapter {
        private final Field biomeSource;
        private final Field worldOptions;
        private final Field sampleUtils;
        private final Field previewData;
        private final Field completedSynchro;
        private final Method workUnitFactory;
        private final Method seed;
        private final Field minecraftServer;
        private final Constructor<?> workBatch;
        private final Method processBatch;
        private final Method cancelBatch;
        private final Method applyChunkResult;

        private Adapter(Class<?> managerClass) throws ReflectiveOperationException {
            biomeSource = field(managerClass, "biomeSource");
            worldOptions = field(managerClass, "worldOptions");
            sampleUtils = field(managerClass, "sampleUtils");
            previewData = field(managerClass, "previewData");
            completedSynchro = field(managerClass, "completedSynchro");
            workUnitFactory = managerClass.getDeclaredMethod("workUnitFactory", ChunkPos.class, int.class);
            workUnitFactory.setAccessible(true);
            Class<?> worldOptionsClass = worldOptions.getType();
            seed = worldOptionsClass.getMethod("seed");
            ClassLoader loader = managerClass.getClassLoader();
            Class<?> batchClass = Class.forName("com.caeruleusTait.world.preview.backend.worker.WorkBatch", false, loader);
            workBatch = batchClass.getConstructor(List.class, Object.class, previewData.getType());
            processBatch = batchClass.getMethod("process");
            cancelBatch = batchClass.getMethod("cancel");
            applyChunkResult = batchClass.getDeclaredMethod("applyChunkResult", List.class);
            applyChunkResult.setAccessible(true);
            Class<?> sampleUtilsClass = sampleUtils.getType();
            minecraftServer = field(sampleUtilsClass, "minecraftServer");
        }

        private static Adapter forManager(Object manager) throws ReflectiveOperationException {
            return new Adapter(manager.getClass());
        }

        private Object biomeSource(Object manager) throws IllegalAccessException {
            return biomeSource.get(manager);
        }

        private long seed(Object manager) throws ReflectiveOperationException {
            Object options = worldOptions.get(manager);
            if (options == null) throw new IllegalStateException("World Preview has no world options");
            return (long) seed.invoke(options);
        }

        private boolean hasActualServer(Object manager) throws IllegalAccessException {
            Object utils = sampleUtils.get(manager);
            Object server = utils == null ? null : minecraftServer.get(utils);
            return server != null && !"com.caeruleusTait.world.preview.backend.stubs.DummyMinecraftServer"
                    .equals(server.getClass().getName());
        }

        private Object newWorkUnit(Object manager, ChunkPos chunk, int y) throws ReflectiveOperationException {
            return workUnitFactory.invoke(manager, chunk, y);
        }

        private Object newBatch(Object manager, List<Object> units) throws ReflectiveOperationException {
            return workBatch.newInstance(units, completedSynchro.get(manager), previewData.get(manager));
        }

        private void processBatch(Object batch) throws ReflectiveOperationException {
            processBatch.invoke(batch);
        }

        private void cancelBatch(Object batch) throws ReflectiveOperationException {
            cancelBatch.invoke(batch);
        }

        private void applyChunkResult(Object batch, List<?> results) {
            try {
                applyChunkResult.invoke(batch, results);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("World Preview 1.3.1 publication method became inaccessible", failure);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("World Preview 1.3.1 publication failed", cause);
            }
        }

        private static void applyVanillaChunkResult(Object batch, List<?> results) {
            try {
                Method method = batch.getClass().getDeclaredMethod("applyChunkResult", List.class);
                method.setAccessible(true);
                method.invoke(batch, results);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("World Preview 1.3.1 publication contract is unavailable", failure);
            }
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        }
    }

    private static final class PreviewReflectionException extends RuntimeException {
        private PreviewReflectionException(ReflectiveOperationException cause) {
            super(cause);
        }
    }
}
