#!/usr/bin/env python3
"""Validate the Terralith Terrain Diffusion biome set without starting Minecraft.

The pack/resource load order is vanilla < Terralith < common resources < optional
compatibility pack.  The feature graph matches Minecraft's FeatureSorter: each
adjacent pair in a biome's feature list creates a precedence edge, independently
for each decoration step.
"""
from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TERRALITH = Path(os.environ["APPDATA"]) / "PrismLauncher" / "instances" / "1.21.1 neoforge rpg" / "minecraft" / "mods" / "Terralith_1.21.1_v2.6.2_Neoforge.jar"
DEFAULT_VANILLA = Path(os.environ["USERPROFILE"]) / ".gradle" / "caches" / "fabric-loom" / "1.21.1" / "minecraft-client.jar"
BASE_BIOMES = (
    "minecraft:plains minecraft:snowy_plains minecraft:desert minecraft:swamp "
    "minecraft:forest minecraft:taiga minecraft:snowy_taiga minecraft:savanna "
    "minecraft:windswept_hills minecraft:jungle minecraft:badlands minecraft:meadow "
    "minecraft:grove minecraft:snowy_slopes minecraft:frozen_peaks minecraft:stony_peaks "
    "minecraft:warm_ocean minecraft:ocean minecraft:cold_ocean minecraft:frozen_ocean "
    "terrain-diffusion-mc:forest_sparse terrain-diffusion-mc:taiga_sparse "
    "terrain-diffusion-mc:snowy_taiga_sparse"
).split()
VANILLA_BUILTIN_CARVERS = {"minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"}


def resource_id(path: str, prefix: str) -> str:
    # data/<namespace>/<prefix>/<path>.json
    bits = path.split("/")
    return f"{bits[1]}:{path[len('data/' + bits[1] + '/' + prefix) + 1:-5]}"


def read_layer(source: Path) -> dict[str, dict]:
    if source.is_dir():
        return {p.relative_to(source).as_posix(): json.loads(p.read_text(encoding="utf-8"))
                for p in source.rglob("data/**/*.json")}
    with ZipFile(source) as archive:
        return {name: json.loads(archive.read(name)) for name in archive.namelist()
                if name.startswith("data/") and name.endswith(".json")}


def tag_id_from_path(path: str) -> str:
    bits = path.split("/")
    marker = "/tags/worldgen/placed_feature/"
    return f"{bits[1]}:{path.split(marker, 1)[1][:-5]}"


def tag_value(entry: object) -> tuple[str, bool]:
    if isinstance(entry, str):
        return entry, True
    if isinstance(entry, dict) and isinstance(entry.get("id"), str):
        return entry["id"], entry.get("required", True)
    raise ValueError(f"invalid placed-feature tag entry: {entry!r}")


def resolve_tag(tag: str, tags: dict[str, list[object]], stack: tuple[str, ...] = ()) -> list[str]:
    if tag in stack:
        raise ValueError("tag cycle: " + " -> ".join((*stack, tag)))
    if tag not in tags:
        raise ValueError("missing required placed-feature tag: #" + tag)
    values: list[str] = []
    for entry in tags[tag]:
        value, required = tag_value(entry)
        if value.startswith("#"):
            try:
                values.extend(resolve_tag(value[1:], tags, (*stack, tag)))
            except ValueError:
                if required:
                    raise
        else:
            values.append(value)
    return values


def find_cycles(edges: dict[tuple[int, str], set[tuple[int, str]]]) -> list[list[tuple[int, str]]]:
    state: dict[tuple[int, str], int] = {}
    trail: list[tuple[int, str]] = []
    cycles: list[list[tuple[int, str]]] = []

    def visit(node: tuple[int, str]) -> None:
        state[node] = 1
        trail.append(node)
        for target in edges[node]:
            if state.get(target) == 1:
                cycles.append(trail[trail.index(target):] + [target])
            elif state.get(target, 0) == 0:
                visit(target)
        trail.pop()
        state[node] = 2

    for node in sorted(edges):
        if state.get(node, 0) == 0:
            visit(node)
    return cycles


def ecological_biomes(biomes: dict[str, dict]) -> list[str]:
    """Discover generated ecology biomes so feature validation cannot omit a new label."""
    return sorted(biome_id for biome_id in biomes if biome_id.startswith("terrain-diffusion-mc:ecology/"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--terralith", type=Path, default=DEFAULT_TERRALITH)
    parser.add_argument("--vanilla", type=Path, default=DEFAULT_VANILLA)
    parser.add_argument("--common-before-terralith", action="store_true",
                        help="check the inverse priority for overlapping vanilla biome JSON")
    args = parser.parse_args()
    compat = ROOT / "compat" / "terralith" / "terrain-diffusion-terralith-surface"
    common = ROOT / "common" / "src" / "main" / "resources"
    layers = [args.vanilla, common, args.terralith, compat] if args.common_before_terralith else [args.vanilla, args.terralith, common, compat]
    missing_layers = [str(p) for p in layers if not p.exists()]
    if missing_layers:
        raise SystemExit("missing resource layers: " + ", ".join(missing_layers))

    resources: dict[str, dict] = {}
    tags: dict[str, list[object]] = {}
    for layer in layers:
        for path, data in read_layer(layer).items():
            if "/tags/worldgen/placed_feature/" in path:
                key = tag_id_from_path(path)
                values = data.get("values", [])
                if not isinstance(values, list):
                    raise ValueError(f"invalid values list in placed-feature tag {key}")
                tags[key] = values if data.get("replace", False) else tags.get(key, []) + values
            else:
                resources[path] = data

    biomes = {resource_id(path, "worldgen/biome"): data for path, data in resources.items()
              if "/worldgen/biome/" in path}
    placed = {resource_id(path, "worldgen/placed_feature"): data for path, data in resources.items()
              if "/worldgen/placed_feature/" in path}
    classifier = ROOT / "common" / "src" / "main" / "resources" / "terralith-biomes.tsv"
    classifier_rows = []
    for line in classifier.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        columns = line.split("\t") if "\t" in line else line.split("|")
        if len(columns) != 10:
            raise ValueError("expected 10 tab- or pipe-delimited columns: " + line)
        classifier_rows.append(columns)
    # Cave entries retain their "cave/" resource path in the checked-in table,
    # matching the id passed by TerrainDiffusionBiomeSource to the registry.
    terralith = [f"terralith:{row[0]}" for row in classifier_rows]
    biome_source_ids = [f"terralith:{row[0]}" for row in classifier_rows]
    ecology = ecological_biomes(biomes)
    requested = BASE_BIOMES + terralith + ecology
    unresolved_biomes = sorted(set(requested) - set(biomes))
    unresolved_biome_source = sorted(set(biome_source_ids) - set(biomes))

    unresolved_features: set[str] = set()
    unresolved_carvers: set[str] = set()
    edges: dict[tuple[int, str], set[tuple[int, str]]] = defaultdict(set)
    for biome_id in requested:
        biome = biomes.get(biome_id, {})
        for carver_list in biome.get("carvers", {}).values():
            for carver in carver_list:
                # ResourceLocation's data codec applies the Minecraft default
                # namespace to an unqualified registry key.
                carver_id = carver if ":" in carver else f"minecraft:{carver}"
                path = "data/" + carver_id.replace(":", "/worldgen/configured_carver/") + ".json"
                if carver_id not in VANILLA_BUILTIN_CARVERS and path not in resources:
                    unresolved_carvers.add(carver_id)
        for step, entries in enumerate(biome.get("features", [])):
            expanded: list[str] = []
            for entry in entries:
                if entry.startswith("#"):
                    try:
                        expanded.extend(resolve_tag(entry[1:], tags))
                    except ValueError as error:
                        unresolved_features.add(str(error))
                else:
                    expanded.append(entry)
            for feature in expanded:
                if feature not in placed:
                    unresolved_features.add(feature)
                edges.setdefault((step, feature), set())
            for left, right in zip(expanded, expanded[1:]):
                if left != right:
                    edges[(step, left)].add((step, right))

    cycles = find_cycles(edges)
    priority = "vanilla < common < terralith < compat" if args.common_before_terralith else "vanilla < terralith < common < compat"
    print(f"layers: {priority} (vanilla={args.vanilla.name}, terralith={args.terralith.name})")
    print(f"biomes: requested={len(requested)} base={len(BASE_BIOMES)} terralith={len(terralith)} ecology={len(ecology)} resolved={len(requested)-len(unresolved_biomes)}")
    print("unresolved biome refs: " + (", ".join(unresolved_biomes) if unresolved_biomes else "none"))
    print("BiomeSource codec refs (current code): " + (", ".join(unresolved_biome_source) if unresolved_biome_source else "none"))
    print("unresolved placed-feature refs: " + (", ".join(sorted(unresolved_features)) if unresolved_features else "none"))
    print("unresolved configured-carver refs: " + (", ".join(sorted(unresolved_carvers)) if unresolved_carvers else "none"))
    print(f"feature sorter graph: nodes={len(edges)} edges={sum(map(len, edges.values()))} cycles={len(cycles)}")
    for cycle in cycles[:8]:
        print("cycle: " + " -> ".join(f"step{step}:{feature}" for step, feature in cycle))
    return 1 if unresolved_biomes or unresolved_features or unresolved_carvers or cycles else 0


if __name__ == "__main__":
    raise SystemExit(main())
