"""Regression for Streams water-skin ordering and equal empty surface aliases."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "common/src/main/resources/data/terrain-diffusion-mc"


def surface_node(resource):
    # These fixtures contain one shared configured holder and either no modifiers
    # or a constant count. Equal empty lists collapse in Minecraft's record key.
    return resource["feature"], json.dumps(resource["placement"], sort_keys=True)


def cycle(orders):
    edges = {}
    for order in orders:
        for left, right in zip(order, order[1:]):
            edges.setdefault(left, set()).add(right)
    active, done = set(), set()

    def visit(node):
        if node in active:
            return True
        if node in done:
            return False
        active.add(node)
        if any(visit(child) for child in edges.get(node, ())):
            return True
        active.remove(node)
        done.add(node)
        return False

    return any(visit(node) for node in edges)


class BeachFeatureOrderTest(unittest.TestCase):
    def test_beach_alias_breaks_runtime_water_skin_cycle(self):
        land = json.loads((DATA / "worldgen/placed_feature/eco_surface.json").read_text())
        beach = json.loads((DATA / "worldgen/placed_feature/eco_beach_surface.json").read_text())
        modifier = json.loads((ROOT / "neoforge/src/main/resources/data/terrain-diffusion-mc"
                               / "neoforge/biome_modifier/arid_beach_surface.json").read_text())
        self.assertEqual(modifier["biomes"], "minecraft:beach")
        self.assertEqual(modifier["features"], "terrain-diffusion-mc:eco_beach_surface")
        self.assertEqual(modifier["step"], "local_modifications")
        self.assertEqual(beach["feature"], land["feature"])
        self.assertEqual(beach["placement"], [{"type": "minecraft:count", "count": 1}])
        skin = ("streamsreflowing:water_skin", "")
        original, alias = surface_node(land), surface_node(beach)
        self.assertFalse(cycle([[original, skin], [skin]]))
        self.assertTrue(cycle([[original, skin], [skin, original]]))
        empty_alias = surface_node({"feature": land["feature"], "placement": []})
        self.assertTrue(cycle([[original, skin], [skin, empty_alias]]))
        self.assertFalse(cycle([[original, skin], [skin, alias]]))


if __name__ == "__main__":
    unittest.main()
