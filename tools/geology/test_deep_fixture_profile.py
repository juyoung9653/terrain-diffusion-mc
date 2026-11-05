"""Focused NBT patch checks for the versioned deep-world fixture profile."""
from __future__ import annotations

import gzip
import importlib.util
import argparse
import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BENCHMARK = ROOT / "tools/geology/benchmark.py"


def load():
    spec = importlib.util.spec_from_file_location("td_deep_fixture_benchmark", BENCHMARK)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def value(fields, suffix):
    matches = [field[3] for key, field in fields.items() if key[-1:] == (suffix,)]
    assert len(matches) == 1, (suffix, matches)
    return matches[0]


def compare_receipt(profile, *, lowest, water, terrain, marine):
    return {
        "success": True, "elapsedSeconds": 1.0, "phaseSeconds": {"chunkCensus": 1.0},
        "chunkCensusStatus": "minecraft:full", "centerChunk": [-61, 24], "radiusChunks": 1,
        "candidateOwners": 4, "attempted": 4,
        "underground": {
            "minY": -720, "maxY": 735, "sampledChunks": 4, "columns": 64,
            "floorBedrock": 64, "legacyShelfBedrock": 0,
            "undergroundProfileVersion": profile,
            "worldSurfaceSha256": "surface", "oceanFloorSha256": "ocean",
            "baseTerrainSha256": terrain, "landBaseTerrainSha256": "empty-land",
            "marineBaseTerrainSha256": marine, "landColumns": 0,
            "marineColumns": 64, "marineWaterColumns": water,
            "marineLavaAboveFloor": 0,
            "lowestOceanFloorY": lowest,
        },
    }


def check_profile_comparison(benchmark, directory: Path) -> None:
    inputs = {"mods": "mods", "compatPack": "pack", "depthOverride": "depth"}
    baseline, candidate = directory / "baseline", directory / "candidate"
    for target, profile in ((baseline, 1), (candidate, 2)):
        (target / "world").mkdir(parents=True)
        manifest = {"source": "fixture", "sourceLevelDatSha256": "level", "sourceInputManifestSha256": "input",
                    "fixtureInputManifestSha256": "fixture-input", "scale": 2, "ceiling": 735,
                    "center": "-61,24", "radius": 1, "minY": -720,
                    "undergroundProfileVersion": profile, "actualInputs": inputs}
        (target / "underground-benchmark.json").write_text(json.dumps(manifest), encoding="utf-8")
    (baseline / "world/density-audit.json").write_text(json.dumps(
        compare_receipt(1, lowest=2, water=64, terrain="legacy", marine="legacy-marine")), encoding="utf-8")
    original_inputs = benchmark.verified_inputs
    benchmark.verified_inputs = lambda _path: inputs
    try:
        (candidate / "world/density-audit.json").write_text(json.dumps(
            compare_receipt(2, lowest=2, water=64, terrain="v2", marine="v2-marine")), encoding="utf-8")
        raw_only = benchmark.compare(argparse.Namespace(baseline=baseline, candidate=candidate,
                                                        max_regression_percent=100.0))
        assert raw_only["withinLimit"] is False
        assert raw_only["receiptEvidence"]["actualMarineDeepening"] is False
        (candidate / "world/density-audit.json").write_text(json.dumps(
            compare_receipt(2, lowest=1, water=64, terrain="v2", marine="v2-marine")), encoding="utf-8")
        deepened = benchmark.compare(argparse.Namespace(baseline=baseline, candidate=candidate,
                                                        max_regression_percent=100.0))
        assert deepened["withinLimit"] is True
        assert deepened["receiptEvidence"]["actualMarineDeepening"] is True
        lava_ocean = compare_receipt(2, lowest=1, water=64, terrain="v2", marine="v2-marine")
        lava_ocean["underground"]["marineLavaAboveFloor"] = 1
        (candidate / "world/density-audit.json").write_text(json.dumps(lava_ocean), encoding="utf-8")
        rejected = benchmark.compare(argparse.Namespace(baseline=baseline, candidate=candidate,
                                                       max_regression_percent=100.0))
        assert rejected["withinLimit"] is False
        assert rejected["receiptEvidence"]["marineEvidence"] is False
        lava_ocean["underground"]["marineLavaAboveFloor"] = 0
        (candidate / "world/density-audit.json").write_text(json.dumps(lava_ocean), encoding="utf-8")
        # A repeat of the same V2 profile must not be required to deepen again.
        base_manifest_path = baseline / "underground-benchmark.json"
        base_manifest = json.loads(base_manifest_path.read_text(encoding="utf-8"))
        base_manifest["undergroundProfileVersion"] = 2
        base_manifest_path.write_text(json.dumps(base_manifest), encoding="utf-8")
        (baseline / "world/density-audit.json").write_text(
            (candidate / "world/density-audit.json").read_text(encoding="utf-8"), encoding="utf-8")
        repeat = benchmark.compare(argparse.Namespace(baseline=baseline, candidate=candidate,
                                                     max_regression_percent=100.0))
        assert repeat["withinLimit"] is True
    finally:
        benchmark.verified_inputs = original_inputs


def main() -> None:
    benchmark = load()
    source = ROOT / "build/streams-probe/streams2162-runtime-a/world/level.dat"
    if not source.is_file():
        raise SystemExit(f"Missing prepared fixture source: {source}")
    with tempfile.TemporaryDirectory(prefix="td-deep-profile-", dir=ROOT / "build") as directory:
        level = Path(directory) / "level.dat"
        shutil.copy2(source, level)
        benchmark.patch_level(level, scale=2, underground=True, profile_version=2)
        no_river_fields = benchmark.nbt_fields(gzip.decompress(level.read_bytes()))
        assert no_river_fields[benchmark.GENERATOR_SETTINGS][3] == "terrain-diffusion-mc:underground_v2"
        assert value(no_river_fields, "underground_profile_version") == 2
        assert value(no_river_fields, "underground_rivers") == 0

        shutil.copy2(source, level)
        before = level.read_bytes()
        benchmark.patch_level(level, scale=2, underground=True, rivers=True,
                              river_version=3, profile_version=2)
        fields = benchmark.nbt_fields(gzip.decompress(level.read_bytes()))
        assert fields[benchmark.GENERATOR_SETTINGS][3] == "terrain-diffusion-mc:underground_v2"
        assert value(fields, "underground_profile_version") == 2
        assert value(fields, "underground_river_version") == 3
        assert value(fields, "underground_rivers") == 1
        assert level.read_bytes() != before
        try:
            benchmark.patch_level(level, scale=2, underground=True, rivers=True,
                                  river_version=2, profile_version=2)
        except ValueError as failure:
            assert "requires" in str(failure)
        else:
            raise AssertionError("V2 accepted the legacy river model")
        river_source = source.parent / "data/terrain_diffusion_natural_rivers.dat"
        river_profile = Path(directory) / "terrain_diffusion_natural_rivers.dat"
        shutil.copy2(river_source, river_profile)
        profile = benchmark.patch_river_profile_scale(river_profile, 2, profile_version=11)
        river_fields = benchmark.nbt_fields(gzip.decompress(river_profile.read_bytes()))
        assert profile["version"] == 11
        assert value(river_fields, "version") == 11
        assert value(river_fields, "scale") == 2
        check_profile_comparison(benchmark, Path(directory) / "comparison")
        original_prepare = benchmark.prepare
        try:
            seen = {}
            benchmark.prepare = lambda args: seen.update(vars(args)) or {"ok": True}
            with contextlib.redirect_stdout(io.StringIO()):
                assert benchmark.main(["prepare", "--target", str(Path(directory) / "v1-river"),
                                       "--min-y", "-736", "--scale", "2", "--rivers",
                                       "--river-version", "2", "--profile-version", "1"]) == 0
            assert seen["river_version"] == 2 and seen["profile_version"] == 1
        finally:
            benchmark.prepare = original_prepare
    print("deep fixture profile NBT checks passed")


if __name__ == "__main__":
    main()
