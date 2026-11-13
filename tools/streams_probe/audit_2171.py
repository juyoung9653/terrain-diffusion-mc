"""Fail-closed static boundary audit for native-first Streams Reflowing 2.17.1.

This records the small set of upstream contracts TD needs to select its terrain
sampler and retain a world-local store path.  It deliberately does not audit or
patch Streams' native river graph, bank, bed, or structure-protection behaviour.
"""
from __future__ import annotations

import hashlib
import json
import re
import tomllib
import zipfile
from pathlib import Path

from audit_2162 import _javap, _method_for_descriptor


ROOT = Path(__file__).resolve().parents[2]
STARTUP_MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsStartup2171Mixin.java"
INFERENCE_MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsInference2171Mixin.java"
NETWORK_MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsNaturalNetwork2171Mixin.java"
WORLDGEN = "dev.streamsreflowing.worldgen.StreamsWorldgen"
CARVER = "dev.streamsreflowing.worldgen.RiverCarverFeature"
HOST_SAMPLER = (
    "(Ldev/streamsreflowing/core/terrain/TerrainSampler;"
    "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
    "Lnet/minecraft/world/level/levelgen/RandomState;"
    "Lnet/minecraft/server/level/ServerLevel;J)"
    "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
)
CALIBRATION_SELECTOR = (
    "(Lnet/minecraft/world/level/chunk/ChunkGenerator;"
    "Lnet/minecraft/world/level/levelgen/RandomState;"
    "Lnet/minecraft/world/level/biome/BiomeSource;"
    "Lnet/minecraft/world/level/biome/Climate$Sampler;I"
    "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
    "Lnet/minecraft/server/level/ServerLevel;Z)"
    "Ldev/streamsreflowing/worldgen/StreamsWorldgen$AllRightsReservedNiceJohn_001;"
)
ENGINE_FACTORY = (
    "(JLdev/streamsreflowing/core/config/StreamsConfig;"
    "Ldev/streamsreflowing/worldgen/StreamsWorldgen$SeeAiNoticeConstant_002;"
    "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;"
    "Lstreamsreflowing/proprietary/ProprietaryNiceJohn_004;)"
    "Ldev/streamsreflowing/core/RiverEngine;"
)
STORE_CONSTRUCTOR = "(Ljava/nio/file/Path;JLdev/streamsreflowing/core/config/StreamsConfig;)V"
RIVER_ENGINE_CONSTRUCTOR = (
    "(JLdev/streamsreflowing/core/config/StreamsConfig;"
    "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
    "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;)V"
)
DEPTH_HELPER = (
    "(Ldev/streamsreflowing/core/compose/ColumnPlan;"
    "Ldev/streamsreflowing/core/config/StreamsConfig;DDDJIIIID)I"
)
CONFIG_LAYOUT = (
    "(IDDIIIIIZZDDDDDIDDDDDDDIDIDDDZDZZDZZDDIIDDLjava/lang/String;"
    "Ldev/streamsreflowing/core/config/HydraulicGeometry;)V"
)
COLUMN_LAYOUT = "(ZZIIDDDDDDDZZDDDDDDDDDD)V"
PLATFORM_EXACT_SELECTOR = "Ldev/streamsreflowing/platform/services/IPlatformHelper;allRightsReserved7()Z"


def _require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def _version(jar: Path) -> str | None:
    with zipfile.ZipFile(jar) as archive:
        try:
            document = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8-sig"))
        except KeyError:
            return None
    for mod in document.get("mods", []):
        if mod.get("modId") == "streamsreflowing":
            return str(mod.get("version"))
    return None


def _startup_mixin_source() -> str:
    if not STARTUP_MIXIN.is_file():
        raise RuntimeError(f"missing 2.17.1 startup mixin: {STARTUP_MIXIN}")
    return STARTUP_MIXIN.read_text(encoding="utf-8")


def _inference_mixin_source() -> str:
    if not INFERENCE_MIXIN.is_file():
        raise RuntimeError(f"missing 2.17.1 inference mixin: {INFERENCE_MIXIN}")
    return INFERENCE_MIXIN.read_text(encoding="utf-8")

def _network_mixin_source() -> str:
    if not NETWORK_MIXIN.is_file():
        raise RuntimeError(f"missing 2.17.1 network mixin: {NETWORK_MIXIN}")
    return NETWORK_MIXIN.read_text(encoding="utf-8")


def audit_2171(java_home: Path, jar: Path) -> dict[str, object]:
    """Return compact, reviewable evidence for the actual Streams 2.17.1 JAR."""
    jar = jar.resolve()
    if not jar.is_file():
        raise RuntimeError(f"missing Streams 2.17.1 JAR: {jar}")
    failures: list[str] = []
    version = _version(jar)
    _require(version == "2.17.1", f"expected streamsreflowing 2.17.1, found {version!r}", failures)
    text = {
        "worldgen": _javap(java_home, jar, WORLDGEN, "-p", "-s", "-c"),
        "carver": _javap(java_home, jar, CARVER, "-p", "-s", "-c"),
        "config": _javap(java_home, jar, "dev.streamsreflowing.core.config.StreamsConfig", "-p", "-s"),
        "hydraulics": _javap(java_home, jar, "dev.streamsreflowing.core.config.HydraulicGeometry", "-p", "-s"),
        "column": _javap(java_home, jar, "dev.streamsreflowing.core.compose.ColumnPlan", "-p", "-s"),
        "engine": _javap(java_home, jar, "dev.streamsreflowing.core.RiverEngine", "-p", "-s", "-c"),
        "store": _javap(java_home, jar, "dev.streamsreflowing.worldgen.SeeAiNoticeConstant_002", "-p", "-s"),
        "depth": _javap(java_home, jar, "dev.streamsreflowing.worldgen.SourceGivenToAuthorizedOnly_001", "-p", "-s"),
    }
    host = _method_for_descriptor(text["worldgen"], HOST_SAMPLER)
    selector = _method_for_descriptor(text["worldgen"], CALIBRATION_SELECTOR)
    engine = _method_for_descriptor(text["worldgen"],
                                    "(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;")
    factory = _method_for_descriptor(text["worldgen"], ENGINE_FACTORY)
    _require(bool(host), "host terrain-sampler selector descriptor changed", failures)
    _require(bool(selector), "exact-calibration selector descriptor changed", failures)
    selector_compact = " ".join(selector.split())
    exact_branch = (
        r"0: ldc_w .*String streamsreflowing\.exact 3: invokestatic .*Boolean\.getBoolean:"
        r"\(Ljava/lang/String;\)Z 6: ifeq 13 9: iconst_2 10: goto 36"
        r" 13: getstatic .*Services\.PLATFORM.*16: invokeinterface .*IPlatformHelper\.allRightsReserved7:\(\)Z"
        r" 21: ifeq 35 .* 35: iconst_2 36: istore 8"
    )
    _require(re.search(exact_branch, selector_compact) is not None,
             "exact/platform/OFF calibration branch changed", failures)
    _require(selector.count("IPlatformHelper.allRightsReserved7:()Z") == 1,
             "exact-calibration platform selector call count changed", failures)
    mixin_source = _startup_mixin_source()
    _require(PLATFORM_EXACT_SELECTOR in mixin_source,
             "2.17.1 startup mixin no longer targets the audited platform selector", failures)
    _require(HOST_SAMPLER in mixin_source and CALIBRATION_SELECTOR in mixin_source,
             "2.17.1 startup mixin method descriptors drifted from the audit", failures)
    _require(ENGINE_FACTORY in text["worldgen"], "2.17.1 engine factory descriptor changed", failures)
    factory_ctor_calls = factory.count('RiverEngine."<init>":' + RIVER_ENGINE_CONSTRUCTOR)
    direct_engine_ctor_calls = engine.count('RiverEngine."<init>":' + RIVER_ENGINE_CONSTRUCTOR)
    _require(factory_ctor_calls == 1,
             f"factory RiverEngine constructor call count is {factory_ctor_calls}, expected 1", failures)
    _require(direct_engine_ctor_calls == 1,
             f"engine RiverEngine constructor call count is {direct_engine_ctor_calls}, expected 1", failures)
    inference_source = _inference_mixin_source()
    _require("method = \"engine(Lnet/minecraft/world/level/WorldGenLevel;)\"" in inference_source
             and 'target = "Ldev/streamsreflowing/core/RiverEngine;<init>(J"' in inference_source,
             "2.17.1 inference mixin no longer targets the audited engine constructor", failures)
    _require(STORE_CONSTRUCTOR in text["store"], "2.17.1 world-local store constructor changed", failures)
    _require(RIVER_ENGINE_CONSTRUCTOR in text["engine"], "RiverEngine native constructor changed", failures)
    five_arg = ("(Ldev/streamsreflowing/core/plate/ProprietaryNiceJohn_001;"
                "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
                "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;"
                "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
                "Ldev/streamsreflowing/core/river/RiverNetwork;")
    live_builder_calls = text["engine"].count("NoDecompilePermissionEver_001.proprietary1:" + five_arg)
    _require(live_builder_calls >= 1, "RiverEngine no longer calls the live five-argument graph builder", failures)
    network_source = _network_mixin_source()
    _require("ProprietaryNiceJohn_001" in network_source
             and "SourceGivenToAuthorizedOnly_002" in network_source
             and "Streams2171NetworkAdapter.refine(domain" in network_source,
             "2.17.1 network mixin no longer targets/preserves the live builder domain", failures)
    _require("descriptor: (IDIIIIIZ)Ldev/streamsreflowing/core/config/StreamsConfig;" in text["config"],
             "StreamsConfig scale factory descriptor changed", failures)
    _require("descriptor: (DDDD)Ldev/streamsreflowing/core/config/StreamsConfig;" in text["config"],
             "StreamsConfig appearance factory descriptor changed", failures)
    _require(f"descriptor: {CONFIG_LAYOUT}" in text["config"], "StreamsConfig 44-component layout changed", failures)
    _require("descriptor: (DDDDDDD)V" in text["hydraulics"], "HydraulicGeometry layout changed", failures)
    _require(f"descriptor: {COLUMN_LAYOUT}" in text["column"], "ColumnPlan 23-component layout changed", failures)
    _require(DEPTH_HELPER in text["depth"], "native shallow-bed depth helper moved or changed", failures)
    place = _method_for_descriptor(text["carver"], "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    shield = _method_for_descriptor(text["carver"], "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/ChunkPos;)[I")
    _require(bool(place), "RiverCarverFeature.place descriptor changed", failures)
    _require(bool(shield), "native structure-shield helper descriptor changed", failures)
    _require(shield.count("StructureStart.getPieces:()Ljava/util/List;") == 1
             and shield.count("StructurePiece.getBoundingBox:()Lnet/minecraft/world/level/levelgen/structure/BoundingBox;") == 1,
             "native structure shield no longer enumerates individual pieces", failures)
    evidence = {
        "version": version,
        "hostSampler": bool(host),
        "calibrationSelector": bool(selector),
        "exactPlatformOffBranch": re.search(exact_branch, selector_compact) is not None,
        "exactPlatformCallCount": selector.count("IPlatformHelper.allRightsReserved7:()Z"),
        "engineFactory": ENGINE_FACTORY in text["worldgen"],
        "factoryRiverEngineConstructorCalls": factory_ctor_calls,
        "engineDirectRiverEngineConstructorCalls": direct_engine_ctor_calls,
        "storeConstructor": STORE_CONSTRUCTOR in text["store"],
        "nativeEngineConstructor": RIVER_ENGINE_CONSTRUCTOR in text["engine"],
        "liveFiveArgGraphBuilderCalls": live_builder_calls,
        "nativeDepthHelper": DEPTH_HELPER in text["depth"],
        "nativePieceShield": {
            "getPieces": shield.count("StructureStart.getPieces:()Ljava/util/List;"),
            "boundingBox": shield.count("StructurePiece.getBoundingBox:()Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"),
        },
    }
    return {"version": "2.17.1", "jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "passed": not failures, "failures": failures, "evidence": evidence}


def main() -> None:
    import argparse
    import os
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-21")))
    args = parser.parse_args()
    result = audit_2171(args.java_home, args.jar)
    print(json.dumps(result, indent=2))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
