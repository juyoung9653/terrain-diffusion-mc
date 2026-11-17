"""End-to-end retention checks around the small, exclusive probe runner.

The child processes here only write a tiny diagnostic receipt.  They deliberately
exercise the runner's real receipt/freshness path; no Gradle or Minecraft runtime
is involved.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parent))

from probe_suite import Job, density_receipt, run_suite, streams_receipt


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class ProbeStorageIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / "build")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build/structure-density").mkdir(parents=True)

    def make_run(self, name: str) -> Path:
        run = self.root / "build/structure-density" / name
        (run / "world").mkdir(parents=True)
        (run / "terrain-diffusion-models").mkdir()
        (run / "server.properties").write_text("level-name=world\n", encoding="utf-8")
        for filename, data in {
            "base_model.onnx": b"base-model-" + name.encode(),
            "coarse_model.onnx": b"coarse-model",
            "decoder_model.onnx": b"decoder-model",
        }.items():
            (run / "terrain-diffusion-models" / filename).write_bytes(data)
        manifest = {
            "sha256": {
                "terrain-diffusion-models/base_model.onnx": digest(run / "terrain-diffusion-models/base_model.onnx"),
                "terrain-diffusion-models/coarse_model.onnx": digest(run / "terrain-diffusion-models/coarse_model.onnx"),
                "terrain-diffusion-models/decoder_model.onnx": digest(run / "terrain-diffusion-models/decoder_model.onnx"),
            }
        }
        (run / "input-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return run

    def make_stream_run(self, name: str) -> Path:
        family = self.root / "build/streams-probe"
        family.mkdir(exist_ok=True)
        run = family / name
        (run / "world").mkdir(parents=True)
        (run / "terrain-diffusion-models").mkdir()
        assets = {}
        for filename, data in {
            "base_model.onnx": b"streams-base-" + name.encode(),
            "coarse_model.onnx": b"streams-coarse",
        }.items():
            path = run / "terrain-diffusion-models" / filename
            path.write_bytes(data)
            assets[filename] = {"bytes": len(data), "sha256": digest(path)}
        (run / "input-manifest.json").write_text(json.dumps({"modelAssets": assets}), encoding="utf-8")
        return run

    def job(self, run: Path, *, payload: str = '{"success":true}', exit_code: int = 0) -> Job:
        receipt = run / "world/density-audit.json"
        script = (
            "from pathlib import Path; import sys; "
            "Path(sys.argv[1]).write_text(sys.argv[2], encoding='utf-8'); "
            "sys.exit(int(sys.argv[3]))"
        )
        return Job(run, [sys.executable, "-c", script, str(receipt), payload, str(exit_code)],
                   receipt, density_receipt)

    def test_third_success_archives_oldest_and_preserves_evidence(self) -> None:
        first, second, third = (self.make_run(name) for name in ("first", "second", "third"))
        for run in (first, second, third):
            receipt = run / "world/density-audit.json"
            self.assertEqual(run_suite(self.root, "structure-density", [self.job(run)], {}), 0)
            self.assertTrue(receipt.is_file())

        self.assertTrue((first / "archived-model-inputs.json").is_file())
        self.assertFalse((first / "terrain-diffusion-models/base_model.onnx").exists())
        self.assertTrue((first / "world/density-audit.json").is_file())
        self.assertTrue((first / "validation-evidence.json").is_file())
        self.assertTrue((second / "terrain-diffusion-models/base_model.onnx").is_file())
        self.assertTrue((third / "terrain-diffusion-models/base_model.onnx").is_file())

    def test_keep_inputs_pin_survives_later_successes(self) -> None:
        pinned = self.make_run("pinned")
        self.assertEqual(run_suite(self.root, "structure-density", [self.job(pinned)], {},
                                   keep_inputs=True), 0)
        self.assertTrue((pinned / "keep-probe-inputs.json").is_file())
        for name in ("two", "three", "four"):
            run = self.make_run(name)
            self.assertEqual(run_suite(self.root, "structure-density", [self.job(run)], {}), 0)
        self.assertTrue((pinned / "terrain-diffusion-models/base_model.onnx").is_file())
        self.assertFalse((pinned / "archived-model-inputs.json").exists())
        self.assertTrue((self.root / "build/structure-density/two/archived-model-inputs.json").is_file())
        self.assertTrue((self.root / "build/structure-density/three/terrain-diffusion-models/base_model.onnx").is_file())
        self.assertTrue((self.root / "build/structure-density/four/terrain-diffusion-models/base_model.onnx").is_file())

    def test_unsuccessful_runs_never_call_retention_hook(self) -> None:
        import probe_storage
        for name, job in (
            ("nonzero", lambda run: self.job(run, exit_code=4)),
            ("malformed", lambda run: self.job(run, payload="{bad")),
            ("stale", lambda run: Job(run, [sys.executable, "-c", "print('no replacement receipt')"],
                                       run / "world/density-audit.json", density_receipt)),
        ):
            with self.subTest(name=name):
                run = self.make_run(name)
                if name == "stale":
                    (run / "world/density-audit.json").write_text('{"success":true}', encoding="utf-8")
                with mock.patch.object(probe_storage, "maintain", wraps=probe_storage.maintain) as maintain:
                    self.assertEqual(run_suite(self.root, "structure-density", [job(run)], {}), 1)
                maintain.assert_not_called()
                self.assertTrue((run / "terrain-diffusion-models/base_model.onnx").is_file())

    def test_finalizer_failure_marks_suite_unsuccessful_without_model_removal(self) -> None:
        run = self.make_run("finalizer-failed")
        hook = mock.Mock(side_effect=RuntimeError("archive store unavailable"))
        import probe_storage
        with mock.patch.object(probe_storage, "maintain", wraps=probe_storage.maintain) as maintain:
            self.assertEqual(run_suite(self.root, "structure-density", [self.job(run)], {},
                                       on_success=hook), 1)
        hook.assert_called_once()
        maintain.assert_not_called()
        self.assertTrue((run / "terrain-diffusion-models/base_model.onnx").is_file())
        status = json.loads((self.root / "build/structure-density/suite-status.json").read_text())
        self.assertFalse(status["success"])
        self.assertIn("archive", status["finalizationError"])
        self.assertTrue(json.loads((run / "validation-evidence.json").read_text())["success"])

    def test_finalizer_runs_while_lock_is_held_after_validation(self) -> None:
        run = self.make_run("ordered")
        seen: list[object] = []

        def finalizer() -> None:
            from probe_suite import exclusive_lock
            seen.append(json.loads((run / "validation-evidence.json").read_text())["success"])
            with self.assertRaisesRegex(RuntimeError, "another probe suite"):
                with exclusive_lock(self.root):
                    pass

        import probe_storage
        original_maintain = probe_storage.maintain

        def check_maintenance(*args, **kwargs):
            from probe_suite import exclusive_lock
            self.assertEqual(seen, [True])
            with self.assertRaisesRegex(RuntimeError, "another probe suite"):
                with exclusive_lock(self.root):
                    pass
            return original_maintain(*args, **kwargs)

        with mock.patch.object(probe_storage, "maintain", side_effect=check_maintenance) as maintain:
            self.assertEqual(run_suite(self.root, "structure-density", [self.job(run)], {},
                                       on_success=finalizer), 0)
        self.assertEqual(seen, [True])
        maintain.assert_called_once()

    def test_existing_archive_marker_blocks_child_before_launch(self) -> None:
        run = self.make_run("already-archived")
        (run / "archived-model-inputs.json").write_text("{}", encoding="utf-8")
        launch = mock.Mock(wraps=subprocess.Popen)
        with mock.patch("probe_suite.subprocess.Popen", launch):
            with self.assertRaisesRegex(ValueError, "model inputs archived"):
                run_suite(self.root, "structure-density", [self.job(run)], {})
        launch.assert_not_called()

    def test_storage_failure_marks_suite_unsuccessful_but_keeps_validated_inputs(self) -> None:
        run = self.make_run("storage-failed")
        import probe_storage
        with mock.patch.object(probe_storage, "maintain", side_effect=RuntimeError("object store failed")):
            self.assertEqual(run_suite(self.root, "structure-density", [self.job(run)], {}), 1)
        status = json.loads((self.root / "build/structure-density/suite-status.json").read_text())
        self.assertFalse(status["success"])
        self.assertEqual(status["storage"]["status"], "failed")
        self.assertTrue((run / "terrain-diffusion-models/base_model.onnx").is_file())
        self.assertTrue(json.loads((run / "validation-evidence.json").read_text())["success"])

    def test_mixed_suite_does_not_archive_earlier_success(self) -> None:
        first, failed = self.make_run("mixed-good"), self.make_run("mixed-failed")
        import probe_storage
        with mock.patch.object(probe_storage, "maintain", wraps=probe_storage.maintain) as maintain:
            self.assertEqual(run_suite(self.root, "structure-density",
                                       [self.job(first), self.job(failed, exit_code=7)], {}), 1)
        maintain.assert_not_called()
        self.assertTrue((first / "terrain-diffusion-models/base_model.onnx").is_file())
        self.assertFalse((first / "archived-model-inputs.json").exists())

    def test_streams_manifest_and_receipt_use_the_same_automatic_policy(self) -> None:
        runs = [self.make_stream_run(name) for name in ("first", "second", "third")]
        for run in runs:
            receipt = run / "world/td-streams-probe.jsonl"
            payload = '{"event":"complete","status":"success"}\n'
            script = ("from pathlib import Path; import sys; "
                      "Path(sys.argv[1]).write_text(sys.argv[2], encoding='utf-8')")
            job = Job(run, [sys.executable, "-c", script, str(receipt), payload], receipt, streams_receipt)
            self.assertEqual(run_suite(self.root, "streams-probe", [job], {}), 0)
        first, second, third = runs
        self.assertTrue((first / "archived-model-inputs.json").is_file())
        self.assertFalse((first / "terrain-diffusion-models/base_model.onnx").exists())
        self.assertTrue((first / "world/td-streams-probe.jsonl").is_file())
        self.assertTrue((second / "terrain-diffusion-models/base_model.onnx").is_file())
        self.assertTrue((third / "terrain-diffusion-models/base_model.onnx").is_file())


if __name__ == "__main__":
    unittest.main()
