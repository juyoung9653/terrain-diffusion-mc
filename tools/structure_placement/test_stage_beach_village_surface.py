"""Pure regression tests for the beach-village staging tool."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from stage_beach_village_surface import STRUCTURE_ID, stage


class BeachVillageStageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / ".tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source.json"
        self.source.write_text(json.dumps({
            "version": 1, "enabled": True, "global": {"minGapBlocks": 100},
            "namespaces": {}, "structures": {"minecraft:village_plains": {"reservationRadiusBlocks": 128}},
            "villageDistribution": {"enabled": True, "basicSharePercent": 70},
        }), encoding="utf-8")

    def test_stages_only_the_exact_surface_override(self) -> None:
        # stage requires a workspace output in production; use its real workspace for this pure test.
        output = Path(__file__).resolve().parents[2] / "build/structure-placement/test-beach-stage"
        if output.exists():
            self.skipTest("staging output already exists")
        receipt = stage(self.source, output)
        self.addCleanup(lambda: __import__("shutil").rmtree(output, ignore_errors=True))
        staged = json.loads((output / "terrain-diffusion-structures.json").read_text(encoding="utf-8"))
        self.assertEqual(staged["structures"][STRUCTURE_ID], {"surfaceMode": "SURFACE"})
        self.assertEqual(staged["structures"]["minecraft:village_plains"], {"reservationRadiusBlocks": 128})
        self.assertEqual(staged["villageDistribution"]["basicSharePercent"], 70)
        self.assertEqual(receipt["changed"]["surfaceMode"]["before"], None)

    def test_refuses_an_existing_non_surface_mode(self) -> None:
        value = json.loads(self.source.read_text(encoding="utf-8"))
        value["structures"][STRUCTURE_ID] = {"surfaceMode": "PRESERVE"}
        self.source.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "not safe"):
            stage(self.source, Path(__file__).resolve().parents[2] / "build/structure-placement/test-beach-refusal")


if __name__ == "__main__":
    unittest.main()
