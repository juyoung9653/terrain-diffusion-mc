"""Exercise the actual pre-launch callback and current Java policy, without Minecraft."""
from __future__ import annotations

import contextlib
import gzip
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from streams_probe import run_suite as cli
from streams_probe.aquatic_preflight import ROOT, PACKAGE, fixture_seed

JDK = Path("C:/Program Files/Java/jdk-21") if os.name == "nt" else Path(os.environ.get("JAVA_HOME", "/usr"))
SEED = 2875842661134670257


class AquaticPreflightTest(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / ".tmp"
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "build/streams-probe/test"
        (self.run / "world").mkdir(parents=True)
        (self.run / "server.properties").write_text(f"level-name=world\nlevel-seed={SEED}\n")
        (self.run / "input-manifest.json").write_text(json.dumps({"seed": SEED, "neoforge": "21.1.249"}))
        for relative in (Path("common/src/main/java") / PACKAGE / "world/ecology/SubmergedPlantPolicy.java",
                         Path("tools/streams_probe/java") / PACKAGE / "diagnostic/AquaticFixturePlan.java"):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)

    def invoke(self, chunks: str | None, *, dry=False):
        command = ["test", "--gradle-user-home", str(self.root), "--java-home", str(JDK),
                   "--aquatic-placement-audit", "--max-chunks", "2"]
        if chunks is not None:
            command.append("--chunks=" + chunks)
        if dry:
            command.append("--preflight-only")
        native_children = []
        real_popen = subprocess.Popen

        def only_pure_java(args, *pargs, **kwargs):
            if args[0] == "minecraft-must-not-start":
                native_children.append(args)
                raise AssertionError("Minecraft started after failed preflight")
            return real_popen(args, *pargs, **kwargs)

        with (mock.patch.object(cli, "ROOT", self.root),
              mock.patch.object(cli, "gradle_command", return_value=["minecraft-must-not-start"]),
              mock.patch.object(cli, "validate_tree_ground_runtime", return_value={}),
              mock.patch("probe_suite.subprocess.Popen", side_effect=only_pure_java),
              contextlib.redirect_stderr(io.StringIO()) as err,
              contextlib.redirect_stdout(io.StringIO())):
            try:
                result = cli.main(command)
            except SystemExit as exc:
                result = exc.code
        self.assertEqual(native_children, [])
        return result, err.getvalue()

    def test_zero_site_blocks_real_suite_before_minecraft(self):
        code, error = self.invoke("94,10")
        self.assertEqual(code, 2)
        self.assertIn("No guaranteed sea-pickle site", error)
        self.assertFalse((self.run / "launcher-output.txt").exists())

    def test_selected_adjacent_positive_passes_preflight(self):
        code, error = self.invoke("94,10;95,10", dry=True)
        self.assertEqual((code, error), (0, ""))

    def test_positive_outside_native_search_does_not_count(self):
        code, error = self.invoke("94,10;90,10")
        self.assertEqual(code, 2)
        self.assertIn("No guaranteed sea-pickle site", error)

    def test_implicit_route_and_wrong_seed_block_before_child(self):
        code, error = self.invoke(None)
        self.assertEqual(code, 2)
        self.assertIn("requires explicit --chunks", error)
        (self.run / "server.properties").write_text("level-name=world\nlevel-seed=123\n")
        code, error = self.invoke("94,10;95,10")
        self.assertEqual(code, 2)
        self.assertIn("manifest seed differs", error)

    def test_existing_save_seed_wins_over_server_properties(self):
        def name(value):
            data = value.encode()
            return struct.pack(">H", len(data)) + data
        nbt = (b"\x0a\x00\x00\x0a" + name("Data") + b"\x0a" + name("WorldGenSettings")
               + b"\x04" + name("seed") + struct.pack(">q", SEED) + b"\x00\x00\x00")
        (self.run / "world/level.dat").write_bytes(gzip.compress(nbt))
        (self.run / "server.properties").write_text("level-name=world\nlevel-seed=123\n")
        self.assertEqual(fixture_seed(self.run), SEED)
        (self.run / "world/level.dat").write_bytes(b"invalid")
        with self.assertRaisesRegex(ValueError, "level.dat"):
            fixture_seed(self.run)

    def test_changed_production_policy_is_not_a_cached_success(self):
        policy = self.root / "common/src/main/java" / PACKAGE / "world/ecology/SubmergedPlantPolicy.java"
        text = policy.read_text()
        self.assertIn("return unit(seed, x, z, PICKLE_SALT)", text)
        policy.write_text(text.replace("return unit(seed, x, z, PICKLE_SALT)", "return 1.0"))
        code, error = self.invoke("94,10;95,10")
        self.assertEqual(code, 2)
        self.assertIn("No guaranteed sea-pickle site", error)


if __name__ == "__main__":
    unittest.main()
