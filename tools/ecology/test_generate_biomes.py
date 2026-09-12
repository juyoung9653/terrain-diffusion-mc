import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZIP_DEFLATED, ZipFile


MODULE = Path(__file__).with_name("generate_biomes.py")
SPEC = importlib.util.spec_from_file_location("generate_biomes", MODULE)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GenerateEcologyBiomeTests(unittest.TestCase):
    def test_generation_removes_tree_chains_and_preserves_tags_idempotently(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            vanilla = root / "vanilla.jar"
            self._write_fixture(vanilla)
            output = root / "out"
            inherited = output / "data/minecraft/tags/worldgen/biome/is_forest.json"
            inherited.parent.mkdir(parents=True)
            inherited.write_text(json.dumps({"replace": False, "values": ["example:existing", {"id": "#example:nested", "required": False}]}))
            first = GENERATOR.generate(vanilla, output)
            second = GENERATOR.generate(vanilla, output)
            self.assertEqual(first, second)
            self.assertEqual(set(GENERATOR.SPECS), set(first))
            generated = []
            for name in GENERATOR.SPECS:
                path = output / f"data/terrain-diffusion-mc/worldgen/biome/ecology/{name}.json"
                body = json.loads(path.read_text())
                generated.append(body)
                flattened = [entry for step in body["features"] for entry in step]
                self.assertNotIn("minecraft:trees_chain", flattened)
                self.assertIn("terrain-diffusion-mc:eco_surface", body["features"][2])
                self.assertIn("terrain-diffusion-mc:eco_trees", body["features"][9])
                self.assertEqual(1, body["features"][2].count("terrain-diffusion-mc:eco_surface"))
                self.assertEqual(1, body["features"][9].count("terrain-diffusion-mc:eco_trees"))
                self.assertTrue(first[name]["removedTreePlacements"])
            effects = [body["effects"] for body in generated]
            for effect in effects[1:]:
                self.assertEqual(effects[0], effect)
            tag = json.loads(inherited.read_text())
            self.assertIn("example:existing", tag["values"])
            self.assertIn({"id": "#example:nested", "required": False}, tag["values"])
            ecology = [value for value in tag["values"] if isinstance(value, str) and value.startswith("terrain-diffusion-mc:ecology/")]
            self.assertEqual(len(ecology), len(set(ecology)))
            self.assertEqual(4, len(ecology))  # direct forest and taiga memberships

    def _write_fixture(self, jar):
        names = {source for source, _, _ in GENERATOR.SPECS.values()}
        with ZipFile(jar, "w", ZIP_DEFLATED) as archive:
            for name in names:
                biome = {"temperature": .5, "downfall": .5, "has_precipitation": True,
                         "effects": {"grass_color": 1, "foliage_color": 2, "grass_color_modifier": "swamp"},
                         "features": [["minecraft:ore"], [], [], ["minecraft:trees_chain"], [], [], [], [], [], ["minecraft:flowers"]]}
                archive.writestr(f"data/minecraft/worldgen/biome/{name}.json", json.dumps(biome))
            archive.writestr("data/minecraft/worldgen/placed_feature/trees_chain.json", json.dumps({"feature": "minecraft:tree_config"}))
            archive.writestr("data/minecraft/worldgen/configured_feature/tree_config.json", json.dumps({"type": "minecraft:tree", "config": {}}))
            archive.writestr("data/minecraft/tags/worldgen/biome/is_forest.json", json.dumps({"values": ["minecraft:forest", "minecraft:taiga"]}))
            archive.writestr("data/minecraft/tags/worldgen/biome/is_overworld.json", json.dumps({"values": ["#minecraft:is_forest", "minecraft:plains"]}))


if __name__ == "__main__":
    unittest.main()
