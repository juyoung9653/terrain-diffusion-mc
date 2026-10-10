"""Focused contracts for native Greeneries generation in TD ecology biomes."""

import json
import importlib.util
import os
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CANOPY_SAFE_HEIGHTMAP = "MOTION_BLOCKING_NO_LEAVES"
RESTORED_GROUNDCOVER = {
    "patch_grass_badlands", "patch_grass_forest", "patch_grass_jungle",
    "patch_grass_plain", "patch_grass_savanna", "patch_grass_taiga_2",
    "patch_large_fern", "patch_tall_grass", "patch_tall_grass_2",
}


def installed_greeneries_jar():
    configured = os.environ.get("GREENERIES_JAR")
    if configured:
        return Path(configured)
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    mods = Path(appdata) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods"
    matches = sorted(mods.glob("greeneries-neoforge-1.21.1-*.jar"))
    return matches[-1] if matches else None


class GreeneriesCompatibilityTest(unittest.TestCase):
    def test_optional_native_overlays_are_conditioned_without_installed_jar(self):
        paths = list((ROOT / "neoforge/src/main/resources/data/greeneries/worldgen/placed_feature").glob("*.json"))
        self.assertEqual(7, len(paths))
        for path in paths:
            data = json.loads(path.read_text())
            self.assertEqual([{"type": "neoforge:mod_loaded", "modid": "greeneries"}],
                             data.get("neoforge:conditions"), path.name)

    def test_native_species_selection_uses_standard_biome_tags(self):
        resources = ROOT / "neoforge/src/main/resources/data"
        self.assertFalse(list((resources / "greeneries/tags/worldgen/biome").glob("*.json")))
        actual_biomes = {
            "terrain-diffusion-mc:ecology/" + path.stem
            for path in (ROOT / "common/src/main/resources/data/terrain-diffusion-mc/worldgen/biome/ecology").glob("*.json")
        }
        climate = []
        for name in ("hot", "cold", "temperate"):
            data = json.loads((resources / f"c/tags/worldgen/biome/is_{name}/overworld.json").read_text())
            self.assertFalse(data["replace"])
            climate.extend(data["values"])
        self.assertEqual(set(climate), actual_biomes)
        self.assertEqual(len(climate), 19)
        # Native selectors own common/rare membership; do not inject native patches again.
        for path in (resources / "terrain-diffusion-mc/neoforge/biome_modifier").rglob("*.json"):
            data = json.loads(path.read_text())
            if data["type"] in ("neoforge:add_features", "terrain-diffusion-mc:greeneries_native_vegetation"):
                features = data["features"]
                if isinstance(features, str):
                    features = [features]
                self.assertFalse(any(f.startswith("greeneries:") for f in features), path)

    def test_native_route_preserves_installed_vanilla_placement_definitions(self):
        vanilla = os.environ.get("VANILLA_JAR")
        vanilla = Path(vanilla) if vanilla else Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"
        if not vanilla.is_file():
            self.skipTest("set VANILLA_JAR to validate original placement definitions")
        spec = importlib.util.spec_from_file_location("generate_biomes", Path(__file__).with_name("generate_biomes.py"))
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        resources = ROOT / "neoforge/src/main/resources/data/terrain-diffusion-mc"
        with zipfile.ZipFile(vanilla) as jar:
            for name, (source, _, _) in generator.SPECS.items():
                original = json.loads(jar.read(f"data/minecraft/worldgen/biome/{source}.json"))["features"][9]
                expected = [f for f in original if f != "minecraft:glow_lichen" and not f.startswith("minecraft:trees_")]
                modifier = json.loads((resources / f"neoforge/biome_modifier/greeneries_native/{name}.json").read_text())
                actual = modifier["features"]
                self.assertEqual([f.replace("minecraft:", "terrain-diffusion-mc:greeneries_native/") for f in expected], actual)
                self.assertTrue(any("flower" in f for f in actual), name)
                for feature in expected:
                    path = feature.split(":", 1)[1]
                    clone = json.loads((resources / f"worldgen/placed_feature/greeneries_native/{path}.json").read_text())
                    original_placed = json.loads(jar.read(f"data/minecraft/worldgen/placed_feature/{path}.json"))
                    original_heightmaps = [
                        placement["heightmap"] for placement in original_placed["placement"]
                        if placement["type"] == "minecraft:heightmap"
                    ]
                    expected_heightmaps = list(original_heightmaps)
                    actual_heightmaps = []
                    for placement in clone["placement"]:
                        if placement["type"] == "minecraft:heightmap":
                            actual_heightmaps.append(placement["heightmap"])
                            placement["heightmap"] = original_heightmaps.pop(0)
                    self.assertFalse(original_heightmaps, path)
                    if path in RESTORED_GROUNDCOVER:
                        self.assertEqual([CANOPY_SAFE_HEIGHTMAP], actual_heightmaps, path)
                    else:
                        self.assertEqual(expected_heightmaps, actual_heightmaps, path)
                    self.assertEqual(original_placed, clone)

    def test_greeneries_native_groundcover_uses_canopy_safe_heightmap_only(self):
        jar_path = installed_greeneries_jar()
        if jar_path is None:
            self.skipTest("set GREENERIES_JAR to validate an installed Greeneries jar")
        overlays = ROOT / "neoforge/src/main/resources/data/greeneries/worldgen/placed_feature"
        with zipfile.ZipFile(jar_path) as jar:
            expected = {
                Path(name).name for name in jar.namelist()
                if name.startswith("data/greeneries/worldgen/placed_feature/patch_")
                and name.endswith(".json") and not name.endswith("patch_reeds.json")
            }
            self.assertEqual(expected, {path.name for path in overlays.glob("*.json")})
            for name in expected:
                native = json.loads(jar.read("data/greeneries/worldgen/placed_feature/" + name))
                overlay = json.loads((overlays / name).read_text())
                self.assertEqual([{"type": "neoforge:mod_loaded", "modid": "greeneries"}],
                                 overlay.pop("neoforge:conditions", None), name)
                heightmaps = []
                for placement in overlay["placement"]:
                    if placement["type"] == "minecraft:heightmap":
                        heightmaps.append(placement["heightmap"])
                        placement["heightmap"] = "WORLD_SURFACE_WG"
                self.assertEqual([CANOPY_SAFE_HEIGHTMAP], heightmaps, name)
                self.assertEqual(native, overlay, name)

    def test_soil_categories_and_optional_ids_match_installed_mods(self):
        directory = ROOT / "common/src/main/resources/data/terrain-diffusion-mc/tags/block"
        groups = {}
        for name in ("grass", "ordinary", "barren"):
            data = json.loads((directory / f"greeneries_{name}_soils.json").read_text())
            self.assertFalse(data["replace"])
            groups[name] = {entry if isinstance(entry, str) else entry["id"] for entry in data["values"]}
            for entry in data["values"]:
                if not isinstance(entry, str):
                    self.assertFalse(entry["required"])
                else:
                    self.assertTrue(entry.startswith("minecraft:"))
        self.assertIn("minecraft:grass_block", groups["grass"])
        self.assertIn("minecraft:dirt", groups["ordinary"])
        self.assertIn("minecraft:coarse_dirt", groups["barren"])
        for group, path in (("grass", "grass_slab"), ("ordinary", "dirt_slab"), ("barren", "coarse_slab")):
            self.assertIn("terrain_slabs:" + path, groups[group])
        for name in ("grassy_sandy_dirt", "grassy_silt", "grassy_earthen_clay", "grassy_permafrost", "rooted_grass_block"):
            self.assertIn("immersive_weathering:" + name, groups["grass"])
        for name in ("sandy_dirt", "permafrost"):
            self.assertIn("immersive_weathering:" + name, groups["barren"])
        self.assertTrue(groups["grass"].isdisjoint(groups["ordinary"] | groups["barren"]))
        self.assertTrue(groups["ordinary"].isdisjoint(groups["barren"]))
        all_soils = set.union(*groups.values())
        self.assertTrue(all_soils.isdisjoint({"minecraft:sand", "minecraft:red_sand",
                                            "terrain_slabs:sand_slab", "terrain_slabs:red_sand_slab"}))
        for root in ("common", "neoforge"):
            self.assertFalse((ROOT / root / "src/main/resources/data/minecraft/tags/block/dirt.json").exists())
        jar_path = installed_greeneries_jar()
        if jar_path is not None:
            for namespace, pattern in (("terrain_slabs", "terrain_slabs-*.jar"),
                                       ("immersive_weathering", "Immersive-weathering-*.jar")):
                matches = list(jar_path.parent.glob(pattern))
                if not matches:
                    continue
                with zipfile.ZipFile(matches[-1]) as jar:
                    for soil in all_soils:
                        if soil.startswith(namespace + ":"):
                            self.assertIn(f"assets/{namespace}/blockstates/{soil.split(':')[1]}.json", jar.namelist())

    def test_installed_greeneries_has_affected_grass_and_separate_wet_reeds(self):
        jar_path = installed_greeneries_jar()
        if jar_path is None:
            self.skipTest("set GREENERIES_JAR to validate an installed Greeneries jar")

        with zipfile.ZipFile(jar_path) as jar:
            grass = json.loads(jar.read("data/minecraft/worldgen/configured_feature/patch_grass.json"))
            inner = grass["config"]["feature"]
            states = inner["feature"]["config"]["to_place"]["entries"]
            names = {entry["data"]["Name"] for entry in states}
            self.assertIn("minecraft:short_grass", names)
            self.assertNotIn("would_survive", json.dumps(inner))

            reeds = json.loads(jar.read("data/greeneries/worldgen/placed_feature/patch_reeds.json"))
            serialized_reeds = json.dumps(reeds)
            self.assertIn("would_survive", serialized_reeds)
            self.assertIn("matching_fluids", serialized_reeds)

            def members(tag):
                namespace, name = tag.split(":", 1)
                data = json.loads(jar.read(f"data/{namespace}/tags/block/{name}.json"))
                result = set()
                for entry in data["values"]:
                    result.update(members(entry[1:]) if entry.startswith("#") else {entry})
                return result

            candidates = members("greeneries:grass") | members("greeneries:fern")
            self.assertTrue({"minecraft:short_grass", "minecraft:fern",
                             "greeneries:bushy_grass", "greeneries:medium_grass"} <= candidates)
            self.assertTrue(candidates.isdisjoint(members("greeneries:reeds")))
            self.assertTrue(candidates.isdisjoint({"minecraft:dead_bush", "minecraft:cactus"}))
            for name in ("patch_blue_grass", "patch_red_fescue", "patch_red_fescue_small",
                         "patch_common_bent_grass", "patch_common_bent_grass_small", "patch_royal_fern"):
                native = json.loads(jar.read(f"data/greeneries/worldgen/configured_feature/{name}.json"))
                self.assertEqual(native["type"], "minecraft:random_patch")
                self.assertGreater(native["config"]["tries"], 0)
                provider = native["config"]["feature"]["feature"]["config"]["to_place"]
                self.assertEqual(provider["type"], "minecraft:weighted_state_provider")
                self.assertTrue({entry["data"]["Name"] for entry in provider["entries"]} <= candidates, name)


if __name__ == "__main__":
    unittest.main()
