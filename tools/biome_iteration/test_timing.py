"""Offline fixtures for biome-iteration timing summaries."""
from __future__ import annotations

from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("biome_iteration_timing", ROOT / "tools/biome_iteration/timing.py")
timing = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(timing)


class TimingTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".tmp").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.start = datetime(2026, 11, 19, 9, 0, tzinfo=timezone.utc).timestamp()

    def fixture(self, *, include_settle=True):
        shots = self.root / "screenshots"; shots.mkdir()
        first, second = shots / "one.png", shots / "two.png"
        first.write_bytes(b"one"); second.write_bytes(b"two")
        os.utime(first, (self.start + 210, self.start + 210))
        os.utime(second, (self.start + 214, self.start + 214))
        phase = {"startedUtc": "2026-11-19T09:03:00+00:00", "elapsedSeconds": 10.0}
        receipt = {"phases": [phase], "biomeVisualReview": {"sites": [
            {"id": "one", "images": [{"path": str(first)}, {"path": str(second)}]}]}}
        (self.root / "process.json").write_text(json.dumps({"start_epoch": self.start}), encoding="utf-8")
        launch = self.root / "launch-result.json"
        launch.write_text(json.dumps({"passed": True, "exit_code": 0, "receipt": receipt}), encoding="utf-8")
        os.utime(launch, (self.start + 220, self.start + 220))
        logs = self.root / "logs"; logs.mkdir()
        lines = ["[1911월2026 18:01:00.000] Chunk profile opening cloned world chunk-profile"]
        if include_settle:
            lines.append("[1911월2026 18:02:00.000] CHUNK_PROFILE_SETTLE_START")
        logs.joinpath("latest.log").write_text("\n".join(lines), encoding="utf-8")

    def test_breakdown_splits_launcher_world_settle_and_phase(self):
        self.fixture()
        result = timing.breakdown(self.root)
        self.assertEqual(210.0, result["processToFirstImageSeconds"])
        self.assertEqual(1, result["phases"]["count"])
        self.assertEqual(10.0, result["phases"]["sumElapsedSeconds"])
        self.assertEqual(20.0, result["phases"]["phaseCompletionToFirstImageSeconds"])
        self.assertEqual(60.0, result["startup"]["processToWorldOpenSeconds"])
        self.assertEqual(60.0, result["startup"]["worldOpenToSettleStartSeconds"])
        self.assertEqual(60.0, result["startup"]["settleStartToFirstPhaseSeconds"])
        self.assertEqual([], result["startup"]["missingMarkers"])

    def test_missing_marker_is_reported_without_inventing_duration(self):
        self.fixture(include_settle=False)
        result = timing.breakdown(self.root)
        self.assertIn("settleStart", result["startup"]["missingMarkers"])
        self.assertIsNone(result["startup"]["worldOpenToSettleStartSeconds"])
        self.assertIsNone(result["startup"]["settleStartToFirstPhaseSeconds"])

    def test_stale_marker_does_not_hide_a_later_marker_from_this_run(self):
        self.fixture()
        log = self.root / "logs/latest.log"
        log.write_text("\n".join([
            "[1811월2026 18:01:00.000] Chunk profile opening cloned world old-run",
            log.read_text(encoding="utf-8"),
        ]), encoding="utf-8")
        result = timing.breakdown(self.root)
        self.assertEqual(60.0, result["startup"]["processToWorldOpenSeconds"])
        self.assertIn("worldOpen", result["startup"]["ignoredOutOfRangeMarkers"])

    def test_preparation_and_native_timings_remain_separate_from_runtime(self):
        self.fixture()
        (self.root / "prepare-timing.json").write_text(json.dumps({
            "throughManifestSeconds": 91.234,
            "verifiedCopySecondsByGroup": {"mods": 4.5, "terrain-diffusion-models": 80.1},
        }), encoding="utf-8")
        launch = self.root / "launch-result.json"
        row = json.loads(launch.read_text(encoding="utf-8"))
        row["receipt"]["biomeVisualReview"]["timing"] = {"censusSeconds": 12.3, "captureSeconds": 8.4}
        launch.write_text(json.dumps(row), encoding="utf-8")
        os.utime(launch, (self.start + 220, self.start + 220))
        result = timing.breakdown(self.root)
        self.assertEqual(210.0, result["processToFirstImageSeconds"])
        self.assertEqual(91.23, result["preparation"]["throughManifestSeconds"])
        self.assertEqual(80.1, result["preparation"]["verifiedCopySecondsByGroup"]["terrain-diffusion-models"])
        self.assertEqual({"captureSeconds": 8.4, "censusSeconds": 12.3}, result["nativeReview"])

    def test_log_timezone_is_explicit_and_validated(self):
        self.assertEqual("UTC+09:00", timing.log_timezone("+09:00").tzname(None))
        with self.assertRaisesRegex(ValueError, "±HH:MM"):
            timing.log_timezone("Asia/Seoul")


if __name__ == "__main__":
    unittest.main()
