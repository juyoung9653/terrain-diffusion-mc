"""Pure contracts for the copied-save client diagnostic launcher."""
from __future__ import annotations

import argparse
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("instance_client_probe_run", HERE / "run.py")
assert SPEC and SPEC.loader
run = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(run)


def record(*, grass: int, skipped: int, seed: int = 4, rectangle: str = "x1..2,z3..4,y5..6") -> dict:
    return {"seed": seed, "snapshot": {"mods": {"td.jar": "a"}, "config": {}, "models": {}},
            "worldSnapshot": {"levelDat": "z"}, "runtime": {"runtimeJarSha256": "runtime"},
            "observer": {"Observer.class": "b"}, "exitCode": 0,
            "receipt": {"event": "td_instance_client_river_material_audit", "status": "success_observation",
                        "settled": True, "actualTicks": 100, "observation":
                        {"rectangle": rectangle, "waterBottoms": 8, "submergedGrassBeds": grass},
                        "treeGroundSkippedWrites": skipped}}


class InstanceClientProbeRunTest(unittest.TestCase):
    def test_runtime_preflight_failure_never_copies_or_launches(self) -> None:
        scratch = HERE.parents[2] / "build"; scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as temp:
            root = Path(temp); output = root / "output"; game = root / "game"; world = game / "saves" / "TD-River-Copy"
            world.mkdir(parents=True); (world / "level.dat").write_bytes(b"x")
            (root / "probe.jar").write_bytes(b"x")
            launch = mock.Mock(DEFAULT_INSTANCE=game, DEFAULT_LIBRARIES=root / "libraries", NEO="21.1.264",
                               DEFAULT_JAVA=root / "java/bin/javaw.exe")
            args = argparse.Namespace(world="TD-River-Copy", probe=root / "probe.jar", output=output, baseline=None, tree_ground_fix=False)
            with mock.patch.object(run, "ROOT", root), mock.patch.object(run, "load_launch", return_value=launch), \
                 mock.patch.object(run, "installed_td_jar", return_value=root / "td.jar"), \
                 mock.patch.object(run, "jar_has_class", return_value=False), \
                 mock.patch.object(run, "validate_tree_ground_runtime", side_effect=ValueError("bad selector")), \
                 mock.patch.object(run.shutil, "copy2") as copied, mock.patch.object(run.subprocess, "Popen") as popen:
                with self.assertRaisesRegex(ValueError, "bad selector"):
                    run.run(args)
            copied.assert_not_called(); popen.assert_not_called(); self.assertFalse(output.exists())

    def test_unreproduced_baseline_cannot_verify_fix(self) -> None:
        baseline, fixed = record(grass=0, skipped=0), record(grass=0, skipped=3)
        self.assertEqual(("success_observation", "baseline_did_not_exercise_defect"), run.regression_verdict(baseline, fixed))

    def test_observation_without_baseline_is_not_regression(self) -> None:
        self.assertEqual(("success_observation", "unreproducible_or_unsettled"),
                         run.regression_verdict({**record(grass=2, skipped=0), "receipt": {"settled": False}}, record(grass=0, skipped=4)))

    def test_verified_regression_requires_same_inputs_and_physical_result(self) -> None:
        self.assertEqual(("verified_regression", "baseline_defect_reproduced_and_fixed"),
                         run.regression_verdict(record(grass=3, skipped=0), record(grass=0, skipped=2)))
        changed = record(grass=0, skipped=2); changed["observer"] = {"Observer.class": "changed"}
        self.assertEqual(("success_observation", "inputs_or_observer_mismatch"),
                         run.regression_verdict(record(grass=3, skipped=0), changed))

    def test_fix_payload_can_differ_but_runtime_and_coverage_must_match(self) -> None:
        baseline, fixed = record(grass=3, skipped=0), record(grass=0, skipped=2)
        fixed["runtime"]["mixinJarSha256"] = "optional-relocated-fix"
        self.assertEqual("verified_regression", run.regression_verdict(baseline, fixed)[0])
        fixed["receipt"]["observation"]["waterBottoms"] = 7
        self.assertEqual("success_observation", run.regression_verdict(baseline, fixed)[0])
        fixed["receipt"]["observation"]["waterBottoms"] = 8
        for counter in (0, True, "2"):
            fixed["receipt"]["treeGroundSkippedWrites"] = counter
            self.assertEqual("success_observation", run.regression_verdict(baseline, fixed)[0])

    def test_successful_mock_launch_sets_exact_probe_properties(self) -> None:
        scratch = HERE.parents[2] / "build"; scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as temp:
            root = Path(temp); game = root / "game"; world = game / "saves" / "TD-River-Copy"; world.mkdir(parents=True)
            (world / "level.dat").write_bytes(b"level"); (game / "mods").mkdir(); (root / "probe.jar").write_bytes(b"probe")
            captured = []
            real_launcher = run.load_launch()
            def command(runtime, target, natives):
                metadata = {"arguments": {"jvm": [], "game": []}, "mainClass": "example.Main"}
                with mock.patch.object(real_launcher, "production_libraries", return_value=([], [], metadata)), \
                     mock.patch.object(real_launcher, "json_file", return_value={"minecraftArguments": "--gameDir ${game_directory}"}):
                    result = real_launcher.make_command(runtime, target, natives)
                captured.extend(result)
                return result
            launch = mock.Mock(DEFAULT_INSTANCE=game, DEFAULT_LIBRARIES=root / "libraries", DEFAULT_ASSETS=root / "assets",
                               DEFAULT_JAVA=root / "java/bin/javaw.exe", NEO="21.1.264", production_libraries=lambda _: ([], [], {}),
                               extract_natives=lambda *_: None, make_command=command)
            result_receipt = record(grass=0, skipped=2)["receipt"]
            result_receipt.update(world="TD-River-Copy", worldRoot=str(world.resolve()))
            def fresh(path, _started):
                path.write_text("{}", encoding="utf-8")
                return result_receipt
            args = argparse.Namespace(world="TD-River-Copy", probe=root / "probe.jar", output=root / "out", baseline=None, tree_ground_fix=False)
            process = mock.Mock(pid=12); process.wait.return_value = 0
            with mock.patch.object(run, "ROOT", root), mock.patch.object(run, "load_launch", return_value=launch), \
                 mock.patch.object(run, "installed_td_jar", return_value=root / "td.jar"), mock.patch.object(run, "jar_has_class", return_value=False), \
                 mock.patch.object(run, "validate_tree_ground_runtime", return_value={"runtimeJarSha256": "r"}), \
                 mock.patch.object(run, "instance_snapshot", return_value=record(grass=0, skipped=2)["snapshot"]), \
                 mock.patch.object(run, "observer_hashes", return_value={"Probe.class": "p"}), mock.patch.object(run, "level_seed", return_value=7), \
                 mock.patch.object(run, "world_snapshot", return_value={"levelDat": "l"}), mock.patch.object(run, "read_fresh_receipt", side_effect=fresh), \
                 mock.patch.object(run.subprocess, "Popen", return_value=process):
                result = run.run(args)
            self.assertEqual("success_observation", result["status"])
            self.assertIn("-Dtd.instanceProbe.enable=true", captured)
            self.assertIn("-Dtd.instanceProbe.world=TD-River-Copy", captured)
            self.assertIn(str(game.resolve()), captured)

    def test_modern_seed_smoke_for_existing_copied_world(self) -> None:
        import os
        level = Path(os.environ.get("APPDATA", "~")).expanduser() / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/saves/TD-River-Saved-Copy-20261113/level.dat"
        if not level.is_file(): self.skipTest("existing copied world unavailable")
        self.assertIs(type(run.level_seed(level)), int)


if __name__ == "__main__": unittest.main()
