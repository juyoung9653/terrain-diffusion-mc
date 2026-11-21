"""JDK-only contract test for the bounded Streams 2.17.3 marsh-mouth taper.

The behavioural harness compiles the production adapter with small, named fixture
classes.  A separate `javap` check verifies that the installed Streams JAR still
exposes the RiverNetwork methods the production access layer relies on.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import tempfile
from pathlib import Path
from test_2171_gate import find_jdk

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsMarshMouthNetwork2173.java"
HARNESS = ROOT / "tools/streams_probe/MarshMouth2173Harness.java"
PROCESS_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def installed_streams_jar() -> Path | None:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    mods = Path(appdata) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods"
    matches = list(mods.glob("StreamsReflowing-1.21.1-neoforge-2.17.3.jar"))
    return matches[0] if len(matches) == 1 else None


def write(source_root: Path, relative: str, contents: str) -> Path:
    path = source_root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents.lstrip("\ufeff"), encoding="utf-8")
    return path


def fixture_sources(source_root: Path) -> list[Path]:
    """Small API-shaped dependencies used only to exercise the pure graph adapter."""
    return [
        write(source_root, "com/github/xandergos/terraindiffusionmc/config/TerrainDiffusionConfig.java", """
package com.github.xandergos.terraindiffusionmc.config;
public final class TerrainDiffusionConfig {
    private TerrainDiffusionConfig() {}
    public static int tileSize() { return 16; }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/pipeline/LocalTerrainProvider.java", """
package com.github.xandergos.terraindiffusionmc.pipeline;
import java.util.ArrayList;
import java.util.List;
public final class LocalTerrainProvider {
    private record Range(int minX, int maxX, int water) {}
    private static final List<Range> WETLAND = new ArrayList<>();
    public static void clearWetlandWater() { WETLAND.clear(); }
    public static void addWetlandWater(int minX, int maxX, int water) {
        WETLAND.add(new Range(minX, maxX, water));
    }
    public HeightmapData fetchHeightmap(int minZ, int minX, int maxZ, int maxX) {
        HeightmapData data = new HeightmapData();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int worldX = minX + x;
            for (Range range : WETLAND) if (worldX >= range.minX && worldX <= range.maxX) {
                data.wetlandWaterLevels[z][x] = (short) range.water;
            }
        }
        return data;
    }
    public static final class HeightmapData {
        public final short[][] wetlandWaterLevels = new short[16][16];
    }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/Streams2171NetworkAdapter.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
public final class Streams2171NetworkAdapter { public static final int PROFILE = 14; }
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalRiverAdapter.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
public final class StreamsNaturalRiverAdapter {
    private static final LocalTerrainProvider PROVIDER = new LocalTerrainProvider();
    public static boolean active(Object config) { return true; }
    public static int version(Object config) { return 14; }
    public static LocalTerrainProvider providerForNaturalGraph(Object config) { return PROVIDER; }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsMarineSampling.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
import java.util.Map;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
public final class StreamsMarineSampling {
    public static boolean within16(int x, int z, Map<Long, LocalTerrainProvider.HeightmapData> tiles,
                                   LocalTerrainProvider provider) { return false; }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
import dev.streamsreflowing.core.river.Segment;
import java.util.Map;
public final class StreamsRecordAccess {
    public static double number(Object value, String name) {
        Segment segment = (Segment) value;
        return switch (name) {
            case "x1" -> segment.x1; case "z1" -> segment.z1; case "y1" -> segment.y1;
            case "w1" -> segment.w1; case "x2" -> segment.x2; case "z2" -> segment.z2;
            case "y2" -> segment.y2; case "w2" -> segment.w2;
            default -> throw new IllegalArgumentException(name);
        };
    }
    public static Object copy(Object value, Map<String, ?> replacements) {
        Segment s = (Segment) value;
        return new Segment(number(replacements, "x1", s.x1), number(replacements, "z1", s.z1),
                number(replacements, "y1", s.y1), number(replacements, "w1", s.w1),
                number(replacements, "x2", s.x2), number(replacements, "z2", s.z2),
                number(replacements, "y2", s.y2), number(replacements, "w2", s.w2));
    }
    private static double number(Map<String, ?> values, String name, double fallback) {
        Object value = values.get(name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/Streams2162NetworkAccess.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
import dev.streamsreflowing.core.river.RiverNetwork;
import dev.streamsreflowing.core.river.Sink;
import dev.streamsreflowing.core.river.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
public final class Streams2162NetworkAccess {
    public static boolean applies(Object network) { return network instanceof RiverNetwork; }
    public static List<?> segments(Object network) { return ((RiverNetwork) network).sourceGivenToAuthorizedOnly1(); }
    public static boolean contains(Object domain, double x, double z) { return true; }
    public static double borderDistance(Object domain, double x, double z) { return 64.0; }
    public static Object lakeNear(Object network, int x, int z, int radius) { return null; }
    public static long endpoint(Object value, String x, String z) {
        Segment segment = (Segment) value;
        return Math.round(segment.x2) << 32 ^ Math.round(segment.z2);
    }
    public static Object reassemble(Object network, List<?> segments, Object config,
                                    Set<Long> displacedSinks) {
        RiverNetwork original = (RiverNetwork) network;
        List<Sink> kept = new ArrayList<>();
        for (Sink sink : original.sinks()) {
            long key = Math.round(sink.x()) << 32 ^ Math.round(sink.z());
            if (!displacedSinks.contains(key)) kept.add(sink);
        }
        return new RiverNetwork(segments, kept);
    }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalNetwork.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
import dev.streamsreflowing.core.river.RiverNetwork;
import java.util.List;
public final class StreamsNaturalNetwork {
    public static boolean lastFilterSinks;
    public static Object reassemble(Object network, List<?> segments, Object config,
                                    boolean filterSinks) {
        lastFilterSinks = filterSinks;
        return new RiverNetwork(segments);
    }
}
"""),
        write(source_root, "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsDryGrade2173.java", """
package com.github.xandergos.terraindiffusionmc.compat.streams;
public final class StreamsDryGrade2173 {
    public static int calls;
    public static void reset() { calls = 0; }
    public static Object refine(Object network, Object domain, Object config) {
        calls++;
        return network;
    }
}
"""),
        write(source_root, "dev/streamsreflowing/core/plate/PlateLattice.java", """
package dev.streamsreflowing.core.plate;
public final class PlateLattice { }
"""),
        write(source_root, "dev/streamsreflowing/core/plate/PlateKey.java", """
package dev.streamsreflowing.core.plate;
public final class PlateKey { }
"""),
        write(source_root, "dev/streamsreflowing/core/plate/ProprietaryNiceJohn_001.java", """
package dev.streamsreflowing.core.plate;
public final class ProprietaryNiceJohn_001 {
    public ProprietaryNiceJohn_001(PlateLattice lattice, PlateKey key) { }
}
"""),
        write(source_root, "dev/streamsreflowing/core/river/Segment.java", """
package dev.streamsreflowing.core.river;
public final class Segment {
    public final double x1, z1, y1, w1, x2, z2, y2, w2;
    public Segment(double x1, double z1, double y1, double w1,
                   double x2, double z2, double y2, double w2) {
        this.x1=x1; this.z1=z1; this.y1=y1; this.w1=w1;
        this.x2=x2; this.z2=z2; this.y2=y2; this.w2=w2;
    }
}
"""),
        write(source_root, "dev/streamsreflowing/core/river/RiverNetwork.java", """
package dev.streamsreflowing.core.river;
import java.util.List;
public final class RiverNetwork {
    private final List<?> segments;
    private final List<Sink> sinks;
    public RiverNetwork(List<?> segments) {
        this(segments, List.of(new Sink(912, 0), new Sink(777, 777)));
    }
    public RiverNetwork(List<?> segments, List<Sink> sinks) {
        this.segments = List.copyOf(segments); this.sinks = List.copyOf(sinks);
    }
    public List<?> sourceGivenToAuthorizedOnly1() { return segments; }
    public List<Sink> sinks() { return sinks; }
}
"""),
        write(source_root, "dev/streamsreflowing/core/river/Sink.java", """
package dev.streamsreflowing.core.river;
public record Sink(double x, double z) { }
"""),
    ]


def require_streams_abi(jdk: Path, jar: Path) -> None:
    result = subprocess.run(
        [str(jdk / "bin/javap.exe"), "-p", "-classpath", str(jar),
         "dev.streamsreflowing.core.river.RiverNetwork"],
        cwd=ROOT, text=True, capture_output=True, creationflags=PROCESS_FLAGS,
    )
    if result.returncode:
        raise AssertionError("javap RiverNetwork failed: " + result.stderr[-1200:])
    for method in ("sourceGivenToAuthorizedOnly1", "noDecompilePermission1"):
        if method not in result.stdout:
            raise AssertionError("installed Streams 2.17.3 RiverNetwork lacks " + method)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", type=Path, help="installed Streams 2.17.3 JAR for ABI audit")
    args = parser.parse_args()
    jdk = find_jdk(args.java_home)
    jar = args.jar.resolve() if args.jar else installed_streams_jar()
    if jar is not None:
        if not jar.is_file():
            raise SystemExit("missing Streams JAR: " + str(jar))
        require_streams_abi(jdk, jar)

    (ROOT / "build/.tmp").mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="marsh-mouth-", dir=ROOT / "build/.tmp") as temporary:
        workspace = Path(temporary)
        source_root = workspace / "src"
        fixture = fixture_sources(source_root)
        production_adapter = write(
            source_root,
            "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsMarshMouthNetwork2173.java",
            ADAPTER.read_text(encoding="utf-8"),
        )
        harness = write(
            source_root,
            "com/github/xandergos/terraindiffusionmc/compat/streams/MarshMouth2173Harness.java",
            HARNESS.read_text(encoding="utf-8"),
        )
        compilation = subprocess.run(
            [str(jdk / "bin/javac.exe"), "--release", "21", "-d", str(workspace),
             *map(str, [*fixture, production_adapter, harness])],
            cwd=ROOT, text=True, capture_output=True, creationflags=PROCESS_FLAGS,
        )
        if compilation.returncode:
            raise AssertionError("marsh-mouth fixture compilation failed:\n" + compilation.stderr)
        result = subprocess.run(
            [str(jdk / "bin/java.exe"), "-ea", "-cp", str(workspace),
             "com.github.xandergos.terraindiffusionmc.compat.streams.MarshMouth2173Harness"],
            cwd=ROOT, text=True, capture_output=True, creationflags=PROCESS_FLAGS,
        )
        if result.returncode:
            raise AssertionError("marsh-mouth harness failed:\n" + result.stdout + result.stderr)
    expected = "MarshMouth2173Harness: PASS"
    if expected not in result.stdout:
        raise AssertionError("missing harness result: " + result.stdout + result.stderr)
    print(result.stdout.strip())
    print("Streams 2.17.3 ABI: " + (str(jar) if jar else "not supplied"))


if __name__ == "__main__":
    main()





