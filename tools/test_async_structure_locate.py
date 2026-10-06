"""Exercise production async locate admission/cleanup with controlled Minecraft stubs.

No game, models, Gradle, or installed instance changes. Native mixin application is
covered separately by AsyncLocateAudit.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/AsyncStructureLocate.java"
STUBS = {
    "net.minecraft.world.level.ChunkPos": "public record ChunkPos(int x, int z) {}",
    "net.minecraft.world.level.chunk.status.ChunkStatus": "public class ChunkStatus { public static final ChunkStatus STRUCTURE_STARTS = new ChunkStatus(); }",
    "net.minecraft.world.level.chunk.ChunkAccess": "public class ChunkAccess {}",
    "net.minecraft.server.level.ChunkLevel": "public class ChunkLevel { public static int byStatus(Object status) { return 44; } }",
    "net.minecraft.server.level.TicketType": "public class TicketType<T> { public static <T> TicketType<T> create(String name, java.util.Comparator<T> order) { return new TicketType<>(); } }",
    "net.minecraft.server.level.ChunkResult": """
public record ChunkResult<T>(T value) {
    public T orElseThrow(java.util.function.Supplier<? extends RuntimeException> failure) { if (value == null) throw failure.get(); return value; }
    public String getError() { return "fixture failure"; }
}
""",
    "net.minecraft.server.MinecraftServer": """
import java.util.concurrent.*;
public class MinecraftServer implements AutoCloseable {
    private volatile Thread thread;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { thread = new Thread(r, "Fixture server"); return thread; });
    public boolean running = true;
    public boolean isSameThread() { return Thread.currentThread() == thread; }
    public boolean isRunning() { return running; }
    public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> action) { return CompletableFuture.supplyAsync(action, executor); }
    public void execute(Runnable action) { executor.execute(action); }
    public void close() { running = false; executor.shutdownNow(); }
}
""",
    "net.minecraft.server.level.ServerLevel": """
import net.minecraft.server.MinecraftServer;
public class ServerLevel {
    private final MinecraftServer server;
    public final ServerChunkCache chunks;
    public ServerLevel(MinecraftServer server) { this.server = server; chunks = new ServerChunkCache(server); }
    public MinecraftServer getServer() { return server; }
    public ServerChunkCache getChunkSource() { return chunks; }
}
""",
    "net.minecraft.server.level.ServerChunkCache": """
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.concurrent.*;
public class ServerChunkCache {
    private final MinecraftServer server;
    public final java.util.Set<Long> tickets = ConcurrentHashMap.newKeySet();
    public final BlockingQueue<CompletableFuture<ChunkResult<ChunkAccess>>> started = new LinkedBlockingQueue<>();
    public int maximum;
    public ServerChunkCache(MinecraftServer server) { this.server = server; }
    public void addRegionTicket(TicketType<Long> type, ChunkPos pos, int distance, Long id) {
        if (!server.isSameThread() || distance != -11 || !tickets.add(id)) throw new AssertionError("Invalid ticket admission");
        maximum = Math.max(maximum, tickets.size());
    }
    public void removeRegionTicket(TicketType<Long> type, ChunkPos pos, int distance, Long id) {
        if (!server.isSameThread() || !tickets.remove(id)) throw new AssertionError("Invalid ticket removal");
    }
    public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFuture(int x, int z, ChunkStatus status, boolean required) {
        if (server.isSameThread() || tickets.size() != 1) throw new AssertionError("Blocking or unticketed request");
        var future = new CompletableFuture<ChunkResult<ChunkAccess>>(); started.add(future); return future;
    }
}
""",
}

TEST = """
import com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.concurrent.*;
public class AsyncStructureLocateTest {
    record Worker(CompletableFuture<Boolean> result, Thread thread) {}
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static Worker worker(ServerLevel level) {
        var result = new CompletableFuture<Boolean>();
        var thread = new Thread(() -> {
            try { require(AsyncStructureLocate.getChunk(level, -2, 3) != null, "No exact chunk"); result.complete(Thread.currentThread().isInterrupted()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        thread.start(); return new Worker(result, thread);
    }
    static CompletableFuture<ChunkResult<ChunkAccess>> started(ServerLevel level) throws Exception {
        var future = level.chunks.started.poll(3, TimeUnit.SECONDS); require(future != null, "No chunk admission"); return future;
    }
    static void finish(CompletableFuture<ChunkResult<ChunkAccess>> future) { future.complete(new ChunkResult<>(new ChunkAccess())); }
    static void failed(Worker worker) throws Exception {
        try { worker.result.get(3, TimeUnit.SECONDS); throw new AssertionError("Expected failure"); }
        catch (ExecutionException | CancellationException expected) {}
    }
    static void drained(MinecraftServer server, ServerLevel level) throws Exception {
        require(server.submit(() -> level.chunks.tickets.isEmpty()).get(3, TimeUnit.SECONDS), "Leaked ticket");
    }
    public static void main(String[] args) throws Exception {
        try (var server = new MinecraftServer()) {
            var level = new ServerLevel(server);
            var first = worker(level); var one = started(level);
            var second = worker(level);
            require(level.chunks.started.poll(150, TimeUnit.MILLISECONDS) == null, "Parallel native admission");
            require(!server.submit(() -> false).get(1, TimeUnit.SECONDS), "Server callback blocked");
            finish(one); first.result.get(3, TimeUnit.SECONDS);
            var two = started(level); finish(two); second.result.get(3, TimeUnit.SECONDS); drained(server, level);
            require(level.chunks.maximum == 1, "Admission exceeded one ticket");
            var interrupted = worker(level); var active = started(level); interrupted.thread.interrupt();
            Thread.sleep(120); require(!interrupted.result.isDone() && level.chunks.tickets.size() == 1, "Interrupt abandoned admitted work");
            finish(active); require(interrupted.result.get(3, TimeUnit.SECONDS), "Interrupt flag not restored"); drained(server, level);
            var failure = worker(level); started(level).completeExceptionally(new IllegalStateException("fixture")); failed(failure); drained(server, level);
            require(server.submit(() -> { try { AsyncStructureLocate.getChunk(level, 0, 0); return false; } catch (IllegalStateException expected) { return true; } }).get(), "Mainthread wait admitted");
            var stopping = worker(level); started(level); var queued = worker(level);
            server.submit(() -> { AsyncStructureLocate.stop(server); return true; }).get();
            failed(stopping); failed(queued); drained(server, level); failed(worker(level));
        }
        try (var server = new MinecraftServer()) {
            var level = new ServerLevel(server);
            var blocked = new CountDownLatch(1); var release = new CountDownLatch(1);
            server.execute(() -> { blocked.countDown(); try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); } });
            require(blocked.await(1, TimeUnit.SECONDS), "No server gate");
            var stopped = server.submit(() -> { AsyncStructureLocate.stop(server); return true; });
            var late = worker(level); Thread.sleep(120); release.countDown(); stopped.get(); failed(late); drained(server, level);
            require(level.chunks.started.isEmpty(), "Queued callback admitted generation after stop");
        }
        System.out.println("AsyncStructureLocateTest: PASS (serial exact requests; responsive server; interrupted drain; failure cleanup; active/queued stop; late callback rejection)");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(java_home / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
    java = str(java_home / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
    if not javac or not java:
        parser.error("Pass --java-home for a JDK 21+ installation")
    with tempfile.TemporaryDirectory(prefix="td-locate-test-") as directory:
        target = Path(directory)
        sources = [str(SOURCE)]
        for name, body in STUBS.items():
            package, _, _ = name.rpartition(".")
            path = target / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + ";\n" + body, encoding="utf-8")
            sources.append(str(path))
        test = target / "AsyncStructureLocateTest.java"
        test.write_text(TEST, encoding="utf-8")
        sources.append(str(test))
        subprocess.run([javac, "--release", "21", "-d", directory, *sources], check=True)
        subprocess.run([java, "-cp", directory, "AsyncStructureLocateTest"], check=True, timeout=30)


if __name__ == "__main__":
    main()
