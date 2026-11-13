"""Regression tests for the Streams suite's aquatic fixture receipt gate."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from run_suite import aquatic_placement_receipt


class AquaticReceiptTest(unittest.TestCase):
    def receipt(self, *rows: dict) -> str:
        scratch = Path(__file__).resolve().parents[2] / ".tmp"
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            path = Path(directory) / "td-streams-probe.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            return aquatic_placement_receipt(path)

    def test_accepts_complete_physical_evidence(self) -> None:
        audit = {"event": "aquatic_placement_audit", "status": "passed",
                 "vanillaHook": True, "seaPickleHook": True,
                 "reefSeaPickleHook": True, "policyGuards": True,
                 "streamsPresent": True, "streamsHook": True}
        fixture = {"event": "aquatic_placement_fixture", "status": "passed"}
        for field in ("acceptedShort", "acceptedTallPair", "rejectedShort",
                      "rejectedTallPair", "acceptedSeaPickle", "rejectedSeaPickle",
                      "acceptedReefSeaPickle", "rejectedReefSeaPickle", "streamsPresent",
                      "streamsAcceptedShort", "streamsAcceptedTallPair",
                      "streamsRejectedShort", "streamsRejectedTallPair"):
            fixture[field] = True
        self.assertEqual(self.receipt(audit, fixture), "success")

    def test_rejects_missing_pickle_write_evidence(self) -> None:
        audit = {"event": "aquatic_placement_audit", "status": "passed",
                 "vanillaHook": True, "seaPickleHook": True,
                 "reefSeaPickleHook": True, "policyGuards": True,
                 "streamsPresent": True, "streamsHook": True}
        fixture = {"event": "aquatic_placement_fixture", "status": "passed",
                   "acceptedShort": True, "acceptedTallPair": True,
                   "rejectedShort": True, "rejectedTallPair": True,
                   "acceptedSeaPickle": True, "rejectedSeaPickle": True,
                   "acceptedReefSeaPickle": True, "rejectedReefSeaPickle": False}
        self.assertEqual(self.receipt(audit, fixture),
                         "aquatic_placement_physical_evidence_missing")


if __name__ == "__main__":
    unittest.main()
