"""Fail before launching Gradle, including when a later fixture is incompatible."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

from tools.streams_probe import run_water_appearance_suite as cli
from streams_probe import water_appearance_preflight as checks


class AppearancePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=cli.ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "build/streams-probe/one"
        (self.run / "mods").mkdir(parents=True)
        self.jar = self.mod("renamed.jar")

    def mod(self, name, version="2.16.2"):
        path = self.run / "mods" / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/neoforge.mods.toml",
                             f'[[mods]]\nmodId="streamsreflowing"\nversion="{version}"\n')
        return path

    def test_selects_actual_fixture_mod_by_metadata(self):
        self.mod("Streams-old.jar.disabled", "2.15.2")
        self.assertEqual(self.jar, checks.streams_jar(self.run))

    def test_missing_duplicate_and_unsupported_mods_fail(self):
        self.jar.unlink()
        with self.assertRaises(ValueError): checks.streams_jar(self.run)
        self.mod("new.jar", "2.16.3")
        with self.assertRaisesRegex(ValueError, "2.16.3"): checks.streams_jar(self.run)
        self.mod("duplicate.jar")
        with self.assertRaisesRegex(ValueError, "exactly one"): checks.streams_jar(self.run)

    def test_checks_use_fixture_jar_and_selected_jdk(self):
        home = self.root / "jdk"
        (home / "bin").mkdir(parents=True)
        for name in ("java", "javac", "javap"):
            (home / "bin" / (name + (".exe" if os.name == "nt" else ""))).touch()
        with mock.patch.object(checks, "run_check") as check:
            checks.check_streams_contract(self.run, {"JAVA_HOME": str(home)})
        self.assertEqual(2, check.call_count)
        for call in check.call_args_list:
            args = call.args[1]
            self.assertEqual(str(self.jar), args[args.index("--jar") + 1])
            self.assertEqual(str(home), args[args.index("--java-home") + 1])

    def test_bad_jdk_cannot_silently_fall_back(self):
        with mock.patch.object(checks, "run_check") as check:
            with self.assertRaises(ValueError):
                checks.check_streams_contract(self.run, {"JAVA_HOME": str(self.root / "missing")})
            check.assert_not_called()

    def test_failed_check_reports_cause(self):
        result = subprocess.CompletedProcess([], 1, "", "bad native accessor")
        with mock.patch.object(checks.subprocess, "run", return_value=result), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "bad native accessor"):
                checks.run_check("adapter", [], {})

    def invoke(self, *, receipt_error=None, native_errors=None, names=("one",), check_only=False):
        args = ["appearance", *names, "--gradle-user-home", str(self.root)]
        if check_only: args.append("--check-only")
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(cli, "ROOT", self.root))
            stack.enter_context(mock.patch.object(cli.sys, "argv", args))
            stack.enter_context(mock.patch.object(cli, "environment", return_value={}))
            stack.enter_context(mock.patch.object(cli, "prepared_run",
                side_effect=lambda root, family, name, manifest: root / name))
            stack.enter_context(mock.patch.object(cli, "validate_fixture"))
            stack.enter_context(mock.patch.object(cli, "gradle_command", return_value=["must-not-launch"]))
            receipt = stack.enter_context(mock.patch.object(cli, "check_receipt_contract", side_effect=receipt_error))
            native = stack.enter_context(mock.patch.object(cli, "check_streams_contract", side_effect=native_errors))
            launch = stack.enter_context(mock.patch("probe_suite.subprocess.Popen"))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if receipt_error or (native_errors and any(isinstance(x, Exception) for x in native_errors)):
                with self.assertRaises(RuntimeError): cli.main()
            else:
                self.assertEqual(0, cli.main())
            launch.assert_not_called()
            return receipt, native

    def test_receipt_failure_blocks_server(self):
        receipt, native = self.invoke(receipt_error=RuntimeError("receipt regression"))
        receipt.assert_called_once()
        native.assert_not_called()

    def test_native_failure_blocks_server(self):
        self.invoke(native_errors=[RuntimeError("native mismatch")])

    def test_later_fixture_failure_blocks_every_server(self):
        receipt, native = self.invoke(names=("one", "two"), native_errors=[None, RuntimeError("second fixture")])
        receipt.assert_called_once()
        self.assertEqual(2, native.call_count)

    def test_check_only_runs_all_checks_without_launch(self):
        receipt, native = self.invoke(names=("one", "two"), check_only=True)
        receipt.assert_called_once()
        self.assertEqual([self.root / "build/streams-probe" / n for n in ("one", "two")],
                         [call.args[0] for call in native.call_args_list])


if __name__ == "__main__":
    unittest.main()
