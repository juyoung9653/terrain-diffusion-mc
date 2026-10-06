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


if __name__ == "__main__":
    unittest.main()
