"""Prevent costly fixture/coordinate mistakes without starting Minecraft."""
from __future__ import annotations

import hashlib
import csv
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from streams_probe.preflight import select_chunks, validate_fixture
from streams_probe import run_suite as cli


class PreflightTest(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[2] / "build"
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "build/streams-probe/test"
        (self.run / "world").mkdir(parents=True)
        (self.run / "mods").mkdir()
        (self.run / "config").mkdir()
        (self.run / "server.properties").write_text("level-name=world\n")
        self.manifest = {"mods": [], "configs": {}, "neoforge": "21.1.249"}
        self.write_manifest()
        guard = mock.patch.object(cli, "validate_tree_ground_runtime", return_value={"calls": 1})
        self.runtime_guard = guard.start()
        self.addCleanup(guard.stop)

    def write_manifest(self):
        (self.run / "input-manifest.json").write_text(json.dumps(self.manifest))

    def test_negative_blocks_and_requested_order(self):
        self.assertEqual(select_chunks(None, "-1713,-1745;-1728,-1760;-1,-16;0,16", 4),
                         "-108,-110;-1,-1;0,1")
        self.assertEqual(select_chunks("0,1;-108,-110", "-1713,-1745", 4), "0,1;-108,-110")
        for chunks, sites, maximum in (("-107,-110", "-1713,-1745", 4),
                                       ("0,0;0,0", None, 4), (None, "0,0;16,0", 1),
                                       (None, "1.5,2", 4)):
            with self.subTest(chunks=chunks, sites=sites):
                with self.assertRaises(ValueError): select_chunks(chunks, sites, maximum)

    def test_layout_and_expected_cache(self):
        self.assertEqual(validate_fixture(self.run, "fresh")["routingEvidence"], "fresh")
        for name in ("data", "streamsreflowing"):
            misplaced = self.run / name
            misplaced.mkdir()
            with self.assertRaisesRegex(ValueError, "misplaced"): validate_fixture(self.run)
            misplaced.rmdir()
        with self.assertRaisesRegex(ValueError, "expected a saved"): validate_fixture(self.run, "present")
        cache = self.run / "world/streamsreflowing"
        cache.mkdir()
        with self.assertRaisesRegex(ValueError, "no graph files"): validate_fixture(self.run)
        (cache / "tile.srt").write_bytes(b"graph")
        self.assertEqual(validate_fixture(self.run, "present")["cacheFiles"], 1)
        with self.assertRaisesRegex(ValueError, "fresh routing"): validate_fixture(self.run, "fresh")

    def test_prepared_fixture_contract_checks_copied_inputs_before_launch(self):
        mod = self.run / "mods/streams.jar"
        mod.write_bytes(b"mod")
        server_hash = hashlib.sha256((self.run / "server.properties").read_bytes()).hexdigest()
        self.manifest.update({"preparedFreshChunks": True, "riverCachePolicy": "fresh",
                              "sha256": {"server.properties": server_hash,
                                         "mods/streams.jar": hashlib.sha256(b"mod").hexdigest()}})
        self.write_manifest()
        self.assertEqual(validate_fixture(self.run)["routingEvidence"], "fresh")
        with self.assertRaisesRegex(ValueError, "disagrees"):
            validate_fixture(self.run, "present")
        mod.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            validate_fixture(self.run)
        mod.write_bytes(b"mod")
        cache = self.run / "world/streamsreflowing"
        cache.mkdir()
        (cache / "tile.srt").write_bytes(b"graph")
        with self.assertRaisesRegex(ValueError, "fresh routing"):
            validate_fixture(self.run)
        (cache / "tile.srt").unlink()
        cache.rmdir()
        (self.run / "world/region").mkdir()
        (self.run / "world/level.dat").write_bytes(b"initialized")
        cache.mkdir()
        (cache / "tile.srt").write_bytes(b"graph")
        self.assertEqual(validate_fixture(self.run, "present")["effectiveCachePolicy"], "present")
        self.assertIsNone(validate_fixture(self.run)["effectiveCachePolicy"])
        mod.write_bytes(b"changed-again")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            validate_fixture(self.run)

    def test_declared_inputs_and_copied_state_must_match(self):
        mod = self.run / "mods/streams.jar"
        mod.write_bytes(b"mod")
        self.manifest["mods"] = [{"file": mod.name, "sha256": hashlib.sha256(b"mod").hexdigest()}]
        self.write_manifest()
        validate_fixture(self.run)
        mod.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"): validate_fixture(self.run)
        mod.write_bytes(b"mod")
        self.manifest["playerCacheDiagnostic"] = {"copiedFiles": {
            "data/terrain_diffusion_world_settings.dat": hashlib.sha256(b"marker").hexdigest(),
            "streamsreflowing": {"tile.srt": hashlib.sha256(b"graph").hexdigest()}}}
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "missing"): validate_fixture(self.run)
        (self.run / "world/data").mkdir()
        (self.run / "world/data/terrain_diffusion_world_settings.dat").write_bytes(b"marker")
        (self.run / "world/streamsreflowing").mkdir()
        (self.run / "world/streamsreflowing/tile.srt").write_bytes(b"graph")
        validate_fixture(self.run)
        (self.run / "world/streamsreflowing/extra.srt").write_bytes(b"other")
        with self.assertRaisesRegex(ValueError, "inventory mismatch"): validate_fixture(self.run)

    def test_unrun_chunks_and_noncanonical_world_are_rejected(self):
        (self.run / "world/region").mkdir()
        with self.assertRaisesRegex(ValueError, "generated chunk"): validate_fixture(self.run)
        (self.run / "world/region").rmdir()
        (self.run / "server.properties").write_text("level-name=other\n")
        with self.assertRaisesRegex(ValueError, "level-name"): validate_fixture(self.run)

    def test_legacy_generated_world_keeps_restart_support(self):
        (self.run / "world/level.dat").write_bytes(b"existing")
        (self.run / "world/region").mkdir()
        (self.run / "world/streamsreflowing").mkdir()
        (self.run / "world/streamsreflowing/graph.srt").write_bytes(b"graph")
        self.assertEqual(validate_fixture(self.run, "present")["routingEvidence"], "saved")
        self.assertEqual(validate_fixture(self.run)["routingEvidence"], "saved")
        with self.assertRaisesRegex(ValueError, "fresh routing"):
            validate_fixture(self.run, "fresh")

    def test_automatic_fresh_policy_still_requires_fresh_network_evidence(self):
        self.manifest["riverCachePolicy"] = "fresh"
        self.write_manifest()
        args = SimpleNamespace(chunks="0,0", expect_river_cache=None, surface_audit=False,
                               verify_restart=False, natural_rivers="true")
        prepare, check = cli.surface_checks(self.run, args, cli.streams_receipt)
        prepare()
        receipt = self.run / "world/td-streams-probe.jsonl"
        receipt.write_text('{"event":"complete","status":"success"}\n')
        with (mock.patch.object(cli, "streams_profile_receipt", return_value="success"),
              mock.patch.object(cli, "natural_network_policy_receipt", return_value="missing-network") as network):
            self.assertEqual(check(receipt), "missing-network")
            network.assert_called_once_with(receipt)

    def test_config_migration_only_allowed_for_initialized_world(self):
        (self.run / "config/streams.properties").write_bytes(b"original")
        self.manifest["configs"] = {"streams.properties": hashlib.sha256(b"original").hexdigest()}
        self.write_manifest()
        (self.run / "config/streams.properties").write_bytes(b"migrated")
        with self.assertRaisesRegex(ValueError, "hash mismatch"): validate_fixture(self.run)
        (self.run / "world/level.dat").write_bytes(b"initialized")
        validate_fixture(self.run)
        with self.assertRaisesRegex(ValueError, "fresh routing"): validate_fixture(self.run, "fresh")

    def test_cli_checks_before_launch_and_preflight_only(self):
        base = ["test", "--gradle-user-home", str(self.root)]
        with mock.patch.object(cli, "ROOT", self.root), mock.patch.object(cli, "run_suite") as launch:
            with self.assertRaises(SystemExit):
                cli.main(base + ["--chunks=-107,-110", "--block-sites=-1713,-1745"])
            launch.assert_not_called()
            self.assertEqual(cli.main(base + ["--preflight-only", "--block-sites=-1713,-1745"]), 0)
            launch.assert_not_called()

    def test_runtime_mismatch_blocks_real_suite_before_child_launch(self):
        # Exercise the real suite/Job.prepare connection, not only the checker.
        self.runtime_guard.side_effect = ValueError("runtime target has 0 Context.setBlock calls")
        with (mock.patch.object(cli, "ROOT", self.root),
              mock.patch.object(cli, "gradle_command", return_value=["unused-gradle"]),
              mock.patch("probe_suite.subprocess.Popen") as child):
            with self.assertRaises(SystemExit) as failure:
                cli.main(["test", "--gradle-user-home", str(self.root)])
            self.assertEqual(failure.exception.code, 2)
            child.assert_not_called()
        self.assertEqual(self.runtime_guard.call_args.args[0],
                         self.root / "neoforge/build/moddev/artifacts/neoforge-21.1.249-merged.jar")

    def test_declared_cache_mismatch_blocks_real_suite_before_child_launch(self):
        self.manifest.update({"preparedFreshChunks": True, "riverCachePolicy": "fresh",
                              "sha256": {"server.properties": hashlib.sha256(
                                  (self.run / "server.properties").read_bytes()).hexdigest()}})
        self.write_manifest()
        cache = self.run / "world/streamsreflowing"
        cache.mkdir()
        (cache / "tile.srt").write_bytes(b"graph")
        with (mock.patch.object(cli, "ROOT", self.root),
              mock.patch.object(cli, "gradle_command", return_value=["unused-gradle"]),
              mock.patch("probe_suite.subprocess.Popen") as child):
            with self.assertRaises(SystemExit) as failure:
                cli.main(["test", "--gradle-user-home", str(self.root)])
            self.assertEqual(failure.exception.code, 2)
            child.assert_not_called()

    def test_cold_audit_arguments_and_fresh_receipt_evidence(self):
        base = ["test", "--gradle-user-home", str(self.root)]
        with (mock.patch.object(cli, "ROOT", self.root),
              mock.patch.object(cli, "run_suite") as launch,
              mock.patch.object(cli, "gradle_command",
                                side_effect=lambda root, command, daemon, **kwargs: command) as gradle):
            with self.assertRaises(SystemExit):
                cli.main(base + ["--preflight-only", "--cold-biome-spatial-audit"])
            with self.assertRaises(SystemExit):
                cli.main(base + ["--preflight-only", "--cold-biome-spatial-audit",
                                 "--cold-center=0,0", "--cold-step=17"])
            with self.assertRaises(SystemExit):
                cli.main(base + ["--preflight-only", "--bank-write-trace=1,2;3,4"])
            with self.assertRaises(SystemExit):
                cli.main(base + ["--preflight-only", "--bank-write-trace=1,2",
                                 "--bank-trace-min-y=161", "--bank-trace-max-y=160"])
            self.assertEqual(cli.main(base + ["--preflight-only", "--cold-weather-audit",
                                               "--cold-biome-spatial-audit", "--cold-center=-16,32",
                                               "--cold-tiles=1", "--cold-step=4"]), 0)
            launch.assert_not_called()
            launch.return_value = 0
            self.assertEqual(cli.main(base + ["--cold-weather-audit",
                                               "--cold-biome-spatial-audit", "--cold-center=-16,32",
                                               "--cold-tiles=1", "--cold-step=4",
                                               "--bank-write-trace=1280,-1441",
                                               "--bank-trace-min-y=145", "--bank-trace-max-y=156",
                                               "--plan-audit", "--settle-water", "--chunks=0,0"]), 0)
            command = launch.call_args.args[2][0].command
            self.assertIn("-PtdProbe.coldWeatherAudit=true", command)
            self.assertIn("-PtdProbe.coldBiomeSpatialAudit=true", command)
            self.assertIn("-PtdProbe.coldX=-16", command)
            self.assertIn("-PtdProbe.coldZ=32", command)
            self.assertIn("-PtdProbe.coldTiles=1", command)
            self.assertIn("-PtdProbe.coldStep=4", command)
            self.assertIn(f"-PtdProbe.coldOutput={self.run / 'world/td-cold-biomes.csv'}",
                          command)
            self.assertIn("-PtdProbe.bankWriteTrace=true", command)
            self.assertIn("-PtdProbe.bankTraceX=1280", command)
            self.assertIn("-PtdProbe.bankTraceZ=-1441", command)
            self.assertIn("-PtdProbe.bankTraceMinY=145", command)
            self.assertIn("-PtdProbe.bankTraceMaxY=156", command)
            self.assertIn("-PtdProbe.planAudit=true", command)
            self.assertIn("-PtdProbe.settleWater=true", command)
            self.assertEqual(gradle.call_args.kwargs["neoforge_version"], "21.1.249")
        receipt = self.run / "world/td-streams-probe.jsonl"
        receipt.write_text('{"event":"complete","status":"success"}\n'
                           '{"event":"cold_weather_audit","passed":true,"status":"passed"}\n'
                           '{"event":"cold_biome_spatial_audit","status":"passed","samples":1,"csv":"td-cold-biomes.csv"}\n')
        started = time.time_ns()
        self.assertEqual(cli.cold_audit_receipt(receipt, weather=True, spatial=True,
                                                started_ns=started),
                         "cold_biome_spatial_artifact_missing_or_stale")
        artifact = self.run / "world/td-cold-biomes.csv"
        artifact.write_text("x,z\n0,0\n")
        future = started + 2_000_000_000
        os.utime(artifact, ns=(future, future))
        self.assertEqual(cli.cold_audit_receipt(receipt, weather=True, spatial=True,
                                                started_ns=started), "success")
        receipt.write_text('{"event":"complete","status":"success"}\n'
                           '{"event":"cold_weather_audit","loaded":false}\n'
                           '{"event":"cold_biome_spatial_audit","status":"passed","samples":1,"csv":"td-cold-biomes.csv"}\n')
        self.assertEqual(cli.cold_audit_receipt(receipt, weather=True, spatial=True,
                                                started_ns=started),
                         "cold_weather_audit_not_loaded_or_failed")

    def test_native_water_settlement_receipt_requires_all_selected_chunks(self):
        receipt = self.run / "world/td-streams-probe.jsonl"
        receipt.write_text('{"event":"complete","status":"success"}\n')
        self.assertEqual(cli.native_water_settlement_receipt(receipt, expected={"0,0"}),
                         "native_water_settlement_missing")
        receipt.write_text(receipt.read_text() +
                           '{"event":"native_water_settled","status":"passed","startTick":"10","endTick":"110","tickableChunks":"1","selectedChunks":"1"}\n')
        self.assertEqual(cli.native_water_settlement_receipt(receipt, expected={"0,0"}),
                         "success")
        receipt.write_text(receipt.read_text().replace('"selectedChunks":"1"', '"selectedChunks":"2"'))
        self.assertEqual(cli.native_water_settlement_receipt(receipt, expected={"0,0"}),
                         "native_water_settlement_chunk_count_mismatch")

    def test_network_audit_keeps_fixed_chunk_generation_and_natural_property(self):
        base = ["test", "--gradle-user-home", str(self.root), "--network-audit",
                "--natural-rivers=true", "--chunks=0,0", "--plan-audit", "--settle-water"]
        with (mock.patch.object(cli, "ROOT", self.root),
              mock.patch.object(cli, "run_suite", return_value=0) as launch,
              mock.patch.object(cli, "gradle_command",
                                side_effect=lambda root, command, daemon, **kwargs: command)):
            self.assertEqual(cli.main(base), 0)
        command = launch.call_args.args[2][0].command
        self.assertIn("-PtdRiver.natural=true", command)
        self.assertIn("-PtdProbe.naturalRiverAudit=true", command)
        self.assertIn("-PtdProbe.chunks=0,0", command)
        self.assertIn("-PtdProbe.planAudit=true", command)
        self.assertIn("-PtdProbe.settleWater=true", command)
        self.assertNotIn("-PtdProbe.naturalPlanOnly=true", command)

    def test_manifest_traversal_rejected(self):
        self.manifest["mods"] = [{"file": "../../secret", "sha256": "f" * 64}]
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "manifest path"): validate_fixture(self.run)

    def test_restart_runner_archives_serializable_evidence_and_checks_fresh_artifacts(self):
        world = self.run / "world"
        (world / "data").mkdir()
        (world / "streamsreflowing").mkdir()
        (world / "streamsreflowing/tile.srt").write_bytes(b"graph")
        (world / "level.dat").write_bytes(b"world")
        for name in ("terrain_diffusion_world_settings.dat", "terrain_diffusion_natural_rivers.dat"):
            (world / "data" / name).write_bytes(b"marker")
        network = world / "td-natural-river-network.csv"
        network.write_text("x1,z1,y1,w1,x2,z2,y2,w2\n0,0,70,1,1,1,69,1\n")
        surface = world / "td-streams-probe-surface-audit-0-0.csv"
        def write_surface(block="minecraft:sand", temperature="30"):
            fields = ["x", "z", "actualTopmostY", "actualTopmostBlock", "terrainTopSolidY",
                      "terrainTopSolidBlock", "actualSavedTerrainBiome", "actualTopWaterY", "ecoTemperature"]
            with surface.open("w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(fields)
                for z in range(16):
                    for x in range(16):
                        writer.writerow([x, z, 70, block, 70, block, "minecraft:desert", -65, temperature])
        write_surface()
        receipt = world / "td-streams-probe.jsonl"
        receipt.write_text('{"event":"surface_audit","chunk":"0,0"}\n'
                           '{"event":"complete","status":"success"}\n')
        args = SimpleNamespace(chunks="0,0", verify_restart=True, surface_audit=True,
                               expect_river_cache="present")
        prepare, check = cli.surface_checks(self.run, args, cli.streams_receipt)
        prepare()
        archives = list((self.run / "restart-checks").iterdir())
        self.assertEqual(len(archives), 1)
        self.assertEqual(json.loads((archives[0] / "before.json").read_text())["chunks"], ["0,0"])
        self.assertTrue((archives[0] / "receipt-before.jsonl").is_file())
        self.assertEqual(check(receipt), "surface_evidence_missing_or_stale")
        write_surface(temperature="31")
        future = time.time_ns() + 2_000_000_000
        for path in (surface, network):
            os.utime(path, ns=(future, future))
        self.assertEqual(check(receipt), "success")
        report = json.loads((archives[0] / "comparison.json").read_text())
        self.assertEqual(report["recomputed"]["ecoTemperature"], 256)
        write_surface(block="minecraft:grass_block")
        os.utime(surface, ns=(future, future))
        self.assertEqual(check(receipt), "restart_state_changed")
        receipt.write_text('{"event":"complete","status":"success"}\n')
        self.assertEqual(check(receipt), "surface_evidence_missing_or_duplicate")


if __name__ == "__main__":
    unittest.main()
