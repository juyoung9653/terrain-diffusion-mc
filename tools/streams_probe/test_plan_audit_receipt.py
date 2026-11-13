"""Receipt contract for the native Streams plan-audit artifacts."""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import time
import unittest

from run_suite import (greeneries_density_receipt, natural_network_policy_receipt, network_audit_receipt, plan_audit_receipt,
                       streams_profile_receipt, validate_neoforge_version)


class PlanAuditReceiptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "td-streams-probe.jsonl"
        self.started_ns = time.time_ns()
        self.chunk = "-12,4"
        self.csv = self.path.parent / "td-streams-probe-plan-audit--12-4.csv"
        header = ("x,z,naturalPreCarveY,actualSurfaceY,actualGroundY,actualTopWaterY,"
                  "naturalBiome,inChannel,planWaterSurfaceY,planWidth,planDistance,planSlope,"
                  "planBankBlend,naturalGroundMinusPlanWater,actualWaterMinusPlanWater")
        row = ",".join("value" for _ in range(15))
        self.csv.write_text(header + "\n" + "\n".join(row for _ in range(256)) + "\n", encoding="utf-8")
        os.utime(self.csv, ns=(self.started_ns + 1_000_000, self.started_ns + 1_000_000))
        self.rows = [
            {"event": "chunk", "chunk": self.chunk,
             "planAuditStatus": "engine_plan_after_full_chunk",
             "planAuditCsv": self.csv.name},
            {"event": "complete", "status": "success"},
        ]

    def check(self) -> str:
        self.path.write_text("\n".join(json.dumps(row) for row in self.rows), encoding="utf-8")
        return plan_audit_receipt(self.path, expected={self.chunk}, started_ns=self.started_ns)

    def test_complete_fresh_256_column_audit_passes(self) -> None:
        self.assertEqual("success", self.check())

    def test_engine_absent_status_fails(self) -> None:
        self.rows[0]["planAuditStatus"] = "engine_absent_after_full_chunk"
        self.assertEqual("plan_audit_status_missing_or_failed", self.check())

    def test_header_only_csv_fails(self) -> None:
        self.csv.write_text(self.csv.read_text(encoding="utf-8").splitlines()[0] + "\n", encoding="utf-8")
        os.utime(self.csv, ns=(self.started_ns + 1_000_000, self.started_ns + 1_000_000))
        self.assertEqual("plan_audit_csv_not_256_rows", self.check())

    def test_shifted_csv_row_fails(self) -> None:
        lines = self.csv.read_text(encoding="utf-8").splitlines()
        lines[1] += ",shifted"
        self.csv.write_text("\n".join(lines) + "\n", encoding="utf-8")
        os.utime(self.csv, ns=(self.started_ns + 1_000_000, self.started_ns + 1_000_000))
        self.assertEqual("plan_audit_csv_bad_columns", self.check())

    def test_stale_csv_fails(self) -> None:
        os.utime(self.csv, ns=(self.started_ns - 1_000_000, self.started_ns - 1_000_000))
        self.assertEqual("plan_audit_csv_stale", self.check())

    def test_csv_must_belong_to_its_chunk(self) -> None:
        self.rows[0]["planAuditCsv"] = "td-streams-probe-plan-audit-0-0.csv"
        self.assertEqual("plan_audit_csv_name_mismatch", self.check())

    def test_neoforge_manifest_must_match_runner(self) -> None:
        (self.path.parent / "input-manifest.json").write_text(
            json.dumps({"neoforge": "21.1.264"}), encoding="utf-8")
        validate_neoforge_version(self.path.parent, "21.1.264")
        with self.assertRaisesRegex(ValueError, "requires NeoForge"):
            validate_neoforge_version(self.path.parent, "21.1.249")

    def test_requested_v14_profile_accepts_native_receipt_strings(self) -> None:
        self.path.write_text(json.dumps({"event": "streams_profile", "status": "passed",
                                         "enabled": "true", "version": "14",
                                         "exactTerrain": "true"}), encoding="utf-8")
        self.assertEqual("success", streams_profile_receipt(self.path, natural_rivers="true"))

    def test_network_audit_requires_fresh_csv_and_profile(self) -> None:
        self.path.write_text(json.dumps({"event": "streams_profile", "status": "passed",
                                         "enabled": "false", "version": "0",
                                         "exactTerrain": "true"}), encoding="utf-8")
        for name, content in (("td-natural-river-network.csv", "x1,z1,y1,w1,x2,z2,y2,w2\n"),
                              ("td-natural-river-profile.txt", "enabled=false\n")):
            artifact = self.path.parent / name
            artifact.write_text(content, encoding="utf-8")
            os.utime(artifact, ns=(self.started_ns + 1_000_000, self.started_ns + 1_000_000))
        self.assertEqual("success", network_audit_receipt(self.path, started_ns=self.started_ns,
                                                            natural_rivers="false"))
        (self.path.parent / "td-natural-river-network.csv").unlink()
        self.assertEqual("network_audit_artifact_missing",
                         network_audit_receipt(self.path, started_ns=self.started_ns,
                                               natural_rivers="false"))

    def network_policy(self, **values: object) -> str:
        row = {"event": "natural_network_policy", "status": "observed",
               "invocations": "3", "completed": "2", "failed": "0"}
        row.update(values)
        self.path.write_text(json.dumps(row), encoding="utf-8")
        return natural_network_policy_receipt(self.path)

    def test_network_policy_accepts_completed_positive_prefetch(self) -> None:
        self.assertEqual("success", self.network_policy())

    def test_network_policy_rejects_missing_hook(self) -> None:
        self.path.write_text(json.dumps({"event": "complete", "status": "success"}), encoding="utf-8")
        self.assertEqual("natural_network_policy_missing", natural_network_policy_receipt(self.path))

    def test_network_policy_rejects_zero_completed(self) -> None:
        self.assertEqual("natural_network_policy_not_executed_or_failed", self.network_policy(completed="0"))

    def test_network_policy_rejects_failed_hook(self) -> None:
        self.assertEqual("natural_network_policy_not_executed_or_failed", self.network_policy(failed="1"))

    def test_network_policy_rejects_malformed_counts(self) -> None:
        self.assertEqual("natural_network_policy_invalid", self.network_policy(invocations="many"))

    def test_greeneries_density_fixture_passes_only_when_passed(self) -> None:
        self.path.write_text(json.dumps({"event": "greeneries_density_fixture", "status": "passed"}), encoding="utf-8")
        self.assertEqual("success", greeneries_density_receipt(self.path))
        self.path.write_text(json.dumps({"event": "greeneries_density_fixture", "status": "failed"}), encoding="utf-8")
        self.assertEqual("greeneries_density_fixture_failed", greeneries_density_receipt(self.path))

    def test_greeneries_density_fixture_missing_fails(self) -> None:
        self.path.write_text(json.dumps({"event": "complete", "status": "success"}), encoding="utf-8")
        self.assertEqual("greeneries_density_fixture_missing", greeneries_density_receipt(self.path))


if __name__ == "__main__":
    unittest.main()
