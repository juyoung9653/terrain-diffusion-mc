"""Focused contract tests for the shared dependency-free Java test helper."""

from pathlib import Path
import subprocess
import unittest
from unittest import mock

from tools.structure_placement import java_test_support as support


class JavaTestSupportTest(unittest.TestCase):
    def test_compile_and_run_preserves_commands_and_output_directory(self):
        output = Path("build/test-java-support/out")
        with mock.patch.object(support, "ensure_output", return_value=output), \
             mock.patch.object(support, "java_bin", return_value=Path("jdk/bin")), \
             mock.patch.object(support, "run") as run:
            support.compile_and_run(output, [Path("One.java"), Path("Two.java")], "ExampleTest")
        self.assertEqual(run.call_args_list, [
            mock.call([Path("jdk/bin/javac"), "-encoding", "UTF-8", "-d", output,
                       Path("One.java"), Path("Two.java")]),
            mock.call((Path("jdk/bin/java"), "-cp", output, "ExampleTest")),
        ])

    def test_run_forwards_output_and_propagates_failed_process(self):
        failed = subprocess.CompletedProcess(["javac"], 1, stdout="compiler error\n")
        with mock.patch.object(support.subprocess, "run", return_value=failed) as execute, \
             self.assertRaises(subprocess.CalledProcessError), \
             mock.patch("builtins.print") as output:
            support.run(["javac", "Broken.java"])
        expected_flags = support.subprocess.CREATE_NO_WINDOW if support.os.name == "nt" else 0
        self.assertEqual(execute.call_args.kwargs["creationflags"], expected_flags)
        output.assert_called_once_with("compiler error\n", end="", flush=True)

    def test_run_uses_no_window_flag_on_windows(self):
        completed = subprocess.CompletedProcess(["java"], 0, stdout="")
        with mock.patch.object(support, "os") as fake_os, \
             mock.patch.object(support.subprocess, "CREATE_NO_WINDOW", 0x08000000,
                               create=True), \
             mock.patch.object(support.subprocess, "run", return_value=completed) as execute:
            fake_os.name = "nt"
            support.run(["java"])
        self.assertEqual(execute.call_args.kwargs["creationflags"], 0x08000000)


if __name__ == "__main__":
    unittest.main()
