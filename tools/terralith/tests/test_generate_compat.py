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


def evaluate_surface(rule, biome_id, stone_depth_offset):
    """Small evaluator for the deterministic biome/stone-depth subset in this fixture."""
    rule_type = rule["type"]
    if rule_type == "minecraft:block":
        return rule["result_state"]["Name"]
    if rule_type == "minecraft:sequence":
        for child in rule["sequence"]:
            result = evaluate_surface(child, biome_id, stone_depth_offset)
            if result is not None:
                return result
        return None
    if rule_type != "minecraft:condition":
        return None
    predicate = rule["if_true"]
    predicate_type = predicate["type"]
    matches = (
        biome_id in predicate["biome_is"] if predicate_type == "minecraft:biome"
        else stone_depth_offset <= predicate["offset"] if predicate_type == "minecraft:stone_depth"
        else predicate_type == "minecraft:above_preliminary_surface"
    )
    return evaluate_surface(rule["then_run"], biome_id, stone_depth_offset) if matches else None


class GenerateCompatTest(unittest.TestCase):
    def make_fixture(self, root: Path) -> tuple[Path, Path]:
        repo = root / "repo"
        noise = repo / MODULE.TD_NOISE_SETTINGS
        preset = repo / MODULE.TD_WORLD_PRESET
        noise.parent.mkdir(parents=True)
        preset.parent.mkdir(parents=True)
        td_base = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:bedrock"}}}
        td_deep = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate"}}}
        shore_top = {"type": "minecraft:condition", "if_true": {"type": "minecraft:stone_depth", "offset": 0}, "then_run": {"type": "minecraft:sequence", "sequence": [{"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": ["minecraft:beach", "minecraft:snowy_beach"]}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:sand"}}}, {"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": ["minecraft:stony_shore"]}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:stone"}}}]}}
        shore_subsurface = {"type": "minecraft:condition", "if_true": {"type": "minecraft:stone_depth", "offset": 4}, "then_run": {"type": "minecraft:sequence", "sequence": [{"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": ["minecraft:beach", "minecraft:snowy_beach"]}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:sandstone"}}}, {"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": ["minecraft:stony_shore"]}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:stone"}}}]}}
        noise.write_text(json.dumps({"sea_level": 63, "noise": {"min_y": -64, "height": 2032}, "noise_router": {"temperature": 0, "vegetation": 0, "initial_density_without_jaggedness": {"type": "terrain-diffusion-mc:terrain_diffusion"}}, "surface_rule": {"type": "minecraft:sequence", "sequence": [td_base, td_deep, shore_top, shore_subsurface, {"type": "minecraft:block", "result_state": {"Name": "minecraft:dirt"}}]}}))
        preset.write_text(json.dumps({"dimensions": {"minecraft:overworld": {"generator": {"biome_source": {"type": "terrain-diffusion-mc:terrain_diffusion"}}}, "minecraft:the_end": {"generator": {}}}}))
        jar = root / "terralith.jar"
        source_base = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:bedrock"}}}
        source_deep = {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate"}}}
        core_surface = {"type": "minecraft:condition", "if_true": {"type": "minecraft:above_preliminary_surface"}, "then_run": {"type": "minecraft:sequence", "sequence": [{"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": ["terralith:gravel_beach"]}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:gravel"}}}, {"type": "minecraft:condition", "if_true": {"type": "minecraft:noise_threshold", "noise": "terralith:surface/test"}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:grass_block"}}}]}}
        core_cave = {"type": "minecraft:condition", "if_true": {"type": "minecraft:not", "invert": {"type": "minecraft:above_preliminary_surface"}}, "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:mud"}}}
        with zipfile.ZipFile(jar, "w") as archive:
            write_json(archive, MODULE.JAR_OVERWORLD_NOISE, {"surface_rule": {"type": "minecraft:sequence", "sequence": [source_base, core_surface, core_cave, source_deep]}})
            write_json(archive, MODULE.JAR_OVERWORLD_DIMENSION, {"neoforge:conditions": [{"modid": "lithostitched"}]})
            write_json(archive, "data/terralith/worldgen/noise/surface/test.json", {})
            for index in range(95):
                name = f"cave/deep_{index}" if index == 0 else "gravel_beach" if index == 1 else f"biome_{index}"
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
            self.assertTrue(manifest["validation"]["vanilla_shore_rules_outside_terralith_gate"])
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

            # The Terralith gate precedes TD fallback, but vanilla shores are
            # not gate members and must reach TD's shore rules.
            surface = noise["surface_rule"]
            gate_ids = set(surface["sequence"][1]["if_true"]["biome_is"])
            self.assertNotIn("minecraft:beach", gate_ids)
            self.assertNotIn("minecraft:snowy_beach", gate_ids)
            self.assertNotIn("minecraft:stony_shore", gate_ids)
            self.assertIn("terralith:gravel_beach", gate_ids)
            self.assertEqual("minecraft:sand", evaluate_surface(surface, "minecraft:beach", 0))
            self.assertEqual("minecraft:sand", evaluate_surface(surface, "minecraft:snowy_beach", 0))
            self.assertEqual("minecraft:sandstone", evaluate_surface(surface, "minecraft:beach", 4))
            self.assertEqual("minecraft:stone", evaluate_surface(surface, "minecraft:stony_shore", 0))
            self.assertEqual("minecraft:stone", evaluate_surface(surface, "minecraft:stony_shore", 4))
            self.assertEqual("minecraft:gravel", evaluate_surface(surface, "terralith:gravel_beach", 0))

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
