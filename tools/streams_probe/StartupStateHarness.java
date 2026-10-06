import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStartupState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StartupStateHarness {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        long seed = 4280182694740400482L;
        Path fresh = root.resolve("fresh");
        check(StreamsStartupState.resolve(fresh, true, seed, 1), "fresh world selects exact");
        byte[] original = Files.readAllBytes(fresh.resolve("data/terrain_diffusion_streams_exact_v1.txt"));
        Files.createDirectories(fresh.resolve("streamsreflowing/minecraft_overworld/td-natural-v7"));
        check(StreamsStartupState.resolve(fresh, false, seed, 1), "restart retains exact despite native caches");
        check(java.util.Arrays.equals(original, Files.readAllBytes(fresh.resolve("data/terrain_diffusion_streams_exact_v1.txt"))), "restart does not rewrite marker");
        fails(() -> StreamsStartupState.resolve(fresh, false, seed + 1, 1), "seed mismatch");
        fails(() -> StreamsStartupState.resolve(fresh, false, seed, 2), "scale mismatch");
        Path old = root.resolve("old-without-streams");
        check(!StreamsStartupState.resolve(old, false, seed, 1), "existing world without Streams state remains native");
        check(!Files.exists(old), "existing world is untouched");
        Path imported = root.resolve("imported");
        Files.createDirectories(imported.resolve("streamsreflowing"));
        check(!StreamsStartupState.resolve(imported, true, seed, 1), "native state blocks fresh opt-in");
        Path other = root.resolve("other-seed");
        check(StreamsStartupState.resolve(other, true, seed + 1, 1), "other world selects independent mode");
        Path bad = root.resolve("corrupt/data");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("terrain_diffusion_streams_exact_v1.txt"), "garbage");
        fails(() -> StreamsStartupState.resolve(bad.getParent(), true, seed, 1), "corruption fails closed");
        Path unwritable = root.resolve("file-as-world");
        Files.writeString(unwritable, "not a directory");
        fails(() -> StreamsStartupState.resolve(unwritable, true, seed, 1), "I/O failure fails closed");
        System.out.println("Startup state: 11 fresh, legacy, restart, world-isolation and failure assertions passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void fails(Checked action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
