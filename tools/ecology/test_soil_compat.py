import json
import os
import re
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CLASSIFIER = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/EcologyClassifier.java"
RESOURCE_ROOT = ROOT / "neoforge/src/main/resources/data/terrain-diffusion-mc"
BIOME_TAG = RESOURCE_ROOT / "tags/worldgen/biome/ecology.json"
FEATURE_TAG = RESOURCE_ROOT / "tags/worldgen/placed_feature/immersive_weathering_surface_soil.json"
MODIFIER = RESOURCE_ROOT / "neoforge/biome_modifier/remove_immersive_weathering_surface_soil.json"

IW_FEATURES = (
    "earthen_clay",
    "sandy_dirt",
    "loam",
    "permafrost",
)


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def classifier_biomes():
    source = CLASSIFIER.read_text(encoding="utf-8")
    match = re.search(r"BIOMES\s*=\s*List\.of\((.*?)\);", source, re.S)
    if not match:
        raise AssertionError("could not find EcologyClassifier.BIOMES")
    return re.findall(r'"([^"]+)"', match.group(1))


def installed_iw_jar():
    configured = os.environ.get("IW_JAR")
    if configured:
        return Path(configured)
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    candidate = (
        Path(appdata)
        / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods"
        / "Immersive-weathering-V1.0.5-neoforge-beta.jar"
    )
    return candidate if candidate.is_file() else None


class SoilCompatibilityResourcesTest(unittest.TestCase):
    def test_removal_is_limited_to_ecology_biomes_and_surface_soil_features(self):
        expected_biomes = [
            f"terrain-diffusion-mc:ecology/{name}" for name in classifier_biomes()
        ]
        biome_tag = load_json(BIOME_TAG)
        feature_tag = load_json(FEATURE_TAG)
        modifier = load_json(MODIFIER)

        self.assertEqual(15, len(expected_biomes))
        self.assertEqual({"replace": False, "values": expected_biomes}, biome_tag)
        self.assertEqual(
            {
                "replace": False,
                "values": [
                    {
                        "id": f"immersive_weathering:{feature}",
                        "required": False,
                    }
                    for feature in IW_FEATURES
                ],
            },
            feature_tag,
        )
        self.assertEqual(
            {
                "type": "neoforge:remove_features",
                "biomes": "#terrain-diffusion-mc:ecology",
                "features": "#terrain-diffusion-mc:immersive_weathering_surface_soil",
                "steps": "raw_generation",
            },
            modifier,
        )

        serialized_modifier = json.dumps(modifier)
        self.assertNotIn("#minecraft:is_overworld", serialized_modifier)
        self.assertNotIn("#minecraft:is_savanna", serialized_modifier)
        self.assertNotIn("#minecraft:is_jungle", serialized_modifier)
        self.assertNotIn("#minecraft:is_taiga", serialized_modifier)

    def test_installed_immersive_weathering_features_are_raw_soil_replacements(self):
        jar_path = installed_iw_jar()
        if jar_path is None:
            self.skipTest("set IW_JAR to validate an installed Immersive Weathering jar")

        inherited_tags = {
            "earthen_clay": "#minecraft:is_savanna",
            "sandy_dirt": "#minecraft:is_savanna",
            "loam": "#minecraft:is_jungle",
            "permafrost": "#minecraft:is_taiga",
        }
        with zipfile.ZipFile(jar_path) as jar:
            for feature in IW_FEATURES:
                with self.subTest(feature=feature):
                    modifier = json.loads(
                        jar.read(
                            f"data/immersive_weathering/neoforge/biome_modifier/has_{feature}.json"
                        )
                    )
                    configured = json.loads(
                        jar.read(
                            f"data/immersive_weathering/worldgen/configured_feature/{feature}.json"
                        )
                    )
                    biome_tag = json.loads(
                        jar.read(
                            f"data/immersive_weathering/tags/worldgen/biome/has_{feature}.json"
                        )
                    )

                    self.assertEqual("neoforge:add_features", modifier["type"])
                    self.assertEqual([f"immersive_weathering:{feature}"], modifier["features"])
                    self.assertEqual("raw_generation", modifier["step"])
                    self.assertIn(inherited_tags[feature], biome_tag["values"])
                    self.assertEqual("minecraft:ore", configured["type"])
                    replaced_blocks = {
                        target["target"].get("block")
                        for target in configured["config"]["targets"]
                    }
                    self.assertTrue(
                        replaced_blocks.intersection({"minecraft:grass_block", "minecraft:dirt"})
                    )


if __name__ == "__main__":
    unittest.main()
