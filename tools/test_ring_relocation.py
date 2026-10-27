"""Exercise the production redirect with model-free Minecraft/Mixin API stubs."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/ChunkGeneratorStructureStateMixin.java"

STUBS = {
    "org.spongepowered.asm.mixin.Shadow": "public @interface Shadow {}",
    "org.spongepowered.asm.mixin.Final": "public @interface Final {}",
    "org.spongepowered.asm.mixin.Mutable": "public @interface Mutable {}",
    "org.spongepowered.asm.mixin.injection.Inject": "public @interface Inject { String[] method(); At at(); }",
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfo": "public class CallbackInfo {}",
    "net.minecraft.world.level.levelgen.structure.StructureSet": "public class StructureSet {}",
    "com.github.xandergos.terraindiffusionmc.world.structure.UnifiedVillageSets": """
public class UnifiedVillageSets {
    public static <T> java.util.List<T> rewrite(Object source, java.util.List<T> sets) { return sets; }
}
""",
    "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource": """
import net.minecraft.world.level.biome.BiomeSource;
public class InjectorBiomeSource extends BiomeSource {
    private final BiomeSource delegate;
    public InjectorBiomeSource(BiomeSource delegate) { this.delegate = delegate; }
    public BiomeSource rootDelegate() {
        return delegate instanceof InjectorBiomeSource wrapped ? wrapped.rootDelegate() : delegate;
    }
}
""",
    "com.mojang.datafixers.util.Pair": "public record Pair<A,B>(A first, B second) {}",
    "net.minecraft.core.BlockPos": "public record BlockPos(int x, int y, int z) {}",
    "net.minecraft.core.Holder": "public record Holder<T>(T value) {}",
    "net.minecraft.util.RandomSource": "public class RandomSource {}",
    "net.minecraft.world.level.biome.Biome": "public class Biome {}",
    "net.minecraft.world.level.biome.Climate": "public class Climate { public static class Sampler {} }",
    "net.minecraft.world.level.chunk.ChunkGeneratorStructureState": "public class ChunkGeneratorStructureState {}",
    "org.spongepowered.asm.mixin.Mixin": "public @interface Mixin { Class<?>[] value(); }",
    "org.spongepowered.asm.mixin.injection.At": 'public @interface At { String value(); String target() default ""; }',
    "org.spongepowered.asm.mixin.injection.Redirect": "public @interface Redirect { String[] method(); At at(); int require(); int allow(); }",
    "net.minecraft.world.level.biome.BiomeSource": """
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import java.util.function.Predicate;
public class BiomeSource {
    public int calls;
    public Object[] arguments;
    public Pair<BlockPos,Holder<Biome>> answer = new Pair<>(new BlockPos(7,8,9), new Holder<>(new Biome()));
    public Pair<BlockPos,Holder<Biome>> findBiomeHorizontal(int x,int y,int z,int r,
            Predicate<Holder<Biome>> p,RandomSource random,Climate.Sampler sampler) {
        calls++; arguments = new Object[]{x,y,z,r,p,random,sampler}; return answer;
    }
}
""",
    "com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource": """
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import java.util.function.Predicate;
public class TerrainDiffusionBiomeSource extends BiomeSource {
    public Pair<BlockPos,Holder<Biome>> findRingBiome(int x,int y,int z,int r,
            Predicate<Holder<Biome>> p,RandomSource random) {
        throw new AssertionError("Ring relocation must not start coarse or exact terrain work");
    }
}
""",
}

TEST = """
import com.github.xandergos.terraindiffusionmc.mixin.ChunkGeneratorStructureStateMixin;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import java.util.Arrays;
import java.util.function.Predicate;
public class RingRelocationTest {
    public static void main(String[] args) throws Exception {
        var hook = Arrays.stream(ChunkGeneratorStructureStateMixin.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("terrainDiffusion$findRingBiome")).findFirst().orElseThrow();
        hook.setAccessible(true);
        var mixin = new ChunkGeneratorStructureStateMixin();
        var terrain = new TerrainDiffusionBiomeSource();
        var random = new RandomSource();
        var sampler = new Climate.Sampler();
        Predicate<Holder<Biome>> forbidden = biome -> { throw new AssertionError("No biome evaluation at startup"); };
        for (int i = 0; i < 512; i++) {
            if (hook.invoke(mixin, terrain, i * 128 - 32000, 0, -i * 128, 112,
                    forbidden, random, sampler) != null) throw new AssertionError("Expected original-ring fallback");
        }
        if (terrain.calls != 0) throw new AssertionError("Terrain lookup was invoked");
        var wrapped = new dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource(terrain);
        var nested = new dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource(wrapped);
        for (var source : new BiomeSource[]{wrapped, nested}) {
            if (hook.invoke(mixin, source, 1, 2, 3, 112, forbidden, random, sampler) != null
                    || source.calls != 0) throw new AssertionError("Wrapped terrain queried at startup");
        }
        var wrappedOther = new dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource(new BiomeSource());
        if (hook.invoke(mixin, wrappedOther, 1, 2, 3, 112, forbidden, random, sampler) != wrappedOther.answer
                || wrappedOther.calls != 1) throw new AssertionError("Wrapped ordinary world changed");
        if (wrapped.findBiomeHorizontal(1,2,3,112,forbidden,random,sampler) != wrapped.answer
                || wrapped.calls != 1) throw new AssertionError("Ordinary wrapped search intercepted");
        var other = new BiomeSource();
        Object[] expected = {-53,17,96,112,forbidden,random,sampler};
        var answer = hook.invoke(mixin, other, -53,17,96,112,forbidden,random,sampler);
        if (answer != other.answer || other.calls != 1 || !Arrays.equals(other.arguments, expected))
            throw new AssertionError("Non-Terrain-Diffusion forwarding changed");
        other.answer = null;
        if (hook.invoke(mixin, other, -53,17,96,112,forbidden,random,sampler) != null)
            throw new AssertionError("Non-Terrain-Diffusion null result changed");
        if (terrain.findBiomeHorizontal(-53,17,96,112,forbidden,random,sampler) != terrain.answer
                || terrain.calls != 1) throw new AssertionError("Ordinary lookup intercepted");
        System.out.println("RingRelocationTest: PASS (512 direct calls; wrapped/nested terrain skipped; ordinary sources and searches forwarded)");
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
    if not java or not javac:
        parser.error("JDK required; supply --java-home")
    with tempfile.TemporaryDirectory(prefix="ring-relocation-") as directory:
        target = Path(directory)
        sources = [str(SOURCE), str(ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/RingBiomeSourcePolicy.java")]
        for name, body in STUBS.items():
            package, _, cls = name.rpartition(".")
            path = target / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + ";\n" + body, encoding="utf-8")
            sources.append(str(path))
        test = target / "RingRelocationTest.java"
        test.write_text(TEST, encoding="utf-8")
        sources.append(str(test))
        subprocess.run([javac, "--release", "21", "-d", directory, *sources], check=True)
        subprocess.run([java, "-cp", directory, "RingRelocationTest"], check=True)


if __name__ == "__main__":
    main()
