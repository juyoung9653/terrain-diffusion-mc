"""A successful JVM/terminal receipt cannot substitute for appearance evidence."""
import json
import os
from pathlib import Path
import tempfile
import time
import unittest

from tools.streams_probe.run_water_appearance_suite import receipt


class AppearanceReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "td-streams-probe.jsonl"
        self.csv = self.path.parent / "td-river-bed-appearance.csv"
        self.started = time.time_ns()
        self.csv.write_text("x,z\n-62,1696\n")
        os.utime(self.csv, ns=(self.started + 1_000_000, self.started + 1_000_000))
        target = {"nativePlantBlocks": 12, "fixedCandidates": 64, "shallowAccepted": 4,
                  "deepAccepted": 40, "oneBlockNativeStalks": 0,
                  "shallowNativeStalks": 4, "deepNativeStalks": 16, "dryRejected": True,
                  "excludedBiomeRejected": True, "customChainRejected": True}
        self.rows = [
            {"event": "vanilla_kelp_audit", "status": "passed", "targets": {
                "minecraft:kelp_cold": dict(target), "minecraft:kelp_warm": dict(target)}},
            {"event": "river_bed_appearance_audit", "status": "passed", "fullChunks": 25,
             "waterColumns": 20, "supportedWaterColumns": 20, "isolatedOneCellDips": 0},
            {"event": "appearance_water_settled", "gameTicks": 100, "tickableChunks": 25},
            {"event": "complete", "status": "success"}]

    def check(self, smooth=True):
        self.path.write_text("\n".join(json.dumps(row) for row in self.rows), encoding="utf-8")
        return receipt(self.path, smooth=smooth, kelp=True, started_ns=self.started)

    def test_complete_evidence(self):
        self.assertEqual("success", self.check())

    def test_terminal_success_alone_is_insufficient(self):
        self.rows = self.rows[-1:]
        self.assertNotEqual("success", self.check())

    def test_baseline_pockets_cannot_pass_fixed_acceptance(self):
        self.rows[1]["isolatedOneCellDips"] = 7
        self.assertEqual("success", self.check(smooth=False))
        self.assertNotEqual("success", self.check())

    def test_no_water_is_not_a_pass(self):
        self.rows[1]["waterColumns"] = 0
        self.assertNotEqual("success", self.check())

    def test_both_native_kelp_features_and_guards_are_required(self):
        del self.rows[0]["targets"]["minecraft:kelp_warm"]["customChainRejected"]
        self.assertNotEqual("success", self.check())

    def test_shallow_kelp_thinning_is_required(self):
        self.rows[0]["targets"]["minecraft:kelp_warm"]["shallowAccepted"] = 10
        self.rows[0]["targets"]["minecraft:kelp_warm"]["deepAccepted"] = 40
        self.assertNotEqual("success", self.check())

    def test_native_shallow_kelp_gate_is_required(self):
        self.rows[0]["targets"]["minecraft:kelp_cold"]["shallowNativeStalks"] = 8
        self.rows[0]["targets"]["minecraft:kelp_cold"]["deepNativeStalks"] = 16
        self.assertNotEqual("success", self.check())

    def test_stale_csv_cannot_pass(self):
        os.utime(self.csv, ns=(self.started - 2_000_000_000, self.started - 2_000_000_000))
        self.assertNotEqual("success", self.check())

    def test_duplicate_river_evidence_is_rejected(self):
        self.rows.insert(1, self.rows[1])
        self.assertNotEqual("success", self.check())

    def test_unsettled_water_is_rejected(self):
        self.rows[2]["gameTicks"] = 99
        self.assertNotEqual("success", self.check())

    def test_native_string_counters_are_accepted(self):
        self.rows[2].update(gameTicks="100", tickableChunks="25")
        self.assertEqual("success", self.check())
        self.rows.insert(2, {"event": "appearance_ocean_generated", "fullChunks": "72"})
        self.check()
        self.assertEqual("success", receipt(self.path, smooth=True, kelp=True, ocean=True))

    def test_invalid_counter_is_rejected(self):
        self.rows[2]["gameTicks"] = "invalid"
        self.assertNotEqual("success", self.check())

    def test_requested_ocean_evidence_is_required(self):
        self.check()
        self.assertNotEqual("success", receipt(self.path, smooth=True, kelp=True, ocean=True))
        self.rows.insert(2, {"event": "appearance_ocean_generated", "fullChunks": 72})
        self.check()
        self.assertEqual("success", receipt(self.path, smooth=True, kelp=True, ocean=True))


if __name__ == "__main__":
    unittest.main()
