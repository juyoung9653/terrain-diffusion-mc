"""JDK-only Streams 2.16.2 logical-record and natural-profile regression test."""
from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2162-review" / "adapter-test"
SOURCES = (
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverProfile.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalConfig.java",
    ROOT / "tools/streams_probe/Streams2162AdapterHarness.java",
)


def find_jdk(requested: Path | None) -> Path:
    candidates = ([requested] if requested else []) + [
        Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None,
        Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21")]
    for candidate in candidates:
        if candidate and (candidate / "bin/javac.exe").is_file() and (candidate / "bin/javap.exe").is_file():
            return candidate
    raise SystemExit("JDK 21 with javac and javap is required")


def inspect(jdk: Path, jar: Path, name: str) -> str:
    process = subprocess.run([str(jdk / "bin/javap.exe"), "-p", "-s", "-classpath", str(jar), name],
                             cwd=ROOT, text=True, capture_output=True)
    if process.returncode:
        raise RuntimeError(f"javap {name} failed: {process.stderr[-1000:]}")
    return process.stdout


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"missing JAR: {jar}")
    jdk = find_jdk(args.java_home)
    config = inspect(jdk, jar, "dev.streamsreflowing.core.config.StreamsConfig")
    hydraulic = inspect(jdk, jar, "dev.streamsreflowing.core.config.HydraulicGeometry")
    column = inspect(jdk, jar, "dev.streamsreflowing.core.compose.ColumnPlan")
    require("descriptor: (IDDIIIIIZZDDDDDIDDDDDDDIDIDDDZDZZDZZDDIIDDLjava/lang/String;Ldev/streamsreflowing/core/config/HydraulicGeometry;)V" in config,
            "StreamsConfig is not the audited 44-component layout")
    require("public static dev.streamsreflowing.core.config.StreamsConfig proprietary1();" in config,
            "StreamsConfig default factory changed")
    require("descriptor: (DDDDDDD)V" in hydraulic, "HydraulicGeometry layout changed")
    require("descriptor: (ZZIIDDDDDDDZZDDDDDDDDDD)V" in column, "ColumnPlan 23-component layout changed")
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-cp", str(jar),
                    "-d", str(OUT), *map(str, SOURCES)], cwd=ROOT, check=True)
    subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", os.pathsep.join((str(OUT), str(jar))),
                    "com.github.xandergos.terraindiffusionmc.diagnostic.Streams2162AdapterHarness"],
                   cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
