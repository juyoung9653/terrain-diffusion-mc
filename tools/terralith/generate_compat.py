#!/usr/bin/env python3
"""Generate the optional Terralith surface compatibility datapack.

The pack deliberately does not copy Terralith biomes, features, its dimension
override, or its density functions.  Terralith and Lithostitched must already be
installed.  It only replaces TD's noise-settings resource with one that retains
TD's noise router, height, sea level, and base rules while applying Terralith's
surface program when (and only when) the selected biome is a Terralith biome.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import zipfile
from collections import Counter
from typing import Any, Iterable

TD_NOISE_SETTINGS = Path("common/src/main/resources/data/terrain-diffusion-mc/worldgen/noise_settings/terrain_diffusion.json")
TD_UNDERGROUND_NOISE_SETTINGS = Path("common/src/main/resources/data/terrain-diffusion-mc/worldgen/noise_settings/underground.json")
TD_UNDERGROUND_V2_NOISE_SETTINGS = Path("common/src/main/resources/data/terrain-diffusion-mc/worldgen/noise_settings/underground_v2.json")
TD_WORLD_PRESET = Path("common/src/main/resources/data/terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json")
BIOME_PREFIX = "data/terralith/worldgen/biome/"
JAR_OVERWORLD_NOISE = "data/minecraft/worldgen/noise_settings/overworld.json"
JAR_OVERWORLD_DIMENSION = "data/minecraft/dimension/overworld.json"

# Surface rules have no predicate for a noise-router field.  Keep this explicit
# deny-list for malformed/future source rules that would claim such a channel.
# minecraft:temperature is intentionally absent: SurfaceRules reads the selected
# biome's temperature, not noise_router.temperature.
TD_ZERO_CHANNEL_PREDICATES = {
    "minecraft:continents",
    "minecraft:erosion",
    "minecraft:depth",
    "minecraft:ridges",
}

VANILLA_SAND_SHORES = {"minecraft:beach", "minecraft:snowy_beach"}
VANILLA_STONY_SHORE = "minecraft:stony_shore"


def read_json_from_jar(jar: zipfile.ZipFile, name: str) -> dict[str, Any]:
    try:
        return json.loads(jar.read(name))
    except KeyError as error:
        raise ValueError(f"required JAR resource is missing: {name}") from error


def walk(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def is_bedrock_rule(rule: dict[str, Any]) -> bool:
    return (
        rule.get("type") == "minecraft:condition"
        and rule.get("then_run", {}).get("result_state", {}).get("Name") == "minecraft:bedrock"
        and rule.get("if_true", {}).get("random_name") == "minecraft:bedrock_floor"
    )


def is_deepslate_rule(rule: dict[str, Any]) -> bool:
    return (
        rule.get("type") == "minecraft:condition"
        and rule.get("if_true", {}).get("random_name") == "minecraft:deepslate"
        and rule.get("then_run", {}).get("type") in {"minecraft:block", "minecraft:sequence"}
    )


def sequence_entries(rule: dict[str, Any]) -> list[dict[str, Any]]:
    if rule.get("type") != "minecraft:sequence" or not isinstance(rule.get("sequence"), list):
        raise ValueError("expected a minecraft:sequence surface rule")
    return rule["sequence"]


def extract_terralith_core(surface_rule: dict[str, Any]) -> dict[str, Any]:
    """Remove Terralith's global base layers; TD supplies its own versions."""
    entries = sequence_entries(surface_rule)
    core = [entry for entry in entries if not is_bedrock_rule(entry) and not is_deepslate_rule(entry)]
    if len(core) == len(entries):
        raise ValueError("could not find Terralith bedrock/deepslate base rules")
    return {"type": "minecraft:sequence", "sequence": core}


def td_base_entries(surface_rule: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    entries = sequence_entries(surface_rule)
    bedrock = next((entry for entry in entries if is_bedrock_rule(entry)), None)
    deepslate = next((entry for entry in entries if is_deepslate_rule(entry)), None)
    if bedrock is None:
        raise ValueError("TD surface rule has no bedrock base rule")
    if deepslate is None:
        raise ValueError("TD surface rule has no deepslate base rule")
    return bedrock, deepslate, [entry for entry in entries if entry is not bedrock and entry is not deepslate]


def biome_rule_ids(rule: dict[str, Any]) -> set[str]:
    """Return direct biome predicate IDs from a surface-rule subtree."""
    return {
        biome_id
        for node in walk(rule)
        if node.get("type") == "minecraft:biome"
        for biome_id in node.get("biome_is", [])
        if isinstance(biome_id, str)
    }


def validate_vanilla_shore_rules(fallback: list[dict[str, Any]]) -> None:
    """Require TD's shore material rules to stay outside the Terralith-only gate."""
    matched: dict[tuple[int, str], set[str]] = {}
    for entry in fallback:
        condition = entry.get("if_true", {})
        if entry.get("type") != "minecraft:condition" or condition.get("type") != "minecraft:stone_depth":
            continue
        offset = condition.get("offset")
        then_run = entry.get("then_run", {})
        if then_run.get("type") != "minecraft:sequence":
            continue
        for branch in then_run.get("sequence", []):
            state = branch.get("then_run", {}).get("result_state", {}).get("Name")
            ids = biome_rule_ids(branch)
            if offset in {0, 4} and isinstance(state, str):
                matched.setdefault((offset, state), set()).update(ids)

    expected = {
        (0, "minecraft:sand"): VANILLA_SAND_SHORES,
        (4, "minecraft:sandstone"): VANILLA_SAND_SHORES,
        (0, "minecraft:stone"): {VANILLA_STONY_SHORE},
        (4, "minecraft:stone"): {VANILLA_STONY_SHORE},
    }
    missing = [
        f"offset {offset} {block}: {', '.join(sorted(ids - matched.get((offset, block), set())))}"
        for (offset, block), ids in expected.items()
        if not ids <= matched.get((offset, block), set())
    ]
    if missing:
        raise ValueError("TD surface rule lacks vanilla shore material handling outside Terralith gate: " + "; ".join(missing))


def collect_surface_dependencies(surface_rule: dict[str, Any], jar_names: set[str]) -> dict[str, Any]:
    biome_ids: set[str] = set()
    noise_ids: set[str] = set()
    predicate_types: Counter[str] = Counter()
    absolute_anchors: list[int] = []
    for node in walk(surface_rule):
        node_type = node.get("type")
        if isinstance(node_type, str):
            predicate_types[node_type] += 1
        if node.get("type") == "minecraft:biome":
            biome_ids.update(value for value in node.get("biome_is", []) if isinstance(value, str))
        noise = node.get("noise")
        if isinstance(noise, str):
            noise_ids.add(noise)
        for value in node.values():
            if isinstance(value, dict) and isinstance(value.get("absolute"), int):
                absolute_anchors.append(value["absolute"])

    # Terralith uses this non-resource marker in its source surface program to
    # stop a generic strip rule.  It is not a registered biome and must not be
    # mistaken for a missing dependency.
    source_markers = sorted(value for value in biome_ids if value.startswith("terralith:comment_"))
    terralith_biomes = sorted(value for value in biome_ids if value.startswith("terralith:") and value not in source_markers)
    terralith_noises = sorted(value for value in noise_ids if value.startswith("terralith:"))
    missing_noises = [
        value for value in terralith_noises
        if f"data/terralith/worldgen/noise/{value.split(':', 1)[1]}.json" not in jar_names
    ]
    if missing_noises:
        raise ValueError("surface rule references missing Terralith noises: " + ", ".join(missing_noises))
    zero_channel = sorted(set(predicate_types) & TD_ZERO_CHANNEL_PREDICATES)
    if zero_channel:
        raise ValueError("surface rule requires TD-zero router channels: " + ", ".join(zero_channel))
    if "minecraft:above_preliminary_surface" not in predicate_types:
        raise ValueError("surface rule lacks above_preliminary_surface and cannot align with TD terrain")
    return {
        "biome_ids": terralith_biomes,
        "source_markers": source_markers,
        "noise_ids": terralith_noises,
        "predicate_types": dict(sorted(predicate_types.items())),
        "td_zero_channel_predicates": zero_channel,
        "biome_climate_predicates": sorted(set(predicate_types) & {"minecraft:temperature"}),
        "absolute_anchor_range": [min(absolute_anchors), max(absolute_anchors)] if absolute_anchors else None,
    }


def biome_inventory(jar: zipfile.ZipFile, jar_names: set[str]) -> list[dict[str, Any]]:
    paths = sorted(name for name in jar_names if name.startswith(BIOME_PREFIX) and name.endswith(".json"))
    result = []
    for name in paths:
        biome = read_json_from_jar(jar, name)
        biome_id = "terralith:" + name[len(BIOME_PREFIX):-5]
        cave_category = "surface"
        if "/cave/" in name:
            cave_category = "cave"
            if "/deep_" in name or "/deep_cave" in name:
                cave_category = "deep_cave"
        features = biome.get("features", [])
        result.append({
            "id": biome_id,
            "climate": {
                "temperature": biome.get("temperature"),
                "downfall": biome.get("downfall"),
                "has_precipitation": biome.get("has_precipitation"),
            },
            "features": {
                "generation_steps": len(features),
                "placed_feature_count": sum(len(step) for step in features if isinstance(step, list)),
                "carvers": biome.get("carvers", {}),
            },
            "cave_category": cave_category,
        })
    return result


def validate_biome_reference_closure(jar: zipfile.ZipFile, jar_names: set[str]) -> dict[str, int]:
    """Validate direct biome feature/carver/tag references without copying them."""
    biome_paths = sorted(name for name in jar_names if name.startswith(BIOME_PREFIX) and name.endswith(".json"))
    terralith_features: set[str] = set()
    terralith_tags: set[str] = set()
    carvers: set[str] = set()
    for name in biome_paths:
        biome = read_json_from_jar(jar, name)
        for step in biome.get("features", []):
            for feature in step if isinstance(step, list) else []:
                if isinstance(feature, str):
                    if feature.startswith("terralith:"):
                        terralith_features.add(feature)
                    elif feature.startswith("#terralith:"):
                        terralith_tags.add(feature[1:])
        for values in biome.get("carvers", {}).values():
            carvers.update(value for value in values if isinstance(value, str))
    missing_features = [
        value for value in sorted(terralith_features)
        if f"data/terralith/worldgen/placed_feature/{value.split(':', 1)[1]}.json" not in jar_names
    ]
    missing_tags = [
        value for value in sorted(terralith_tags)
        if f"data/terralith/tags/worldgen/placed_feature/{value.split(':', 1)[1]}.json" not in jar_names
    ]
    # Unqualified carvers use minecraft's namespace.  The only non-vanilla one
    # in this version is cave_extra_underground, supplied by Terralith's JAR.
    source_carvers = {
        value for value in carvers
        if f"data/minecraft/worldgen/configured_carver/{value.split(':')[-1]}.json" in jar_names
    }
    if missing_features or missing_tags:
        missing = missing_features + ["#" + value for value in missing_tags]
        raise ValueError("biome references missing Terralith resources: " + ", ".join(missing))
    return {
        "terralith_placed_features": len(terralith_features),
        "terralith_placed_feature_tags": len(terralith_tags),
        "configured_carvers": len(carvers),
        "source_jar_minecraft_configured_carvers": len(source_carvers),
    }


def build_pack(jar_path: Path, repo_root: Path, output: Path) -> dict[str, Any]:
    td_noise_path = repo_root / TD_NOISE_SETTINGS
    td_underground_noise_path = repo_root / TD_UNDERGROUND_NOISE_SETTINGS
    td_underground_v2_noise_path = repo_root / TD_UNDERGROUND_V2_NOISE_SETTINGS
    td_preset_path = repo_root / TD_WORLD_PRESET
    if (not td_noise_path.is_file() or not td_underground_noise_path.is_file()
            or not td_underground_v2_noise_path.is_file() or not td_preset_path.is_file()):
        raise ValueError("repository TD noise settings, both underground noise settings, or world preset is missing")
    td_noise = json.loads(td_noise_path.read_text(encoding="utf-8"))
    td_underground_noise = json.loads(td_underground_noise_path.read_text(encoding="utf-8"))
    td_underground_v2_noise = json.loads(td_underground_v2_noise_path.read_text(encoding="utf-8"))
    # 1.21.1 reads this density field when evaluating above_preliminary_surface.
    # The newer preliminary_surface_level key is silently ignored by its codec.
    for source_noise, terrain_profile in ((td_noise, 1), (td_underground_noise, 1),
                                          (td_underground_v2_noise, 2)):
        router = source_noise.get("noise_router", {})
        density = {"type": "terrain-diffusion-mc:terrain_diffusion"}
        if terrain_profile == 2:
            density["terrain_profile"] = 2
        if (router.get("initial_density_without_jaggedness") != density
                or router.get("final_density") != density
                or "preliminary_surface_level" in router):
            raise ValueError("Minecraft 1.21.1 requires TD initial_density_without_jaggedness for Terralith surface rules")
    td_preset = json.loads(td_preset_path.read_text(encoding="utf-8"))
    if not jar_path.is_file():
        raise ValueError(f"JAR does not exist: {jar_path}")

    with zipfile.ZipFile(jar_path) as jar:
        names = set(jar.namelist())
        terralith_noise = read_json_from_jar(jar, JAR_OVERWORLD_NOISE)
        dimension_override = read_json_from_jar(jar, JAR_OVERWORLD_DIMENSION)
        inventory = biome_inventory(jar, names)
        if len(inventory) != 95:
            raise ValueError(f"expected 95 Terralith biome definitions, found {len(inventory)}")
        dependencies = collect_surface_dependencies(terralith_noise["surface_rule"], names)
        biome_closure = validate_biome_reference_closure(jar, names)

    inventory_ids = [item["id"] for item in inventory]
    referenced = set(dependencies["biome_ids"])
    unknown_references = sorted(referenced - set(inventory_ids))
    if unknown_references:
        raise ValueError("surface rule references unregistered Terralith biomes: " + ", ".join(unknown_references))

    core = extract_terralith_core(terralith_noise["surface_rule"])
    legacy_bedrock, legacy_deepslate, legacy_fallback = td_base_entries(td_noise["surface_rule"])
    td_bedrock, td_deepslate, td_fallback = td_base_entries(td_underground_noise["surface_rule"])
    v2_bedrock, v2_deepslate, v2_fallback = td_base_entries(td_underground_v2_noise["surface_rule"])
    validate_vanilla_shore_rules(legacy_fallback)
    validate_vanilla_shore_rules(td_fallback)
    validate_vanilla_shore_rules(v2_fallback)
    # The gate prevents the source rule's generic dirt/gravel fallbacks from
    # changing TD-selected biomes.  Bedrock remains first.  Terralith's cave
    # branch must run before TD's deepslate rule, just as it does in the source.
    gate = {
        "type": "minecraft:condition",
        "if_true": {"type": "minecraft:biome", "biome_is": inventory_ids},
        "then_run": core,
    }
    generated_legacy_noise = copy.deepcopy(td_noise)
    generated_legacy_noise["surface_rule"] = {
        "type": "minecraft:sequence",
        "sequence": [copy.deepcopy(legacy_bedrock), copy.deepcopy(gate), copy.deepcopy(legacy_deepslate)]
                    + copy.deepcopy(legacy_fallback),
    }
    generated_underground_noise = copy.deepcopy(td_underground_noise)
    generated_underground_noise["surface_rule"] = {
        "type": "minecraft:sequence",
        "sequence": [copy.deepcopy(td_bedrock), gate, copy.deepcopy(td_deepslate)] + copy.deepcopy(td_fallback),
    }
    generated_underground_v2_noise = copy.deepcopy(td_underground_v2_noise)
    generated_underground_v2_noise["surface_rule"] = {
        "type": "minecraft:sequence",
        "sequence": [copy.deepcopy(v2_bedrock), copy.deepcopy(gate), copy.deepcopy(v2_deepslate)]
                    + copy.deepcopy(v2_fallback),
    }
    generated_preset = copy.deepcopy(td_preset)
    generated_biome_source = generated_preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]
    generated_biome_source["terralith"] = True
    generated_biome_source["natural_biomes"] = True

    noise_output = output / "data/terrain-diffusion-mc/worldgen/noise_settings/terrain_diffusion.json"
    underground_noise_output = output / "data/terrain-diffusion-mc/worldgen/noise_settings/underground.json"
    underground_v2_noise_output = output / "data/terrain-diffusion-mc/worldgen/noise_settings/underground_v2.json"
    preset_output = output / "data/terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json"
    inventory_output = output / "terralith-biome-inventory.json"
    manifest_output = output / "compat-manifest.json"
    pack_meta_output = output / "pack.mcmeta"
    generated_paths = {noise_output, underground_noise_output, underground_v2_noise_output,
                       preset_output, inventory_output, manifest_output, pack_meta_output}
    if output.exists():
        unexpected = [path for path in output.rglob("*") if path.is_file() and path not in generated_paths]
        if unexpected:
            raise ValueError("refusing nonempty unrecognized output directory: " + str(unexpected[0]))
    for path in generated_paths:
        path.parent.mkdir(parents=True, exist_ok=True)

    source_hash = hashlib.sha256(jar_path.read_bytes()).hexdigest()
    manifest = {
        "format": 1,
        "source": {"jar": jar_path.name, "sha256": source_hash},
        "required_mods": ["terralith", "lithostitched"],
        "generated_resources": [
            str(noise_output.relative_to(output)).replace("\\", "/"),
            str(underground_noise_output.relative_to(output)).replace("\\", "/"),
            str(underground_v2_noise_output.relative_to(output)).replace("\\", "/"),
            str(preset_output.relative_to(output)).replace("\\", "/"),
        ],
        "not_copied": ["Terralith biome definitions", "Terralith features", "minecraft:overworld dimension override", "Terralith density functions"],
        "counts": {
            "terralith_biomes": len(inventory),
            "surface_referenced_terralith_biomes": len(dependencies["biome_ids"]),
            "surface_referenced_terralith_noises": len(dependencies["noise_ids"]),
            "surface_predicate_types": len(dependencies["predicate_types"]),
            **biome_closure,
            "generated_datapack_resources": 4,
        },
        "validation": {
            "td_legacy_router_preserved": generated_legacy_noise["noise_router"] == td_noise["noise_router"],
            "td_legacy_noise_shape_preserved": generated_legacy_noise["noise"] == td_noise["noise"],
            "td_legacy_sea_level_preserved": generated_legacy_noise["sea_level"] == td_noise["sea_level"],
            "td_legacy_bedrock_rule_preserved": generated_legacy_noise["surface_rule"]["sequence"][0] == legacy_bedrock,
            "td_legacy_deepslate_rule_preserved": generated_legacy_noise["surface_rule"]["sequence"][2] == legacy_deepslate,
            "td_router_preserved": generated_underground_noise["noise_router"] == td_underground_noise["noise_router"],
            "minecraft_1_21_1_preliminary_density_validated": True,
            "td_noise_shape_preserved": generated_underground_noise["noise"] == td_underground_noise["noise"],
            "td_sea_level_preserved": generated_underground_noise["sea_level"] == td_underground_noise["sea_level"],
            "td_bedrock_rule_preserved": generated_underground_noise["surface_rule"]["sequence"][0] == td_bedrock,
            "td_deepslate_rule_preserved": generated_underground_noise["surface_rule"]["sequence"][2] == td_deepslate,
            "td_v2_router_preserved": generated_underground_v2_noise["noise_router"] == td_underground_v2_noise["noise_router"],
            "td_v2_noise_shape_preserved": generated_underground_v2_noise["noise"] == td_underground_v2_noise["noise"],
            "td_v2_sea_level_preserved": generated_underground_v2_noise["sea_level"] == td_underground_v2_noise["sea_level"],
            "td_v2_bedrock_rule_preserved": generated_underground_v2_noise["surface_rule"]["sequence"][0] == v2_bedrock,
            "td_v2_deepslate_rule_preserved": generated_underground_v2_noise["surface_rule"]["sequence"][2] == v2_deepslate,
            "vanilla_shore_rules_outside_terralith_gate": True,
            "vanilla_sand_shores": sorted(VANILLA_SAND_SHORES),
            "vanilla_stony_shore": VANILLA_STONY_SHORE,
            "td_zero_channel_predicates": dependencies["td_zero_channel_predicates"],
            "biome_climate_predicates": dependencies["biome_climate_predicates"],
            "uses_above_preliminary_surface": "minecraft:above_preliminary_surface" in dependencies["predicate_types"],
            "high_altitude_note": "The source has absolute anchors from 0 through 319 but no y_above/y_below upper-bound predicate; above_preliminary_surface follows TD's preliminary surface.",
            "absolute_anchor_range": dependencies["absolute_anchor_range"],
            "terralith_dimension_override_copied": False,
            "world_preset_terralith_flag": True,
            "world_preset_natural_biomes_flag": True,
            "world_preset_underground": generated_biome_source.get("underground", False),
            "world_preset_underground_rivers": generated_biome_source.get("underground_rivers", False),
            "world_preset_underground_river_version": generated_biome_source.get("underground_river_version", 1),
            "world_preset_underground_profile_version": generated_biome_source.get("underground_profile_version", 1),
        },
        "surface_dependencies": dependencies,
        "biome_reference_closure": biome_closure,
        "limitations": [
            "This pack does not apply Terralith density functions; skylands, spires, and crater shapes remain deferred.",
            "Biome climate selection is supplied by Terrain Diffusion's Terralith-enabled biome source, not by Terralith's minecraft:overworld dimension override.",
            "Biome-source unions can expose placed-feature order cycles between Terralith and TD biomes; this structural resource check does not validate Minecraft's registry ordering.",
            "Generation was structurally validated only; no visual world validation is claimed.",
        ],
        "terralith_dimension_override": {
            "present_in_source_jar": bool(dimension_override),
            "requires_lithostitched_condition": "lithostitched" in json.dumps(dimension_override, sort_keys=True),
            "copied": False,
        },
    }
    pack_meta = {"pack": {"pack_format": 48, "description": "Terrain Diffusion optional Terralith surface compatibility (requires Terralith + Lithostitched)"}}
    for path, data in ((noise_output, generated_legacy_noise),
                       (underground_noise_output, generated_underground_noise),
                       (underground_v2_noise_output, generated_underground_v2_noise),
                       (preset_output, generated_preset), (inventory_output, {"biomes": inventory}),
                       (manifest_output, manifest), (pack_meta_output, pack_meta)):
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(path)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path, help="Terralith 1.21.1 JAR")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / "compat/terralith/terrain-diffusion-terralith-surface")
    args = parser.parse_args()
    try:
        manifest = build_pack(args.jar, args.repo_root, args.output)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"generation failed: {error}", file=sys.stderr)
        return 2
    print(f"generated {args.output} ({manifest['counts']['terralith_biomes']} biomes, {manifest['counts']['surface_referenced_terralith_noises']} validated surface noises)")
    print("requires Terralith + Lithostitched; TD router/height preserved; visual validation not performed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
