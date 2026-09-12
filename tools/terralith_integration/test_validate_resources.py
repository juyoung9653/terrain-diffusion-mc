import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from validate_resources import ecological_biomes, find_cycles, resolve_tag


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


if __name__ == "__main__":
    unittest.main()
