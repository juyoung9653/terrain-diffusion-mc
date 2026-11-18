import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from validate_resources import (ecological_biomes, find_cycles, resolve_tag,
                                submerged_wetland_soil_writers)


class FeatureSorterGraphTests(unittest.TestCase):
    def test_acyclic_graph_has_no_cycle(self):
        graph = {(9, "a"): {(9, "b")}, (9, "b"): {(9, "c")}, (9, "c"): set()}
        self.assertEqual([], find_cycles(graph))

    def test_cycle_is_reported(self):
        graph = {(9, "a"): {(9, "b")}, (9, "b"): {(9, "a")}}
        self.assertEqual([[(9, "a"), (9, "b"), (9, "a")]], find_cycles(graph))

    def test_required_and_optional_nested_tags(self):
        self.assertEqual(["minecraft:ok"], resolve_tag("root", {"root": ["minecraft:ok", {"id": "#missing", "required": False}]}))
        with self.assertRaisesRegex(ValueError, "missing required"):
            resolve_tag("missing", {})

    def test_ecology_biomes_are_discovered_by_prefix(self):
        biomes = {"terrain-diffusion-mc:ecology/desert": {}, "terrain-diffusion-mc:ecology/temperate_forest": {}, "minecraft:plains": {}}
        self.assertEqual(["terrain-diffusion-mc:ecology/desert", "terrain-diffusion-mc:ecology/temperate_forest"], ecological_biomes(biomes))

    def test_rejects_later_ocean_floor_disk_that_replaces_wetland_mud_with_dirt(self):
        biome = "terrain-diffusion-mc:ecology/mangrove_swamp"
        biomes = {biome: {"features": [[], [], ["terrain-diffusion-mc:eco_surface"], [], [], [], ["minecraft:disk_grass"]]}}
        placed = {"minecraft:disk_grass": {"feature": "minecraft:disk_grass", "placement": [
            {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR_WG"}]}}
        configured = {"minecraft:disk_grass": {"type": "minecraft:disk", "config": {
            "target": {"blocks": ["minecraft:dirt", "minecraft:mud"]},
            "state_provider": {"fallback": {"state": {"Name": "minecraft:dirt"}}}}}}
        self.assertEqual([biome + ": step 6 minecraft:disk_grass can overwrite mud underwater"],
                         submerged_wetland_soil_writers(biomes, placed, configured))

    def test_allows_later_ocean_floor_disk_that_does_not_write_dirt_or_grass(self):
        biome = "terrain-diffusion-mc:ecology/freshwater_swamp"
        biomes = {biome: {"features": [[], [], ["terrain-diffusion-mc:eco_surface"], [], [], [], ["minecraft:disk_clay"]]}}
        placed = {"minecraft:disk_clay": {"feature": "minecraft:disk_clay", "placement": [
            {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR_WG"}]}}
        configured = {"minecraft:disk_clay": {"type": "minecraft:disk", "config": {
            "target": {"blocks": ["minecraft:dirt", "minecraft:mud"]},
            "state_provider": {"fallback": {"state": {"Name": "minecraft:clay"}}}}}}
        self.assertEqual([], submerged_wetland_soil_writers(biomes, placed, configured))


if __name__ == "__main__":
    unittest.main()
