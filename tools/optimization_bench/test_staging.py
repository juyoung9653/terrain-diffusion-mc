"""Lightweight staging integration check; it does not launch Minecraft."""
import argparse
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

try:
    from . import launch
except ImportError:  # direct `python tools/optimization_bench/test_staging.py`
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import launch


def write_jar(path: Path, members: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in members.items(): archive.writestr(name, data)


class StagingIntegrationTest(unittest.TestCase):
    def test_replacement_and_config_precede_disk_onnx_guard(self) -> None:
        # The sandbox temp location is read-only on this Windows host; keep the
        # self-cleaning fixture beneath the repository build directory.
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "build") as directory:
            root = Path(directory)
            target = root / "fixture"; (target / "mods").mkdir(parents=True); (target / "config").mkdir()
            nested = root / "nested-onnx.jar"; write_jar(nested, {"ai/onnxruntime/OrtEnvironment.class": b"old"})
            write_jar(target / "mods" / "terrain.jar", {"META-INF/jarjar/onnxruntime_gpu.jar": nested.read_bytes()})
            replacement = root / "terrain-slim.jar"; write_jar(replacement, {"mod.txt": b"slim"})
            companion = root / "onnx-disk.jar"; write_jar(companion, {"ai/onnxruntime/OrtEnvironment.class": b"disk"})
            (target / "config" / "sample.properties").write_text("enabled=false\n", encoding="utf-8")
            args = argparse.Namespace(instance=root / "empty-instance", replace_mod=[f"terrain.jar={replacement}"],
                                      config_line=["config/sample.properties:enabled=true"], probe_jar=None,
                                      extra_mod=[companion], exclude_prefix=[])
            receipt = launch.add_runtime_inputs(args, target)
            self.assertEqual(hashlib.sha256((target / "mods" / "terrain.jar").read_bytes()).hexdigest(), hashlib.sha256(replacement.read_bytes()).hexdigest())
            self.assertEqual((target / "config" / "sample.properties").read_text(encoding="utf-8"), "enabled=true\n")
            self.assertTrue(any(entry.startswith("replace:terrain.jar:source=") for entry in receipt))
            self.assertIn("config:config/sample.properties:enabled=true", receipt)
            self.assertTrue((target / "mods" / companion.name).is_file())


if __name__ == "__main__":
    unittest.main()
