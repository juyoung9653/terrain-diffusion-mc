"""Audit the native Streams demand-to-sampler priority contract."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from audit_streams_compat import ROOT, DEFAULT_JARS, javap, method_body, find_java_home


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JARS["2.15.2"])
    args = parser.parse_args()
    args.java_home = find_java_home(args.java_home)
    output = ROOT / "build/streams-sampling-audit"
    output.mkdir(parents=True, exist_ok=True)
    classes = {}
    for name in ("core.build.BuildGate", "core.grid.LowResGrid", "core.grid.LowResGrid$PrioritizedTask",
                 "worldgen.StreamPrefetcher"):
        text = javap(args.java_home, args.jar, "dev.streamsreflowing." + name, "-p", "-c", "-s")
        (output / (name.split(".")[-1] + ".txt")).write_text(text, encoding="utf-8")
        classes[name] = text
    gate = method_body(classes["core.build.BuildGate"], "public static void asDemanded(java.lang.Runnable);")
    assert "descriptor: (Ljava/lang/Runnable;)V" in gate
    assert gate.count("InterfaceMethod java/lang/Runnable.run:()V") == 1
    task = classes["core.grid.LowResGrid$PrioritizedTask"]
    assert "private final int a;\n    descriptor: I" in task
    ctor = method_body(task, "dev.streamsreflowing.core.grid.LowResGrid$PrioritizedTask(int, long, long, java.lang.Runnable);")
    assert 'descriptor: (IJJLjava/lang/Runnable;)V' in ctor
    assert re.search(r"iload_1\s+\d+: putfield\s+.*Field a:I", ctor)
    run = method_body(task, "public void run();")
    assert "descriptor: ()V" in run and run.count("InterfaceMethod java/lang/Runnable.run:()V") == 1
    priority = method_body(classes["core.grid.LowResGrid"], "private static int b();")
    code = re.sub(r"\s+", " ", priority)
    assert "12: ifeq 17 15: iconst_0 16: ireturn" in code
    assert "String streamsreflowing-prefetch" in code
    assert "28: ifeq 35 31: iconst_1 32: goto 36 35: iconst_0" in code
    grid = classes["core.grid.LowResGrid"]
    # Both generic tasks and row-band jobs use the same captured-priority wrapper.
    assert grid.count('LowResGrid$PrioritizedTask."<init>":(IJJLjava/lang/Runnable;)V') == 2
    prefetch = classes["worldgen.StreamPrefetcher"]
    code = re.sub(r"\s+", " ", prefetch)
    assert re.search(r"450: iconst_1 451: invokestatic .*?LowResGrid.setUrgentBuild:\(Z\)V.*?461: invokestatic .*?BuildGate.asDemanded:", code)
    registration = json.loads((ROOT / "neoforge/src/main/resources/terrain-diffusion-mc.neoforge.mixins.json").read_text())
    assert {"StreamsDemandPriorityMixin", "StreamsSamplerPriorityMixin"} <= set(registration["mixins"])
    plugin = (ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsStructureShieldMixinPlugin.java").read_text()
    gate_block = plugin.split('if (mixinClassName.equals("com.github.xandergos.terraindiffusionmc.mixin.StreamsLakeLevelsMixin")', 1)[1].split("\n        }", 1)[0]
    assert "StreamsDemandPriorityMixin" in gate_block and "StreamsSamplerPriorityMixin" in gate_block
    assert "hasStreamsVersion(StreamsStructureShieldMixinPlugin::hasModernHooks)" in gate_block
    print(f"PASS {args.jar.name}: demand marker, urgent submission branch, task constructors, priority field, run descriptors and version gate")


if __name__ == "__main__":
    main()
