"""Focused no-Gradle regressions for tools.build_neoforge."""
from __future__ import annotations

import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
