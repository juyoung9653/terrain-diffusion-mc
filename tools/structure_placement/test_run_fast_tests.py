"""Focused checks of the bounded offline launcher, without invoking Java."""

import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest import mock

from tools.structure_placement import run_fast_tests as launcher


class FastTestLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.checks = list(launcher.CHECKS[:4])

    def _fake_subprocess(self, failures=()):
        state = {"active": 0, "maximum": 0, "ids": []}
        guard = threading.Lock()

        def run(argv, **kwargs):
            check_id = argv[-1]
            with guard:
                state["active"] += 1
                state["maximum"] = max(state["maximum"], state["active"])
                state["ids"].append(check_id)
            time.sleep(0.025)
            kwargs["stdout"].write(f"ran {check_id}\n".encode())
            with guard:
                state["active"] -= 1
            return subprocess.CompletedProcess(argv, 3 if check_id in failures else 0)

        return state, run

    def test_sequential_and_parallel_cover_same_tasks_with_bounded_workers(self):
        for jobs in (1, 4):
            with self.subTest(jobs=jobs):
                state, fake = self._fake_subprocess()
                run_dir = self.output / str(jobs)
                run_dir.mkdir()
                with mock.patch.object(launcher, "command", side_effect=lambda check, _: ["python", check.id]), \
                     mock.patch.object(launcher.subprocess, "run", side_effect=fake):
                    rows, maximum = launcher.run_checks(self.checks, jobs, run_dir, None)
                self.assertEqual([row["id"] for row in rows], [check.id for check in self.checks])
                self.assertEqual(set(state["ids"]), {check.id for check in self.checks})
                self.assertEqual(len(state["ids"]), len(self.checks))
                self.assertTrue(all(row["status"] == "passed" for row in rows))
                self.assertLessEqual(maximum, jobs)
                self.assertEqual(maximum, state["maximum"])
                self.assertEqual(maximum, jobs)
                self.assertTrue(all(Path(row["log"]).is_file() for row in rows))

    def test_failure_does_not_prevent_other_checks(self):
        state, fake = self._fake_subprocess({"village"})
        with mock.patch.object(launcher, "command", side_effect=lambda check, _: ["python", check.id]), \
             mock.patch.object(launcher.subprocess, "run", side_effect=fake):
            rows, _ = launcher.run_checks(self.checks, 2, self.output, None)
        self.assertEqual(len(state["ids"]), len(self.checks))
        self.assertEqual([row["id"] for row in rows], [check.id for check in self.checks])
        self.assertEqual([row["status"] for row in rows], ["passed", "failed", "passed", "passed"])
        self.assertEqual(rows[1]["exit_code"], 3)

    def test_explicit_missing_dependency_and_duplicate_are_errors(self):
        with mock.patch.object(launcher, "REPORT_ROOT", self.output):
            with self.assertRaises(SystemExit) as missing:
                launcher.main(["--only", "preflight"])
            with self.assertRaises(SystemExit) as duplicate:
                launcher.main(["--only", "placement", "placement"])
        self.assertEqual(missing.exception.code, 2)
        self.assertEqual(duplicate.exception.code, 2)

    def test_cli_reports_skips_and_failure_in_deterministic_order(self):
        state, fake = self._fake_subprocess({"village"})
        with mock.patch.object(launcher, "REPORT_ROOT", self.output), \
             mock.patch.object(launcher, "command", side_effect=lambda check, _: ["python", check.id]), \
             mock.patch.object(launcher.subprocess, "run", side_effect=fake), \
             mock.patch.object(launcher, "unavailable", side_effect=lambda check, _: "fixture unavailable" if check.id in ("config", "moss", "preflight") else None):
            self.assertEqual(launcher.main(["--jobs", "3"]), 1)
        reports = list(self.output.glob("*/report.json"))
        self.assertEqual(len(reports), 1)
        report = json.loads(reports[0].read_text(encoding="utf-8"))
        self.assertEqual([row["id"] for row in report["results"]], [check.id for check in launcher.CHECKS])
        self.assertEqual([row["status"] for row in report["results"][-3:]], ["skipped"] * 3)
        self.assertEqual(report["max_concurrency"], 3)
        self.assertEqual(set(state["ids"]), {check.id for check in self.checks})

    def test_windows_child_has_no_console_window_flag(self):
        state, fake = self._fake_subprocess()
        flags = []

        def capture(argv, **kwargs):
            flags.append(kwargs["creationflags"])
            return fake(argv, **kwargs)

        with mock.patch.object(launcher, "command", return_value=["python", "placement"]), \
             mock.patch.object(launcher, "os", name="fake_os") as fake_os, \
             mock.patch.object(launcher.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
             mock.patch.object(launcher.subprocess, "run", side_effect=capture):
            fake_os.name = "nt"
            launcher.run_checks(self.checks[:1], 1, self.output, None)
        self.assertEqual(state["ids"], ["placement"])
        self.assertEqual(flags, [0x08000000])

    def test_launcher_lock_rejects_second_instance(self):
        lock_path = self.output / "launcher.lock"
        with launcher.LauncherLock(lock_path):
            with self.assertRaisesRegex(RuntimeError, "another fast-test launcher"):
                with launcher.LauncherLock(lock_path):
                    pass
        with launcher.LauncherLock(lock_path):
            pass


if __name__ == "__main__":
    unittest.main()
