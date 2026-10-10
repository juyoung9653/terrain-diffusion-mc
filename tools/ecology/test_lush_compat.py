"""Resolve the actual Lush add-feature edge against TD's scoped removal resources."""

import json
import os
from pathlib import Path
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "neoforge/src/main/resources/data/terrain-diffusion-mc"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_feature_tag(tag, available):
    result = set()
    for entry in tag["values"]:
        feature = entry if isinstance(entry, str) else entry["id"]
        required = isinstance(entry, str) or entry.get("required", True)
        if feature in available:
            result.add(feature)
        elif required:
            raise ValueError(f"Required feature is missing: {feature}")
    return result


class LushCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.removal = load(RESOURCES / "neoforge/biome_modifier/remove_lush_ecology_vegetation.json")
        self.restore = load(RESOURCES / "neoforge/biome_modifier/restore_lush_ecology_vegetation.json")
        self.clone = load(RESOURCES / "worldgen/placed_feature/lush_ecology_vegetation.json")
        self.tag = load(RESOURCES / "tags/worldgen/placed_feature/lush_ecology_vegetation.json")
        self.biomes = set(load(RESOURCES / "tags/worldgen/biome/ecology.json")["values"])

    def test_optional_dependency_absent_and_unrelated_features_preserved(self):
        unrelated = {"lush:rock", "lush:mushroom_scatter", "greeneries:patch_reeds"}
        self.assertEqual(set(), resolve_feature_tag(self.tag, unrelated))
        removed = resolve_feature_tag(self.tag, unrelated | {"lush:lush_vegetation"})
        self.assertEqual({"lush:lush_vegetation"}, removed)
        self.assertEqual(unrelated, (unrelated | removed) - removed)

    def test_all_generated_ecology_biomes_have_replacement_and_scope_excludes_vanilla(self):
        paths = list((ROOT / "common/src/main/resources/data/terrain-diffusion-mc/worldgen/biome/ecology").glob("*.json"))
        generated = {f"terrain-diffusion-mc:ecology/{path.stem}" for path in paths}
        self.assertEqual(generated, self.biomes)
        self.assertEqual(19, len(generated))
        self.assertTrue(self.biomes.isdisjoint({
            "minecraft:desert", "minecraft:beach", "minecraft:forest",
            "minecraft:plains", "minecraft:sunflower_plains",
        }))
        for path in paths:
            with self.subTest(biome=path.stem):
                self.assertIn("terrain-diffusion-mc:eco_groundcover", load(path)["features"][9])

    def test_installed_lush_injection_is_removed_in_same_generation_step(self):
        configured = os.environ.get("LUSH_JAR")
        mods = Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods"
        jar_path = Path(configured) if configured else next(mods.glob("lush-*.jar"), None)
        if jar_path is None:
            self.skipTest("Set LUSH_JAR for installed-mod integration validation")
        with zipfile.ZipFile(jar_path) as jar:
            addition = json.loads(jar.read("data/lush/neoforge/biome_modifier/lush_vegetation.json"))
            feature = addition["features"]
            placed = json.loads(jar.read(f"data/lush/worldgen/placed_feature/{feature.split(':')[1]}.json"))
            configured_feature = json.loads(jar.read(f"data/lush/worldgen/configured_feature/{feature.split(':')[1]}.json"))
            self.assertEqual("neoforge:add_features", addition["type"])
            self.assertEqual("#minecraft:is_overworld", addition["biomes"])
            self.assertEqual("neoforge:remove_features", self.removal["type"])
            self.assertEqual(addition["step"], self.removal["steps"])
            self.assertEqual("#terrain-diffusion-mc:ecology", self.removal["biomes"])
            self.assertEqual("#terrain-diffusion-mc:lush_ecology_vegetation", self.removal["features"])
            self.assertIn(feature, resolve_feature_tag(self.tag, {feature}))
            self.assertEqual(feature, placed["feature"])
            count = next(p["count"] for p in placed["placement"]
                         if p["type"] == "minecraft:count")
            self.assertEqual(40, count)
            clone = dict(self.clone)
            self.assertEqual([{"type": "neoforge:mod_loaded", "modid": "lush"}],
                             clone.pop("neoforge:conditions"))
            self.assertEqual(placed, clone)
            # Lush uses its own Feature, so SimpleBlockFeature's hook cannot intercept it.
            self.assertEqual("lush:lush_vegetation", configured_feature["type"])
            self.assertTrue(any(p["type"] == "minecraft:biome" for p in placed["placement"]))

    def test_restore_is_scoped_to_lush_plus_greeneries_and_reuses_native_feature(self):
        conditions = self.restore["neoforge:conditions"]
        self.assertEqual({"lush", "greeneries"}, {condition["modid"] for condition in conditions})
        self.assertTrue(all(condition["type"] == "neoforge:mod_loaded" for condition in conditions))
        self.assertEqual("terrain-diffusion-mc:lush_ecology_vegetation", self.restore["type"])
        self.assertEqual(self.removal["biomes"], self.restore["biomes"])
        self.assertEqual("terrain-diffusion-mc:lush_ecology_vegetation", self.restore["features"])
        self.assertNotEqual(self.removal["features"], self.restore["features"])

        conditions = self.clone.pop("neoforge:conditions")
        self.assertEqual([{"type": "neoforge:mod_loaded", "modid": "lush"}], conditions)
        self.assertEqual("lush:lush_vegetation", self.clone["feature"])
        self.assertEqual(40, self.clone["placement"][0]["count"])
        self.assertEqual(["minecraft:count", "minecraft:in_square", "minecraft:heightmap", "minecraft:biome"],
                         [placement["type"] for placement in self.clone["placement"]])

    def test_adapter_is_version_gated_and_guards_each_direct_plant_write(self):
        java = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc"
        compatibility = (java / "compat/lush/LushCompatibility.java").read_text(encoding="utf-8")
        scope = (java / "compat/lush/LushVegetationPlacementScope.java").read_text(encoding="utf-8")
        mixin = (java / "mixin/LushVegetationFeatureMixin.java").read_text(encoding="utf-8")
        config = load(ROOT / "neoforge/src/main/resources/terrain-diffusion-mc.lush.mixins.json")

        self.assertIn('AUDITED_LUSH_VERSION = "2.2"', compatibility)
        self.assertIn('"greeneries".equals(mod.getModId())', compatibility)
        self.assertEqual(["LushVegetationFeatureMixin"], config["mixins"])
        self.assertIn("LushVegetationMixinPlugin", config["plugin"])
        self.assertTrue(config["required"])
        self.assertIn("source.naturalBiomes()", scope)
        self.assertIn('startsWith("ecology/")', scope)
        self.assertIn("GreeneriesSoilPolicy.acceptance", scope)
        guard = scope[scope.index("public static boolean allowsPlant"):scope.index(
            "private static boolean isNaturalTerrainDiffusion")]
        self.assertLess(guard.index("!isEcologyBiome(level, pos)"),
                        guard.index("GreeneriesSoilPolicy.acceptance"))
        self.assertIn('method = "placeShort"', mixin)
        self.assertIn('method = "placeTall"', mixin)
        self.assertEqual(2, mixin.count("LushVegetationPlacementScope.allowsPlant(level, pos)"))


if __name__ == "__main__":
    unittest.main()
