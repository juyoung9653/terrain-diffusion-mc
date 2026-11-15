"""The normal Streams CLI must stop before Gradle when compatibility preflight fails."""
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from streams_probe import run_suite as cli


class AutomaticPreflightTest(unittest.TestCase):
    def test_failed_compatibility_check_blocks_normal_cli_child(self):
        with tempfile.TemporaryDirectory(dir=cli.ROOT / ".tmp") as temporary:
            root = Path(temporary)
            run = root / "build/streams-probe/candidate"
            run.mkdir(parents=True)
            (root / "jdk").mkdir()
            (root / ".gradle").mkdir()
            (root / ("gradlew.bat" if os.name == "nt" else "gradlew")).touch()
            with patch.object(cli, "ROOT", root), \
                    patch.object(cli, "prepared_run", return_value=run), \
                    patch.object(cli, "validate_neoforge_version"), \
                    patch.object(cli, "validate_fixture", return_value={}), \
                    patch.object(cli, "validate_streams_compat",
                                 side_effect=ValueError("bank hook missing")) as compat, \
                    patch("probe_suite.subprocess.Popen") as child, \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as result:
                    cli.main(["candidate", "--java-home", str(root / "jdk"),
                              "--gradle-user-home", str(root / ".gradle")])
                self.assertEqual(result.exception.code, 2)
                compat.assert_called_once_with(root, run, root / "jdk")
                child.assert_not_called()
            self.assertFalse((run / "world").exists())


if __name__ == "__main__":
    unittest.main()
