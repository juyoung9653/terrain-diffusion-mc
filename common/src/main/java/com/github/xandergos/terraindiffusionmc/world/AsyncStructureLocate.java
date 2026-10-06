package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Exact async-locate chunk verification without a server-thread managedBlock. */
public final class AsyncStructureLocate {
    // Keep a weak stopped-state tombstone: late external callbacks must not reopen
    // admission between the stop hook and Minecraft flipping its running flag.
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final TicketType<Long> TICKET = TicketType.create("td-async-locate", Comparator.<Long>naturalOrder());
    private static final int DISTANCE = 33 - ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);

    private static final class State {
        final Semaphore capacity = new Semaphore(1, true);
        final Set<Request> requests = ConcurrentHashMap.newKeySet();
        volatile boolean stopped;
    }

    private static final class Request {
        final State state;
        final ServerLevel level;
        final ChunkPos pos;
        final long id = IDS.incrementAndGet();
        final AtomicBoolean released = new AtomicBoolean();
        boolean ticket; // Server-thread owned.

        Request(State state, ServerLevel level, int x, int z) {
            this.state = state; this.level = level; this.pos = new ChunkPos(x, z);
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            if (ticket) level.getChunkSource().removeRegionTicket(TICKET, pos, DISTANCE, id);
            state.requests.remove(this);
            state.capacity.release();
        }
    }

    /** Must run on the server before terrain-provider shutdown. */
    public static void stop(MinecraftServer server) {
        State state;
        synchronized (STATES) {
            state = STATES.computeIfAbsent(server, ignored -> new State());
            state.stopped = true;
        }
        for (Request request : state.requests) request.release();
    }

    public static ChunkAccess getChunk(ServerLevel level, int x, int z) {
        MinecraftServer server = level.getServer();
        if (server.isSameThread()) throw new IllegalStateException("Locate chunk wait must be off the server thread");
        State state;
        synchronized (STATES) {
            if (!server.isRunning()) throw new CancellationException("Server is stopping");
            state = STATES.computeIfAbsent(server, ignored -> new State());
        }
        try {
            while (!state.capacity.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                if (state.stopped || !server.isRunning()) throw new CancellationException("Server is stopping");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Locate admission interrupted");
        }
        Request request = new Request(state, level, x, z);
        synchronized (STATES) {
            if (state.stopped || !server.isRunning()) {
                state.capacity.release();
                throw new CancellationException("Server is stopping");
            }
            state.requests.add(request);
        }
        boolean interrupted = false;
        try {
            var admitted = server.submit(() -> {
                if (state.stopped || request.released.get() || !server.isRunning())
                    throw new CancellationException("Server is stopping");
                level.getChunkSource().addRegionTicket(TICKET, request.pos, DISTANCE, request.id);
                request.ticket = true;
                return true;
            });
            // Interrupts cannot abandon admitted generation or release its ticket early.
            // The stop hook can release it when this world's lifetime ends.
            interrupted |= await(admitted, state, server).interrupted();
            var result = await(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.STRUCTURE_STARTS, true), state, server);
            interrupted |= result.interrupted();
            var chunk = result.value();
            return chunk.orElseThrow(() -> new IllegalStateException("Locate structure chunk unavailable: " + chunk.getError()));
        } finally {
            // Keep the capacity slot until its unique ticket is actually removed.
            server.execute(request::release);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Awaited<T>(T value, boolean interrupted) { }

    private static <T> Awaited<T> await(CompletableFuture<T> future, State state, MinecraftServer server) {
        boolean interrupted = false;
        try {
            while (true) {
                if (state.stopped || !server.isRunning()) throw new CancellationException("Server is stopping");
                try { return new Awaited<>(future.get(100, TimeUnit.MILLISECONDS), interrupted); }
                catch (InterruptedException ignored) { interrupted = true; }
                catch (TimeoutException ignored) { }
                catch (ExecutionException failure) { throw new IllegalStateException("Locate chunk request failed", failure.getCause()); }
            }
        } catch (RuntimeException failure) {
            if (interrupted) Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private AsyncStructureLocate() { }
}
