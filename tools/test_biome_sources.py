"""Compile and execute the production TD biome-source wrapper recognizer."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/TerrainDiffusionBiomeSources.java"

STUBS = {
    "net.minecraft.world.level.biome.BiomeSource": """
public class BiomeSource {
    public int biomeQueries;
    public Object getNoiseBiome() { biomeQueries++; return new Object(); }
}
""",
    "com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource": """
import net.minecraft.world.level.biome.BiomeSource;
public class TerrainDiffusionBiomeSource extends BiomeSource { }
""",
    "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource": """
import net.minecraft.world.level.biome.BiomeSource;
public class InjectorBiomeSource extends BiomeSource {
    private final BiomeSource root;
    private final boolean throwsOnAccess;
    public InjectorBiomeSource(BiomeSource source) { this(source, false); }
    public InjectorBiomeSource(BiomeSource source, boolean throwsOnAccess) {
        this.root = source instanceof InjectorBiomeSource wrapped ? wrapped.rootDelegate() : source;
        this.throwsOnAccess = throwsOnAccess;
    }
    public BiomeSource rootDelegate() {
        if (throwsOnAccess) throw new IllegalStateException("fixture failure");
        return root;
    }
}
""",
    "fixture.LookalikeBiomeSource": """
import net.minecraft.world.level.biome.BiomeSource;
public class LookalikeBiomeSource extends BiomeSource {
    private final BiomeSource root;
    public LookalikeBiomeSource(BiomeSource root) { this.root = root; }
    public BiomeSource rootDelegate() { return root; }
}
""",
}

HARNESS = """
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import fixture.LookalikeBiomeSource;
import net.minecraft.world.level.biome.BiomeSource;

public class BiomeSourcesTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TerrainDiffusionBiomeSource terrain = new TerrainDiffusionBiomeSource();
        BiomeSource vanilla = new BiomeSource();
        InjectorBiomeSource wrapped = new InjectorBiomeSource(terrain);
        InjectorBiomeSource nested = new InjectorBiomeSource(wrapped);
        InjectorBiomeSource vanillaWrapped = new InjectorBiomeSource(vanilla);
        InjectorBiomeSource throwing = new InjectorBiomeSource(terrain, true);
        LookalikeBiomeSource lookalike = new LookalikeBiomeSource(terrain);

        require(TerrainDiffusionBiomeSources.find(terrain) == terrain, "direct TD identity");
        require(TerrainDiffusionBiomeSources.find(wrapped) == terrain, "wrapped TD root");
        require(TerrainDiffusionBiomeSources.find(nested) == terrain, "nested TD root");
        require(TerrainDiffusionBiomeSources.isTerrainDiffusion(nested), "nested TD boolean");
        require(TerrainDiffusionBiomeSources.find(vanillaWrapped) == null, "wrapped vanilla rejected");
        require(!TerrainDiffusionBiomeSources.isTerrainDiffusion(vanillaWrapped), "wrapped vanilla boolean");
        require(TerrainDiffusionBiomeSources.find(lookalike) == null, "lookalike rejected");
        require(TerrainDiffusionBiomeSources.find(throwing) == null, "throwing accessor rejected");
        require(TerrainDiffusionBiomeSources.find(null) == null, "null rejected");
        require(terrain.biomeQueries == 0 && wrapped.biomeQueries == 0 && nested.biomeQueries == 0,
                "recognition must not bypass the active biome delegate");
        System.out.println("BiomeSourcesTest: PASS (direct, Lithostitched wrapper, nested, and safe rejection cases)");
    }
}
"""


def main() -> None:
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(java_home / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
    java = str(java_home / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK required; set JAVA_HOME")
    scratch = ROOT / ".tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="td-biome-sources-", dir=scratch) as directory:
        output = Path(directory)
        sources = [str(SOURCE)]
        for name, body in STUBS.items():
            package, _, _ = name.rpartition(".")
            path = output / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {package};\n{body}", encoding="utf-8")
            sources.append(str(path))
        harness = output / "BiomeSourcesTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(str(harness))
        subprocess.run([javac, "--release", "21", "-d", directory, *sources], check=True)
        subprocess.run([java, "-cp", directory, "BiomeSourcesTest"], check=True)


if __name__ == "__main__":
    main()
