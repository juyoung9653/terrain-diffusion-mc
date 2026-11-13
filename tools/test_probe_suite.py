"""Focused contract checks for the unattended probe runner; no Gradle launch."""

from __future__ import annotations

import json
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from probe_suite import (Job, density_receipt, exclusive_lock, gradle_command,
                         prepared_run, run_suite, streams_receipt, unique_runs)


class ProbeSuiteTest(unittest.TestCase):
    def setUp(self) -> None:
        scratch = Path(__file__).resolve().parents[1] / "build"
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build/structure-density").mkdir(parents=True)

    def make_run(self, name: str) -> Path:
        run = self.root / "build/structure-density" / name
        (run / "world").mkdir(parents=True)
        (run / "server.properties").write_text("level-name=world\n")
        return run

    def job(self, run: Path, payload: str, exit_code: int = 0, delay: float = 0) -> Job:
        script = ("from pathlib import Path; import sys,time; "
                  "time.sleep(float(sys.argv[2])); "
                  "Path(sys.argv[1]).write_text(sys.argv[3]); "
                  "print('streamed child output',flush=True); "
                  "sys.exit(int(sys.argv[4]))")
        receipt = run / "world/density-audit.json"
        return Job(run, [sys.executable, "-c", script, str(receipt), str(delay), payload,
                         str(exit_code)], receipt, density_receipt)

    def test_nested_reparse_point_is_rejected_without_symlink_privileges(self) -> None:
        run = self.make_run("nested")
        region = run / "world" / "region"
        region.mkdir()
        with mock.patch("probe_suite.is_link", side_effect=lambda path: path == region):
            with self.assertRaisesRegex(ValueError, "linked fixture descendant"):
                prepared_run(self.root, "structure-density", str(run), "server.properties")

    def test_streams_littoral_kelp_receipt_requires_zero_shelf_and_deep_evidence(self) -> None:
        from tools.streams_probe.run_suite import vanilla_kelp_receipt
        path = self.make_run("kelp") / "world/td-streams-probe.jsonl"
        target = {"shelfSixAccepted": 0, "transitionSevenAccepted": 2,
                  "deepAccepted": 8, "shelfSixNativeStalks": 0,
                  "transitionSevenNativeStalks": 1, "deepTwelveNativeStalks": 5}
        row = {"event": "vanilla_kelp_audit", "status": "passed",
               "targets": {"minecraft:kelp_cold": target, "minecraft:kelp_warm": dict(target)}}
        path.write_text(json.dumps(row), encoding="utf-8")
        self.assertEqual(vanilla_kelp_receipt(path), "success")
        target["shelfSixAccepted"] = 1
        path.write_text(json.dumps(row), encoding="utf-8")
        self.assertEqual(vanilla_kelp_receipt(path), "vanilla_kelp_audit_littoral_gate_failed")

    def test_streams_submerged_bed_receipt_requires_settled_physical_water(self) -> None:
        from tools.streams_probe.run_suite import submerged_river_bed_receipt
        path = self.make_run("bed") / "world/td-streams-probe.jsonl"
        row = {"event": "submerged_river_bed_audit", "status": "passed",
               "method": "actual_loaded_water_bottom_after_settlement", "settledTicks": 100,
               "fullChunks": 2, "waterBeds": 12, "surfaceWaterBeds": 9, "submergedGrassBeds": 0,
               "dryExposedGrassBlocks": 7, "treeGroundWritesSkipped": 1}
        path.write_text(json.dumps(row), encoding="utf-8")
        self.assertEqual(submerged_river_bed_receipt(path, expected={"94,10", "95,10"}), "success")
        row["submergedGrassBeds"] = 1
        path.write_text(json.dumps(row), encoding="utf-8")
        self.assertEqual(submerged_river_bed_receipt(path, expected={"94,10", "95,10"}),
                         "submerged_river_bed_audit_physical_evidence_failed")
        row["submergedGrassBeds"] = 0
        row["surfaceWaterBeds"] = 0
        path.write_text(json.dumps(row), encoding="utf-8")
        self.assertEqual(submerged_river_bed_receipt(path, expected={"94,10", "95,10"}),
                         "submerged_river_bed_audit_physical_evidence_failed")

    def test_sequential_success_status_and_live_lock(self) -> None:
        one, two = self.make_run("one"), self.make_run("two")
        result: list[int] = []
        worker = threading.Thread(target=lambda: result.append(run_suite(
            self.root, "structure-density", [self.job(one, '{"success":true}', delay=.25),
                                             self.job(two, '{"success":true}')], {}, .02)))
        worker.start()
        status = self.root / "build/structure-density/suite-status.json"
        for _ in range(100):
            if status.exists() and json.loads(status.read_text())["active"]:
                break
            time.sleep(.01)
        else:
            self.fail("active status was not published")
        with self.assertRaisesRegex(RuntimeError, "another probe suite"):
            with exclusive_lock(self.root):
                pass
        worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result, [0])
        published = json.loads(status.read_text())
        self.assertIsNone(published["active"])
        self.assertEqual([row["name"] for row in published["completed"]], ["one", "two"])
        self.assertTrue(all(row["success"] for row in published["completed"]))
        self.assertTrue(published["success"])
        self.assertGreaterEqual(published["elapsedSeconds"], 0)
        self.assertIn("finished", published)
        self.assertIn("streamed child output", (one / "launcher-output.txt").read_text())

    def test_stale_malformed_and_nonzero_are_failures(self) -> None:
        stale, malformed, nonzero = (self.make_run(name) for name in ("stale", "malformed", "nonzero"))
        (stale / "world/density-audit.json").write_text('{"success":true}')
        missing_writer = Job(stale, [sys.executable, "-c", "print('no receipt')"],
                             stale / "world/density-audit.json", density_receipt)
        code = run_suite(self.root, "structure-density", [missing_writer,
                         self.job(malformed, "{bad"), self.job(nonzero, '{"success":true}', 7)], {})
        self.assertEqual(code, 1)
        rows = json.loads((self.root / "build/structure-density/suite-status.json").read_text())["completed"]
        self.assertEqual(rows[0]["probeStatus"], "stale_receipt")
        self.assertTrue(rows[1]["probeStatus"].startswith("invalid_receipt"))
        self.assertEqual(rows[2]["processExit"], 7)
        self.assertTrue(all(not row["success"] for row in rows))

    def test_launch_failure_is_published(self) -> None:
        run = self.make_run("launch")
        receipt = run / "world/density-audit.json"
        job = Job(run, [str(self.root / "no-such-executable")], receipt, density_receipt)
        self.assertEqual(run_suite(self.root, "structure-density", [job], {}), 1)
        row = json.loads((self.root / "build/structure-density/suite-status.json").read_text())["completed"][0]
        self.assertIn("launch_error", row["error"])
        self.assertIsNone(row["processExit"])
        self.assertIn("launch_error", (run / "launcher-output.txt").read_text(encoding="utf-8"))

    def test_success_records_evidence_and_source_change_rejects_it(self):
        from build_evidence import PROBE_RECEIPT, read_validation, source_fingerprint
        source = self.root / "common/src/main/java/Main.java"
        source.parent.mkdir(parents=True)
        source.write_text("before")
        good = self.make_run("evidence")
        self.assertEqual(run_suite(self.root, "structure-density",
                                   [self.job(good, '{"success":true}')], {}), 0)
        row = read_validation(good / PROBE_RECEIPT, source_fingerprint(self.root))
        self.assertEqual(row["result"]["receipt"], str(Path("world/density-audit.json")))
        bad = self.make_run("mutated")
        job = self.job(bad, '{"success":true}')
        script = ("from pathlib import Path; import sys; "
                  "Path(sys.argv[1]).write_text('{\"success\":true}'); "
                  "Path(sys.argv[2]).write_text('after')")
        job = Job(bad, [sys.executable, "-c", script, str(job.receipt), str(source)],
                  job.receipt, density_receipt)
        self.assertEqual(run_suite(self.root, "structure-density", [job], {}), 1)
        rejected = json.loads((bad / PROBE_RECEIPT).read_text())
        self.assertFalse(rejected["success"])
        self.assertEqual(rejected["probeStatus"], "production_sources_changed_during_probe")

    def test_all_preflight_checks_run_under_lock_before_any_child(self) -> None:
        one, two = self.make_run("preflight_one"), self.make_run("preflight_two")
        def fail_preflight():
            with self.assertRaisesRegex(RuntimeError, "another probe suite"):
                with exclusive_lock(self.root):
                    pass
            raise ValueError("bad fixture")
        first = self.job(one, '{"success":true}')
        second = self.job(two, '{"success":true}')
        second = Job(second.run, second.command, second.receipt, second.check_receipt, fail_preflight)
        with self.assertRaisesRegex(ValueError, "bad fixture"):
            run_suite(self.root, "structure-density", [first, second], {})
        self.assertFalse(first.receipt.exists())
        self.assertFalse((one / "launcher-output.txt").exists())

    def test_receipt_callback_failure_is_published_and_suite_continues(self) -> None:
        one, two = self.make_run("callback_bad"), self.make_run("callback_good")
        def bad_receipt(path):
            raise ValueError("missing numeric evidence")
        first = self.job(one, '{"success":true}')
        first = Job(first.run, first.command, first.receipt, bad_receipt)
        self.assertEqual(run_suite(self.root, "structure-density", [first, self.job(two, '{"success":true}')], {}), 1)
        status = json.loads((self.root / "build/structure-density/suite-status.json").read_text())
        self.assertIsNone(status["active"])
        self.assertIn("invalid_receipt:ValueError", status["completed"][0]["probeStatus"])
        self.assertTrue(status["completed"][1]["success"])

    def test_paths_and_receipt_shapes(self) -> None:
        run = self.make_run("safe")
        self.assertEqual(prepared_run(self.root, "structure-density", str(run), "server.properties"), run)
        for value in (str(self.root / "world"), str(run / "world"), str(self.root / "build/structure-density/../safe")):
            with self.assertRaises(ValueError):
                prepared_run(self.root, "structure-density", value, "server.properties")
        with self.assertRaises(ValueError):
            unique_runs([run, run])
        link = self.root / "build/structure-density/link"
        try:
            link.symlink_to(run, target_is_directory=True)
        except (OSError, NotImplementedError):
            pass
        else:
            with self.assertRaises(ValueError):
                prepared_run(self.root, "structure-density", str(link), "server.properties")
        linked_world = self.make_run("linked-world")
        outside = self.root / "outside"
        outside.mkdir()
        (linked_world / "world").rmdir()
        try:
            (linked_world / "world").symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError):
            pass
        else:
            with self.assertRaises(ValueError):
                prepared_run(self.root, "structure-density", str(linked_world), "server.properties")
        receipt = run / "world/td-streams-probe.jsonl"
        receipt.write_text('{"event":"complete","status":"success"}\n')
        self.assertEqual(streams_receipt(receipt), "success")
        receipt.write_text('{"event":"complete","status":"success"}\n{broken')
        self.assertTrue(streams_receipt(receipt).startswith("invalid_receipt"))

    def test_gradle_argument_contract(self) -> None:
        wrapper = self.root / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        wrapper.write_text("stub")
        args = gradle_command(self.root, ["-I", "tools/structure_placement/density.init.gradle",
                                          "-PtdDensityPrefetch=32"], False)
        self.assertIn("--offline", args)
        self.assertNotIn("--daemon", args)
        self.assertEqual(args[-1], "-PtdDensityPrefetch=32")

    def test_child_launch_is_windowless_on_windows(self) -> None:
        run = self.make_run("windowless")
        with mock.patch("probe_suite.subprocess.Popen", wraps=subprocess.Popen) as launch:
            self.assertEqual(run_suite(self.root, "structure-density",
                                       [self.job(run, '{"success":true}')], {}), 0)
        self.assertFalse(launch.call_args.kwargs["shell"])
        self.assertEqual(launch.call_args.kwargs["creationflags"],
                         subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)

    def test_density_cli_options_and_invalid_prefetch(self) -> None:
        run = self.make_run("density")
        (run / "eula.txt").write_text("eula=true\n")
        (self.root / "gradlew.bat").write_text("stub")
        script = Path(__file__).parent / "structure_placement/run_suite.py"
        spec = importlib.util.spec_from_file_location("density_suite_cli", script)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.ROOT = self.root
        with mock.patch.object(module, "run_suite", return_value=0) as launch:
            self.assertEqual(module.main(["--java-home", str(self.root), "--gradle-user-home",
                                          str(self.root), "--prefetch", "32", "--shore-audit",
                                          "--verify-well-support", str(run)]), 0)
        jobs = launch.call_args.args[2]
        self.assertEqual(len(jobs), 1)
        self.assertIn("-PtdDensityPrefetch=32", jobs[0].command)
        self.assertIn("-PtdShoreAudit=true", jobs[0].command)
        self.assertIn("-PtdDensitySupport=true", jobs[0].command)
        self.assertIn("-PtdDensityWellSupport=true", jobs[0].command)
        self.assertIn(f"-PtdStructureProbeDir={run}", jobs[0].command)
        self.assertNotIn("--daemon", jobs[0].command)
        with self.assertRaises(SystemExit) as rejected:
            module.main(["--prefetch", "0", str(run)])
        self.assertEqual(rejected.exception.code, 2)

    def test_requested_checks_need_actual_evidence(self) -> None:
        from tools.structure_placement.run_suite import requested_receipt
        path = self.make_run("requested") / "world/density-audit.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text('{"success":true}')
        self.assertEqual(requested_receipt(path, support=False, well=False, shore=False), "success")
        for flags, expected in [
            ((True, False, False), "missing_support_evidence"),
            ((False, True, False), "missing_well_evidence"),
            ((False, False, True), "missing_shore_evidence"),
        ]:
            self.assertEqual(requested_receipt(path, support=flags[0], well=flags[1], shore=flags[2]), expected)
        for count in [0, -1, True, "1", None]:
            path.write_text(json.dumps({"success": True, "shoreSamples": count,
                                        "support": {"featureSupport": count, "wellFloorBlocks": count}}))
            self.assertNotEqual(requested_receipt(path, support=True, well=True, shore=True), "success")
        path.write_text(json.dumps({"success": True, "shoreSamples": 3,
                                    "support": {"featureSupport": 12, "wellFloorBlocks": 4}}))
        self.assertEqual(requested_receipt(path, support=True, well=True, shore=True),
                         "missing_foundation_repair_evidence")
        path.write_text(json.dumps({"success": True, "shoreSamples": 3,
                                    "support": {"featureSupport": 12, "wellFloorBlocks": 4},
                                    "foundationSupportChecks": {"status": "passed", "restored": 5, "rejected": 6}}))
        self.assertEqual(requested_receipt(path, support=True, well=True, shore=True), "success")
        path.write_text('{"success":false}')
        self.assertEqual(requested_receipt(path, support=True, well=True, shore=True), "not_success")

    def test_tunnel_pass_label_cannot_replace_settlement_and_physical_evidence(self) -> None:
        from tools.streams_probe.run_suite import short_tunnel_receipt
        path, rows = self.tunnel_evidence()

        def status(candidate: list[dict]) -> str:
            path.write_text("\n".join(json.dumps(row) for row in candidate), encoding="utf-8")
            return short_tunnel_receipt(path)

        self.assertEqual(status(rows), "success")
        # All of these were accepted by the old status-only tunnel checker.
        self.assertNotEqual(status([rows[1], rows[2]]), "success")
        self.assertNotEqual(status([rows[1], rows[0], rows[2]]), "success")
        self.assertNotEqual(status([rows[0], rows[0], rows[1], rows[2]]), "success")
        for field, values in {
            "settledTicks": [None, "99", "100.5", True, "NaN"],
            "plansInBounds": [None, "0"],
            "fullChunks": [None, "0"],
            "interiorColumns": [None, "0"],
            "method": [None, "planner_only"],
            "firstGeneration": [None, "unknown"],
            "retainedWrites": [None, "0"],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    self.assertNotEqual(status([rows[0], {**rows[1], field: value}, rows[2]]), "success")
        restart = {**rows[1], "firstGeneration": "false", "retainedWrites": "0"}
        self.assertEqual(status([{**rows[0], "connected": "true"}, restart, rows[2]]), "success")
        # Water may settle into previously dry cells; pre-tick wet count is not
        # a required lower bound on the final connected channel's wet count.
        self.assertEqual(status([{**rows[0], "wetColumns": "0"}, rows[1], rows[2]]), "success")

    def test_tunnel_evidence_must_match_saved_artifacts(self) -> None:
        from tools.streams_probe.run_suite import short_tunnel_receipt
        path, _ = self.tunnel_evidence()
        self.assertEqual(short_tunnel_receipt(path), "success")
        for name in ("td-short-river-tunnel-plan.txt", "td-short-river-tunnel-audit.csv"):
            with self.subTest(artifact=name):
                artifact = path.parent / name
                original = artifact.read_bytes()
                artifact.write_bytes(original + b"stale or changed\n")
                self.assertEqual(short_tunnel_receipt(path), "short_river_tunnel_artifact_mismatch")
                artifact.unlink()
                self.assertEqual(short_tunnel_receipt(path), "short_river_tunnel_missing_artifact")
                artifact.write_bytes(original)
        # Java's CSV writer may use Windows newlines; the receipt hashes joined rows.
        csv = path.parent / "td-short-river-tunnel-audit.csv"
        csv.write_bytes(csv.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(short_tunnel_receipt(path), "success")

    def tunnel_evidence(self) -> tuple[Path, list[dict]]:
        path = self.make_run("tunnel") / "world/td-streams-probe.jsonl"
        plan = "40.0\n10.0,30.0\n0.0,0.0,80.0,2.0\n40.0,0.0,78.0,2.0\n"
        samples = "kind,x,z,wet\nmask_core,20,0,true\n"
        (path.parent / "td-short-river-tunnel-plan.txt").write_bytes(plan.encode())
        (path.parent / "td-short-river-tunnel-audit.csv").write_bytes(samples.encode())
        rows = [
            {"event": "short_river_tunnel_pre_ticks", "protectedColumns": "10",
             "wetColumns": "4", "connected": "false"},
            {"event": "short_river_tunnel_audit", "status": "passed", "settledTicks": "100",
             "plansInBounds": "1", "fullChunks": "4", "interiorColumns": "2",
             "firstGeneration": "true", "retainedWrites": "20",
             "method": "actual_full_chunks_full_mask_connected_water_roof_and_portals",
             "planHash": hashlib.sha256(plan.encode()).hexdigest(),
             "physicalSampleHash": hashlib.sha256(samples.rstrip("\n").encode()).hexdigest()},
            {"event": "complete", "status": "success"},
        ]
        path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
        return path, rows

    def test_replay_receipt_requires_identity_hits_and_no_failure(self) -> None:
        from tools.structure_placement.run_suite import requested_receipt
        path = self.make_run("replay") / "world/density-audit.json"
        path.parent.mkdir(exist_ok=True)
        for mode, counter in (("record", "recordedTiles"), ("replay", "replayedTiles")):
            row = {"success": True, "terrainMode": mode, "terrainReplay": {
                "mode": mode, "snapshotId": "a" * 64, "misses": 0,
                counter: 3, "failure": None}}
            def status() -> str:
                path.write_text(json.dumps(row))
                return requested_receipt(path, support=False, well=False, shore=False,
                                         terrain_mode=mode, snapshot_id="a" * 64)
            self.assertEqual(status(), "success")
            for field, bad in (("snapshotId", "b" * 64), ("mode", "full"),
                               ("misses", 1), ("misses", False), ("failure", "corrupt"),
                               (counter, 0), (counter, True)):
                before = row["terrainReplay"][field]
                row["terrainReplay"][field] = bad
                self.assertNotEqual(status(), "success")
                row["terrainReplay"][field] = before

    def test_replay_cli_modes_and_seal_only_after_success(self) -> None:
        from tools.structure_placement import run_suite as module
        run = self.make_run("record")
        (run / "eula.txt").write_text("eula=true\n")
        (self.root / "gradlew.bat").write_text("stub")
        snapshot = self.root / "build/terrain-replay/base"
        args = ["--java-home", str(self.root), "--gradle-user-home", str(self.root)]
        with mock.patch.object(module, "ROOT", self.root), \
             mock.patch.object(module, "prepare_snapshot", return_value="a" * 64), \
             mock.patch.object(module, "seal_snapshot") as seal, \
             mock.patch.object(module, "run_suite", return_value=1) as launch:
            self.assertEqual(module.main(args + ["--record-terrain", str(snapshot), str(run)]), 1)
            seal.assert_not_called()
            launch.return_value = 0
            self.assertEqual(module.main(args + ["--record-terrain", str(snapshot), str(run)]), 0)
            seal.assert_called_once_with(snapshot, run / "world/density-audit.json")
            self.assertIn("-PtdTerrainMode=record", launch.call_args.args[2][0].command)
            seal.reset_mock()
            self.assertEqual(module.main(args + ["--replay-terrain", str(snapshot), str(run)]), 0)
            seal.assert_not_called()
            self.assertIn("-PtdTerrainMode=replay", launch.call_args.args[2][0].command)
            launch.reset_mock()
            with self.assertRaises(SystemExit):
                module.main(["--shore-audit", "--replay-terrain", str(snapshot), str(run)])
            launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
