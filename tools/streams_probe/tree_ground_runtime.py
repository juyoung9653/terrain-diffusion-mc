#!/usr/bin/env python3
"""Fail closed when the submerged-tree mixin no longer matches its runtime target."""
from __future__ import annotations

import hashlib
import re
import subprocess
import os
from pathlib import Path

TARGET_CLASS = "net.minecraft.world.level.levelgen.feature.treedecorators.AlterGroundDecorator"
METHOD_DESCRIPTOR = (
    "placeBlockAt(Lnet/minecraft/world/level/levelgen/feature/treedecorators/"
    "TreeDecorator$Context;Lnet/minecraft/core/BlockPos;"
    "Lnet/neoforged/neoforge/event/level/AlterGroundEvent$StateProvider;)V"
)
SET_BLOCK_TARGET = (
    "Lnet/minecraft/world/level/levelgen/feature/treedecorators/"
    "TreeDecorator$Context;setBlock(Lnet/minecraft/core/BlockPos;"
    "Lnet/minecraft/world/level/block/state/BlockState;)V"
)
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/SubmergedTreeGroundMixin.java"
JVM_METHOD_DESCRIPTOR = "(Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/event/level/AlterGroundEvent$StateProvider;)V"
JAVAP_INVOKE = "net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""): digest.update(block)
    return digest.hexdigest()

def source_selector(source: Path) -> dict[str, str]:
    if not source.is_file(): raise ValueError(f"missing mixin source: {source}")
    text = source.read_text(encoding="utf-8")
    methods = re.findall(r'@WrapOperation\s*\(\s*method\s*=\s*"([^"]+)"', text, re.S)
    targets = re.findall(r'target\s*=\s*"([^"]+)"', text, re.S)
    if len(methods) != 1 or methods[0] != METHOD_DESCRIPTOR:
        raise ValueError(f"ambiguous or wrong placeBlockAt selector: {methods}")
    if targets.count(SET_BLOCK_TARGET) != 1:
        raise ValueError(f"missing or ambiguous Context.setBlock target: {targets}")
    if not re.search(r'\brequire\s*=\s*1\b', text):
        raise ValueError("WrapOperation must require exactly one target")
    return {"sourceSha256": sha256(source), "selector": methods[0], "atTarget": targets[0]}

def method_section(javap: str) -> str:
    marker = "private void placeBlockAt(net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator$Context, net.minecraft.core.BlockPos, net.neoforged.neoforge.event.level.AlterGroundEvent$StateProvider);"
    start = javap.find(marker)
    if start < 0: raise ValueError("runtime missing event-aware three-argument placeBlockAt")
    following = re.search(r'\n  (?:private|public|protected|static) ', javap[start + len(marker):])
    section = javap[start:] if following is None else javap[start:start + len(marker) + following.start()]
    if f"descriptor: {JVM_METHOD_DESCRIPTOR}" not in section: raise ValueError("runtime event overload descriptor mismatch")
    return section

def run_javap(java_home: Path, classpath: Path, class_name: str, verbose: bool = False) -> str:
    javap = java_home / "bin" / ("javap.exe" if os.name == "nt" else "javap")
    if not javap.is_file(): raise ValueError(f"missing javap: {javap}")
    command = [str(javap), "-p", "-s", "-c", *( ["-v"] if verbose else []), "-classpath", str(classpath), class_name]
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (subprocess.TimeoutExpired, OSError) as error:
        raise ValueError(f"javap unavailable: {error}") from error
    if result.returncode: raise ValueError(f"javap failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout

def validate_tree_ground_runtime(runtime_jar: Path, java_home: Path, mixin_source: Path = DEFAULT_MIXIN) -> dict:
    runtime_jar, java_home = Path(runtime_jar), Path(java_home)
    if not runtime_jar.is_file(): raise ValueError(f"missing runtime jar: {runtime_jar}")
    selector = source_selector(Path(mixin_source))
    section = method_section(run_javap(java_home, runtime_jar, TARGET_CLASS))
    calls = len(re.findall(r"Method " + re.escape(JAVAP_INVOKE), section))
    if calls != 1: raise ValueError(f"runtime target has {calls} Context.setBlock calls, expected 1")
    return {"runtimeJar": str(runtime_jar), "runtimeJarSha256": sha256(runtime_jar), **selector, "calls": calls}

def validate_tree_ground_mixin_jar(runtime_jar: Path, java_home: Path, mixin_jar: Path, mixin_class: str) -> dict:
    """Validate the annotation carried by the actual JAR the launcher will load."""
    runtime = validate_tree_ground_runtime(runtime_jar, java_home)
    mixin_jar = Path(mixin_jar)
    if not mixin_jar.is_file(): raise ValueError(f"missing mixin jar: {mixin_jar}")
    output = run_javap(Path(java_home), mixin_jar, mixin_class, verbose=True)
    blocks = re.findall(r"RuntimeVisibleAnnotations:(.*?)(?=\n\s*(?:Runtime|Code:|LineNumberTable:|$))", output, re.S)
    annotated = [block for block in blocks if METHOD_DESCRIPTOR in block and SET_BLOCK_TARGET in block]
    if len(annotated) != 1 or "WrapOperation" not in annotated[0] or not re.search(r"\brequire\s*=\s*1\b", annotated[0]):
        raise ValueError("compiled mixin lacks one exact WrapOperation annotation selector/target/require")
    runtime.update({"mixinJar": str(mixin_jar), "mixinJarSha256": sha256(mixin_jar), "mixinClass": mixin_class})
    return runtime

