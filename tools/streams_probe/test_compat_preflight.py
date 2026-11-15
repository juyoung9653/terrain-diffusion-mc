from __future__ import annotations

import io
from pathlib import Path
import sys
import tempfile
import tomllib
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from streams_probe import compat_preflight


class CompatPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "build")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "run"
        (self.run / "mods").mkdir(parents=True)
        self.jdk = self.root / "jdk"
        for tool in ("java.exe", "javac.exe", "javap.exe"):
            target = self.jdk / "bin" / tool
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"")

    def streams(self, name="streams.jar", version="2.17.3") -> Path:
        jar = self.run / "mods" / name
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/neoforge.mods.toml", "[[mods]]\nmodId='streamsreflowing'\nversion='" + version + "'\n")
        return jar

    def ok(self, command, **kwargs):
        return mock.Mock(returncode=0, stdout="ok", stderr="")

    def test_2173_runs_exact_fixture_jar_and_all_required_checks(self):
        jar = self.streams()
        calls = []
        def execute(command, **kwargs):
            calls.append((command, kwargs)); return self.ok(command)
        receipt = compat_preflight.validate_streams_compat(self.root, self.run, self.jdk, execute=execute)
        self.assertEqual(receipt["status"], "passed")
        self.assertEqual(receipt["version"], "2.17.3")
        self.assertEqual(len(calls), 10)
        self.assertTrue(all(str(jar) in command or command[2].endswith(
            ("test_2171_gate.py", "test_2173_raised_cap_policy.py",
             "test_river_regression_contract.py"))
                            for command, _ in calls))
        self.assertTrue(all(kwargs["env"]["JAVA_HOME"] == str(self.jdk) for _, kwargs in calls))

    def test_script_failure_stops_before_later_checks(self):
        self.streams()
        calls = []
        def execute(command, **kwargs):
            calls.append(command)
            return mock.Mock(returncode=1 if len(calls) == 2 else 0, stdout="bad", stderr="")
        with self.assertRaisesRegex(ValueError, "test_2171_adapter.py"):
            compat_preflight.validate_streams_compat(self.root, self.run, self.jdk, execute=execute)
        self.assertEqual(len(calls), 2)

    def test_missing_duplicate_and_future_versions_are_rejected(self):
        self.assertIsNone(compat_preflight.find_streams_jar(self.root / "absent-mods"))
        with self.assertRaisesRegex(ValueError, "no Streams"):
            compat_preflight.validate_streams_compat(self.root, self.run, self.jdk, execute=self.ok)
        self.streams("one.jar")
        self.streams("two.jar")
        with self.assertRaisesRegex(ValueError, "multiple"):
            compat_preflight.find_streams_jar(self.run / "mods")
        (self.run / "mods" / "two.jar").unlink()
        # Rewrite the only metadata-bearing fixture artifact, then require a contract.
        self.streams("future.jar", "9.9.9")
        (self.run / "mods" / "one.jar").unlink()
        with self.assertRaisesRegex(ValueError, "no audited"):
            compat_preflight.validate_streams_compat(self.root, self.run, self.jdk, execute=self.ok)

    def test_legacy_supported_version_is_explicitly_not_covered(self):
        for version in ("2.13.1", "2.13.5", "2.13.8", "2.15.2", "2.16.2"):
            with self.subTest(version=version):
                self.streams(version=version)
                receipt = compat_preflight.validate_streams_compat(self.root, self.run, self.jdk, execute=self.ok)
                self.assertEqual(receipt["status"], "legacy_not_covered")


if __name__ == "__main__":
    unittest.main()
