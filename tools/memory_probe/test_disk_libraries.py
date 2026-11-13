"""Fixture coverage for receipt verification and atomic disk-library installation."""

import tempfile
import unittest
import zipfile
from pathlib import Path

import disk_libraries
import json
from build_evidence import ROOT, PROBE_RECEIPT, source_fingerprint, check_validations, write_release

SCRATCH = Path(__file__).resolve().parents[2] / ".tmp"
SCRATCH.mkdir(exist_ok=True)

class DiskLibrariesTest(unittest.TestCase):
    def artifact(self, path: Path, manifest: str, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
            for name, body in entries.items():
                archive.writestr(name, body)

    def receipt(self, path: Path, library: Path, mod: Path) -> None:
        path.write_text(
            "formatVersion=1\nmode=neoforge-disk-library\n"
            "module=com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0\n"
            f"sourceSha256={'0' * 64}\n"
            f"libraryFile={library.name}\nlibrarySha256={disk_libraries.sha256(library)}\n"
            f"modFile={mod.name}\nmodSha256={disk_libraries.sha256(mod)}\n",
            encoding="utf-8",
        )

    def test_verify_and_install_pair(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            library, mod, receipt = root / "onnx-library.jar", root / "td.jar", root / "receipt.properties"
            self.artifact(library, "Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nImplementation-Version: 1.20.0\r\n\r\n",
                          {"ai/onnxruntime/OrtEnvironment.class": b"ort",
                           "ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll": b"cuda"})
            self.artifact(mod, "Manifest-Version: 1.0\r\n\r\n", {"td/Main.class": b"td"})
            self.receipt(receipt, library, mod)
            disk_libraries.verify(receipt, library, mod)
            fingerprint = source_fingerprint(ROOT)
            result = root / "probe-result.json"
            result.write_text('{"success":true}')
            proof = root / PROBE_RECEIPT
            proof.write_text(json.dumps({"schema": 1, "kind": "probe-validation", "success": True,
                                         "processExit": 0, "probeStatus": "success",
                                         "sourceFingerprint": fingerprint, "receipt": result.name,
                                         "receiptSha256": disk_libraries.sha256(result), "inputManifestSha256": None}))
            write_release(ROOT, mod, fingerprint, check_validations([proof], fingerprint), [])
            destination = root / "mods"
            disk_libraries.install(receipt, library, mod, destination)
            self.assertEqual(library.read_bytes(), (destination / library.name).read_bytes())
            self.assertEqual(mod.read_bytes(), (destination / mod.name).read_bytes())
            self.assertTrue((destination / (mod.name + ".onnx-receipt.properties")).is_file())

    def test_rejects_nested_or_unmarked_library(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            library, mod, receipt = root / "onnx-library.jar", root / "td.jar", root / "receipt.properties"
            self.artifact(library, "Manifest-Version: 1.0\r\nImplementation-Version: 1.20.0\r\n\r\n",
                          {"ai/onnxruntime/OrtEnvironment.class": b"ort"})
            self.artifact(mod, "Manifest-Version: 1.0\r\n\r\n", {"META-INF/jarjar/onnxruntime.jar": b"nested"})
            self.receipt(receipt, library, mod)
            with self.assertRaisesRegex(ValueError, "FML LIBRARY"):
                disk_libraries.verify(receipt, library, mod)

    def test_rejects_hash_tampering(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            library, mod, receipt = root / "onnx-library.jar", root / "td.jar", root / "receipt.properties"
            self.artifact(library, "Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nImplementation-Version: 1.20.0\r\n\r\n",
                          {"ai/onnxruntime/OrtEnvironment.class": b"ort"})
            self.artifact(mod, "Manifest-Version: 1.0\r\n\r\n", {"td/Main.class": b"td"})
            self.receipt(receipt, library, mod)
            with library.open("ab") as output:
                output.write(b"changed")
            with self.assertRaisesRegex(ValueError, "hash"):
                disk_libraries.verify(receipt, library, mod)

    def test_rejects_library_disguised_as_mod(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            library, mod, receipt = root / "onnx-library.jar", root / "td.jar", root / "receipt.properties"
            self.artifact(library, "Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nImplementation-Version: 1.20.0\r\n\r\n",
                          {"ai/onnxruntime/OrtEnvironment.class": b"ort", "META-INF/neoforge.mods.toml": b"[[mods]]"})
            self.artifact(mod, "Manifest-Version: 1.0\r\n\r\n", {"td/Main.class": b"td"})
            self.receipt(receipt, library, mod)
            with self.assertRaisesRegex(ValueError, "standalone FML LIBRARY"):
                disk_libraries.verify(receipt, library, mod)

    def test_rejects_gpu_library_without_windows_cuda_provider(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            library, mod, receipt = root / "onnx-library.jar", root / "td.jar", root / "receipt.properties"
            self.artifact(library, "Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nImplementation-Version: 1.20.0\r\n\r\n",
                          {"ai/onnxruntime/OrtEnvironment.class": b"ort"})
            self.artifact(mod, "Manifest-Version: 1.0\r\n\r\n", {"td/Main.class": b"td"})
            self.receipt(receipt, library, mod)
            with self.assertRaisesRegex(ValueError, "CUDA native provider"):
                disk_libraries.verify(receipt, library, mod)


if __name__ == "__main__":
    unittest.main()
