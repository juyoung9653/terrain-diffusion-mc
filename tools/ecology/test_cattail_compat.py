"""Exercise the effective CTM rules against freshwater and marine cases."""
import json
import os
from pathlib import Path
import re
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "assets/minecraft/optifine/ctm/"
RESOURCE = PREFIX + "cattails1/1.properties"
PACK = ROOT / "compat/nature-x-depth/terrain-diffusion-nature-x-depth"
RULES = [PREFIX + f"cattails1/{i}.properties" for i in range(5)] + [PREFIX + "algae/0.properties"]


def properties(text):
    return dict(line.split("=", 1) for raw in text.splitlines()
                if (line := raw.strip()) and not line.startswith("#") and "=" in line)


def heights(value):
    low, _, high = value.partition("-")
    return range(int(low), int(high or low) + 1)


def matches(rule, texture, biome, y):
    return (texture in rule["matchTiles"].split() and biome in rule["biomes"].split()
            and y in heights(rule["heights"]))


class CattailCompatibilityTest(unittest.TestCase):
    def test_freshwater_has_complete_plane_and_base_mask_pair(self):
        rules = [properties((PACK / name).read_text(encoding="utf-8")) for name in RULES[:5]]
        for biome in ("minecraft:river", "minecraft:swamp", "minecraft:mangrove_swamp", "terralith:warm_river"):
            for y in (62, 63, 125, 196, 319):
                with self.subTest(biome=biome, y=y):
                    self.assertTrue(matches(rules[0], "inv_x32", biome, y))
                    self.assertTrue(matches(rules[1], "seagrass", biome, y))
                    for i in range(3):
                        self.assertTrue(matches(rules[2+i], f"seagrass{i}", biome, y))
        self.assertEqual(rules[1]["tiles"], "inv_16x")
        self.assertEqual(rules[1]["method"], "fixed")
        for rule in rules:
            self.assertNotIn(61, heights(rule["heights"]))
            self.assertNotIn(320, heights(rule["heights"]))

    def test_marine_and_unclassified_ecology_keep_underwater_textures(self):
        for name in RULES:
            rule = properties((PACK / name).read_text(encoding="utf-8"))
            for biome in ("minecraft:ocean", "minecraft:beach", "minecraft:lukewarm_ocean",
                          "minecraft:stony_shore", "terralith:gravel_beach",
                          "terrain-diffusion-mc:ecology/temperate_forest"):
                for y in (62, 125):
                    self.assertFalse(matches(rule, rule["matchTiles"], biome, y), (name, biome, y))
            for biome in rule["biomes"].split():
                self.assertRegex(biome, r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")

    def test_algae_is_freshwater_surface_only(self):
        rule = properties((PACK / RULES[-1]).read_text(encoding="utf-8"))
        self.assertTrue(matches(rule, "inv_x32_1", "minecraft:swamp", 62))
        self.assertFalse(matches(rule, "inv_x32_1", "minecraft:swamp", 61))
        self.assertFalse(matches(rule, "inv_x32_1", "minecraft:ocean", 62))

    def test_source_pack_assets_and_all_replacement_rules_are_covered(self):
        source = os.environ.get("NATURE_X_PACK")
        if not source:
            self.skipTest("set NATURE_X_PACK to audit the installed source pack")
        from PIL import Image
        with zipfile.ZipFile(source) as archive:
            names = set(archive.namelist())
            for name in RULES:
                original = properties(archive.read(name).decode("utf-8"))
                overlay = properties((PACK / name).read_text(encoding="utf-8"))
                self.assertEqual({k: v for k, v in original.items() if k not in ("heights", "biomes")},
                                 {k: v for k, v in overlay.items() if k not in ("heights", "biomes")})
                for tile in overlay["tiles"].split():
                    tiles = list(heights(tile)) if re.fullmatch(r"\d+-\d+", tile) else [tile]
                    for entry in tiles:
                        self.assertIn(str(Path(name).parent / f"{entry}.png").replace("\\", "/"), names)
            for name in names:
                if not (name.startswith(PREFIX) and name.endswith(".properties")):
                    continue
                rule = properties(archive.read(name).decode("utf-8"))
                if rule.get("matchTiles") not in ("inv_x32", "inv_x32_1", "seagrass", "seagrass0", "seagrass1", "seagrass2"):
                    continue
                effective = properties((PACK / name).read_text(encoding="utf-8")) if (PACK / name).exists() else rule
                self.assertIn("biomes", effective, name)
            with archive.open(PREFIX + "cattails1/inv_16x.png") as stream:
                self.assertEqual(Image.open(stream).convert("RGBA").getchannel("A").getextrema(), (0, 0))
            model = json.loads(archive.read("assets/minecraft/models/block/tall_seagrass_top.json"))
            self.assertIn("block/inv_x32_1", model["textures"].values())


if __name__ == "__main__":
    unittest.main()
