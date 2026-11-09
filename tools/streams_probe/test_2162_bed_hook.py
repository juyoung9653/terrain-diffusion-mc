"""Static, fail-closed audit for the Streams 2.16.2 shallow-bed hook.

This only reads the distributed Streams bytecode and TD mixin source.  It never
starts Minecraft or generates chunks.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIXIN = ROOT / (
    "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/"
    "StreamsShallowBed2162Mixin.java"
)
TARGET = "dev.streamsreflowing.worldgen.NoDecompilePermissionEver_001"
CARVER = "dev.streamsreflowing.worldgen.RiverCarverFeature"
DEPTH_DESCRIPTOR = (
    "(Ldev/streamsreflowing/core/compose/ColumnPlan;"
    "Ldev/streamsreflowing/core/config/StreamsConfig;DDDJIIIID)I"
)


def java_tool(name: str, java_home: Path | None) -> str:
    candidate = java_home / "bin" / f"{name}.exe" if java_home else None
    if candidate and candidate.is_file():
        return str(candidate)
    for known in (Path(r"C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.8.9-hotspot"),
                  Path(r"C:\\Program Files\\Java\\jdk-21")):
        candidate = known / "bin" / f"{name}.exe"
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name) or ""


def javap(javap_path: str, jar: Path, class_name: str, *flags: str) -> str:
    result = subprocess.run([javap_path, *flags, "-classpath", str(jar), class_name],
                            cwd=ROOT, text=True, capture_output=True,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode or "Compiled from" not in result.stdout:
        raise AssertionError(f"javap failed for {class_name}: {result.stderr[-600:]}")
    return result.stdout


def method_for_descriptor(text: str, descriptor: str) -> str:
    marker = "descriptor: " + descriptor
    at = text.find(marker)
    if at < 0:
        return ""
    declarations = list(re.finditer(r"(?m)^  (?:public|private|protected|static) .*", text))
    prior = [match for match in declarations if match.start() < at]
    assert prior, "method declaration preceding descriptor was not found"
    start = prior[-1].start()
    following = [match for match in declarations if match.start() > at]
    end = len(text) if not following else following[0].start()
    return text[start:end]


def default_jar() -> Path:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return Path("missing-StreamsReflowing-2.16.2.jar")
    return Path(appdata) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods" / (
        "StreamsReflowing-1.21.1-neoforge-2.16.2.jar"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, default=default_jar())
    parser.add_argument("--java-home", type=Path,
                        default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args = parser.parse_args()
    assert args.jar.is_file(), f"missing Streams JAR: {args.jar}"
    javap_path = java_tool("javap", args.java_home)
    assert javap_path, "javap was not found"

    target_signature = javap(javap_path, args.jar, TARGET, "-p", "-s")
    target = javap(javap_path, args.jar, TARGET, "-p", "-s", "-c")
    helper = method_for_descriptor(target, DEPTH_DESCRIPTOR)
    assert helper, "2.16.2 depth helper descriptor missing"
    public_depth_helpers = re.findall(
        r"(?ms)^  public static int proprietary1\([^;]+;\n    descriptor: "
        + re.escape(DEPTH_DESCRIPTOR), target_signature)
    assert len(public_depth_helpers) == 1, "public static depth helper is no longer unique"
    assert re.search(r"(?:private|public|protected) static int proprietary1\(", helper), (
        "depth helper is no longer static int proprietary1"
    )

    carver = javap(javap_path, args.jar, CARVER, "-p", "-s", "-c")
    place = method_for_descriptor(
        carver, "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    assert place, "RiverCarverFeature.place descriptor missing"
    invocation = "NoDecompilePermissionEver_001.proprietary1:" + DEPTH_DESCRIPTOR
    assert place.count(invocation) == 1, "carver no longer has one depth-helper invocation"

    source = MIXIN.read_text(encoding="utf-8")
    required = (
        '@Mixin(targets = "dev.streamsreflowing.worldgen.NoDecompilePermissionEver_001", remap = false)',
        "@ModifyReturnValue(method = DEPTH_HELPER, at = @At(\"RETURN\"), require = 1, remap = false)",
        "private static int terrainDiffusion$preservePlannedShallowBed",
        "StreamsNaturalRiverAdapter.active(config)",
        "StreamsNaturalRiverAdapter.version(config)",
        'StreamsRecordAccess.read(plan, "inChannel")',
        'StreamsRecordAccess.read(plan, "source")',
        'StreamsRecordAccess.read(plan, "terminal")',
        'StreamsRecordAccess.number(plan, "waterSurfaceY")',
        'StreamsRecordAccess.number(plan, "bedY")',
        "ShallowRiverBedPolicy.normalizeDepth",
    )
    missing = [item for item in required if item not in source]
    assert not missing, "mixin guard contract missing: " + ", ".join(missing)
    assert "source()" not in source and "terminal()" not in source, (
        "mixin must use the audited record access mapping for source/terminal"
    )
    print("StreamsShallowBed2162HookTest: PASS", args.jar.name)


if __name__ == "__main__":
    main()
