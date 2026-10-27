"""Compile and exercise the dependency-free Hybrid Aquatic world-profile marker."""

import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/hybridaquatic/HybridAquaticWorldProfile.java"

HARNESS = r'''
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticWorldProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class HybridAquaticWorldProfileTest {
    private static final String NAME = "terrain_diffusion_hybrid_aquatic.properties";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void fails(String message, Throwing action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    private static Path marker(Path world) { return world.resolve("data").resolve(NAME); }
    private static void profile(Path world, String version, String enabled, String seed) throws Exception {
        Files.createDirectories(marker(world).getParent());
        Properties values = new Properties();
        values.setProperty("profileVersion", version);
        values.setProperty("enabled", enabled);
        values.setProperty("seed", seed);
        try (var writer = Files.newBufferedWriter(marker(world))) { values.store(writer, "test"); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("worlds");
        Files.createDirectories(root);
        try {
            Path freshOn = root.resolve("fresh-on");
            check(HybridAquaticWorldProfile.resolve(freshOn, true, 91L, true), "fresh enabled world");
            check(Files.isRegularFile(marker(freshOn)), "fresh marker written");
            Properties parsed = new Properties();
            try (var reader = Files.newBufferedReader(marker(freshOn))) { parsed.load(reader); }
            check("1".equals(parsed.getProperty("profileVersion")), "version persisted");
            check("true".equals(parsed.getProperty("enabled")), "enabled persisted");
            check("91".equals(parsed.getProperty("seed")), "seed persisted");
            check(HybridAquaticWorldProfile.resolve(freshOn, false, 91L, true), "enabled restart sticky");

            Path freshOff = root.resolve("fresh-off");
            check(!HybridAquaticWorldProfile.resolve(freshOff, true, 92L, false), "fresh disabled world");
            check(!HybridAquaticWorldProfile.resolve(freshOff, false, 92L, true), "disabled restart stays disabled");

            Path old = root.resolve("initialized-without-marker");
            Files.createDirectories(old);
            check(!HybridAquaticWorldProfile.resolve(old, false, 93L, true), "old unmarked disabled");
            check(!Files.exists(marker(old)), "old unmarked remains untouched");

            fails("wrong seed rejected", () -> HybridAquaticWorldProfile.resolve(freshOn, false, 94L, true));
            Path wrongVersion = root.resolve("wrong-version");
            profile(wrongVersion, "2", "true", "95");
            fails("wrong version rejected", () -> HybridAquaticWorldProfile.resolve(wrongVersion, false, 95L, true));
            Path corrupt = root.resolve("corrupt");
            Files.createDirectories(marker(corrupt).getParent());
            Files.writeString(marker(corrupt), "not a properties marker\n");
            fails("corrupt marker rejected", () -> HybridAquaticWorldProfile.resolve(corrupt, false, 96L, true));
            fails("enabled profile cannot disable config", () -> HybridAquaticWorldProfile.resolve(freshOn, false, 91L, false));

            Path independentA = root.resolve("independent-a");
            Path independentB = root.resolve("independent-b");
            check(HybridAquaticWorldProfile.resolve(independentA, true, 101L, true), "independent A enabled");
            check(!HybridAquaticWorldProfile.resolve(independentB, true, 202L, false), "independent B disabled");
            check(HybridAquaticWorldProfile.resolve(independentA, false, 101L, true), "A unchanged by B");
            check(!HybridAquaticWorldProfile.resolve(independentB, false, 202L, true), "B unchanged by A");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
'''


def main() -> None:
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(java_home / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
    java = str(java_home / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK required; set JAVA_HOME")
    scratch = ROOT / ".tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hybrid-aquatic-world-profile-", dir=scratch) as directory:
        output = Path(directory)
        harness = output / "HybridAquaticWorldProfileTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run([javac, "--release", "21", "-d", directory, str(SOURCE), str(harness)], check=True)
        subprocess.run([java, "-cp", directory, "HybridAquaticWorldProfileTest", directory], check=True)
    print("PASS: HybridAquaticWorldProfile")


if __name__ == "__main__":
    main()
