"""Generate opt-in ecological biomes from Minecraft 1.21.1 resources."""
import argparse
import copy
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
SPECS = {
    "temperate_forest": ("forest", "forest", .7),
    "temperate_woodland": ("forest", "forest", .7),
    "temperate_grassland": ("forest", "plains", .7),
    "boreal_forest": ("taiga", "taiga", .2),
    "boreal_woodland": ("taiga", "taiga", .2),
    "cold_grassland": ("taiga", "plains", .2),
    "dry_woodland": ("savanna", "savanna", 1.2),
    "dry_grassland": ("savanna", "savanna", 1.2),
    "tropical_forest": ("jungle", "jungle", .95),
    "tropical_woodland": ("jungle", "jungle", .95),
    "desert": ("desert", "desert", 2.),
    "snowy_boreal_forest": ("snowy_taiga", "snowy_taiga", -.5),
    "temperate_rainforest": ("forest", "forest", .7),
    "montane_forest": ("taiga", "taiga", .2),
    "steppe": ("plains", "plains", .7),
    "snowy_boreal_woodland": ("snowy_taiga", "snowy_taiga", -.5),
    "snowy_tundra": ("snowy_plains", "snowy_plains", -.5),
    "alpine_tundra": ("snowy_plains", "snowy_slopes", -.5),
    "glacial_highlands": ("snowy_plains", "frozen_peaks", -.7),
}

VEGETAL_FEATURES = [
    "minecraft:glow_lichen",
    "terrain-diffusion-mc:eco_trees",
    "terrain-diffusion-mc:eco_groundcover",
]

CANOPY_FLOOR_FEATURES = {
    "patch_grass_badlands", "patch_grass_forest", "patch_grass_jungle",
    "patch_grass_plain", "patch_grass_savanna", "patch_grass_taiga_2",
    "patch_large_fern", "patch_tall_grass", "patch_tall_grass_2",
}


def write_groundcover_resources(output):
    resources = {
        "configured_feature/eco_groundcover.json": {
            "type": "terrain-diffusion-mc:eco_groundcover",
            "config": {},
        },
        "placed_feature/eco_groundcover.json": {
            "feature": "terrain-diffusion-mc:eco_groundcover",
            "placement": [],
        },
    }
    for relative, body in resources.items():
        path = output / "data/terrain-diffusion-mc/worldgen" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")


def generate(vanilla, output, neoforge_output=None):
    with ZipFile(vanilla) as jar:
        resources = {n: json.loads(jar.read(n)) for n in jar.namelist()
                     if n.startswith("data/minecraft/") and n.endswith(".json")
                     and ("/worldgen/" in n or "/tags/worldgen/biome/" in n)}
    cache = {}

    def tree_reference(value, visiting=frozenset()):
        if isinstance(value, dict):
            return value.get("type") == "minecraft:tree" or any(tree_reference(v, visiting) for v in value.values())
        if isinstance(value, list):
            return any(tree_reference(v, visiting) for v in value)
        if not isinstance(value, str) or not value.startswith("minecraft:"):
            return False
        if value in cache:
            return cache[value]
        if value in visiting:
            return False
        result = False
        for kind in ("configured_feature", "placed_feature"):
            path = f"data/minecraft/worldgen/{kind}/{value.split(':', 1)[1]}.json"
            if path in resources:
                result |= tree_reference(resources[path], visiting | {value})
        cache[value] = result
        return result

    report = {}
    names = {}
    for name, (source, tag_source, temperature) in SPECS.items():
        biome = copy.deepcopy(resources[f"data/minecraft/worldgen/biome/{source}.json"])
        removed = []
        for index, features in enumerate(biome["features"]):
            removed += [f for f in features if tree_reference(f)]
            biome["features"][index] = [f for f in features if not tree_reference(f)]
        biome["features"][2].append("terrain-diffusion-mc:eco_surface")
        if neoforge_output is not None:
            write_native_groundcover(neoforge_output, name, biome["features"][9], resources)
        biome["features"][9] = list(VEGETAL_FEATURES)
        biome["temperature"] = temperature
        biome["downfall"] = .65
        biome["has_precipitation"] = name != "desert"
        # Shared foliage/grass tint prevents label changes from making painted borders.
        # Actual aridity is expressed by the continuous soil and vegetation fields.
        biome["effects"].update(grass_color=0x83A957, foliage_color=0x6B9847,
                                water_color=4159204, water_fog_color=329011,
                                sky_color=7907327, fog_color=12638463)
        biome["effects"].pop("grass_color_modifier", None)
        path = output / f"data/terrain-diffusion-mc/worldgen/biome/ecology/{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(biome, indent=2) + "\n", encoding="utf-8")
        report[name] = {"source": source, "removedTreePlacements": removed}
        names[f"terrain-diffusion-mc:ecology/{name}"] = "minecraft:" + tag_source

    # Inherit direct vanilla tag memberships; nested tags then inherit these automatically.
    for path, body in resources.items():
        if "/tags/worldgen/biome/" not in path:
            continue
        direct = {v if isinstance(v, str) else v.get("id") for v in body.get("values", [])}
        additions = [name for name, base in names.items() if base in direct]
        if not additions:
            continue
        target = output / path
        old = json.loads(target.read_text(encoding="utf-8")) if target.exists() else {"replace": False, "values": []}
        existing = {v if isinstance(v, str) else v.get("id") for v in old["values"]}
        old["values"].extend(value for value in additions if value not in existing)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(old, indent=2) + "\n", encoding="utf-8")
    write_groundcover_resources(output)
    if neoforge_output is not None:
        write_village_compat_pack(output, neoforge_output)
        write_native_biome_tags(neoforge_output)
        write_json(neoforge_output / "data/terrain-diffusion-mc/tags/worldgen/biome/ecology.json",
                   {"replace": False, "values": list(names)})
    return report


def write_village_compat_pack(common_output, neoforge_output):
    """Preserve TD village eligibility when later packs replace vanilla biome tags."""
    pack = neoforge_output / "compat/td_village_biomes"
    write_json(pack / "pack.mcmeta", {"pack": {
        "pack_format": 48,
        "description": "Restore Terrain Diffusion village biomes after Towns and Towers",
    }})
    for kind in ("plains", "desert", "savanna", "snowy", "taiga"):
        tag = common_output / f"data/minecraft/tags/worldgen/biome/has_structure/village_{kind}.json"
        if not tag.exists():
            continue
        biomes = [value for value in json.loads(tag.read_text(encoding="utf-8"))["values"]
                  if isinstance(value, str) and value.startswith("terrain-diffusion-mc:")]
        write_json(pack / f"data/minecraft/tags/worldgen/biome/has_structure/village_{kind}.json", {
            "replace": False,
            "values": biomes,
        })


def write_native_groundcover(output, name, features, resources):
    """Use original providers and placements when Greeneries supplies its overrides.

    Distinct placed-feature holders avoid ordering cycles with vanilla biomes:
    NeoForge appends modifiers after the biome's existing features. Configured
    features stay native registry references, including datapack overrides.
    """
    native = []
    for feature in features:
        if feature in VEGETAL_FEATURES:
            continue
        path = feature.split(":", 1)[1]
        native.append("terrain-diffusion-mc:greeneries_native/" + path)
        placed = copy.deepcopy(resources[f"data/minecraft/worldgen/placed_feature/{path}.json"])
        if path in CANOPY_FLOOR_FEATURES:
            for placement in placed["placement"]:
                if placement["type"] == "minecraft:heightmap":
                    placement["heightmap"] = "MOTION_BLOCKING_NO_LEAVES"
        write_json(output / f"data/terrain-diffusion-mc/worldgen/placed_feature/greeneries_native/{path}.json",
                   placed)
    conditions = [{"type": "neoforge:mod_loaded", "modid": "greeneries"}]
    modifier = output / "data/terrain-diffusion-mc/neoforge/biome_modifier"
    write_json(modifier / f"greeneries_native/{name}.json", {
        "neoforge:conditions": conditions,
        "type": "terrain-diffusion-mc:greeneries_native_vegetation",
        "biomes": "terrain-diffusion-mc:ecology/" + name,
        "features": native,
    })
    write_json(modifier / "greeneries_native/remove_climate_groundcover.json", {
        "neoforge:conditions": conditions,
        "type": "neoforge:remove_features",
        "biomes": "#terrain-diffusion-mc:ecology",
        "features": "terrain-diffusion-mc:eco_groundcover",
        "steps": "vegetal_decoration",
    })


def write_native_biome_tags(output):
    # Greeneries' own tags decide common/rare species; TD does not assign species.
    tags = {"is_cold/overworld": [], "is_hot/overworld": [],
            "is_temperate/overworld": [], "is_plains": []}
    for name, (source, tag_source, _) in SPECS.items():
        climate = "cold" if source in ("taiga", "snowy_taiga", "snowy_plains") else (
            "hot" if source in ("savanna", "jungle", "desert") else "temperate")
        biome = "terrain-diffusion-mc:ecology/" + name
        tags[f"is_{climate}/overworld"].append(biome)
        if tag_source == "plains":
            tags["is_plains"].append(biome)
    for tag, values in tags.items():
        write_json(output / f"data/c/tags/worldgen/biome/{tag}.json", {"replace": False, "values": values})


def write_json(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vanilla", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "common/src/main/resources")
    parser.add_argument("--neoforge-output", type=Path,
                        help="also write Greeneries compatibility resources to this resource root")
    args = parser.parse_args()
    print(json.dumps(generate(args.vanilla, args.output, args.neoforge_output), indent=2))


if __name__ == "__main__":
    main()
