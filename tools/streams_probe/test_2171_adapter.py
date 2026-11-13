"""JDK-only adapter-layout regression for the installed Streams 2.17.1 JAR."""
from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path

from audit_2171 import audit_2171


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2171-review" / "adapter-test"
SOURCES = (
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverProfile.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalConfig.java",
    ROOT / "tools/streams_probe/Streams2162AdapterHarness.java",
)


def find_jdk(requested: Path | None) -> Path:
    candidates = [requested, Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None,
                  Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21")]
    for candidate in candidates:
        if candidate and all((candidate / "bin" / f"{name}.exe").is_file() for name in ("java", "javac", "javap")):
            return candidate
    raise SystemExit("JDK 21 with java/javac/javap is required")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    jdk, jar = find_jdk(args.java_home), args.jar.resolve()
    receipt = audit_2171(jdk, jar)
    if not receipt["passed"]:
        raise SystemExit("Streams 2.17.1 static audit failed: " + "; ".join(receipt["failures"]))
    OUT.mkdir(parents=True, exist_ok=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-cp", str(jar), "-d", str(OUT), *map(str, SOURCES)],
                   cwd=ROOT, check=True, creationflags=flags)
    result = subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", os.pathsep.join((str(OUT), str(jar))),
                             "com.github.xandergos.terraindiffusionmc.diagnostic.Streams2162AdapterHarness"],
                            cwd=ROOT, check=True, capture_output=True, text=True, creationflags=flags)
    if "Streams2162AdapterHarness: PASS" not in result.stdout:
        raise SystemExit("adapter harness did not report success: " + result.stdout + result.stderr)
    print("Streams2171AdapterTest: PASS native boundary + " + result.stdout.strip())


if __name__ == "__main__":
    main()
