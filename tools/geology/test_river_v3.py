"""Compile the pure V3 river checks and prove V2 matches the committed model."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "underground-river-v3-test"
PACKAGE = Path("com/github/xandergos/terraindiffusionmc/world/geology")
FILES = ("GeologyModel.java", "LegacyUndergroundRiverModel.java", "UndergroundRiverModel.java")
FROZEN_V2_DIGEST = "c1d8dea703caa373d48e83ad8c5e8307c1e73557246d1f86959354fbaaf5afe9"


def java_home() -> Path:
    candidates = [os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21",
                  r"C:\Program Files\Android\Android Studio\jbr"]
    for value in candidates:
        if value and (Path(value) / "bin" / "javac.exe").is_file():
            return Path(value)
    raise SystemExit("JDK 21 is required")


def run(command: list[str]) -> str:
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, creationflags=flags)
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    result.check_returncode()
    return result.stdout.strip()


def snapshot_source() -> str:
    return """import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRiverModel;
import java.security.MessageDigest;
public final class Snapshot {
  public static void main(String[] args) throws Exception {
    MessageDigest digest=MessageDigest.getInstance(\"SHA-256\");
    UndergroundRiverModel m=new UndergroundRiverModel(6187795302574383407L,2,2);
    for(int x=-960;x<=960;x+=31) for(int z=-800;z<=800;z+=29) {
      Object c=m.column(x,z); digest.update((x+\",\"+z+\":\"+String.valueOf(c)+\"\\n\").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    for(int x=-4;x<=3;x++) for(int z=-3;z<=4;z++) digest.update(m.reaches(x,z).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for(byte b:digest.digest()) System.out.printf(\"%02x\", b); System.out.println();
  }
}"""


def compile_snapshot(name: str, revision: str | None, home: Path) -> str:
    source_root = OUT / name / "src"
    classes = OUT / name / "classes"
    if source_root.exists(): shutil.rmtree(source_root)
    destination = source_root / PACKAGE
    destination.mkdir(parents=True)
    for filename in FILES:
        target = destination / filename
        if revision:
            target.write_bytes(subprocess.check_output(["git", "show", f"{revision}:common/src/main/java/{PACKAGE.as_posix()}/{filename}"], cwd=ROOT))
        else:
            target.write_bytes((ROOT / "common/src/main/java" / PACKAGE / filename).read_bytes())
    harness = source_root / "Snapshot.java"; harness.write_text(snapshot_source(), encoding="utf-8")
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir(parents=True)
    run([str(home / "bin" / "javac.exe"), "--release", "21", "-d", str(classes),
         *map(str, destination.glob("*.java")), str(harness)])
    return run([str(home / "bin" / "java.exe"), "-cp", str(classes), "Snapshot"])


def main() -> None:
    home = java_home(); OUT.mkdir(parents=True, exist_ok=True)
    historical = compile_snapshot("checkpoint-v2", "33e2816", home)
    current = compile_snapshot("current-v2", None, home)
    if historical != FROZEN_V2_DIGEST:
        raise AssertionError(f"checkpoint V2 digest unexpectedly changed: {historical}")
    if current != FROZEN_V2_DIGEST:
        raise AssertionError(f"V2 changed: expected={FROZEN_V2_DIGEST}, current={current}")
    classes = OUT / "v3-classes"; classes.mkdir(parents=True, exist_ok=True)
    sources = [ROOT / "common/src/main/java" / PACKAGE / filename for filename in FILES]
    sources.append(ROOT / "tools/geology/UndergroundRiverV3Test.java")
    run([str(home / "bin" / "javac.exe"), "--release", "21", "-d", str(classes), *map(str, sources)])
    run([str(home / "bin" / "java.exe"), "-ea", "-cp", str(classes), "UndergroundRiverV3Test"])
    print(f"V2 frozen digest: {current}")


if __name__ == "__main__":
    main()
