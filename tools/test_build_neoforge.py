"""Focused no-Gradle regressions for tools.build_neoforge."""
from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("build_neoforge", ROOT / "tools/build_neoforge.py")
assert SPEC and SPEC.loader
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class BuildNeoForgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")).write_text("", encoding="utf-8")
        self.cache = self.root / ".gradle"; self.cache.mkdir()
        self.java = self.root / "jdk"; (self.java / "bin").mkdir(parents=True)
        suffix = ".exe" if os.name == "nt" else ""
        for tool in ("java", "javac"):
            (self.java / "bin" / (tool + suffix)).write_text("", encoding="utf-8")

    def plan(self):
        return build.build_plan(self.root, java_home=self.java, gradle_user_home=self.cache)

    def test_defaults_override_inherited_java_and_gradle_homes_and_keep_version_one_argument(self):
        with patch.object(build, "DEFAULT_JAVA_HOME", self.java), \
                patch.dict(os.environ, {"JAVA_HOME": "C:/bad-jdk", "GRADLE_USER_HOME": "C:/bad-cache"}):
            command, env = build.build_plan(self.root)
        self.assertEqual(env["GRADLE_USER_HOME"], str(self.cache.resolve()))
        self.assertEqual(env["JAVA_HOME"], str(self.java.resolve()))
        self.assertEqual(command.count("-Pneoforge_version=21.1.249"), 1)
        self.assertNotIn("-Pneoforge_version", command)

    def test_invalid_input_does_not_launch_subprocess(self):
        with patch("subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "missing populated Gradle"):
                build.run(self.root, java_home=self.java, gradle_user_home=self.root / "missing")
        process.assert_not_called()

    def test_jre_only_and_unsafe_windows_argument_do_not_launch_subprocess(self):
        suffix = ".exe" if os.name == "nt" else ""
        (self.java / "bin" / ("javac" + suffix)).unlink()
        with patch("subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "java/javac"):
                build.run(self.root, java_home=self.java, gradle_user_home=self.cache)
        process.assert_not_called()
        (self.java / "bin" / ("javac" + suffix)).write_text("", encoding="utf-8")
        unsafe = self.root / "cache&unsafe"; unsafe.mkdir()
        if os.name == "nt":
            with patch("subprocess.run") as process:
                with self.assertRaisesRegex(ValueError, "metacharacters"):
                    build.run(self.root, java_home=self.java, gradle_user_home=unsafe)
            process.assert_not_called()

    def test_check_only_never_launches_subprocess(self):
        with patch("subprocess.run") as process:
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, build.run(self.root, java_home=self.java,
                                              gradle_user_home=self.cache, check_only=True))
        process.assert_not_called()

    def test_nonzero_build_exit_propagates(self):
        with patch("subprocess.run", return_value=type("Result", (), {"returncode": 7})()) as process:
            self.assertEqual(7, build.run(self.root, java_home=self.java, gradle_user_home=self.cache))
        self.assertEqual(process.call_args.kwargs["env"]["JAVA_HOME"], str(self.java.resolve()))
        self.assertFalse(process.call_args.kwargs["shell"])

    def test_stale_validation_stops_before_gradle(self):
        proof = self.root / "validation-evidence.json"
        proof.write_text(json.dumps({"schema": 1, "kind": "probe-validation", "success": True,
                                     "processExit": 0, "probeStatus": "success",
                                     "sourceFingerprint": "obsolete"}))
        with patch("subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "source differs"):
                build.run(self.root, java_home=self.java, validations=[proof])
        process.assert_not_called()

    def test_missing_validation_stops_before_gradle(self):
        with patch("subprocess.run") as process:
            with self.assertRaises(FileNotFoundError):
                build.run(self.root, java_home=self.java, validations=[self.root / "missing.json"])
        process.assert_not_called()

    def test_successful_build_records_exact_artifact_and_validation(self):
        import build_evidence
        result = self.root / "result.json"
        result.write_text('{"success":true}')
        proof = self.root / "validation-evidence.json"
        proof.write_text(json.dumps({"schema": 1, "kind": "probe-validation", "success": True,
                                     "processExit": 0, "probeStatus": "success",
                                     "sourceFingerprint": build.source_fingerprint(self.root),
                                     "receipt": result.name, "receiptSha256": build.sha256(result),
                                     "inputManifestSha256": None}))
        mod = self.root / "neoforge/build/libs/td.jar"

        def fake_gradle(*args, **kwargs):
            mod.parent.mkdir(parents=True)
            with zipfile.ZipFile(mod, "w") as archive:
                archive.writestr("example/Main.class", b"compiled")
            receipt = self.root / "neoforge/build/disk-libraries/onnx-runtime-receipt.properties"
            receipt.parent.mkdir(parents=True)
            receipt.write_text("formatVersion=1\nmode=neoforge-disk-library\n"
                               "module=com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0\n"
                               "sourceSha256=source\nlibraryFile=ort.jar\nlibrarySha256=library\n"
                               f"modFile=td.jar\nmodSha256={build.sha256(mod)}\n")
            return type("Result", (), {"returncode": 0})()

        with patch("subprocess.run", side_effect=fake_gradle):
            self.assertEqual(0, build.run(self.root, java_home=self.java, validations=[proof]))
        bound = build_evidence.verify_release(mod, self.root)
        self.assertEqual(bound["modSha256"], build.sha256(mod))
        self.assertEqual(bound["validations"][0]["sha256"], build.sha256(proof))


if __name__ == "__main__":
    unittest.main()
