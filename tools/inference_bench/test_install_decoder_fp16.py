"""Pure safety checks for the bounded FP16 installer; no instance is touched."""
from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("install_decoder_fp16", ROOT / "tools/inference_bench/install_decoder_fp16.py")
INSTALL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSTALL)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class RewritePrecisionTest(unittest.TestCase):
    def test_appends_missing_precision_and_preserves_crlf(self):
        original = b"validate_model=true\r\ninference.device=gpu\r\n"
        self.assertEqual(INSTALL.rewrite_precision(original),
                         original + b"inference.decoder_precision=fp16\r\n")

    def test_replaces_one_precision_without_other_changes(self):
        original = b"validate_model=true\nfoo=bar\ninference.decoder_precision=fp32\n"
        self.assertEqual(INSTALL.rewrite_precision(original),
                         b"validate_model=true\nfoo=bar\ninference.decoder_precision=fp16\n")

    def test_rejects_duplicate_precision(self):
        original = b"validate_model=true\ninference.decoder_precision=fp32\ninference.decoder_precision=fp16\n"
        with self.assertRaisesRegex(INSTALL.InstallError, "at most one"):
            INSTALL.rewrite_precision(original)


class RollbackTest(unittest.TestCase):
    def test_failure_after_config_write_restores_every_replaced_target(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="test-fp16-install-") as temporary:
            root = Path(temporary)
            instance, backup, fixture = root / "instance", root / "backup", root / "fixture"
            targets = {
                "jar": instance / "mods/td.jar",
                "model": instance / "terrain-diffusion-models/decoder_model_fp16.onnx",
                "runtime": instance / "mods/td.jar.onnx-receipt.properties",
                "config": instance / "config/terrain-diffusion-mc.properties",
            }
            originals = {key: (key + "-old").encode() for key in targets}
            for key, target in targets.items():
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(originals[key])
            sources = {}
            for key in ("jar", "model", "runtime"):
                path = root / (key + "-new")
                path.write_bytes((key + "-new").encode())
                sources[key] = path
            updated = b"validate_model=true\ninference.decoder_precision=fp16\n"
            prepared = {
                "fixture": fixture, "instance": instance, "backup": backup,
                "jar": {"source": sources["jar"], "target": targets["jar"], "sha256": digest(sources["jar"].read_bytes())},
                "model": {"source": sources["model"], "target": targets["model"], "sha256": digest(sources["model"].read_bytes())},
                "runtime_receipt": {"source": sources["runtime"], "target": targets["runtime"], "sha256": digest(sources["runtime"].read_bytes())},
                "config": {"target": targets["config"], "after": updated, "after_sha256": digest(updated)},
            }
            real_atomic_bytes = INSTALL.atomic_bytes

            def fail_receipt(data, destination):
                if destination.name == "install-receipt.json":
                    raise OSError("simulated receipt failure")
                return real_atomic_bytes(data, destination)

            with patch.object(INSTALL, "preflight", return_value=prepared), patch.object(INSTALL, "atomic_bytes", side_effect=fail_receipt):
                with self.assertRaisesRegex(OSError, "simulated receipt failure"):
                    INSTALL.apply(prepared)
            for key, target in targets.items():
                self.assertEqual(target.read_bytes(), originals[key], key)


if __name__ == "__main__":
    unittest.main()
