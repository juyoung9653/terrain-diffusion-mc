import unittest
from pathlib import Path
import tempfile

from tools.ecology.compare_saved_vegetation import (
    GRASS_SUPPORTS,
    is_ground_plant,
    slope_bin,
    summarize_columns,
    WorldReader,
)


class SavedVegetationComparisonTest(unittest.TestCase):
    def test_ground_plant_scope_does_not_treat_cover_as_a_plant(self):
        self.assertTrue(is_ground_plant("greeneries:short_red_fescue"))
        self.assertTrue(is_ground_plant("minecraft:short_grass"))
        self.assertFalse(is_ground_plant("minecraft:oak_leaves"))
        self.assertFalse(is_ground_plant("minecraft:snow"))
        self.assertIn("terrain_slabs:grass_slab", GRASS_SUPPORTS)

    def test_summary_uses_only_target_biome_exposed_grass(self):
        target = "terrain-diffusion-mc:ecology/steppe"
        columns = {
            (0, 0): (64, "minecraft:grass_block", "greeneries:short_red_fescue", target),
            (-1, 0): (64, "minecraft:grass_block", "", target),
            (1, 0): (65, "minecraft:grass_block", "", target),
            (0, -1): (64, "minecraft:grass_block", "", target),
            (0, 1): (64, "minecraft:grass_block", "", target),
            (1, 1): (64, "minecraft:coarse_dirt", "", target),
        }
        report = summarize_columns(columns, (0, 1, 0, 1), target)
        self.assertEqual(report["target_biome_columns"], 4)
        self.assertEqual(report["eligible_exposed_grass_columns"], 3)
        self.assertEqual(report["planted_columns"], 1)
        self.assertEqual(report["grass_support_bins"]["minecraft:grass_block"]["coverage"],
                         1 / 3)
        self.assertEqual(report["slope_bins"]["gentle_1"]["planted"], 1)

    def test_slope_bins_are_stable(self):
        self.assertEqual([slope_bin(value) for value in (0, 1, 2, 3, 4, 9)],
                         ["flat_0", "gentle_1", "moderate_2_3", "moderate_2_3",
                          "steep_4_plus", "steep_4_plus"])

    def test_reader_rejects_a_non_world_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "level.dat"):
                WorldReader(Path(directory))


if __name__ == "__main__":
    unittest.main()
