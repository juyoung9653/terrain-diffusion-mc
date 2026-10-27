package com.terrain.chunkreadprobe;
import java.nio.file.Files;
import java.nio.file.Path;
public final class test_counter {
    private static final class Level { public Object dimension() { return "minecraft:overworld"; } }
    private static final class MapLike { @SuppressWarnings("unused") private final Level level = new Level(); }
    private static final class Manager { @SuppressWarnings("unused") private final MapLike this$0 = new MapLike(); }
    private static final class Ticket { public Object getType() { return "probe_ticket"; } public int getTicketLevel() { return 31; } }
    public static void main(String[] args) throws Exception {
        Path output = Path.of("build", "memory-probe", "chunk-read-counter-test.json");
        Files.createDirectories(output.getParent()); Files.deleteIfExists(output);
        ChunkReadCounter.configure(output);
        ChunkReadCounter.observed("minecraft:overworld", 1, 2);
        ChunkReadCounter.observed("minecraft:overworld", 1, 2);
        ChunkReadCounter.observed("minecraft:the_nether", -4, 7);
        ChunkReadCounter.ticketObserved(new Manager(), ((long) -9 << 32) | (3L & 0xffffffffL), new Ticket(), true);
        ChunkReadCounter.ticketObserved(new Manager(), ((long) -9 << 32) | (3L & 0xffffffffL), new Ticket(), false);
        ChunkReadCounter.schedulingObserved(new MapLike(), ((long) -9 << 32) | (3L & 0xffffffffL), 33, false);
        ChunkReadCounter.write(); String json = Files.readString(output);
        if (!json.contains("\"totalReads\":3") || !json.contains("\"reads\":2") || !json.contains("minecraft:the_nether")
                || !json.contains("\"ticketEvents\":2") || !json.contains("probe_ticket") || !json.contains("\"x\":3")
                || !json.contains("\"z\":-9") || !json.contains("topScheduling") || !json.contains("schedulingStacks") || !json.contains("\"reflectionErrors\":0")) throw new AssertionError(json);
        Files.deleteIfExists(output);
    }
}
