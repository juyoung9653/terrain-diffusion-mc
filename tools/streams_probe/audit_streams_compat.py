"""Audit Streams binary hook contracts without launching NeoForge.

The audit deliberately fails closed: a changed descriptor, ordinal, record layout,
or call-site reason is a compatibility review item, never a version-gate bypass.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JARS = {
    "2.13.5": ROOT / "build/river-v9-validation/delivery/client/mods/StreamsReflowing-1.21.1-neoforge-2.13.5.jar.disabled",
    "2.13.8": Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods/StreamsReflowing-1.21.1-neoforge-2.13.8.jar",
    "2.15.2": Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods/StreamsReflowing-1.21.1-neoforge-2.15.2.jar",
}
CARVER = "dev.streamsreflowing.worldgen.RiverCarverFeature"


def javap_path(path: Path) -> str:
    """Use the Windows 8.3 path where JDK 21 cannot close a Unicode JAR path."""
    if os.name != "nt":
        return str(path)
    size = ctypes.windll.kernel32.GetShortPathNameW(str(path), None, 0)
    if not size:
        return str(path)
    buffer = ctypes.create_unicode_buffer(size)
    ctypes.windll.kernel32.GetShortPathNameW(str(path), buffer, size)
    return buffer.value or str(path)


def javap(java_home: Path, jar: Path, class_name: str, *flags: str) -> str:
    command = [str(java_home / "bin/javap.exe"), *flags, "-classpath", str(jar), class_name]
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    emitted_class = "Compiled from" in result.stdout or "Classfile " in result.stdout or class_name in result.stdout
    if result.returncode and not emitted_class and javap_path(jar) != str(jar):
        command[command.index("-classpath") + 1] = javap_path(jar)
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    # JDK 21 on this Windows installation emits this close-time error for JARs
    # under Unicode paths after printing the requested class in full.
    emitted_class = "Compiled from" in result.stdout or "Classfile " in result.stdout or class_name in result.stdout
    if result.returncode and not ("AccessDeniedException:" in result.stderr and emitted_class):
        raise RuntimeError(f"javap {class_name} failed: {result.stderr[-1000:]}")
    return result.stdout


def place_block(carver: str) -> str:
    try:
        return carver.split("public boolean place(", 1)[1].split("\n  private ", 1)[0]
    except IndexError as error:
        raise AssertionError("RiverCarverFeature.place body not found") from error


def require(check: bool, message: str, failures: list[str]) -> None:
    if not check:
        failures.append(message)


def method_body(class_text: str, declaration: str) -> str:
    """Return one javap method block, keyed by its human-readable declaration."""
    start = class_text.find(declaration)
    if start < 0:
        return ""
    next_method = re.search(r"\n  (?:private|public|protected|static) ", class_text[start + len(declaration):])
    return class_text[start:] if next_method is None else class_text[start:start + len(declaration) + next_method.start()]


def audit_jar(java_home: Path, jar: Path, version: str) -> dict[str, object]:
    """Return raw, reviewable binary evidence for one supported Streams JAR."""
    classes = {
        "carver": (CARVER, ("-p", "-s", "-c")),
        "carverVerbose": (CARVER, ("-v", "-p")),
        "bankStyle": ("dev.streamsreflowing.worldgen.bank.BankStyle", ("-p", "-s", "-c")),
        "worldgen": ("dev.streamsreflowing.worldgen.StreamsWorldgen", ("-p", "-s")),
        "engine": ("dev.streamsreflowing.core.RiverEngine", ("-p", "-s")),
        "tracer": ("dev.streamsreflowing.core.river.NetworkTracer", ("-p", "-s")),
        "blacklist": ("dev.streamsreflowing.worldgen.StreamBiomeBlacklist", ("-p", "-s")),
        "config": ("dev.streamsreflowing.core.config.StreamsConfig", ("-p", "-s")),
        "hydraulic": ("dev.streamsreflowing.core.config.HydraulicGeometry", ("-p", "-s")),
        "segment": ("dev.streamsreflowing.core.river.RiverSegment", ("-p", "-s")),
        "column": ("dev.streamsreflowing.core.compose.ColumnPlan", ("-p", "-s")),
        "network": ("dev.streamsreflowing.neoforge.net.StreamFlowNet", ("-p", "-s")),
        "payload": ("dev.streamsreflowing.neoforge.net.StreamFlowNet$FlowGridPayload", ("-p", "-s")),
    }
    text = {name: javap(java_home, jar, class_name, *flags) for name, (class_name, flags) in classes.items()}
    failures: list[str] = []
    carver, place = text["carver"], place_block(text["carver"])
    normalized = {name: " ".join(value.split()) for name, value in text.items()}
    writes = re.findall(r"(?m)^\s*(\d+): invokeinterface .*WorldGenLevel\.setBlock:", place)
    grade_calls = re.findall(r"Method a:\(IILjava/lang/String;II\)I", place)
    expected_grade_calls = {"2.13.5": 12, "2.13.8": 13, "2.15.2": 13}.get(version)
    dry_ordinal = 7 if version == "2.15.2" else 6
    water_ordinal = dry_ordinal + 1
    require(expected_grade_calls is not None, f"unsupported audited version {version}", failures)
    if expected_grade_calls is not None:
        require(len(grade_calls) == expected_grade_calls,
                f"grade-call count is {len(grade_calls)}, expected {expected_grade_calls}", failures)
    require(len(writes) > water_ordinal, "place has too few WorldGenLevel.setBlock calls", failures)
    if len(writes) > water_ordinal:
        # The version-specific dry write must use AIR; its next write uses water.
        require(re.search(r"Blocks\.AIR.*?astore\s+(\d+)", place, re.S) is not None,
                "place no longer initializes a local AIR block state", failures)
        air_match = re.search(r"Blocks\.AIR.*?astore\s+(\d+)", place, re.S)
        water_match = re.search(r"streamWaterState:.*?astore\s+(\d+)", place, re.S)
        lines = place.splitlines()
        hit_lines = [i for i, line in enumerate(lines) if "WorldGenLevel.setBlock:" in line]
        sixth = "\n".join(lines[max(0, hit_lines[dry_ordinal] - 18):hit_lines[dry_ordinal] + 1])
        seventh = "\n".join(lines[max(0, hit_lines[water_ordinal] - 18):hit_lines[water_ordinal] + 1])
        require(air_match is not None and re.search(rf"aload\s+{air_match.group(1)}\s*\n.*?setBlock:", sixth, re.S) is not None,
                f"setBlock ordinal {dry_ordinal} is no longer the dry-bank AIR write", failures)
        require(water_match is not None and re.search(rf"aload\s+{water_match.group(1)}\s*\n.*?setBlock:", seventh, re.S) is not None,
                f"setBlock ordinal {water_ordinal} is no longer the adjacent stream-water write", failures)
    require("a(int, int, java.lang.String, int, int); descriptor: (IILjava/lang/String;II)I" in normalized["carver"],
            "pool-grade hook descriptor changed", failures)
    require("junction pool (neighbour at r=" in text["carverVerbose"],
            "pool-grade reason literal changed", failures)
    require("side=\\u0001)" in text["carverVerbose"], "pool-grade reason no longer includes side", failures)
    extra_reason = None
    extra_snippet = None
    if version == "2.13.8":
        extra_reason = "berm lake level-out (\\u0001)"
        require(re.search(r"InvokeDynamic #34:makeConcatWithConstants.*?Method arguments:\s*#107 berm lake level-out \(\\u0001\)", text["carverVerbose"], re.S) is not None,
                "2.13.8 extra grade call reason is no longer bootstrap #34 berm lake level-out", failures)
        require(re.search(r"8158: iload\s+65\s+8160: iload\s+65\s+8162: iload\s+66\s+8164: invokestatic .*Math\.max:\(II\)I\s+8167: invokestatic .*a:\(IILjava/lang/String;II\)I", place, re.S) is not None,
                "2.13.8 extra grade call no longer passes Math.max(current, proposed)", failures)
        extra_lines = place.splitlines()
        extra_index = next(i for i, line in enumerate(extra_lines) if "8167: invokestatic" in line)
        extra_snippet = "\n".join(extra_lines[extra_index - 9:extra_index + 2])
    if version == "2.15.2":
        audit_2152_bank(carver, text["bankStyle"], writes, failures)
    else:
        require(carver.count("BankStyle.zoneBlock") == 1, "RiverCarverFeature BankStyle.zoneBlock occurrence changed", failures)
    for signature, description in (
        ("a(net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.ChunkPos); descriptor: (Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/ChunkPos;)[I", "bank protection-array helper"),
        ("a(int[], int, int, int); descriptor: ([IIII)Z", "bank protection predicate helper"),
    ):
        require(signature in normalized["carver"], f"{description} reflection layout changed", failures)
    require(version == "2.15.2" or "zoneBlock(int, double, boolean); descriptor: (IDZ)Lnet/minecraft/world/level/block/state/BlockState;" in normalized["bankStyle"],
            "BankStyle.zoneBlock descriptor changed", failures)
    bank_helpers = {
        "2.13.5": {
            "declaration": "private static void a(net.minecraft.world.level.WorldGenLevel, net.minecraft.core.BlockPos$MutableBlockPos, int, int, int, net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.block.state.BlockState, int, boolean, java.util.List<dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.List<dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.Map<java.lang.Long, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>, int, int, java.lang.String, boolean, boolean);",
            "descriptor": "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos$MutableBlockPos;IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Map;IILjava/lang/String;ZZ)V",
            "surface_set": r"157: aload_1\s+158: iload_2\s+159: iload\s+18\s+161: iload_3\s+162: invokevirtual .*MutableBlockPos\.set:\(III\)",
            "mixin": ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsBankMaterialMixin.java",
        },
        "2.13.8": {
            "declaration": "private static void a(net.minecraft.world.level.WorldGenLevel, dev.streamsreflowing.core.RiverEngine, int, int, net.minecraft.core.BlockPos$MutableBlockPos, int, int, int, int, boolean, net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.block.state.BlockState, int, boolean, java.util.List<dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.List<dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, dev.streamsreflowing.worldgen.bank.BankStyle>, java.util.Map<java.lang.Long, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>, int, int, java.lang.String, boolean, boolean);",
            "descriptor": "(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Map;IILjava/lang/String;ZZ)V",
            "surface_set": r"253: aload\s+4\s+255: iload\s+5\s+257: iload\s+23\s+259: iload\s+6\s+261: invokevirtual .*MutableBlockPos\.set:\(III\)",
            "mixin": ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsBankMaterial2138Mixin.java",
        },
    }
    bank_helper = bank_helpers.get(version)
    if bank_helper is not None:
        body = method_body(carver, bank_helper["declaration"])
        require(bool(body), "selected bank helper declaration missing", failures)
        require(("descriptor: " + bank_helper["descriptor"]) in body,
                "selected bank helper descriptor changed", failures)
        require(re.search(bank_helper["surface_set"], body, re.S) is not None,
                "selected bank helper no longer positions mutable surface at the audited x/scan/z locals", failures)
        mixin_source = bank_helper["mixin"].read_text(encoding="utf-8")
        require(("method = \"a" + bank_helper["descriptor"] + "\"") in mixin_source,
                "selected bank-material mixin target descriptor no longer matches native helper", failures)
        if version == "2.13.8":
            require("index = 5" in mixin_source and "index = 7" in mixin_source,
                    "2.13.8 bank mixin no longer captures audited x slot 5 and scan input slot 7", failures)
    for signature, description in (
        ("engine(net.minecraft.world.level.WorldGenLevel); descriptor: (Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;", "StreamsWorldgen.engine"),
        ("config(); descriptor: ()Ldev/streamsreflowing/core/config/StreamsConfig;", "RiverEngine.config"),
        ("plan(int, int); descriptor: (II)Ldev/streamsreflowing/core/compose/ColumnPlan;", "RiverEngine.plan"),
        ("lakeNear(int, int, int); descriptor: (III)Ldev/streamsreflowing/core/river/Lake;", "RiverEngine.lakeNear"),
        ("lakeNearChunk(int, int, int); descriptor: (III)Z", "RiverEngine.lakeNearChunk"),
        ("isBlocked(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>); descriptor: (Lnet/minecraft/core/Holder;)Z", "StreamBiomeBlacklist.isBlocked"),
    ):
        source = "worldgen" if description.startswith("StreamsWorldgen") else "engine" if description.startswith("RiverEngine") else "blacklist"
        require(signature in normalized[source], f"{description} reflection descriptor changed", failures)
    record_expectations = {
        "config": ("minRiverAccumulation", "gridSpacing", "minSeaChainNodes", "minLengthScope", "confineToBanks", "hydraulics"),
        "hydraulic": ("bendAsymmetry", "channelBars"),
        "segment": ("x1", "z1", "y1", "w1", "x2", "z2", "y2", "w2", "sp1", "sp2"),
        "column": ("inChannel", "inCorridor", "waterSurfaceY", "bedY", "bankBlend", "distance", "width", "gradeY", "slope", "flowDirX", "flowDirZ", "terminal", "source", "upstreamSlope", "fluidFlowDirX", "fluidFlowDirZ", "distToOutlet", "curvature", "lateral", "distToJunction", "flatRun", "speed"),
    }
    for name, accessors in record_expectations.items():
        for accessor in accessors:
            require(re.search(rf"\b{accessor}\(\); descriptor:", normalized[name]) is not None,
                    f"{name} reflective accessor missing: {accessor}", failures)
    require("descriptor: (ZZIIDDDDDDDZZDDDDDDDDD)V" in normalized["column"],
            "ColumnPlan 22-field constructor changed", failures)
    networking = {
        "register": "register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent); descriptor: (Lnet/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent;)V",
        "payloadConstructor": "FlowGridPayload(int, int, byte[]); descriptor: (II[B)V",
        "payloadType": "type(); descriptor: ()Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;",
    }
    require(networking["register"] in normalized["network"], "StreamFlowNet.register descriptor changed", failures)
    for key in ("payloadConstructor", "payloadType"):
        require(networking[key] in normalized["payload"], f"FlowGridPayload {key} descriptor changed", failures)
    return {"version": version, "jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "passed": not failures, "failures": failures,
            "gradeCalls": len(grade_calls), "setBlockOffsets": writes, "dryAirOrdinal": dry_ordinal,
            "waterOrdinal": water_ordinal, "zoneBlockCalls": carver.count("BankStyle.zoneBlock"),
            "bankHelperDescriptor": bank_helper["descriptor"] if bank_helper else None,
            "networking": networking,
            "evidence": {"poolHookReason": "junction pool (neighbour at r=\\u0001 side=\\u0001)",
                         "dryAirOffset": writes[dry_ordinal] if len(writes) > dry_ordinal else None,
                         "waterOffset": writes[water_ordinal] if len(writes) > water_ordinal else None,
                         "extra2138GradeOffset": "8167" if version == "2.13.8" else None,
                         "extra2138Reason": extra_reason,
                         "extra2138Snippet": extra_snippet}}


def audit_2152_bank(carver: str, bank_style: str, writes: list[str], failures: list[str]) -> None:
    """Pin reviewed ring semantics, branch positions and our selected hook sites."""
    mixins = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin"
    source = (mixins / "StreamsBankMaterial2152Mixin.java").read_text(encoding="utf-8")
    descriptor = re.search(r'method = "a([^"\n]+)"', source).group(1)
    blocks = re.split(r"\n  (?=(?:private|public|protected|static) )", carver)
    bodies = [block for block in blocks if "descriptor: " + descriptor + "\n" in block]
    require(len(bodies) == 1, "2.15.2 ring bank helper descriptor changed", failures)
    body = bodies[0] if bodies else ""
    code = " ".join(body.split())
    require(body.count("BankRing.pick:") == 1 and "723: invokevirtual" in code,
            "2.15.2 single native ring selection changed", failures)
    require("BankRing.pick:(JIIIDDLdev/streamsreflowing/worldgen/bank/BankRing$Metrics;I)Lnet/minecraft/world/level/block/state/BlockState;" in body,
            "2.15.2 ring pick contract changed", failures)
    require("726: astore 48" in code and "1179: ifnull 1590" in code,
            "2.15.2 null material must skip top and depth", failures)
    require("1213: invokestatic" in code and "1329: invokeinterface" in code,
            "2.15.2 separate top/depth writes moved", failures)
    require("1231: aload 56" in code and "1236: aload 48" in code and "1238: astore 59" in code,
            "2.15.2 depth material locals changed", failures)
    require("index = 22" in source and "iload 22" in code and "BankRing$Metrics." in body,
            "2.15.2 point-bar decision capture changed", failures)
    instructions = re.sub(r"#\d+(?:,\s*\d+)?\s*//[^\n]*", "", body)
    instructions = " ".join(instructions.split())
    for sequence in (
        "610: iload 22 612: invokestatic 615: goto 621 618: getstatic 621: astore 44",
        "661: invokevirtual 664: istore 45 666: aload 32 668: invokevirtual 671: iload 45 673: invokeinterface 678: checkcast 681: astore 46",
        "705: aload 46 707: lload 33 709: iload 5 711: iload 24 713: iload 6 715: dload 38 717: dload 42 719: aload 44 721: iload 45 723: invokevirtual 726: astore 48",
    ):
        require(sequence in instructions, "2.15.2 point-bar/ring selection dataflow changed", failures)
    require("ringIndexAt(int, double, double, double, boolean, double, double);" in bank_style,
            "2.15.2 native ring selector missing", failures)
    require(writes[6:9] == ["8900", "8960", "8992"],
            "2.15.2 log/dry-bank/water write branches moved", failures)
    require(writes[12:16] == ["13011", "13093", "13109", "13133"],
            "2.15.2 recap/vegetation writes moved", failures)
    for ordinal in (12, 13, 14, 15):
        require(f"ordinal = {ordinal}" in source, f"2.15.2 missing recap hook {ordinal}", failures)
    shallow = (mixins / "StreamsShallowBanks2152Mixin.java").read_text(encoding="utf-8")
    require("ordinal = 7" in shallow, "2.15.2 dry-bank hook must exclude log removal", failures)


def find_java_home(requested: Path | None) -> Path:
    candidates = [requested] if requested else []
    candidates.extend([Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else [])
    candidates.extend([Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21"), Path(r"C:\Program Files\Microsoft\jdk-21")])
    return next(candidate for candidate in candidates if candidate and (candidate / "bin/javap.exe").is_file())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", action="append", metavar="VERSION=PATH")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/river-2138-validation/audit")
    args = parser.parse_args()
    jars = DEFAULT_JARS.copy()
    if args.jar:
        jars = {}
        for value in args.jar:
            version, separator, path = value.partition("=")
            if not separator or not version or not path:
                parser.error("--jar must be VERSION=PATH")
            jars[version] = Path(path)
    for version, jar in jars.items():
        if not jar.is_file():
            raise SystemExit(f"missing {version} JAR: {jar}")
    java_home = find_java_home(args.java_home)
    results = [audit_jar(java_home, jar, version) for version, jar in jars.items()]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "result.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    lines = ["# Streams compatibility audit", "", "JDK `javap` audit; no game process was launched.", ""]
    for result in results:
        lines.extend([f"## {result['version']}", "", f"- passed: `{result['passed']}`", f"- pool-grade calls: `{result['gradeCalls']}`", f"- dry AIR ordinal {result['dryAirOrdinal']} / water ordinal {result['waterOrdinal']} offsets: `{result['evidence']['dryAirOffset']}` / `{result['evidence']['waterOffset']}`", f"- RiverCarverFeature BankStyle.zoneBlock calls: `{result['zoneBlockCalls']}`", f"- pool hook reason: `{result['evidence']['poolHookReason']}`", f"- extra 2.13.8 reason: `{result['evidence']['extra2138Reason']}`", ""])
        if result["evidence"]["extra2138Snippet"]:
            lines.extend(["The extra call is preserved: it passes `Math.max(current, proposed)`, so `poolGrade` returns the native proposal before its junction-pool reason filter.", "", "```text", result["evidence"]["extra2138Snippet"], "```", ""])
    (args.output_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps(results, indent=2))
    if not all(result["passed"] for result in results):
        raise SystemExit("Streams compatibility audit failed")


if __name__ == "__main__":
    main()
