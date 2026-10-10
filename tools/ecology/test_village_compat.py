"""Village compatibility survives an external replacement of vanilla biome tags."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.ecology.generate_biomes import write_village_compat_pack

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / "common/src/main/resources"
NEOFORGE = ROOT / "neoforge/src/main/resources"


class VillageCompatibilityTest(unittest.TestCase):
    def test_pack_appends_exact_td_mapping_after_external_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_village_compat_pack(COMMON, output)
            for kind in ("plains", "desert", "savanna", "snowy", "taiga"):
                relative = Path(f"compat/td_village_biomes/data/minecraft/tags/worldgen/biome/has_structure/village_{kind}.json")
                generated = json.loads((output / relative).read_text())
                self.assertEqual(generated, json.loads((NEOFORGE / relative).read_text()))
                original = json.loads((COMMON / f"data/minecraft/tags/worldgen/biome/has_structure/village_{kind}.json").read_text())
                self.assertEqual(generated["values"], original["values"])
                self.assertFalse(generated["replace"])
                self.assertTrue(generated["values"])
                for biome in generated["values"]:
                    self.assertFalse(biome.startswith("#"))
                    namespace, path = biome.split(":")
                    self.assertTrue((COMMON / f"data/{namespace}/worldgen/biome/{path}.json").is_file())


if __name__ == "__main__":
    unittest.main()
