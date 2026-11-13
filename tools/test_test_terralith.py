"""Entry-point preflight regressions for tools/test_terralith.py."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch
import unittest
import zipfile


SCRIPT = Path(__file__).with_name("test_terralith.py")
SPEC = importlib.util.spec_from_file_location("test_terralith_entry", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class TerralithEntryPreflightTest(unittest.TestCase):
    def assert_preflight_stops_before_child(self, arguments: list[str]) -> None:
        with patch.object(MODULE.subprocess, "run") as child:
            with self.assertRaises(SystemExit):
                MODULE.main(arguments)
        child.assert_not_called()

    def test_missing_jar_stops_before_compilation(self) -> None:
        self.assert_preflight_stops_before_child(["--jar", "missing-terralith.jar"])

    def test_bad_explicit_jdk_stops_before_compilation(self) -> None:
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / ".tmp") as raw:
            fixture = Path(raw) / "terralith.jar"
            with zipfile.ZipFile(fixture, "w") as archive:
                archive.writestr("data/terralith/worldgen/biome/test.json", "{}")
            self.assert_preflight_stops_before_child([
                "--jar", str(fixture), "--java-home", "missing-jdk"])

    def test_child_environment_reaches_a_real_child_with_space_in_temp_path(self) -> None:
        home = Path("C:/Program Files/Java/jdk-21")
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / ".tmp", prefix="temp path ") as raw:
            scratch = Path(raw)
            environment = MODULE.child_environment(home, scratch)
            child = subprocess.run([sys.executable, "-c", "import os,tempfile; print(os.environ['TMP']); print(tempfile.gettempdir())"],
                                   env=environment, text=True, capture_output=True, check=True,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        actual = child.stdout.splitlines()
        self.assertEqual(environment["JAVA_HOME"], str(home))
        self.assertEqual(actual, [str(scratch), str(scratch)])
        self.assertEqual(environment["TMP"], str(scratch))
        self.assertEqual(environment["TEMP"], str(scratch))
        self.assertIn(str(home / "bin"), environment["PATH"])
        self.assertIn('java.io.tmpdir="' + str(scratch) + '"', environment["JAVA_TOOL_OPTIONS"])

    def test_unwritable_scratch_stops_before_compilation(self) -> None:
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / ".tmp") as raw:
            blocker = Path(raw) / "not-a-directory"
            blocker.write_bytes(b"block")
            with patch.object(MODULE, "SCRATCH", blocker), \
                    patch.object(MODULE, "validate_terralith_jar", return_value=Path("ok.jar")), \
                    patch.object(MODULE, "resolve_java_home", return_value=Path("C:/Program Files/Java/jdk-21")), \
                    patch.object(MODULE.subprocess, "run") as child:
                with self.assertRaises(SystemExit):
                    MODULE.main(["--jar", "ignored.jar"])
            child.assert_not_called()

    def test_real_java_accepts_space_in_temporary_directory(self) -> None:
        try:
            home = MODULE.resolve_java_home(None)
        except ValueError as error:
            self.skipTest(str(error))
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / ".tmp", prefix="java temp ") as raw:
            scratch = Path(raw)
            executable = home / "bin" / ("java.exe" if sys.platform == "win32" else "java")
            result = subprocess.run(
                [str(executable), "-XshowSettings:properties", "-version"],
                env=MODULE.child_environment(home, scratch),
                text=True, capture_output=True, check=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            actual = next(line.split(" = ", 1)[1] for line in result.stderr.splitlines()
                          if line.strip().startswith("java.io.tmpdir = "))
            self.assertEqual(Path(actual), scratch)


if __name__ == "__main__":
    unittest.main()
