"""Fail-closed static hook audit for Streams Reflowing 2.16.2.

This deliberately inspects bytecode through ``javap`` instead of trusting a
version label.  It is imported by ``audit_streams_compat.py``; it does not
start Minecraft or write outside its caller's audit receipt.
"""
from __future__ import annotations

import ctypes
import hashlib
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIXINS = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin"
CARVER = "dev.streamsreflowing.worldgen.RiverCarverFeature"

BANK_HELPER = (
    "(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;"
    "IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZ"
    "Lnet/minecraft/world/level/block/state/BlockState;"
    "Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;"
    "Ljava/util/List;Ljava/util/Map;Ldev/streamsreflowing/worldgen/ProprietaryNiceJohn_003;"
    "IILjava/lang/String;ZZLdev/streamsreflowing/core/compose/ColumnPlan;)V"
)
RING_PICK = (
    "streamsreflowing/proprietary/AllRightsReservedNiceJohn_009.proprietary1:"
    "(JIIIDDLstreamsreflowing/proprietary/AllRightsReservedNiceJohn_009"
    "$LicensedCodeNotForAnalysis_001;I)Lnet/minecraft/world/level/block/state/BlockState;"
)
TOP_HELPER = (
    "Method proprietary1:(Lnet/minecraft/world/level/WorldGenLevel;"
    "Lnet/minecraft/core/BlockPos$MutableBlockPos;III"
    "Lnet/minecraft/world/level/block/state/BlockState;I)V"
)
CONTAINMENT_FILL = (
    "(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;"
    "Lnet/minecraft/core/BlockPos$MutableBlockPos;Ldev/streamsreflowing/core/compose/ColumnPlan;"
    "IIIIIZII)V"
)
PLACE_TOP_HELPER_OFFSETS = ["2323", "4594", "11258", "13308", "14071"]
SEAGRASS_COLUMN = (
    "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;"
    "Lnet/minecraft/core/BlockPos$MutableBlockPos;IIII)Z"
)
NETWORK_FACTORY = (
    "(Ldev/streamsreflowing/core/plate/ProprietaryNiceJohn_001;"
    "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
    "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;"
    "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
    "Ldev/streamsreflowing/core/river/RiverNetwork;"
)


def _short_path(path: Path) -> str:
    if __import__("os").name != "nt":
        return str(path)
    size = ctypes.windll.kernel32.GetShortPathNameW(str(path), None, 0)
    if not size:
        return str(path)
    buffer = ctypes.create_unicode_buffer(size)
    ctypes.windll.kernel32.GetShortPathNameW(str(path), buffer, size)
    return buffer.value or str(path)


def _javap(java_home: Path, jar: Path, class_name: str, *flags: str) -> str:
    command = [str(java_home / "bin/javap.exe"), *flags, "-classpath", str(jar), class_name]
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    emitted = "Compiled from" in result.stdout or class_name in result.stdout
    if result.returncode and not emitted:
        command[command.index("-classpath") + 1] = _short_path(jar)
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        emitted = "Compiled from" in result.stdout or class_name in result.stdout
    if result.returncode and not (emitted and "AccessDeniedException:" in result.stderr):
        raise RuntimeError(f"javap {class_name} failed: {result.stderr[-800:]}")
    if not emitted:
        raise RuntimeError(f"javap {class_name} produced no readable class")
    return result.stdout


def _method_for_descriptor(text: str, descriptor: str) -> str:
    marker = "descriptor: " + descriptor
    at = text.find(marker)
    if at < 0:
        return ""
    start = text.rfind("\n  ", 0, at)
    next_method = re.search(r"\n  (?:public|private|protected|static) ", text[at + len(marker):])
    end = len(text) if next_method is None else at + len(marker) + next_method.start()
    return text[start:end]


def _require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def _source(name: str) -> str:
    path = MIXINS / name
    if not path.is_file():
        raise RuntimeError(f"missing audited mixin source: {path}")
    return path.read_text(encoding="utf-8")


def audit_2162(java_home: Path, jar: Path) -> dict[str, object]:
    """Audit the new 2.16.2 contracts and return a compact serializable receipt."""
    if not jar.is_file():
        raise RuntimeError(f"missing Streams 2.16.2 JAR: {jar}")
    classes = {
        "carver": CARVER,
        "worldgen": "dev.streamsreflowing.worldgen.StreamsWorldgen",
        "seagrass": "dev.streamsreflowing.worldgen.SourceGivenToAuthorizedOnly_004",
        "network": "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001",
        "config": "dev.streamsreflowing.core.config.StreamsConfig",
        "hydraulic": "dev.streamsreflowing.core.config.HydraulicGeometry",
        "column": "dev.streamsreflowing.core.compose.ColumnPlan",
    }
    text = {key: _javap(java_home, jar, value, "-p", "-s", "-c") for key, value in classes.items()}
    failures: list[str] = []
    bank_source = _source("StreamsBankMaterial2162Mixin.java")
    seagrass_source = _source("StreamsSeagrassFeature2162Mixin.java")
    startup_source = _source("StreamsStartup2162Mixin.java")
    scale_source = _source("StreamsWorldgenScale2162Mixin.java")
    natural_source = _source("StreamsNaturalRiver2162Mixin.java")
    network_source = _source("StreamsNaturalNetwork2162Mixin.java")

    bank = _method_for_descriptor(text["carver"], BANK_HELPER)
    _require(bool(bank), "2.16.2 bank helper descriptor missing", failures)
    _require(bank.count(RING_PICK) == 1, "bank ring selection call count changed", failures)
    _require(bank.count(TOP_HELPER) == 1, "bank exposed-top helper call count changed", failures)
    containment = _method_for_descriptor(text["carver"], CONTAINMENT_FILL)
    _require(bool(containment), "2.16.2 containment fill helper descriptor missing", failures)
    _require(containment.count(TOP_HELPER) == 2,
             "containment fill/lip write paths changed", failures)
    _require('@WrapOperation(method = NATIVE_BANK_FILL' in bank_source
             and 'target = TOP_HELPER, ordinal = 1' in bank_source
             and 'StreamsNativeBankFill.allowsLip(level, engine, x, y, z)' in bank_source,
             "native optional lip hook contract drifted", failures)
    _require("@Inject(method = NATIVE_BANK_FILL" in bank_source
             and "ColumnPlan;IIIIIZII)V" in bank_source
             and "StreamsNativeBankFill.allows(level, targetX, targetZ, crestY, minY, mayAddLip)" in bank_source,
             "native containment admission handler contract drifted", failures)
    _require(re.search(r"127: iload\s+13\s+129: iload\s+8\s+131: iconst_4\s+132: isub", containment) is not None,
             "containment fill no longer uses the audited crest argument 8 and five-block cap", failures)
    # The metrics fed to the pick must still select point-bar vs. non-point-bar
    # from argument 22, then pass that computed metrics local and ring index to
    # the sole native pick.  This pins the @Local(argsOnly = true, index = 22)
    # contract, rather than merely counting the invoke.
    point_bar_metrics = (
        r"609: aload\s+23\s+611: aload_1\s+612: invokevirtual .*RiverEngine\.config"
        r".*615: iload\s+22\s+617: invokestatic .*ColumnPlan;Ldev/streamsreflowing/core/config/StreamsConfig;Z\)"
        r".*626: astore\s+44"
    )
    ring_stack = (
        r"710: aload\s+46\s+712: lload\s+33\s+714: iload\s+5\s+716: iload\s+24"
        r"\s+718: iload\s+6\s+720: dload\s+38\s+722: dload\s+42\s+724: aload\s+44"
        r"\s+726: iload\s+45\s+728: invokevirtual"
    )
    compact_bank = " ".join(bank.split())
    _require(re.search(point_bar_metrics, compact_bank) is not None,
             "bank metrics no longer derive from helper argument 22", failures)
    _require(re.search(ring_stack, compact_bank) is not None,
             "bank ring pick argument stack no longer uses audited metrics/index locals", failures)
    _require("method = BANK_HELPER" in bank_source and "index = 22" in bank_source,
             "bank mixin no longer binds helper or point-bar argument 22", failures)
    _require("AllRightsReservedNiceJohn_009" in bank_source and "ProprietaryNiceJohn_003" in bank_source,
             "bank mixin selection/metrics target drifted", failures)

    place = _method_for_descriptor(text["carver"],
        "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    writes = re.findall(r"(?m)^\s*(\d+): invokeinterface .*WorldGenLevel\.setBlock:", place)
    _require(len(writes) >= 16, "place has fewer than 16 setBlock calls", failures)
    recap_offsets = [writes[i] if len(writes) > i else None for i in (12, 13, 14, 15)]
    _require(recap_offsets == ["13510", "13593", "13609", "13634"],
             f"recap setBlock ordinals moved: {recap_offsets}", failures)
    recap_state_setup = (
        r"getstatic .*Blocks\.GRASS_BLOCK.*defaultBlockState.*astore\s+16"
        r".*getstatic .*Blocks\.SHORT_GRASS.*defaultBlockState.*astore\s+17"
        r".*getstatic .*Blocks\.TALL_GRASS.*defaultBlockState.*astore\s+18"
        r".*getstatic .*Blocks\.TALL_GRASS.*defaultBlockState.*DOUBLE_BLOCK_HALF.*UPPER.*astore\s+19"
    )
    recap_writes = (
        r"13503: aload\s+4\s+13505: aload\s+102\s+13507: aload\s+16\s+13509: iconst_2"
        r"\s+13510: invokeinterface.*13586: aload\s+4\s+13588: aload\s+104\s+13590: aload\s+18"
        r".*13599: aload\s+4\s+13601: aload\s+104.*13606: aload\s+19.*13609: invokeinterface"
        r".*13627: aload\s+4\s+13629: aload\s+104\s+13631: aload\s+17.*13634: invokeinterface"
    )
    compact_place = " ".join(place.split())
    _require(re.search(recap_state_setup, compact_place) is not None,
             "recap grass/tall-grass state setup changed", failures)
    _require(re.search(recap_writes, compact_place) is not None,
             "recap writes no longer consume grass/tall-grass state locals", failures)
    for ordinal in (12, 13, 14, 15):
        _require(f"ordinal = {ordinal}" in bank_source, f"bank recap hook ordinal {ordinal} missing", failures)
    place_top_helpers = re.findall(
        r"(?m)^\s*(\d+): invokestatic .*Method proprietary1:"
        r"\(Lnet/minecraft/world/level/WorldGenLevel;"
        r"Lnet/minecraft/core/BlockPos\$MutableBlockPos;III"
        r"Lnet/minecraft/world/level/block/state/BlockState;I\)V",
        place)
    _require(place_top_helpers == PLACE_TOP_HELPER_OFFSETS,
             f"place exposed-top helper calls moved: {place_top_helpers}", failures)
    _require("terrainDiffusion$climateRaisedCap" in bank_source
             and "at = @At(value = \"INVOKE\", target = TOP_HELPER)" in bank_source
             and "require = 5" in bank_source,
             "place exposed-top helper climate hook missing", failures)

    grass = _method_for_descriptor(text["seagrass"], SEAGRASS_COLUMN)
    _require(bool(grass), "2.16.2 seagrass column helper descriptor missing", failures)
    _require(grass.count("DoublePlantBlock.placeAt:(Lnet/minecraft/world/level/LevelAccessor;") == 1,
             "tall seagrass placement call count changed", failures)
    _require(grass.count("WorldGenLevel.setBlock:(Lnet/minecraft/core/BlockPos;") == 1,
             "short seagrass placement call count changed", failures)
    _require("PLACE_COLUMN" in seagrass_source and "DoublePlantBlock;placeAt(" in seagrass_source
             and "WorldGenLevel;setBlock(" in seagrass_source,
             "seagrass mixin targets no longer match audited calls", failures)

    worldgen = text["worldgen"]
    engine = _method_for_descriptor(worldgen,
        "(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;")
    startup = _method_for_descriptor(worldgen,
        "(Ldev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
        "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/server/level/ServerLevel;J)"
        "Ldev/streamsreflowing/core/terrain/TerrainSampler;")
    _require(bool(engine) and bool(startup), "worldgen engine/startup method descriptor missing", failures)
    _require("StreamsConfig.proprietary1:(IDIIIIIZ)Ldev/streamsreflowing/core/config/StreamsConfig;" in engine,
             "scale config factory call missing from engine", failures)
    _require(engine.count("RiverEngine.\"<init>\":(JLdev/streamsreflowing/core/config/StreamsConfig;") == 1,
             "RiverEngine construction call count changed", failures)
    _require(engine.count("ProprietaryNiceJohn_002.\"<init>\":(Ljava/nio/file/Path;JLdev/streamsreflowing/core/config/StreamsConfig;)V") == 1,
             "versioned store construction call count changed", failures)
    _require("StreamsConfig;proprietary1(IDIIIIIZ)" in scale_source and "RiverEngine;<init>" in natural_source
             and "ProprietaryNiceJohn_002;<init>" in natural_source,
             "scale/natural/store mixin targets drifted", failures)
    _require("TerrainSampler;Lnet/minecraft/world/level/chunk/ChunkGenerator;" in startup_source,
             "startup mixin target drifted", failures)
    selector = _method_for_descriptor(worldgen,
        "(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;"
        "Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;I"
        "Ldev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/server/level/ServerLevel;Z)"
        "Ldev/streamsreflowing/worldgen/StreamsWorldgen$LicensedCodeNotForAnalysis_001;")
    exact_branch = (
        r"String streamsreflowing\.exact.*Boolean\.getBoolean.*6: ifeq\s+13\s+9: iconst_2\s+10: goto\s+36"
        r"\s+13: getstatic.*allRightsReserved7:\(\)Z\s+21: ifeq\s+35.*notForAnalysis7:\(\)I"
        r".*35: iconst_2\s+36: istore\s+8"
    )
    _require(bool(selector) and re.search(exact_branch, " ".join(selector.split())) is not None,
             "fastcal selector no longer preserves exact/platform/OFF branch", failures)
    _require("IPlatformHelper;allRightsReserved7()Z" in startup_source
             and "return original && !StreamsStartupPolicy.useExactTerrain(level)" in startup_source,
             "startup fastcal hook no longer guards the native platform selector", failures)
    dddd_stack = (
        r"sourceGivenToAuthorizedOnly5:\(\)D.*licensed5:\(\)D.*allRightsReserved5:\(\)D"
        r".*notForAnalysis5:\(\)D.*StreamsConfig\.proprietary1:\(DDDD\)"
    )
    _require(re.search(dddd_stack, " ".join(engine.split())) is not None,
             "appearance config DDDD argument order changed", failures)
    _require("StreamsConfig;proprietary1(DDDD)" in scale_source
             and "arguments.get(2)" in scale_source and "arguments.set(2," in scale_source,
             "scale appearance hook no longer adjusts native DDDD argument 2 (max width)", failures)

    network = _method_for_descriptor(text["network"], NETWORK_FACTORY)
    _require(bool(network), "plate-local network factory descriptor missing", failures)
    _require("at = @At(\"RETURN\")" in network_source and "NETWORK_FACTORY" not in network_source,
             "network mixin return hook contract drifted", failures)
    _require("proprietary1(Ldev/streamsreflowing/core/plate/ProprietaryNiceJohn_001;" in network_source,
             "network mixin factory target drifted", failures)

    _require("descriptor: (IDDIIIIIZZDDDDDIDDDDDDDIDIDDDZDZZDZZDDIIDDLjava/lang/String;"
             "Ldev/streamsreflowing/core/config/HydraulicGeometry;)V" in text["config"],
             "StreamsConfig 44-component layout changed", failures)
    _require("descriptor: (DDDDDDD)V" in text["hydraulic"], "HydraulicGeometry layout changed", failures)
    _require("descriptor: (ZZIIDDDDDDDZZDDDDDDDDDD)V" in text["column"],
             "ColumnPlan 23-component layout changed", failures)

    shield = _method_for_descriptor(text["carver"],
        "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/ChunkPos;)[I")
    _require(bool(shield), "native structure shield helper descriptor missing", failures)
    _require(shield.count("StructureStart.getPieces:()Ljava/util/List;") == 1
             and shield.count("StructurePiece.getBoundingBox:()Lnet/minecraft/world/level/levelgen/structure/BoundingBox;") == 1,
             "native structure shield no longer enumerates individual pieces", failures)
    _require("BoundingBox.minX:()I" in shield and "BoundingBox.maxZ:()I" in shield,
             "native structure shield bounding-box contract changed", failures)

    evidence = {
        "bankHelper": BANK_HELPER,
        "containmentFillHelper": CONTAINMENT_FILL,
        "containmentFillAndLipCalls": containment.count(TOP_HELPER),
        "ringPickCalls": bank.count(RING_PICK),
        "topHelperCalls": bank.count(TOP_HELPER),
        "pointBarArgument22": re.search(point_bar_metrics, compact_bank) is not None,
        "ringPickArgumentStack": re.search(ring_stack, compact_bank) is not None,
        "recapOrdinals": {str(i): recap_offsets[n] for n, i in enumerate((12, 13, 14, 15))},
        "recapStateFlow": re.search(recap_writes, compact_place) is not None,
        "placeTopHelperOffsets": place_top_helpers,
        "fastcalExactBranch": re.search(exact_branch, " ".join(selector.split())) is not None,
        "appearanceMaxWidthArgument": 2,
        "seagrassTallCalls": grass.count("DoublePlantBlock.placeAt:(Lnet/minecraft/world/level/LevelAccessor;"),
        "seagrassShortCalls": grass.count("WorldGenLevel.setBlock:(Lnet/minecraft/core/BlockPos;"),
        "nativePieceShield": {"getPieces": shield.count("StructureStart.getPieces:()Ljava/util/List;"),
                               "boundingBox": shield.count("StructurePiece.getBoundingBox:()Lnet/minecraft/world/level/levelgen/structure/BoundingBox;")},
    }
    return {"version": "2.16.2", "jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "passed": not failures, "failures": failures, "evidence": evidence}
