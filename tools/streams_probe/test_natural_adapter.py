"""Compile and smoke-test the natural Streams adapter against each audited local JAR.

This is deliberately a JDK-only check. It neither launches NeoForge nor generates a world.
"""
from __future__ import annotations

import argparse
import subprocess
import os
import re
from pathlib import Path

from audit_streams_compat import audit_jar

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "natural-adapter-test"
JAVA_HOME: Path | None = None
JAVAC: Path | None = None
JAVA: Path | None = None
JAVAP: Path | None = None
SOURCES = [
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverProfile.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverMorphology.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverWatersheds.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverHierarchy.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalConfig.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/Streams2162NetworkAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalNetwork.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsWatershedNetwork.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsHierarchyNetwork.java",
    ROOT / "tools/streams_probe/NaturalAdapterHarness.java",
]
FROZEN_COMPARISON = ROOT / "tools/streams_probe/FrozenProfileComparison.java"
JARS = {
    "2.13.1": ROOT / "build/streams-probe/minimal-12345/mods/StreamsReflowing-1.21.1-neoforge-2.13.1.jar",
    "2.13.5": ROOT / "build/scale5-natural/v2135/mods/StreamsReflowing-1.21.1-neoforge-2.13.5.jar",
    "2.13.8": Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods/StreamsReflowing-1.21.1-neoforge-2.13.8.jar",
}


def run(command: list[str], allow_javap_close_error: bool = False) -> str:
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    # This Windows JDK reports AccessDeniedException while closing a JAR below the
    # Unicode workspace path after it has emitted complete javap output.  Treat only
    # that known close-time diagnostic as nonfatal; every asserted layout still comes
    # from javap's actual output.
    if allow_javap_close_error and "AccessDeniedException:" in result.stderr:
        return result.stdout
    if result.returncode:
        raise RuntimeError("command failed: " + " ".join(command) + "\n" + result.stderr[-2000:])
    return result.stdout + result.stderr


def bytecode_layout(jar: Path, version: str) -> None:
    assert JAVAP is not None
    worldgen = run([str(JAVAP), "-p", "-s", "-c", "-classpath", str(jar), "dev.streamsreflowing.worldgen.StreamsWorldgen"], True)
    tracer = run([str(JAVAP), "-p", "-s", "-c", "-classpath", str(jar), "dev.streamsreflowing.core.river.NetworkTracer"], True)
    config = run([str(JAVAP), "-p", "-s", "-classpath", str(jar), "dev.streamsreflowing.core.config.StreamsConfig"], True)
    hydraulic = run([str(JAVAP), "-p", "-s", "-classpath", str(jar), "dev.streamsreflowing.core.config.HydraulicGeometry"], True)
    carver = run([str(JAVAP), "-p", "-s", "-c", "-classpath", str(jar), "dev.streamsreflowing.worldgen.RiverCarverFeature"], True)
    blacklist = run([str(JAVAP), "-p", "-s", "-classpath", str(jar), "dev.streamsreflowing.worldgen.StreamBiomeBlacklist"], True)
    column = run([str(JAVAP), "-p", "-s", "-classpath", str(jar), "dev.streamsreflowing.core.compose.ColumnPlan"], True)
    engine = run([str(JAVAP), "-p", "-s", "-c", "-classpath", str(jar), "dev.streamsreflowing.core.RiverEngine"], True)
    lake = run([str(JAVAP), "-p", "-s", "-classpath", str(jar), "dev.streamsreflowing.core.river.Lake"], True)
    require("engine(net.minecraft.world.level.WorldGenLevel);" in worldgen, "StreamsWorldgen.engine(WorldGenLevel) missing")
    defaults = "StreamsConfig.defaults:(IDIIIIIZ)" if version == "2.15.2" else "StreamsConfig.defaults:(IDIII)"
    require(defaults in worldgen, "engine no longer calls " + defaults)
    # 2.13.x maps most private names to 'a'; check the stable public build entrypoint
    # and the private assembly shape which receives config plus the segment list.
    normalized_tracer = " ".join(tracer.split())
    normalized_worldgen = " ".join(worldgen.split())
    require("build(" in tracer and "StreamsConfig, long" in tracer, "NetworkTracer.build signature missing")
    require('RiverEngine."<init>":(JLdev/streamsreflowing/core/config/StreamsConfig;Ldev/streamsreflowing/core/terrain/TerrainSampler;Ldev/streamsreflowing/core/terrain/WetnessSampler;)V' in normalized_worldgen,
            "Worldgen.engine RiverEngine(seed, config, terrain, wetness) constructor call missing")
    require('FileNetworkStore."<init>":(Ljava/nio/file/Path;JLdev/streamsreflowing/core/config/StreamsConfig;)V' in normalized_worldgen,
            "Worldgen.engine FileNetworkStore(path, seed, config) constructor call missing")
    require("assemble(dev.streamsreflowing.core.plate.PlateKey, java.util.List<dev.streamsreflowing.core.river.RiverSegment>, java.util.List<dev.streamsreflowing.core.river.Lake>, dev.streamsreflowing.core.config.StreamsConfig);" in normalized_tracer,
            "NetworkTracer base assemble signature missing")
    if version in {"2.13.5", "2.13.8", "2.15.2"}:
        require("assemble(dev.streamsreflowing.core.plate.PlateKey, java.util.List<dev.streamsreflowing.core.river.RiverSegment>, java.util.List<dev.streamsreflowing.core.river.Lake>, java.util.List<dev.streamsreflowing.core.api.Sink>, dev.streamsreflowing.core.Relief, dev.streamsreflowing.core.config.StreamsConfig);" in normalized_tracer,
                "NetworkTracer sinks/relief assemble signature missing")
    require("java.util.List<dev.streamsreflowing.core.river.NetworkTracer$Pt>, dev.streamsreflowing.core.config.StreamsConfig, java.util.List<dev.streamsreflowing.core.river.RiverSegment>" in tracer,
            "NetworkTracer segment assembly signature missing")
    for field in ("minRiverAccumulation", "gridSpacing", "minSeaChainNodes", "minLengthScope", "confineToBanks", "hydraulics"):
        require((" " + field + ";") in config, "StreamsConfig component missing: " + field)
    for field in ("bendAsymmetry", "channelBars"):
        require((" " + field + ";") in hydraulic, "HydraulicGeometry component missing: " + field)
    normalized_carver = " ".join(carver.split())
    place = carver.split("public boolean place(", 1)[1].split("\n  private ", 1)[0]
    writes = re.findall(r"(?m)^\s*(\d+): invokeinterface .*WorldGenLevel\.setBlock:", place)
    dry_air_offsets = {"2.13.1": "6982", "2.13.5": "7198", "2.13.8": "7843", "2.15.2": "8960"}
    dry_ordinal = 7 if version == "2.15.2" else 6
    require(len(writes) > dry_ordinal and writes[dry_ordinal] == dry_air_offsets[version],
            "RiverCarverFeature dry-bank AIR write moved; re-audit its branch before applying v6 retention")
    normalized_blacklist = " ".join(blacklist.split())
    normalized_column = " ".join(column.split())
    normalized_engine = " ".join(engine.split())
    require("lakeNear(int, int, int); descriptor: (III)Ldev/streamsreflowing/core/river/Lake;" in normalized_engine,
            "RiverEngine.lakeNear cell-radius lookup missing")
    require("level(); descriptor: ()I" in " ".join(lake.split()), "Lake.level accessor missing")
    require("a(net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.ChunkPos); descriptor: (Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/ChunkPos;)[I" in normalized_carver,
            "RiverCarverFeature shield-array reflection signature missing")
    require("a(int[], int, int, int); descriptor: ([IIII)Z" in normalized_carver,
            "RiverCarverFeature shield predicate reflection signature missing")
    require("isBlocked(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>); descriptor: (Lnet/minecraft/core/Holder;)Z" in normalized_blacklist,
            "StreamBiomeBlacklist.isBlocked(Holder) missing")
    require("descriptor: (ZZIIDDDDDDDZZDDDDDDDDD)V" in normalized_column,
            "ColumnPlan full 22-field constructor signature missing")
    for accessor, descriptor in (("inChannel", "()Z"), ("inCorridor", "()Z"), ("waterSurfaceY", "()I"),
                                 ("bedY", "()I"), ("bankBlend", "()D"), ("distance", "()D"), ("width", "()D"),
                                 ("gradeY", "()D"), ("slope", "()D"), ("flowDirX", "()D"), ("flowDirZ", "()D"),
                                 ("terminal", "()Z"), ("source", "()Z"), ("upstreamSlope", "()D"),
                                 ("fluidFlowDirX", "()D"), ("fluidFlowDirZ", "()D"), ("distToOutlet", "()D"),
                                 ("curvature", "()D"), ("lateral", "()D"), ("distToJunction", "()D"),
                                 ("flatRun", "()D"), ("speed", "()D")):
        require(accessor + "(); descriptor: " + descriptor in normalized_column,
                "ColumnPlan accessor missing: " + accessor)
    for method, descriptor in (("config", "()Ldev/streamsreflowing/core/config/StreamsConfig;"),
                               ("plan", "(II)Ldev/streamsreflowing/core/compose/ColumnPlan;"),
                               ("planLean", "(II)Ldev/streamsreflowing/core/compose/ColumnPlan;"),
                               ("lakeNearChunk", "(III)Z")):
        require(method + "(" in normalized_engine and "descriptor: " + descriptor in normalized_engine,
                "RiverEngine reflected method missing: " + method)
    require("a(int, int, java.lang.String, int, int); descriptor: (IILjava/lang/String;II)I" in normalized_carver,
            "RiverCarverFeature raster method signature missing")
    require("Method a:(IILjava/lang/String;II)I" in normalized_carver,
            "RiverCarverFeature.place no longer invokes raster method")
    factory_descriptor = ("a(double, double, dev.streamsreflowing.core.river.RiverSample, boolean); descriptor: "
                          "(DDLdev/streamsreflowing/core/river/RiverSample;Z)Ldev/streamsreflowing/core/compose/ColumnPlan;"
                          if version == "2.13.1" else
                          "a(double, double, dev.streamsreflowing.core.river.RiverSample, boolean, dev.streamsreflowing.core.plate.PlateKey[]); descriptor: "
                          "(DDLdev/streamsreflowing/core/river/RiverSample;Z[Ldev/streamsreflowing/core/plate/PlateKey;)Ldev/streamsreflowing/core/compose/ColumnPlan;")
    require(factory_descriptor in normalized_engine,
            "RiverEngine version-specific shared RiverSample-to-ColumnPlan factory signature missing")
    require("insetFor(double, dev.streamsreflowing.core.config.StreamsConfig); descriptor: (DLdev/streamsreflowing/core/config/StreamsConfig;)I" in normalized_engine,
            "RiverEngine.insetFor(double, StreamsConfig) signature missing")
    require("Method java/lang/Math.min:(DD)D" in normalized_engine and "Method java/lang/Math.round:(D)J" in normalized_engine,
            "RiverEngine inset bytecode no longer uses native min/round formula")
    effective_inset_descriptor = ("a(double, double, double, double, double, double); descriptor: (DDDDDD)I"
                                if version == "2.13.1" else
                                "a(double, double, double, double, double, double, dev.streamsreflowing.core.plate.PlateKey[]); descriptor: "
                                "(DDDDDD[Ldev/streamsreflowing/core/plate/PlateKey;)I")
    require(effective_inset_descriptor in normalized_engine and "Method insetFor:(DLdev/streamsreflowing/core/config/StreamsConfig;)I" in normalized_engine,
            "RiverEngine version-specific effective inset method no longer delegates to insetFor")


def compare_frozen_profiles(jars: dict[str, Path], frozen_jar: Path) -> None:
    assert JAVAC is not None and JAVA is not None
    require(frozen_jar.is_file(), "frozen profile JAR missing: " + str(frozen_jar))
    run([str(JAVAC), "--release", "21", "-cp", str(OUT), "-d", str(OUT), str(FROZEN_COMPARISON)])
    for version, jar in jars.items():
        output = run([str(JAVA), "-ea", "-cp", str(OUT), "FrozenProfileComparison",
                      str(frozen_jar), str(jar), str(OUT), "2", "5", "6"])
        print("frozen " + version + ": " + output.strip())


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


def find_java_home(requested: Path | None) -> Path:
    candidates = []
    if requested is not None:
        candidates.append(requested)
    environment = os.environ.get("JAVA_HOME")
    if environment:
        candidates.append(Path(environment))
    candidates.extend((Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21"), Path(r"C:\Program Files\Microsoft\jdk-21")))
    for candidate in candidates:
        if (candidate / "bin" / "javac.exe").is_file() and (candidate / "bin" / "java.exe").is_file() and (candidate / "bin" / "javap.exe").is_file():
            return candidate
    raise SystemExit("JDK 21 with javac, java and javap is required; use --java-home")


def parse_jars(values: list[str] | None) -> dict[str, Path]:
    if not values:
        return JARS
    parsed = {}
    for value in values:
        version, separator, path = value.partition("=")
        if not separator or not version or not path:
            raise SystemExit("--jar must be VERSION=PATH")
        if version in parsed:
            raise SystemExit("duplicate --jar version: " + version)
        parsed[version] = Path(path)
    return parsed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="JDK 21 home; defaults to JAVA_HOME then known installed locations")
    parser.add_argument("--jar", action="append", metavar="VERSION=PATH", help="repeatable audited native JAR override")
    parser.add_argument("--frozen-jar", type=Path, help="preserved JAR for configuration comparison through its supported profile version")
    args = parser.parse_args()
    global JAVA_HOME, JAVAC, JAVA, JAVAP
    JAVA_HOME = find_java_home(args.java_home)
    JAVAC, JAVA, JAVAP = (JAVA_HOME / "bin" / name for name in ("javac.exe", "java.exe", "javap.exe"))
    jars = parse_jars(args.jar)
    missing = [version for version, jar in jars.items() if not jar.is_file()]
    if missing:
        raise SystemExit("audited Streams JAR(s) not found: " + ", ".join(missing))
    OUT.mkdir(parents=True, exist_ok=True)
    assert JAVAC is not None and JAVA is not None
    run([str(JAVAC), "--release", "21", "-d", str(OUT), *map(str, SOURCES)])
    failures = []
    for version, jar in jars.items():
        try:
            bytecode_layout(jar, version)
            if version in {"2.13.5", "2.13.8", "2.15.2"}:
                compatibility = audit_jar(JAVA_HOME, jar, version)
                require(compatibility["passed"], "Streams compatibility audit failed: " + "; ".join(compatibility["failures"]))
            output = run([str(JAVA), "-ea", "-cp", str(OUT) + ";" + str(jar),
                          "com.github.xandergos.terraindiffusionmc.diagnostic.NaturalAdapterHarness"])
            print(version + ": " + output.strip())
        except (AssertionError, RuntimeError) as error:
            lines = str(error).splitlines()
            detail = next((line.strip() for line in lines if "AssertionError:" in line or "IllegalStateException:" in line), lines[-1])
            failures.append(version + ": " + detail)
            print(version + ": MISMATCH " + failures[-1].split(": ", 1)[1])
    if args.frozen_jar is None:
        print("frozen: SKIPPED (pass --frozen-jar PATH to compare profiles 1-3)")
    else:
        try:
            compare_frozen_profiles(jars, args.frozen_jar)
        except (AssertionError, RuntimeError) as error:
            failures.append("frozen: " + str(error).splitlines()[-1])
            print("frozen: MISMATCH " + failures[-1].split(": ", 1)[1])
    if failures:
        raise SystemExit("native adapter smoke mismatch:\n" + "\n".join(failures))


if __name__ == "__main__":
    main()
