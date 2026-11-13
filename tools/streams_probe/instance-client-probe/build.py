#!/usr/bin/env python3
"""Build the strictly-gated NeoForge 21.1.264 instance diagnostic without Gradle."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.streams_probe.tree_ground_runtime import validate_tree_ground_runtime
OUT = HERE / "build"
CLASSES = OUT / "classes"
BASE_JAR = OUT / "td-instance-client-probe-1.0.0.jar"
FIX_JAR = OUT / "td-instance-tree-ground-fix-1.0.0.jar"
PRODUCTION_SOURCES = (
    ROOT / "neoforge" / "src" / "main" / "java" / "com" / "github" / "xandergos" / "terraindiffusionmc" / "compat" / "ecology" / "TreeGroundMaterialPolicy.java",
    ROOT / "neoforge" / "src" / "main" / "java" / "com" / "github" / "xandergos" / "terraindiffusionmc" / "mixin" / "SubmergedTreeGroundMixin.java",
)
RELOCATION = {
    "com.github.xandergos.terraindiffusionmc.compat.ecology": "com.terrain.instanceclientprobe.production.compat.ecology",
    "com.github.xandergos.terraindiffusionmc.mixin": "com.terrain.instanceclientprobe.production.mixin",
}

def load_launcher():
    source = ROOT / "tools" / "optimization_bench" / "launch.py"
    spec = importlib.util.spec_from_file_location("td_instance_probe_launch", source)
    if spec is None or spec.loader is None: raise RuntimeError(f"Cannot load {source}")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module

def digest(paths: list[Path]) -> str:
    value = hashlib.sha256()
    for source in sorted(paths):
        value.update(str(source.relative_to(ROOT)).replace("\\", "/").encode()); value.update(b"\0")
        value.update(source.read_bytes()); value.update(b"\0")
    return value.hexdigest()

def exact_td_jar(launch) -> Path:
    found = sorted(launch.DEFAULT_INSTANCE.joinpath("mods").glob("NeoForge-terrain-diffusion-mc-*.jar"))
    if len(found) != 1: raise RuntimeError(f"Expected exactly one installed Terrain Diffusion jar, got {found}")
    return found[0]

def mixin_extras_jar() -> Path:
    for cache in (ROOT / '.gradle', Path.home() / '.gradle'):
        found = sorted((cache / 'caches/modules-2/files-2.1/io.github.llamalad7/mixinextras-neoforge/0.5.5').rglob('mixinextras-neoforge-0.5.5.jar'))
        if len(found) == 1: return found[0]
    raise RuntimeError('Expected cached MixinExtras 0.5.5 jar')


def relocated_sources(output: Path) -> list[Path]:
    """Copy only the two reviewed sources under the probe package; never split TD's module package."""
    root = output / "generated-tree-ground-fix-src-v4"
    if root.exists(): raise RuntimeError(f"Refusing to reuse generated relocation source directory: {root}")
    output = []
    for original in PRODUCTION_SOURCES:
        text = original.read_text(encoding="utf-8")
        for before, after in RELOCATION.items(): text = text.replace(before, after)
        package = next(line.removeprefix("package ").removesuffix(";") for line in text.splitlines() if line.startswith("package "))
        target = root.joinpath(*package.split("."), original.name)
        target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text, encoding="utf-8"); output.append(target)
    return output

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tree-ground-fix", action="store_true", help="package the two production tree-ground classes and opt-in mixin config")
    parser.add_argument("--output-dir", type=Path, default=OUT, help="new or empty output directory; preserves historical artifacts")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output != OUT.resolve() and output.exists() and any(output.iterdir()): raise RuntimeError(f"--output-dir must be fresh or empty: {output}")
    jar_out = output / (FIX_JAR.name if args.tree_ground_fix else BASE_JAR.name)
    classes = output / ("classes-tree-ground-fix-v4" if args.tree_ground_fix else "classes")
    if jar_out.exists(): raise RuntimeError(f"Refusing to replace existing diagnostic jar: {jar_out}")
    launch = load_launcher()
    java_home = Path(r"C:\Program Files\Java\jdk-21")
    javac, jar = java_home / "bin" / "javac.exe", java_home / "bin" / "jar.exe"
    if not javac.is_file() or not jar.is_file(): raise RuntimeError("JDK 21 javac/jar not found")
    # Guard the exact selected client runtime before generating sources or invoking javac.
    runtime_guard = validate_tree_ground_runtime(
        launch.DEFAULT_LIBRARIES / f"net/neoforged/neoforge/{launch.NEO}/neoforge-{launch.NEO}-client.jar",
        java_home, PRODUCTION_SOURCES[1])
    sources = sorted((HERE / "src" / "main" / "java").rglob("*.java"))
    if args.tree_ground_fix: sources.extend(relocated_sources(output))
    if not sources: raise RuntimeError("No Java sources")
    output.mkdir(parents=True, exist_ok=True)
    if classes.exists(): raise RuntimeError(f"Refusing to reuse class output: {classes}")
    classes.mkdir()
    cp = launch.compile_classpath(launch.DEFAULT_LIBRARIES)
    if args.tree_ground_fix: cp += os.pathsep + str(exact_td_jar(launch)) + os.pathsep + str(mixin_extras_jar())
    # This standalone jar has no Gradle mapping task.  NeoForge's runtime remapper
    # handles the named mixin targets; disable the absent dev annotation mapping task.
    command = [str(javac), "--release", "21", "-proc:none", "-cp", cp, "-d", str(classes), *map(str, sources)]
    print(f"JAVAC {len(sources)} sources -> {classes}")
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()[-6000:]
        raise RuntimeError(f"javac failed ({result.returncode}):\n{detail}")
    # jar.exe writes a temporary archive then renames it, which is blocked by the
    # local Windows sandbox.  zipfile writes directly to this fresh output path.
    with zipfile.ZipFile(jar_out, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        roots = [classes, HERE / "src" / "main" / "resources"]
        if args.tree_ground_fix: roots.append(HERE / "src" / "main" / "tree-ground-fix-resources")
        for root in roots:
            for item in root.rglob("*"):
                if item.is_file(): archive.write(item, item.relative_to(root).as_posix())
    with zipfile.ZipFile(jar_out) as archive:
        names = set(archive.namelist())
    required = {"META-INF/neoforge.mods.toml", "td-instance-client-probe.mixins.json",
                "com/terrain/instanceclientprobe/InstanceClientProbe.class",
                "com/terrain/instanceclientprobe/mixin/ProtoChunkWriteTraceMixin.class",
                "com/terrain/instanceclientprobe/mixin/LevelChunkWriteTraceMixin.class"}
    if args.tree_ground_fix: required.update({"td-instance-tree-ground-fix.mixins.json", "com/terrain/instanceclientprobe/production/compat/ecology/TreeGroundMaterialPolicy.class", "com/terrain/instanceclientprobe/production/mixin/SubmergedTreeGroundMixin.class"})
    missing = required - names
    if missing: raise RuntimeError(f"Built jar is missing: {sorted(missing)}")
    if args.tree_ground_fix:
        common_hash = digest(sorted((HERE / "src" / "main" / "java").rglob("*.java")) + [p for p in sorted((HERE / "src" / "main" / "resources").rglob("*")) if p.is_file()])
        manifest = {"event": "td_instance_tree_ground_fix_build", "jar": jar_out.name,
                    "fixedProbeSourceSha256": common_hash,
                    "treeGroundSourceSha256": digest(list(PRODUCTION_SOURCES)),
                    "relocation": RELOCATION,
                    "installedTerrainDiffusionJar": str(exact_td_jar(launch)),
                    "mixinConfig": "td-instance-tree-ground-fix.mixins.json"}
        manifest["runtimeGuard"] = runtime_guard
        (output / "td-instance-tree-ground-fix-1.0.0.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    else:
        (output / "td-instance-client-probe-1.0.0.manifest.json").write_text(json.dumps({"event": "td_instance_client_probe_build", "jar": jar_out.name, "sourceSha256": digest(sources), "runtimeGuard": runtime_guard}, indent=2) + "\n", encoding="utf-8")
    print(f"BUILT {jar_out}")
    return 0

if __name__ == "__main__": raise SystemExit(main())
