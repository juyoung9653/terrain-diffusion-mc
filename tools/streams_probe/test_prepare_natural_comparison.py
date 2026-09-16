"""Pure fixture check for prepare-natural-comparison.py; no game or source world writes."""
from __future__ import annotations

import gzip
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("prepare-natural-comparison.py")
SPEC = importlib.util.spec_from_file_location("natural_comparison", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
HELPER_SPEC = importlib.util.spec_from_file_location("showcase_helpers_for_test", Path(__file__).parents[1] / "ecology/prepare_showcases.py")
HELPERS = importlib.util.module_from_spec(HELPER_SPEC)
assert HELPER_SPEC.loader is not None
HELPER_SPEC.loader.exec_module(HELPERS)


def string(value: str) -> bytes:
    data = value.encode("utf-8")
    return len(data).to_bytes(2, "big") + data


def named(kind: int, name: str, payload: bytes) -> bytes:
    return bytes([kind]) + string(name) + payload


def compound(name: str, children: bytes) -> bytes:
    return named(10, name, children + b"\x00")


def level_nbt(initialized: int) -> bytes:
    biome_source = compound("biome_source", named(8, "type", string("terrain-diffusion-mc:terrain_diffusion")))
    generator = compound("generator", biome_source)
    overworld = compound("minecraft:overworld", generator)
    dimensions = compound("dimensions", overworld)
    settings = compound("WorldGenSettings", dimensions)
    data = compound("Data", named(1, "initialized", bytes([initialized])) + settings)
    return b"\x0a\x00\x00" + data + b"\x00"


class PrepareNaturalComparisonTest(unittest.TestCase):
    def test_stages_uninitialized_controls_without_mutating_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            original = gzip.compress(level_nbt(1))
            (source / "level.dat").write_bytes(original)
            native = source / "streamsreflowing" / "minecraft_overworld"
            native.mkdir(parents=True)
            (native / "network.srn").write_bytes(b"native-state")
            output = root / "comparison"
            MODULE.stage(source, output)
            self.assertEqual((source / "level.dat").read_bytes(), original)
            self.assertEqual((native / "network.srn").read_bytes(), b"native-state")
            for name in ("baseline", "natural"):
                fields = HELPERS.nbt_fields(gzip.decompress((output / name / "level.dat").read_bytes()))
                self.assertEqual(fields[("Data", "initialized")][3], 0)
                self.assertFalse((output / name / "streamsreflowing").exists())
            self.assertTrue((output / "comparison.json").is_file())


if __name__ == "__main__":
    unittest.main()
