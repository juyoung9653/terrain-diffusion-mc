"""Receipt contract for the fixed seed-7756831701670037456 river screenshots."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("streams_run_suite", ROOT / "tools/streams_probe/run_suite.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)


def receipt() -> dict:
    support = {"x": -1009, "z": 973, "inChannel": True, "source": False, "terminal": False,
               "planWaterY": 71, "planBedY": 70, "actualWaterSurfaceY": 71, "actualBedY": 70,
               "solidBed": True, "oneBlockPlanned": True, "actualMatchesPlan": True, "passed": True}
    return {"event": "river_regression_audit", "status": "passed", "phase": "post_native_water_settlement",
            "settledTicks": 100, "seed": 7756831701670037456, "scale": 1, "naturalRiverProfile": 14,
            "coveredChunks": ["-64,60", "-70,72"],
            "bank": {"x": -1009, "z": 971, "originalGroundY": 71, "capY": 72, "planWaterY": 71,
                     "desertBiome": True, "originalGroundContainsPlanWater": True, "sandSurface": True,
                     "noSolidCap": True, "noFern": True, "sourceBiome": "terrain-diffusion-mc:ecology/desert",
                     "savedBiome": "minecraft:river", "ecologyBiomeId": 11, "desertEcologyId": 11, "passed": True},
            "bankChannelSupports": [support, support | {"z": 974}],
            "shallowBed": {"x": -1106, "z": 1156, "inChannel": True, "source": False, "terminal": False,
                           "planWaterY": 91, "planBedY": 90, "actualWaterSurfaceY": 91, "actualBedY": 90,
                           "solidBed": True, "oneBlockPlanned": True, "actualMatchesPlan": True, "passed": True}}


class RiverRegressionReceiptTest(unittest.TestCase):
    expected = {"-64,60", "-70,72"}

    def check(self, row: dict, expected=None) -> str:
        path = ROOT / "build/river-regression-receipt-tests" / (uuid.uuid4().hex + ".jsonl")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(row) + "\n", encoding="utf-8")
        return MODULE.river_regression_receipt(path, expected=self.expected if expected is None else expected)

    def test_post_settlement_receipt_passes(self):
        self.assertEqual("success", self.check(receipt()))

    def test_pre_settlement_receipt_is_rejected(self):
        self.assertEqual("river_regression_audit_not_post_settlement_fixture",
                         self.check(receipt() | {"settledTicks": 99}))

    def test_wrong_seed_is_rejected(self):
        self.assertEqual("river_regression_audit_not_post_settlement_fixture",
                         self.check(receipt() | {"seed": 1}))

    def test_missing_target_chunk_coverage_is_rejected(self):
        self.assertEqual("river_regression_audit_coverage_missing",
                         self.check(receipt() | {"coveredChunks": ["-64,60"]}))

    def test_false_shallow_bed_is_rejected(self):
        bad = receipt()
        bad["shallowBed"] = bad["shallowBed"] | {"actualBedY": 89, "actualMatchesPlan": False, "passed": False}
        self.assertEqual("river_regression_shallow_bed_not_proven", self.check(bad))

    def test_saved_river_overlay_does_not_replace_source_ecology_evidence(self):
        bad = receipt()
        bad["bank"] = bad["bank"] | {"sourceBiome": "minecraft:river"}
        self.assertEqual("river_regression_bank_not_proven", self.check(bad))


if __name__ == "__main__":
    unittest.main()
