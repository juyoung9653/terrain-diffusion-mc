"""Focused contract checks for the NeoForge Greeneries sand-placement guard."""

import json
import os
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


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
    def test_native_additive_tags_cover_all_ecology_biomes_without_extra_modifiers(self):
        resources = ROOT / "neoforge/src/main/resources/data"
        tag_dir = resources / "greeneries/tags/worldgen/biome"
        actual_biomes = {
            "terrain-diffusion-mc:ecology/" + path.stem
            for path in (ROOT / "common/src/main/resources/data/terrain-diffusion-mc/worldgen/biome/ecology").glob("*.json")
        }
        additions = set()
        for path in tag_dir.glob("*.json"):
            data = json.loads(path.read_text())
            self.assertFalse(data["replace"], path.name)
            self.assertEqual(len(data["values"]), len(set(data["values"])), path.name)
            additions.update(data["values"])
        self.assertEqual(len(actual_biomes), 15)
        self.assertEqual(additions, actual_biomes)
        # Use the mod's own modifier once; adding our own add_features would duplicate it.
        for path in (resources / "terrain-diffusion-mc/neoforge/biome_modifier").glob("*.json"):
            data = json.loads(path.read_text())
            if data["type"] == "neoforge:add_features":
                self.assertNotIn("greeneries:", json.dumps(data))
        jar_path = installed_greeneries_jar()
        if jar_path is not None:
            with zipfile.ZipFile(jar_path) as jar:
                modifiers = [json.loads(jar.read(name)) for name in jar.namelist()
                             if name.startswith("data/greeneries/neoforge/biome_modifier/") and name.endswith(".json")]
                for path in tag_dir.glob("*.json"):
                    matching = [m for m in modifiers if m["biomes"] == "#greeneries:" + path.stem]
                    self.assertEqual(len(matching), 1, path.stem)

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

    def test_optional_groundcover_species_exist_in_installed_jar(self):
        jar_path = installed_greeneries_jar()
        if jar_path is None:
            self.skipTest("set GREENERIES_JAR to validate an installed Greeneries jar")

        with zipfile.ZipFile(jar_path) as jar:
            blockstates = {
                name.removeprefix("assets/greeneries/blockstates/").removesuffix(".json")
                for name in jar.namelist()
                if name.startswith("assets/greeneries/blockstates/") and name.endswith(".json")
            }
            self.assertTrue({
                "very_short_blue_grass", "short_blue_grass", "bushy_blue_grass",
                "very_short_red_fescue", "short_red_fescue", "bushy_red_fescue",
                "very_short_common_bent", "short_common_bent", "bushy_common_bent",
                "very_short_grass", "bushy_grass", "medium_grass",
                "short_royal_fern", "medium_royal_fern",
            } <= blockstates)

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
