"""Fresh Streams fixture scale selection without launching Minecraft."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from streams_probe import prepare

NBT_FIELDS = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]


class PrepareScaleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        (self.source / "mods").mkdir(parents=True)
        (self.source / "config").mkdir()
        (self.source / "terrain-diffusion-models").mkdir()
        (self.source / "mods/StreamsReflowing-1.21.1-neoforge-2.17.1.jar").write_bytes(b"streams")
        (self.source / "config/terrain-diffusion-mc.properties").write_text("decoder_precision=fp32\n")
        (self.source / "config/streamsreflowing.properties").write_text("generateStreams=true\ngenerateLakes=true\n")
        for name in ("base_model.onnx", "coarse_model.onnx", "decoder_model.onnx",
                     "pipeline_data.json", "world_pipeline_config.json"):
            (self.source / "terrain-diffusion-models" / name).write_bytes(name.encode())

    def prepare(self, *arguments: str) -> Path:
        run_root = self.root / "runs"
        with mock.patch.object(prepare, "RUNS", run_root), mock.patch.object(
                sys, "argv", ["prepare.py", "--source", str(self.source), "--name", "scale", *arguments]):
            prepare.main()
        return run_root / "scale"

    def test_scale_one_persists_marker_and_preset(self) -> None:
        run = self.prepare("--streams-version", "2.17.1", "--scale", "1", "--seed", "42")
        manifest = json.loads((run / "input-manifest.json").read_text())
        marker = run / "world/data/terrain_diffusion_world_settings.dat"
        fields = NBT_FIELDS(gzip.decompress(marker.read_bytes()))
        preset = json.loads((run / "world/datapacks/td-probe-world-preset/data/"
                             "terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json").read_text())
        self.assertEqual(manifest["scale"], 1)
        self.assertEqual(manifest["freshScaleMarker"], {
            "scale": 1, "sha256": hashlib.sha256(marker.read_bytes()).hexdigest()})
        self.assertEqual(fields[("data", "scale")][3], 1)
        self.assertEqual(fields[("data", "explicit_scale")][3], 1)
        self.assertEqual(fields[("data", "fast_ground_sampling")][3], 1)
        self.assertEqual(preset["dimensions"]["minecraft:overworld"]["type"],
                         "terrain-diffusion-mc:terrain_diffusion_scale_1")

    def test_default_scale_keeps_the_base_preset_but_is_explicit(self) -> None:
        run = self.prepare("--streams-version", "2.17.1")
        manifest = json.loads((run / "input-manifest.json").read_text())
        self.assertEqual(manifest["scale"], 2)
        self.assertTrue((run / "world/data/terrain_diffusion_world_settings.dat").is_file())
        self.assertFalse((run / "world/datapacks/td-probe-world-preset").exists())


if __name__ == "__main__":
    unittest.main()
