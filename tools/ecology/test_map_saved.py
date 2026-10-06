import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("map_saved", Path(__file__).with_name("map_saved.py"))
MAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MAP)


class SavedMapTests(unittest.TestCase):
    def test_non_straddling_packed_values_cross_long_boundary(self):
        expected = [i % 17 for i in range(64)]
        words = []
        for start in range(0, len(expected), 12):
            words.append(sum(value << (index * 5) for index, value in enumerate(expected[start:start + 12])))
        raw = struct.pack(">i", len(words)) + struct.pack(">" + "Q" * len(words), *words)
        self.assertEqual(expected, MAP.packed_values(raw, (12, 0, len(raw), None), 17, 64, 1))
        with self.assertRaises(ValueError):
            MAP.packed_values(raw, (12, 0, len(raw), None), 17, 4096, 4)
        with self.assertRaises(ValueError):
            MAP.packed_values(b"", None, 2, 64, 1)

    def test_ground_biome_and_negative_section_y(self):
        fields = {("Status",): (8, 0, 0, "minecraft:full")}
        for section, y, block, biome in (("0", -1, "minecraft:grass_block", "test:ground"),
                                          ("1", 0, "minecraft:air", "test:sky")):
            prefix = ("sections", section)
            fields[prefix + ("Y",)] = (1, 0, 0, y)
            fields[prefix + ("block_states", "palette", "0", "Name")] = (8, 0, 0, block)
            fields[prefix + ("biomes", "palette", "0")] = (8, 0, 0, biome)
        with patch.object(MAP, "nbt_fields", return_value=fields):
            columns = MAP.chunk_columns(b"")
        self.assertEqual(256, len(columns))
        self.assertEqual({("minecraft:grass_block", "test:ground", -1)}, set(columns.values()))

    def test_output_cannot_write_into_source_world(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "outside"):
                MAP.render(Path(directory), Path(directory) / "maps", 0, 0, 1)


if __name__ == "__main__":
    unittest.main()
