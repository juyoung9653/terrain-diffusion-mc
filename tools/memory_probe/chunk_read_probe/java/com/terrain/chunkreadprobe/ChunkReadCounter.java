package com.terrain.chunkreadprobe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, opt-in counter: observes C2ME reads/tickets; it never requests a chunk. */
public final class ChunkReadCounter {
    static final int MAX_POSITIONS = 16_384;
    private static final int MAX_TICKET_KEYS = 4_096, MAX_STACKS = 24;
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TicketCounts> TICKETS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> TICKET_STACKS = new ConcurrentHashMap<>();
    // Request stacks have an independent per-dimension cap: common Overworld work cannot evict OtherSide evidence.
    private static final ConcurrentHashMap<String, LongAdder> REQUEST_STACKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SCHEDULING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SCHEDULING_STACKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> REQUEST_STACK_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> REQUEST_EVENTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> SCHEDULING_STACK_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, String> MANAGER_DIMENSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL = new AtomicLong(), TICKET_EVENTS = new AtomicLong(), CHUNK_MAP_SCHEDULING_EVENTS = new AtomicLong(), THE_CHUNK_SYSTEM_SCHEDULING_EVENTS = new AtomicLong();
    private static final LongAdder DROPPED_UNIQUE = new LongAdder(), DROPPED_TICKET_KEYS = new LongAdder();
    private static final LongAdder REFLECTION_ERRORS = new LongAdder();
    private static final AtomicBoolean WRITING = new AtomicBoolean();
    private static volatile long nextWriteNanos;
    private static volatile Path output;
    private static volatile String firstReflectionError = "";

    private ChunkReadCounter() { }
    public static void configure(Path target) { output = target; }

    public static void observed(String dimension, int x, int z) {
        if (output == null) return;
        TOTAL.incrementAndGet();
        String key = dimension + ";" + x + ";" + z;
        LongAdder known = COUNTS.get(key);
        if (known == null) {
            if (COUNTS.size() >= MAX_POSITIONS) { DROPPED_UNIQUE.increment(); maybeWrite(); return; }
            LongAdder candidate = new LongAdder();
            LongAdder raced = COUNTS.putIfAbsent(key, candidate);
            known = raced == null ? candidate : raced;
        }
        known.increment(); maybeWrite();
    }

    /** Called before DistanceManager mutates its ticket map, preserving the requester stack. */
    public static void ticketObserved(Object manager, long packedPos, Object ticket, boolean added) {
        if (output == null) return;
        try {
            String dimension = dimensionForManager(manager, "this$0", "level");
            recordTicket(dimension, packedPos, ticket, added, "ticket");
        } catch (ReflectiveOperationException | RuntimeException error) { reflectionFailure(error); }
    }

    /** Records a synchronous original request before C2ME turns it into a ticket/async load. */
    public static void chunkRequestObserved(Object cache, int x, int z, Object status, boolean create) {
        if (output == null || !create) return;
        try {
            String dimension = dimensionForLevelOwner(cache, "level");
            long sequence = REQUEST_EVENTS.computeIfAbsent(dimension, ignored -> new AtomicLong()).incrementAndGet();
            // Preserve startup callers, then sample one in 128 without allocating a stack per request.
            if (sequence <= 8 || (sequence & 127L) == 1L) sampleRequestStack(dimension, x, z, status);
        } catch (ReflectiveOperationException | RuntimeException error) { reflectionFailure(error); }
    }

    /** C2ME scheduling ingress/correlation, bounded by dimension, level and coordinate. */
    public static void schedulingObserved(Object owner, long packedPos, int level, boolean theChunkSystem) {
        if (output == null) return;
        // Each paired hook samples independently; both routes remain observable.
        AtomicLong events = theChunkSystem ? THE_CHUNK_SYSTEM_SCHEDULING_EVENTS : CHUNK_MAP_SCHEDULING_EVENTS;
        if ((events.incrementAndGet() & 15L) != 1L) return;
        try {
            String source = theChunkSystem ? "theChunkSystem" : "chunkMap";
            Object map = theChunkSystem ? field(owner, "tacs") : owner;
            String dimension = dimensionForLevelOwner(map, "level");
            sampleSchedulingStack(source, dimension, level);
            int x = (int) packedPos, z = (int) (packedPos >>> 32);
            String key = source + ";" + dimension + ";" + level + ";" + x + ";" + z;
            LongAdder count = SCHEDULING.get(key);
            if (count == null) { if (SCHEDULING.size() >= MAX_TICKET_KEYS) return; LongAdder candidate = new LongAdder(); LongAdder raced = SCHEDULING.putIfAbsent(key, candidate); count = raced == null ? candidate : raced; }
            count.increment(); maybeWrite();
        } catch (ReflectiveOperationException | RuntimeException error) { reflectionFailure(error); }
    }

    /** Called at NoTickSystem's player-source boundary, before its async expansion. */
    public static void noTickSourceObserved(Object system, Object pos, boolean added) {
        if (output == null) return;
        try {
            Object loader = field(system, "playerNoTickLoader");
            Object map = field(loader, "tacs");
            String dimension = dimensionForLevelOwner(map, "level");
            long packed = ((Number) pos.getClass().getMethod("toLong").invoke(pos)).longValue();
            recordTicket(dimension, packed, "NO_TICK_PLAYER_SOURCE", added, "noTickSource");
        } catch (ReflectiveOperationException | RuntimeException error) { reflectionFailure(error); }
    }

    private static void recordTicket(String dimension, long packedPos, Object ticket, boolean added, String source)
            throws ReflectiveOperationException {
        long event = TICKET_EVENTS.incrementAndGet();
        if (source.equals("noTickSource") || (event & 255L) == 1L) sampleStack();
        int x = (int) packedPos, z = (int) (packedPos >>> 32);
        String type = source.equals("noTickSource") ? String.valueOf(ticket)
                : String.valueOf(ticket.getClass().getMethod("getType").invoke(ticket));
        int level = source.equals("noTickSource") ? -1
                : ((Number) ticket.getClass().getMethod("getTicketLevel").invoke(ticket)).intValue();
        String key = dimension + ";" + type + ";" + level + ";" + x + ";" + z;
        TicketCounts counts = TICKETS.get(key);
        if (counts == null) {
            if (TICKETS.size() >= MAX_TICKET_KEYS) { DROPPED_TICKET_KEYS.increment(); maybeWrite(); return; }
            TicketCounts candidate = new TicketCounts();
            TicketCounts raced = TICKETS.putIfAbsent(key, candidate);
            counts = raced == null ? candidate : raced;
        }
        if (added) counts.adds.increment(); else counts.removes.increment();
        maybeWrite();
    }

    private static String dimensionForManager(Object manager, String outerField, String levelField)
            throws ReflectiveOperationException { return dimensionForLevelOwner(field(manager, outerField), levelField); }

    private static String dimensionForLevelOwner(Object owner, String levelField) throws ReflectiveOperationException {
        String known = MANAGER_DIMENSIONS.get(owner); if (known != null) return known;
        Object level = field(owner, levelField); Object dimension = level.getClass().getMethod("dimension").invoke(level);
        String resolved = String.valueOf(dimension);
        if (MANAGER_DIMENSIONS.size() < 32) { String raced = MANAGER_DIMENSIONS.putIfAbsent(owner, resolved); return raced == null ? resolved : raced; }
        return resolved;
    }

    private static void sampleRequestStack(String dimension, int x, int z, Object status) {
        StringBuilder stack = new StringBuilder("status=").append(status).append(" :: ");
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("com.terrain.chunkreadprobe") || owner.startsWith("net.minecraft.server.level.ServerChunkCache")) continue;
            if (stack.length() > 32) stack.append(" <- "); stack.append(owner).append('.').append(frame.getMethodName());
            if (stack.length() > 1500) break;
        }
        String key = dimension + " :: " + stack;
        LongAdder known = REQUEST_STACKS.get(key);
        if (known == null) {
            AtomicLong slots = REQUEST_STACK_SLOTS.computeIfAbsent(dimension, ignored -> new AtomicLong());
            if (slots.get() >= 8) return;
            LongAdder candidate = new LongAdder(); LongAdder raced = REQUEST_STACKS.putIfAbsent(key, candidate);
            if (raced == null) { slots.incrementAndGet(); known = candidate; } else known = raced;
        }
        known.increment();
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Class<?> type = object.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void sampleStack() {
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("com.terrain.chunkreadprobe") || owner.startsWith("net.minecraft.server.level.DistanceManager")) continue;
            if (stack.length() > 0) stack.append(" <- ");
            stack.append(owner).append('.').append(frame.getMethodName());
            if (stack.length() > 1500) break;
        }
        String key = stack.toString();
        if (key.isEmpty()) return;
        LongAdder known = TICKET_STACKS.get(key);
        if (known == null) {
            if (TICKET_STACKS.size() >= MAX_STACKS) return;
            LongAdder candidate = new LongAdder(); LongAdder raced = TICKET_STACKS.putIfAbsent(key, candidate);
            known = raced == null ? candidate : raced;
        }
        known.increment();
    }

    private static void sampleSchedulingStack(String source, String dimension, int level) {
        StringBuilder stack = new StringBuilder("level=").append(level).append(" :: ");
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("com.terrain.chunkreadprobe") || owner.equals("net.minecraft.server.level.ChunkMap") || owner.startsWith("com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem")) continue;
            if (stack.length() > 12) stack.append(" <- "); stack.append(owner).append('.').append(frame.getMethodName());
            if (stack.length() > 1500) break;
        }
        String scope = source + " :: " + dimension;
        String key = scope + " :: " + stack;
        LongAdder known = SCHEDULING_STACKS.get(key);
        if (known == null) {
            AtomicLong slots = SCHEDULING_STACK_SLOTS.computeIfAbsent(scope, ignored -> new AtomicLong());
            if (slots.get() >= 8) return;
            LongAdder candidate = new LongAdder(); LongAdder raced = SCHEDULING_STACKS.putIfAbsent(key, candidate);
            if (raced == null) { slots.incrementAndGet(); known = candidate; } else known = raced;
        }
        known.increment();
    }

    public static void reflectionFailure(Exception error) {
        REFLECTION_ERRORS.increment();
        if (firstReflectionError.isEmpty()) synchronized (ChunkReadCounter.class) {
            if (firstReflectionError.isEmpty()) firstReflectionError = error.getClass().getName();
        }
        maybeWrite();
    }

    private static void maybeWrite() {
        long now = System.nanoTime();
        if (now < nextWriteNanos || !WRITING.compareAndSet(false, true)) return;
        nextWriteNanos = now + 5_000_000_000L;
        try { write(); } finally { WRITING.set(false); }
    }

    static void write() {
        Path target = output; if (target == null) return;
        var rows = new ArrayList<Map.Entry<String, LongAdder>>(COUNTS.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        var ticketRows = new ArrayList<Map.Entry<String, TicketCounts>>(TICKETS.entrySet());
        ticketRows.sort(Comparator.comparingLong((Map.Entry<String, TicketCounts> e) -> e.getValue().total()).reversed());
        var stacks = new ArrayList<Map.Entry<String, LongAdder>>(TICKET_STACKS.entrySet());
        stacks.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        StringBuilder json = new StringBuilder("{\"format\":2,\"totalReads\":").append(TOTAL.get())
                .append(",\"trackedUniquePositions\":").append(COUNTS.size())
                .append(",\"droppedNewPositionReads\":").append(DROPPED_UNIQUE.sum()).append(",\"top\":[");
        for (int i = 0; i < Math.min(64, rows.size()); i++) {
            String[] p = rows.get(i).getKey().split(";", 3); if (i != 0) json.append(',');
            json.append("{\"dimension\":\"").append(escape(p[0])).append("\",\"x\":").append(p[1]).append(",\"z\":").append(p[2]).append(",\"reads\":").append(rows.get(i).getValue().sum()).append('}');
        }
        json.append("],\"ticketEvents\":").append(TICKET_EVENTS.get()).append(",\"trackedTicketKeys\":").append(TICKETS.size())
                .append(",\"droppedTicketKeys\":").append(DROPPED_TICKET_KEYS.sum()).append(",\"topTickets\":[");
        for (int i = 0; i < Math.min(64, ticketRows.size()); i++) {
            String[] p = ticketRows.get(i).getKey().split(";", 5); TicketCounts c = ticketRows.get(i).getValue(); if (i != 0) json.append(',');
            json.append("{\"dimension\":\"").append(escape(p[0])).append("\",\"type\":\"").append(escape(p[1])).append("\",\"level\":").append(p[2]).append(",\"x\":").append(p[3]).append(",\"z\":").append(p[4]).append(",\"adds\":").append(c.adds.sum()).append(",\"removes\":").append(c.removes.sum()).append('}');
        }
        json.append("],\"ticketStacks\":[");
        for (int i = 0; i < stacks.size(); i++) { if (i != 0) json.append(','); json.append("{\"stack\":\"").append(escape(stacks.get(i).getKey())).append("\",\"samples\":").append(stacks.get(i).getValue().sum()).append('}'); }
        json.append("],\"topScheduling\":[");
        var scheduled = new ArrayList<Map.Entry<String, LongAdder>>(SCHEDULING.entrySet()); scheduled.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        for (int i = 0; i < Math.min(64, scheduled.size()); i++) { if (i != 0) json.append(','); String[] p = scheduled.get(i).getKey().split(";", 5); json.append("{\"source\":\"").append(p[0]).append("\",\"dimension\":\"").append(escape(p[1])).append("\",\"level\":").append(p[2]).append(",\"x\":").append(p[3]).append(",\"z\":").append(p[4]).append(",\"count\":").append(scheduled.get(i).getValue().sum()).append('}'); }
        json.append("],\"schedulingSampleEvery\":16,\"schedulingStacks\":[");
        var schedulingStacks = new ArrayList<Map.Entry<String, LongAdder>>(SCHEDULING_STACKS.entrySet()); schedulingStacks.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        for (int i = 0; i < schedulingStacks.size(); i++) { if (i != 0) json.append(','); String key = schedulingStacks.get(i).getKey(); int first = key.indexOf(" :: "), second = key.indexOf(" :: ", first + 4); json.append("{\"source\":\"").append(escape(key.substring(0, first))).append("\",\"dimension\":\"").append(escape(key.substring(first + 4, second))).append("\",\"stack\":\"").append(escape(key.substring(second + 4))).append("\",\"samples\":").append(schedulingStacks.get(i).getValue().sum()).append('}'); }
        json.append("],\"chunkRequestStacks\":[");
        var requests = new ArrayList<Map.Entry<String, LongAdder>>(REQUEST_STACKS.entrySet());
        requests.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        for (int i = 0; i < requests.size(); i++) { if (i != 0) json.append(','); String key = requests.get(i).getKey(); int split = key.indexOf(" :: "); json.append("{\"dimension\":\"").append(escape(key.substring(0, split))).append("\",\"stack\":\"").append(escape(key.substring(split + 4))).append("\",\"samples\":").append(requests.get(i).getValue().sum()).append('}'); }
        json.append("],\"chunkRequestCounts\":[");
        var requestCounts = new ArrayList<Map.Entry<String, AtomicLong>>(REQUEST_EVENTS.entrySet()); requestCounts.sort(Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get()).reversed());
        for (int i = 0; i < requestCounts.size(); i++) { if (i != 0) json.append(','); json.append("{\"dimension\":\"").append(escape(requestCounts.get(i).getKey())).append("\",\"requests\":").append(requestCounts.get(i).getValue().get()).append('}'); }
        json.append("],\"chunkRequestStackFirstPerDimension\":8,\"chunkRequestStackEvery\":128,\"reflectionErrors\":").append(REFLECTION_ERRORS.sum()).append(",\"firstReflectionError\":\"").append(escape(firstReflectionError)).append("\"}\n");
        try {
            Path parent = target.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp"); Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException unsupported) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { System.err.println("td-chunk-read-probe write failed: " + error); }
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static final class TicketCounts { final LongAdder adds = new LongAdder(), removes = new LongAdder(); long total() { return adds.sum() + removes.sum(); } }
}

