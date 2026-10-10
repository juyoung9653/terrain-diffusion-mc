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
    def test_steppe_keeps_plain_grass_generation(self):
        self.assertEqual(("plains", "plains", .7), GENERATOR.SPECS["steppe"])

    def test_native_route_is_conditional_and_preserves_placements_and_flowers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            vanilla = root / "vanilla.jar"
            self._write_fixture(vanilla)
            output, neo = root / "out", root / "neo"
            GENERATOR.generate(vanilla, output, neo)
            first = {p.relative_to(neo): p.read_bytes() for p in neo.rglob("*.json")}
            GENERATOR.generate(vanilla, output, neo)
            self.assertEqual(first, {p.relative_to(neo): p.read_bytes() for p in neo.rglob("*.json")})
            modifiers = neo / "data/terrain-diffusion-mc/neoforge/biome_modifier/greeneries_native"
            conditions = [{"type": "neoforge:mod_loaded", "modid": "greeneries"}]
            for name in GENERATOR.SPECS:
                data = json.loads((modifiers / f"{name}.json").read_text())
                self.assertEqual(conditions, data["neoforge:conditions"])
                self.assertEqual("terrain-diffusion-mc:greeneries_native_vegetation", data["type"])
                self.assertEqual("terrain-diffusion-mc:ecology/" + name, data["biomes"])
                self.assertEqual(["terrain-diffusion-mc:greeneries_native/" + path
                                  for path in ("bamboo_light", "vines", "flowers")], data["features"])
            removal = json.loads((modifiers / "remove_climate_groundcover.json").read_text())
            self.assertEqual(conditions, removal["neoforge:conditions"])
            self.assertEqual("neoforge:remove_features", removal["type"])
            self.assertEqual("terrain-diffusion-mc:eco_groundcover", removal["features"])
            with ZipFile(vanilla) as jar:
                for path in (neo / "data/terrain-diffusion-mc/worldgen/placed_feature/greeneries_native").glob("*.json"):
                    self.assertEqual(json.loads(jar.read(f"data/minecraft/worldgen/placed_feature/{path.name}")),
                                     json.loads(path.read_text()))

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
            first_files = {path.relative_to(output): path.read_bytes()
                           for path in output.rglob("*") if path.is_file()}
            second = GENERATOR.generate(vanilla, output)
            self.assertEqual(first, second)
            self.assertEqual(first_files, {path.relative_to(output): path.read_bytes()
                                           for path in output.rglob("*") if path.is_file()})
            self.assertEqual(set(GENERATOR.SPECS), set(first))
            generated = []
            for name in GENERATOR.SPECS:
                path = output / f"data/terrain-diffusion-mc/worldgen/biome/ecology/{name}.json"
                body = json.loads(path.read_text())
                generated.append(body)
                flattened = [entry for step in body["features"] for entry in step]
                self.assertNotIn("minecraft:trees_chain", flattened)
                self.assertEqual(["minecraft:ore"], body["features"][0])
                self.assertEqual(["minecraft:amethyst_geode", "terrain-diffusion-mc:eco_surface"],
                                 body["features"][2])
                self.assertEqual(["minecraft:monster_room"], body["features"][3])
                self.assertEqual(["minecraft:spring_water"], body["features"][8])
                self.assertEqual(GENERATOR.VEGETAL_FEATURES, body["features"][9])
                self.assertNotIn("minecraft:bamboo_light", flattened)
                self.assertNotIn("minecraft:vines", flattened)
                self.assertNotIn("minecraft:flowers", flattened)
                self.assertEqual(["minecraft:freeze_top_layer"], body["features"][10])
                self.assertEqual(1, body["features"][2].count("terrain-diffusion-mc:eco_surface"))
                self.assertEqual(1, body["features"][9].count("terrain-diffusion-mc:eco_trees"))
                self.assertEqual(1, body["features"][9].count("terrain-diffusion-mc:eco_groundcover"))
                self.assertTrue(first[name]["removedTreePlacements"])
            effects = [body["effects"] for body in generated]
            for effect in effects[1:]:
                self.assertEqual(effects[0], effect)
            tag = json.loads(inherited.read_text())
            self.assertIn("example:existing", tag["values"])
            self.assertIn({"id": "#example:nested", "required": False}, tag["values"])
            ecology = [value for value in tag["values"] if isinstance(value, str) and value.startswith("terrain-diffusion-mc:ecology/")]
            self.assertEqual(len(ecology), len(set(ecology)))
            self.assertEqual(sum(tag in ("forest", "taiga") for _, tag, _ in GENERATOR.SPECS.values()), len(ecology))
            configured = json.loads((output / "data/terrain-diffusion-mc/worldgen/configured_feature/eco_groundcover.json").read_text())
            placed = json.loads((output / "data/terrain-diffusion-mc/worldgen/placed_feature/eco_groundcover.json").read_text())
            self.assertEqual({"type": "terrain-diffusion-mc:eco_groundcover", "config": {}}, configured)
            self.assertEqual({"feature": "terrain-diffusion-mc:eco_groundcover", "placement": []}, placed)

    def _write_fixture(self, jar):
        names = {source for source, _, _ in GENERATOR.SPECS.values()}
        with ZipFile(jar, "w", ZIP_DEFLATED) as archive:
            for name in names:
                biome = {"temperature": .5, "downfall": .5, "has_precipitation": True,
                         "effects": {"grass_color": 1, "foliage_color": 2, "grass_color_modifier": "swamp"},
                         "features": [["minecraft:ore"], [], ["minecraft:amethyst_geode"],
                                      ["minecraft:trees_chain", "minecraft:monster_room"], [], [], [], [],
                                      ["minecraft:spring_water"],
                                      ["minecraft:bamboo_light", "minecraft:vines", "minecraft:flowers",
                                       "minecraft:trees_chain"],
                                      ["minecraft:freeze_top_layer"]]}
                archive.writestr(f"data/minecraft/worldgen/biome/{name}.json", json.dumps(biome))
            archive.writestr("data/minecraft/worldgen/placed_feature/trees_chain.json", json.dumps({"feature": "minecraft:tree_config"}))
            archive.writestr("data/minecraft/worldgen/configured_feature/tree_config.json", json.dumps({"type": "minecraft:tree", "config": {}}))
            for name in ("bamboo_light", "vines", "flowers"):
                archive.writestr(f"data/minecraft/worldgen/placed_feature/{name}.json",
                                 json.dumps({"feature": "minecraft:" + name,
                                             "placement": [{"type": "minecraft:biome"}]}))
            archive.writestr("data/minecraft/tags/worldgen/biome/is_forest.json", json.dumps({"values": ["minecraft:forest", "minecraft:taiga"]}))
            archive.writestr("data/minecraft/tags/worldgen/biome/is_overworld.json", json.dumps({"values": ["#minecraft:is_forest", "minecraft:plains"]}))


if __name__ == "__main__":
    unittest.main()
