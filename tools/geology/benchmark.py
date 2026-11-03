#!/usr/bin/env python3
"""Prepare and compare fresh underground-depth density fixtures.

The helper never changes its source fixture.  It stages a normal prepared density
fixture first, then gives the disposable world a tiny datapack which overrides
only the experimental underground type/noise height for that one run.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = ROOT / "build/streams-probe/streams2162-runtime-a"
OVERWORLD = ("Data", "WorldGenSettings", "dimensions", "minecraft:overworld")
GENERATOR_SETTINGS = OVERWORLD + ("generator", "settings")
DIMENSION_TYPE = OVERWORLD + ("type",)
BIOME_SOURCE = OVERWORLD + ("generator", "biome_source")
SAVED_SCALE_DATA = ("data",)

# Keep earlier depth experiments comparable while making the normal scale
# profiles symmetric about the Y=-1/Y=0 boundary.
SCALE_FLOORS = {1: -400, 2: -736, 3: -1072, 4: -1408, 5: -1744, 6: -1968}
LEGACY_FLOORS = {-1200, -608, -304, -64}
RIVER_STATE = Path("world/data/terrain_diffusion_natural_rivers.dat")
RIVER_EXACT_MARKER = Path("world/data/terrain_diffusion_streams_exact_v1.txt")
RIVER_CACHE = Path("world/streamsreflowing")


def _nbt_helpers():
    path = ROOT / "tools/river_planner/prepare-playtest.py"
    spec = importlib.util.spec_from_file_location("td_benchmark_nbt", path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module.nbt_fields


nbt_fields = _nbt_helpers()


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_digest(root: Path) -> str:
    """Hash names and bytes so copied pack mutations cannot hide behind mtime."""
    if not root.is_dir() or root.is_symlink():
        raise ValueError(f"Expected real directory: {root}")
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Refusing linked benchmark input: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big")); digest.update(relative)
        digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def verified_inputs(fixture: Path) -> dict[str, str]:
    """Recheck copied mod/config/model inputs against the fixture manifest."""
    row = json.loads((fixture / "input-manifest.json").read_text(encoding="utf-8"))
    expected = row.get("sha256")
    if not isinstance(expected, dict):
        raise ValueError(f"Missing fixture input hashes: {fixture}")
    for relative, wanted in expected.items():
        if not relative.startswith(("mods/", "config/", "terrain-diffusion-models/")):
            continue
        actual = fixture / relative
        if not actual.is_file() or sha256(actual) != wanted:
            raise ValueError(f"Fixture input changed or missing: {actual}")
    return {
        "mods": tree_digest(fixture / "mods"),
        "config": tree_digest(fixture / "config"),
        "models": tree_digest(fixture / "terrain-diffusion-models"),
        "compatPack": tree_digest(fixture / "world/datapacks/terrain-diffusion-terralith-surface"),
        "depthOverride": tree_digest(fixture / "world/datapacks/td-underground-depth"),
        "riverCacheArchive": (tree_digest(fixture / "benchmark-source-river-cache")
                              if (fixture / "benchmark-source-river-cache").is_dir()
                              else "none"),
    }


def verify_recorded_stable_inputs(manifest: dict[str, object], actual: dict[str, str]) -> None:
    """Prepared manifests may predate this field; when present, reject mutation."""
    recorded = manifest.get("actualInputs")
    if recorded is None:
        return
    if not isinstance(recorded, dict):
        raise ValueError("Invalid recorded actualInputs")
    # Config/model directories can gain runtime cache files.  Their original
    # files are checked individually above; these stable trees must be exact.
    for key in ("mods", "compatPack", "depthOverride", "riverCacheArchive"):
        if recorded.get(key) != actual.get(key):
            raise ValueError(f"Recorded {key} digest no longer matches staged fixture")


def named_byte(name: str, value: bool) -> bytes:
    encoded = name.encode("utf-8")
    return bytes((1,)) + len(encoded).to_bytes(2, "big") + encoded + bytes((1 if value else 0,))


def named_int(name: str, value: int) -> bytes:
    encoded = name.encode("utf-8")
    return bytes((3,)) + len(encoded).to_bytes(2, "big") + encoded + struct.pack(">i", value)


def scalar_payload(kind: int, value: int) -> bytes:
    if kind == 1:
        return struct.pack(">b", value)
    if kind == 3:
        return struct.pack(">i", value)
    raise ValueError(f"Unsupported scalar NBT type {kind}")


def encoded_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > 65535:
        raise ValueError("NBT string exceeds unsigned-short limit")
    return len(encoded).to_bytes(2, "big") + encoded


def patch_level(path: Path, *, scale: int, underground: bool, rivers: bool = False) -> None:
    """Patch only overworld registry ids and the TD biome-source experiment bit."""
    raw = gzip.decompress(path.read_bytes())
    before = nbt_fields(raw)
    for key in (GENERATOR_SETTINGS, DIMENSION_TYPE, BIOME_SOURCE):
        if key not in before:
            raise ValueError(f"Missing level.dat field: {'/'.join(key)}")
    if before[GENERATOR_SETTINGS][0] != 8 or before[DIMENSION_TYPE][0] != 8 or before[BIOME_SOURCE][0] != 10:
        raise ValueError("Unexpected saved overworld worldgen representation")
    changes = {
        GENERATOR_SETTINGS: "terrain-diffusion-mc:underground" if underground else "terrain-diffusion-mc:terrain_diffusion",
        DIMENSION_TYPE: (f"terrain-diffusion-mc:underground_scale_{scale}" if underground
                         else f"terrain-diffusion-mc:terrain_diffusion_scale_{scale}"),
    }
    flag = BIOME_SOURCE + ("underground",)
    bound_scale = BIOME_SOURCE + ("underground_scale",)
    patches: list[tuple[int, int, bytes]] = []
    river_flag = BIOME_SOURCE + ("underground_rivers",)
    if river_flag in before:
        kind, start, end, _old = before[river_flag]
        if kind != 1:
            raise ValueError("Existing underground_rivers flag is not a byte")
        patches.append((start, end, bytes((1 if underground and rivers else 0,))))
    else:
        patches.append((before[BIOME_SOURCE][2] - 1, before[BIOME_SOURCE][2] - 1,
                        named_byte("underground_rivers", underground and rivers)))
    for key, value in changes.items():
        _kind, start, end, _old = before[key]
        patches.append((start, end, encoded_string(value)))
    if flag in before:
        kind, start, end, _old = before[flag]
        if kind != 1:
            raise ValueError("Existing biome-source underground flag is not a byte")
        patches.append((start, end, bytes((1 if underground else 0,))))
    else:
        # A compound payload's last byte is TAG_End.  Insert the optional byte
        # before it; no unrelated compound/list needs re-encoding.
        patches.append((before[BIOME_SOURCE][2] - 1, before[BIOME_SOURCE][2] - 1,
                        named_byte("underground", underground)))
    if bound_scale in before:
        kind, start, end, _old = before[bound_scale]
        if kind != 3:
            raise ValueError("Existing biome-source underground_scale is not an int")
        patches.append((start, end, scalar_payload(kind, 0)))
    else:
        patches.append((before[BIOME_SOURCE][2] - 1, before[BIOME_SOURCE][2] - 1,
                        named_int("underground_scale", 0)))
    enabled = ("Data", "DataPacks", "Enabled")
    kind, start, end, _ = before[enabled]
    if kind != 9 or raw[start] != 8:
        raise ValueError("Expected enabled datapack string list")
    names = [value[3] for key, value in before.items() if key[:-1] == enabled]
    for name in ("file/terrain-diffusion-terralith-surface", "file/td-underground-depth"):
        if name not in names:
            names.append(name)
    patches.append((start, end, bytes((8,)) + len(names).to_bytes(4, "big")
                    + b"".join(encoded_string(name) for name in names)))
    for start, end, encoded in sorted(patches, reverse=True):
        raw = raw[:start] + encoded + raw[end:]
    after = nbt_fields(raw)
    if (any(after[key][3] != value for key, value in changes.items())
            or after[flag][3] != (1 if underground else 0)
            or after[bound_scale][3] != 0):
        raise AssertionError("level.dat worldgen patch did not round-trip")
    path.write_bytes(gzip.compress(raw))


def saved_scale(world: Path) -> int:
    state = world / "data/terrain_diffusion_world_settings.dat"
    if not state.is_file():
        return 2
    fields = nbt_fields(gzip.decompress(state.read_bytes()))
    value = fields.get(("data", "scale"), (None, None, None, 2))[3]
    if type(value) is not int or not 1 <= value <= 6:
        raise ValueError(f"Invalid saved TD scale: {value!r}")
    return value


def patch_saved_scale(path: Path, scale: int) -> None:
    """Persist an explicit scale without re-encoding unrelated saved state."""
    if not 1 <= scale <= 6:
        raise ValueError(f"Scale must be 1..6, got {scale}")
    if not path.is_file():
        raise ValueError("Fixture lacks saved TD world scale data")
    raw = gzip.decompress(path.read_bytes())
    before = nbt_fields(raw)
    data = before.get(SAVED_SCALE_DATA)
    if data is None or data[0] != 10:
        raise ValueError("Saved TD world scale data lacks data compound")
    changes = {
        SAVED_SCALE_DATA + ("scale",): (3, scale),
        SAVED_SCALE_DATA + ("explicit_scale",): (1, 1),
    }
    patches: list[tuple[int, int, bytes]] = []
    for key, (kind, value) in changes.items():
        previous = before.get(key)
        if previous is None:
            encoded_name = key[-1].encode("utf-8")
            encoded = (bytes((kind,)) + len(encoded_name).to_bytes(2, "big")
                       + encoded_name + scalar_payload(kind, value))
            patches.append((data[2] - 1, data[2] - 1, encoded))
        else:
            if previous[0] != kind:
                raise ValueError(f"Invalid saved TD field type: {'/'.join(key)}")
            patches.append((previous[1], previous[2], scalar_payload(kind, value)))
    for start, end, encoded in sorted(patches, reverse=True):
        raw = raw[:start] + encoded + raw[end:]
    after = nbt_fields(raw)
    if (after[SAVED_SCALE_DATA + ("scale",)][3] != scale
            or after[SAVED_SCALE_DATA + ("explicit_scale",)][3] != 1):
        raise AssertionError("Saved scale patch did not round-trip")
    path.write_bytes(gzip.compress(raw))


def patch_river_profile_scale(path: Path, scale: int) -> dict[str, object]:
    """Retain the selected natural-river profile while binding it to the scale."""
    raw = gzip.decompress(path.read_bytes())
    before = nbt_fields(raw)
    required = {
        ("data", "scale"): 3,
        ("data", "version"): 3,
        ("data", "enabled"): 1,
        ("data", "seed"): 4,
    }
    for key, kind in required.items():
        if key not in before or before[key][0] != kind:
            raise ValueError(f"Invalid natural-river profile field: {'/'.join(key)}")
    if before[("data", "version")][3] != 10 or before[("data", "enabled")][3] != 1:
        raise ValueError("Natural-river profile must retain version 10 and enabled=true")
    current = before[("data", "scale")]
    raw = raw[:current[1]] + scalar_payload(3, scale) + raw[current[2]:]
    after = nbt_fields(raw)
    if (after[("data", "scale")][3] != scale
            or after[("data", "version")][3] != 10
            or after[("data", "enabled")][3] != 1):
        raise AssertionError("Natural-river profile scale patch did not round-trip")
    path.write_bytes(gzip.compress(raw))
    return {"seed": after[("data", "seed")][3], "scale": scale,
            "version": 10, "enabled": True}


def fresh_river_cache_for_scale(target: Path, *, source_scale: int, scale: int) -> dict[str, object]:
    """Archive copied scale-bound river state and leave a valid fresh cache."""
    if scale == source_scale:
        return {"cacheMode": "copied-for-matching-scale", "sourceScale": source_scale,
                "scale": scale, "operations": []}
    archive = target / "benchmark-source-river-cache"
    if archive.exists():
        raise ValueError(f"Unexpected benchmark river archive: {archive}")
    operations: list[dict[str, str]] = []
    for relative in (RIVER_STATE, RIVER_EXACT_MARKER, RIVER_CACHE):
        staged = target / relative
        if not staged.exists():
            continue
        saved = archive / relative
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staged), str(saved))
        operations.append({"move": relative.as_posix(), "archive": saved.relative_to(target).as_posix()})
    archived_profile = archive / RIVER_STATE
    if not archived_profile.is_file():
        raise ValueError("Scale override requires copied natural-river profile")
    staged_profile = target / RIVER_STATE
    staged_profile.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(archived_profile, staged_profile)
    profile = patch_river_profile_scale(staged_profile, scale)
    return {
        "cacheMode": "fresh-for-scale", "sourceScale": source_scale, "scale": scale,
        "operations": operations,
        "archivedSha256": {relative.as_posix(): (tree_digest(archive / relative)
                             if (archive / relative).is_dir() else sha256(archive / relative))
                           for relative in (RIVER_STATE, RIVER_EXACT_MARKER, RIVER_CACHE)
                           if (archive / relative).exists()},
        "runtimeRiverProfile": {**profile, "sha256": sha256(staged_profile)},
    }


def ceiling_for_scale(scale: int) -> int:
    path = ROOT / "common/src/main/resources/data/terrain-diffusion-mc/dimension_type" / f"terrain_diffusion_scale_{scale}.json"
    row = json.loads(path.read_text(encoding="utf-8"))
    return int(row["min_y"]) + int(row["height"]) - 1


def ceiling_for_saved_dimension(world: Path) -> int:
    fields = nbt_fields(gzip.decompress((world / "level.dat").read_bytes()))
    value = fields.get(DIMENSION_TYPE, (None, None, None, None))[3]
    if not isinstance(value, str) or ":" not in value:
        raise ValueError(f"Unsupported saved overworld dimension type: {value!r}")
    namespace, name = value.split(":", 1)
    if namespace != "terrain-diffusion-mc":
        raise ValueError(f"Saved overworld is not a TD dimension: {value}")
    path = ROOT / "common/src/main/resources/data/terrain-diffusion-mc/dimension_type" / f"{name}.json"
    if not path.is_file():
        raise ValueError(f"No editable source for saved dimension type: {value}")
    row = json.loads(path.read_text(encoding="utf-8"))
    return int(row["min_y"]) + int(row["height"]) - 1


def _bedrock_rule(node: object, min_y: int) -> None:
    if isinstance(node, dict):
        if node.get("random_name") == "minecraft:bedrock_floor":
            node["true_at_and_below"] = {"above_bottom": 0}
            node["false_at_and_above"] = {"above_bottom": 5}
        for value in node.values():
            _bedrock_rule(value, min_y)
    elif isinstance(node, list):
        for value in node:
            _bedrock_rule(value, min_y)


def write_depth_override(world: Path, scale: int, min_y: int, ceiling: int, *, underground: bool) -> None:
    """Override the registered experimental entries for exactly this disposable world."""
    if min_y % 16 or min_y < -2032 or min_y > -64:
        raise ValueError("min-y must be a 16-aligned value between -2032 and -64")
    height = ceiling - min_y + 1
    if height % 16:
        raise ValueError("Experimental world height must be divisible by 16")
    source = ROOT / "common/src/main/resources/data/terrain-diffusion-mc"
    pack = world / "datapacks/td-underground-depth"
    (pack / "data/terrain-diffusion-mc/dimension_type").mkdir(parents=True)
    (pack / "data/terrain-diffusion-mc/worldgen/noise_settings").mkdir(parents=True)
    type_name = f"underground_scale_{scale}" if underground else f"terrain_diffusion_scale_{scale}"
    dimension = json.loads((source / "dimension_type" / f"{type_name}.json").read_text(encoding="utf-8"))
    dimension.update({"min_y": min_y, "height": height, "logical_height": height})
    (pack / "data/terrain-diffusion-mc/dimension_type" / f"{type_name}.json").write_text(json.dumps(dimension, indent=2) + "\n", encoding="utf-8")
    # Production symmetric profiles deliberately retain the compatibility pack's
    # global underground noise span.  The native generator clamps it to the
    # selected dimension.  Historical arbitrary depths need an explicit noise
    # span so they remain comparable to their prior receipts.
    if underground and min_y != SCALE_FLOORS.get(scale):
        noise = json.loads((world / "datapacks/terrain-diffusion-terralith-surface/data/terrain-diffusion-mc/worldgen/noise_settings/underground.json").read_text(encoding="utf-8"))
        noise["noise"]["min_y"] = min_y
        noise["noise"]["height"] = height
        _bedrock_rule(noise.get("surface_rule"), min_y)
        (pack / "data/terrain-diffusion-mc/worldgen/noise_settings/underground.json").write_text(json.dumps(noise, indent=2) + "\n", encoding="utf-8")
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48,
        "description": f"TD disposable underground benchmark at {min_y}"}}, indent=2) + "\n", encoding="utf-8")


def prepare(args: argparse.Namespace) -> dict[str, object]:
    source = args.source.resolve(strict=True)
    target = args.target.resolve()
    if target.exists():
        raise ValueError(f"Target must be fresh: {target}")
    if target.parent != (ROOT / "build/structure-density").resolve():
        raise ValueError("Target must be a direct child of build/structure-density")
    command = [sys.executable, str(ROOT / "tools/structure_placement/prepare_fixture.py"),
               "--source", str(source), "--target", str(target), f"--center={args.center}",
               "--radius", str(args.radius)]
    subprocess.run(command, cwd=ROOT, check=True)
    world = target / "world"
    # Generic structure fixture preparation intentionally excludes datapacks.
    # Both variants need identical current compatibility rules and pack order.
    if (source / "world/datapacks").exists():
        # A prior disposable run may supply warm caches. Rebuild its depth pack
        # for this target instead of inheriting the prior baseline/candidate.
        shutil.copytree(source / "world/datapacks", world / "datapacks", dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("td-underground-depth"))
    shutil.copytree(ROOT / "compat/terralith/terrain-diffusion-terralith-surface",
                    world / "datapacks/terrain-diffusion-terralith-surface", dirs_exist_ok=True)
    level_fields = nbt_fields(gzip.decompress((world / "level.dat").read_bytes()))
    if ("Data", "Player") in level_fields:
        raise ValueError("Prepared fixture must not contain Data/Player")
    saved = saved_scale(world)
    scale = saved if args.scale is None else args.scale
    if args.scale is not None:
        patch_saved_scale(world / "data/terrain_diffusion_world_settings.dat", scale)
    river_cache = fresh_river_cache_for_scale(target, source_scale=saved, scale=scale)
    ceiling = ceiling_for_saved_dimension(world) if args.scale is None else ceiling_for_scale(scale)
    patch_level(world / "level.dat", scale=scale, underground=args.min_y < -64, rivers=args.rivers)
    if args.rivers and args.min_y < -64:
        (target / "underground-river-audit.txt").write_text("true\n", encoding="ascii")
    write_depth_override(world, scale, args.min_y, ceiling, underground=args.min_y < -64)
    (target / "underground-audit.txt").write_text("true\n", encoding="ascii")
    inputs = verified_inputs(target)
    manifest = {
        "source": str(source), "sourceLevelDatSha256": sha256(source / "world/level.dat"),
        "sourceInputManifestSha256": sha256(source / "input-manifest.json"),
        "fixtureInputManifestSha256": sha256(target / "input-manifest.json"),
        "target": str(target), "minY": args.min_y, "scale": scale, "ceiling": ceiling,
        "center": args.center, "radius": args.radius,
        "actualInputs": inputs,
        "riverCache": river_cache,
        "mode": "underground" if args.min_y < -64 else "baseline",
        "undergroundRivers": args.rivers and args.min_y < -64,
    }
    (target / "underground-benchmark.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def compare(args: argparse.Namespace) -> dict[str, object]:
    def receipt(path: Path) -> dict[str, object]:
        row = json.loads((path / "world/density-audit.json").read_text(encoding="utf-8"))
        if row.get("success") is not True:
            raise ValueError(f"Unsuccessful density receipt: {path}")
        return row
    baseline, candidate = receipt(args.baseline), receipt(args.candidate)
    base_manifest = json.loads((args.baseline / "underground-benchmark.json").read_text(encoding="utf-8"))
    next_manifest = json.loads((args.candidate / "underground-benchmark.json").read_text(encoding="utf-8"))
    match_keys = ("source", "sourceLevelDatSha256", "sourceInputManifestSha256",
                  "fixtureInputManifestSha256", "scale", "ceiling", "center", "radius")
    if any(base_manifest.get(key) != next_manifest.get(key) for key in match_keys):
        raise ValueError("Fixtures do not share source world, scale, ceiling, center and radius")
    base_inputs, next_inputs = verified_inputs(args.baseline), verified_inputs(args.candidate)
    verify_recorded_stable_inputs(base_manifest, base_inputs)
    verify_recorded_stable_inputs(next_manifest, next_inputs)
    if any(base_inputs[key] != next_inputs[key] for key in ("mods", "compatPack")):
        raise ValueError("Actual staged mods or compatibility pack differ")
    base_elapsed, next_elapsed = float(baseline["elapsedSeconds"]), float(candidate["elapsedSeconds"])
    base_census = float(baseline["phaseSeconds"]["chunkCensus"])
    next_census = float(candidate["phaseSeconds"]["chunkCensus"])
    base_ground = baseline.get("underground")
    next_ground = candidate.get("underground")
    if not isinstance(base_ground, dict) or not isinstance(next_ground, dict):
        raise ValueError("Both receipts must include --underground-audit evidence")
    surface_match = (base_ground.get("worldSurfaceSha256") == next_ground.get("worldSurfaceSha256")
                     and base_ground.get("oceanFloorSha256") == next_ground.get("oceanFloorSha256"))
    base_terrain, next_terrain = base_ground.get("baseTerrainSha256"), next_ground.get("baseTerrainSha256")
    base_terrain_match = None if base_terrain is None or next_terrain is None else base_terrain == next_terrain
    same_bounds = (baseline.get("centerChunk") == candidate.get("centerChunk")
                   and baseline.get("radiusChunks") == candidate.get("radiusChunks"))
    result = {"baseline": str(args.baseline), "candidate": str(args.candidate),
              "elapsedSeconds": {"baseline": base_elapsed, "candidate": next_elapsed,
                                 "regressionPercent": (next_elapsed / base_elapsed - 1.0) * 100.0},
              "chunkCensusSeconds": {"baseline": base_census, "candidate": next_census,
                                      "regressionPercent": (next_census / base_census - 1.0) * 100.0},
              "surfaceFingerprintMatch": surface_match, "baseTerrainFingerprintMatch": base_terrain_match,
              "sameBounds": same_bounds, "actualInputs": base_inputs,
              "depthOverrideDigests": {"baseline": base_inputs["depthOverride"],
                                        "candidate": next_inputs["depthOverride"]},
              "undergroundEvidence": {
                  "deepCavesObserved": bool(next_ground.get("deepCavesObserved")),
                  "variedDeepRocksObserved": bool(next_ground.get("variedDeepRocksObserved")),
              },
              "limitPercent": args.max_regression_percent}
    full = (baseline.get("chunkCensusStatus") == "minecraft:full"
            and candidate.get("chunkCensusStatus") == "minecraft:full")
    expected_chunks = (2 * int(base_manifest["radius"])) ** 2
    fixed_area = (baseline.get("candidateOwners") == candidate.get("candidateOwners") == expected_chunks
                  and baseline.get("attempted") == candidate.get("attempted") == expected_chunks
                  and base_ground.get("sampledChunks") == next_ground.get("sampledChunks") == expected_chunks
                  and base_ground.get("columns", 0) > 0 and next_ground.get("columns", 0) > 0)
    depth_range = (base_ground.get("minY") == base_manifest.get("minY")
                   and next_ground.get("minY") == next_manifest.get("minY")
                   and base_ground.get("maxY") == base_manifest.get("ceiling")
                   and next_ground.get("maxY") == next_manifest.get("ceiling"))
    scale_binding = True
    if next_manifest["minY"] == SCALE_FLOORS[int(next_manifest["scale"])]:
        saved = nbt_fields(gzip.decompress((args.candidate / "world/level.dat").read_bytes()))
        recorded_scales = [field[3] for key, field in saved.items() if key[-1:] == ("underground_scale",)]
        scale_binding = (next_ground.get("boundScale") == next_manifest["scale"]
                         and recorded_scales == [next_manifest["scale"]])
    floor = (base_ground.get("floorBedrock") == base_ground.get("columns")
             and next_ground.get("floorBedrock") == next_ground.get("columns")
             and next_ground.get("legacyShelfBedrock") == 0)
    result["receiptEvidence"] = {"fullStatus": full, "expectedChunks": expected_chunks,
                                 "fixedArea": fixed_area, "depthRange": depth_range,
                                 "bottomBedrock": floor, "persistedScale": scale_binding}
    result["withinLimit"] = (result["chunkCensusSeconds"]["regressionPercent"] <= args.max_regression_percent
                             and same_bounds and full and fixed_area and depth_range and floor and scale_binding
                             and base_terrain_match is not False)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("prepare")
    stage.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    stage.add_argument("--target", type=Path, required=True)
    stage.add_argument("--min-y", type=int, required=True)
    stage.add_argument("--scale", type=int, choices=range(1, 7))
    stage.add_argument("--rivers", action="store_true", help="enable the new-world cave river profile and require actual river evidence")
    stage.add_argument("--center", default="0,0")
    stage.add_argument("--radius", type=int, default=4)
    check = commands.add_parser("compare")
    check.add_argument("--baseline", type=Path, required=True)
    check.add_argument("--candidate", type=Path, required=True)
    check.add_argument("--max-regression-percent", type=float, default=100.0)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        if args.radius < 1 or args.radius > 16:
            parser.error("radius must be 1..16; the default four gives a 64-chunk square")
        if args.min_y not in LEGACY_FLOORS | set(SCALE_FLOORS.values()):
            parser.error("--min-y must be -64, a scale floor, or a retained legacy comparison floor")
        if args.scale is not None and args.min_y < -64 and args.min_y != SCALE_FLOORS[args.scale]:
            parser.error("--scale deep fixtures require that scale's symmetric floor")
        print(json.dumps(prepare(args), indent=2))
        return 0
    result = compare(args)
    print(json.dumps(result, indent=2))
    return 0 if result["withinLimit"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
