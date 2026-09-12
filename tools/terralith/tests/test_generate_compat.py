import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).parents[1] / "generate_compat.py"
SPEC = importlib.util.spec_from_file_location("generate_compat", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


def write_json(archive, name, data):
    archive.writestr(name, json.dumps(data))


class GenerateCompatTest(unittest.TestCase):
    def make_fixture(self, root: Path) -> tuple[Path, Path]:
        repo = root / "repo"
        noise = repo / MODULE.TD_NOISE_SETTINGS
        preset = repo / MODULE.TD_WORLD_PRESET
        noise.parent.mkdir(parents=True)
        preset.parent.mkdir(parents=True)
        td_base = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:bedrock"}}}
        td_deep = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate"}}}
        noise.write_text(json.dumps({"sea_level": 63, "noise": {"min_y": -64, "height": 2032}, "noise_router": {"temperature": 0, "vegetation": 0, "initial_density_without_jaggedness": {"type": "terrain-diffusion-mc:terrain_diffusion"}}, "surface_rule": {"type": "minecraft:sequence", "sequence": [td_base, td_deep, {"type": "minecraft:block", "result_state": {"Name": "minecraft:dirt"}}]}}))
        preset.write_text(json.dumps({"dimensions": {"minecraft:overworld": {"generator": {"biome_source": {"type": "terrain-diffusion-mc:terrain_diffusion"}}}, "minecraft:the_end": {"generator": {}}}}))
        jar = root / "terralith.jar"
        source_base = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:bedrock"}}}
        source_deep = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate"}}}
        core_surface = {"type": "minecraft:condition", "if_true": {"type": "minecraft:above_preliminary_surface"}, "then_run": {"type": "minecraft:condition", "if_true": {"type": "minecraft:noise_threshold", "noise": "terralith:surface/test"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:grass_block"}}}}
        core_cave = {"type": "minecraft:condition", "if_true": {"type": "minecraft:not", "invert": {"type": "minecraft:above_preliminary_surface"}}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:mud"}}}
        with zipfile.ZipFile(jar, "w") as archive:
            write_json(archive, MODULE.JAR_OVERWORLD_NOISE, {"surface_rule": {"type": "minecraft:sequence", "sequence": [source_base, core_surface, core_cave, source_deep]}})
            write_json(archive, MODULE.JAR_OVERWORLD_DIMENSION, {"neoforge:conditions": [{"modid": "lithostitched"}]})
            write_json(archive, "data/terralith/worldgen/noise/surface/test.json", {})
            for index in range(95):
                name = f"cave/deep_{index}" if index == 0 else f"biome_{index}"
                write_json(archive, f"data/terralith/worldgen/biome/{name}.json", {"temperature": 0.5, "downfall": 0.8, "has_precipitation": True, "features": [["minecraft:lake"]], "carvers": {"air": ["minecraft:cave"]}})
        return repo, jar

    def test_generates_gated_surface_and_preset(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo, jar = self.make_fixture(root)
            output = root / "pack"
            manifest = MODULE.build_pack(jar, repo, output)
            noise = json.loads((output / "data/terrain-diffusion-mc/worldgen/noise_settings/terrain_diffusion.json").read_text())
            preset = json.loads((output / "data/terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json").read_text())
            inventory = json.loads((output / "terralith-biome-inventory.json").read_text())
            self.assertEqual(95, manifest["counts"]["terralith_biomes"])
            self.assertEqual(1, manifest["counts"]["surface_referenced_terralith_noises"])
            self.assertEqual(0, manifest["counts"]["terralith_placed_features"])
            self.assertEqual(1, manifest["counts"]["configured_carvers"])
            self.assertTrue(manifest["validation"]["td_router_preserved"])
            self.assertTrue(manifest["validation"]["td_bedrock_rule_preserved"])
            self.assertTrue(manifest["validation"]["td_deepslate_rule_preserved"])
            self.assertEqual("minecraft:bedrock", noise["surface_rule"]["sequence"][0]["then_run"]["result_state"]["Name"])
            self.assertEqual(95, len(noise["surface_rule"]["sequence"][1]["if_true"]["biome_is"]))
            self.assertEqual("minecraft:mud", noise["surface_rule"]["sequence"][1]["then_run"]["sequence"][1]["then_run"]["result_state"]["Name"])
            self.assertEqual("minecraft:deepslate", noise["surface_rule"]["sequence"][2]["then_run"]["result_state"]["Name"])
            self.assertTrue(preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]["terralith"])
            self.assertTrue(preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]["natural_biomes"])
            self.assertNotIn("terralith", preset["dimensions"]["minecraft:the_end"])
            self.assertEqual(
                "deep_cave",
                next(item["cave_category"] for item in inventory["biomes"] if item["id"] == "terralith:cave/deep_0"),
            )

    def test_rejects_silently_ignored_newer_preliminary_surface_key(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo, jar = self.make_fixture(root)
            path = repo / MODULE.TD_NOISE_SETTINGS
            noise = json.loads(path.read_text())
            noise["noise_router"]["preliminary_surface_level"] = noise["noise_router"]["initial_density_without_jaggedness"]
            noise["noise_router"]["initial_density_without_jaggedness"] = 0
            path.write_text(json.dumps(noise))
            with self.assertRaisesRegex(ValueError, "1.21.1 requires TD initial_density"):
                MODULE.build_pack(jar, repo, root / "pack")
            self.assertFalse((root / "pack").exists())

    def test_rejects_zero_router_channel_predicate(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo, jar = self.make_fixture(root)
            with zipfile.ZipFile(jar) as archive:
                contents = {name: archive.read(name) for name in archive.namelist()}
            data = json.loads(contents[MODULE.JAR_OVERWORLD_NOISE])
            data["surface_rule"]["sequence"][1]["if_true"] = {"type": "minecraft:continents"}
            contents[MODULE.JAR_OVERWORLD_NOISE] = json.dumps(data).encode()
            with zipfile.ZipFile(jar, "w") as archive:
                for name, content in contents.items():
                    archive.writestr(name, content)
            with self.assertRaisesRegex(ValueError, "TD-zero router"):
                MODULE.build_pack(jar, repo, root / "pack")

    def test_refuses_unrecognized_output_without_removing_input_or_sentinel(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo, jar = self.make_fixture(root)
            output = root / "existing-output"
            output.mkdir()
            sentinel = output / "do-not-delete.txt"
            sentinel.write_text("user data")
            jar_before = jar.read_bytes()
            with self.assertRaisesRegex(ValueError, "unrecognized output"):
                MODULE.build_pack(jar, repo, output)
            self.assertEqual("user data", sentinel.read_text())
            self.assertEqual(jar_before, jar.read_bytes())
            self.assertTrue((repo / MODULE.TD_NOISE_SETTINGS).is_file())
