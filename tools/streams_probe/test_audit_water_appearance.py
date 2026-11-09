import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("audit_water_appearance.py")
spec = importlib.util.spec_from_file_location("appearance", SCRIPT)
appearance = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = appearance
spec.loader.exec_module(appearance)


class AppearanceAlgorithmTest(unittest.TestCase):
    def setUp(self):
        self.blocks = {}

    def block(self, x, y, z):
        return self.blocks.get((x, y, z), "minecraft:stone")

    def test_isolated_supported_one_block_dip(self):
        for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            self.blocks[x, 80, z] = "minecraft:water"
        self.blocks[0, 79, 0] = "minecraft:water"
        columns = {(x, z): appearance.river_column(self.block, x, z, 70, 90)
                   for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))}
        self.assertEqual(columns[0, 0].bed_y, 78)
        self.assertEqual(appearance.isolated_dips(columns), [(0, 0)])

    def test_air_below_water_is_not_a_supported_dip(self):
        for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            self.blocks[x, 80, z] = "minecraft:water"
        self.blocks[0, 79, 0] = "minecraft:water"
        self.blocks[0, 78, 0] = "minecraft:cave_air"
        columns = {(x, z): appearance.river_column(self.block, x, z, 70, 90)
                   for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))}
        self.assertTrue(columns[0, 0].air_below_water)
        self.assertEqual(appearance.isolated_dips(columns), [])

    def test_different_water_surface_is_not_a_dip(self):
        for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            self.blocks[x, 80, z] = "minecraft:water"
        self.blocks[0, 79, 0] = "minecraft:water"
        self.blocks[1, 81, 0] = "minecraft:water"
        columns = {(x, z): appearance.river_column(self.block, x, z, 70, 90)
                   for x, z in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))}
        self.assertEqual(appearance.isolated_dips(columns), [])

    def test_missing_chunk_is_rejected_before_air_is_invented(self):
        class Reader:
            def chunk(self, x, z):
                return None if (x, z) == (0, 0) else object()
        with self.assertRaisesRegex(ValueError, "missing or non-FULL"):
            appearance.require_full(Reader(), (0, 15, 0, 15, 0, 1))

    def test_disconnected_lower_water_is_not_surface_stack(self):
        self.blocks[0, 80, 0] = "minecraft:water"
        self.blocks[0, 78, 0] = "minecraft:water"
        column = appearance.river_column(self.block, 0, 0, 70, 90)
        self.assertEqual((column.water_surface, column.water_bottom, column.bed_y),
                         (80, 80, 79))


if __name__ == "__main__":
    unittest.main()
