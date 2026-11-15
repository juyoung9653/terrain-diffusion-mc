"""Exercise missing coverage at the real build/install boundaries, without Minecraft."""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import build_evidence as evidence
import build_neoforge as build
from memory_probe import disk_libraries
from streams_probe.release_checks import diagnostic_inputs
from streams_probe.test_river_regression_receipt import receipt as river_receipt


class ReleaseChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "fixture"
        (self.run / "mods").mkdir(parents=True)
        self.jar = self.run / "mods/renamed-streams.jar"
        self.write_jar(self.jar)
        self.manifest = self.run / "input-manifest.json"
        self.manifest.write_text(json.dumps({"streamsVersion": "2.17.3", "mods": [
            {"file": self.jar.name, "sha256": evidence.sha256(self.jar)}]}))
        self.diagnostic = self.root / "tools/streams_probe/java/Probe.java"
        self.diagnostic.parent.mkdir(parents=True)
        self.diagnostic.write_text("original diagnostic")
        self.result = self.run / "result.jsonl"
        self.proof = self.run / evidence.PROBE_RECEIPT
        self.mod = self.root / "td.jar"
        with zipfile.ZipFile(self.mod, "w") as archive:
            archive.writestr("example/Main.class", b"production")
        self.java = self.root / "jdk"
        (self.java / "bin").mkdir(parents=True)
        for tool in ("java", "javac"):
            (self.java / "bin" / (tool + (".exe" if os.name == "nt" else ""))).touch()
        (self.root / ".gradle").mkdir()
        (self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")).touch()
        self.write_evidence(covered=False)

    def write_jar(self, path, version="2.17.3", content=b"selected"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/neoforge.mods.toml",
                             'modLoader="javafml"\n[[mods]]\nmodId="streamsreflowing"\n'
                             f'version="{version}"\n')
            archive.writestr("example/Streams.class", content)

    def write_evidence(self, covered):
        rows = [{"event": "complete", "status": "success"}]
        if covered:
            rows = [{"event": "native_water_settled", "status": "passed", "startTick": "10",
                     "endTick": "110", "tickableChunks": "2", "selectedChunks": "2"},
                    river_receipt(), *rows]
        self.result.write_text("\n".join(json.dumps(row) for row in rows))
        inputs = diagnostic_inputs(self.root)
        self.proof.write_text(json.dumps({
            "schema": 1, "kind": "probe-validation", "success": True, "processExit": 0,
            "probeStatus": "success", "sourceFingerprint": evidence.source_fingerprint(self.root),
            "receipt": self.result.name, "receiptSha256": evidence.sha256(self.result),
            "inputManifestSha256": evidence.sha256(self.manifest),
            "diagnosticInputs": [p.relative_to(self.root).as_posix() for p in inputs],
            "diagnosticInputsSha256": evidence.input_fingerprint(self.root, inputs),
            "command": ["-PtdProbe.chunks=-64,60;-70,72", "-PtdProbe.settleWater=true",
                        "-PtdProbe.riverRegressionAudit=true"] if covered else [],
        }))

    def release(self):
        fingerprint = evidence.source_fingerprint(self.root)
        return evidence.write_release(self.root, self.mod, fingerprint,
                                      evidence.check_validations([self.proof], fingerprint, self.root), [])

    def test_unrelated_success_stops_real_build_before_gradle(self):
        with patch.object(build.subprocess, "run") as child:
            with self.assertRaisesRegex(ValueError, "requires --river-regression-audit"):
                build.run(self.root, java_home=self.java, validations=[self.proof])
            child.assert_not_called()

    def test_matching_physical_receipt_can_be_released(self):
        self.write_evidence(covered=True)
        self.release()
        evidence.verify_release(self.mod, self.root)

    def test_old_unfrozen_evidence_is_not_coverage(self):
        self.write_evidence(covered=True)
        row = json.loads(self.proof.read_text())
        del row["diagnosticInputs"]
        del row["diagnosticInputsSha256"]
        self.proof.write_text(json.dumps(row))
        with self.assertRaisesRegex(ValueError, "frozen diagnostic inputs"):
            self.release()

    def test_install_rejects_changed_destination_streams_before_copy(self):
        self.write_evidence(covered=True)
        self.release()
        destination = self.root / "instance/mods"
        destination.mkdir(parents=True)
        self.write_jar(destination / "streams.jar", content=b"different selected bytes")
        with patch.object(disk_libraries, "ROOT", self.root), \
                patch.object(disk_libraries, "verify_release",
                             side_effect=lambda mod: evidence.verify_release(mod, self.root)), \
                patch.object(disk_libraries, "atomic_copy") as copy, \
                patch.object(disk_libraries, "verify") as pairing:
            with self.assertRaisesRegex(ValueError, "selected Streams JAR"):
                disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                       self.mod, destination)
            copy.assert_not_called()
            pairing.assert_not_called()

    def test_install_with_matching_streams_reaches_normal_pairing_and_copy(self):
        self.write_evidence(covered=True)
        self.release()
        destination = self.root / "instance/mods"
        destination.mkdir(parents=True)
        (destination / "renamed-again.jar").write_bytes(self.jar.read_bytes())
        with patch.object(disk_libraries, "ROOT", self.root), \
                patch.object(disk_libraries, "verify_release",
                             side_effect=lambda mod: evidence.verify_release(mod, self.root)), \
                patch.object(disk_libraries, "atomic_copy") as copy, \
                patch.object(disk_libraries, "verify") as pairing:
            disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                   self.mod, destination)
            pairing.assert_called_once()
            self.assertEqual(copy.call_count, 3)

    def test_replaced_fixture_jar_stops_build_before_gradle(self):
        self.write_evidence(covered=True)
        self.write_jar(self.jar, content=b"replacement")
        with patch.object(build.subprocess, "run") as child:
            with self.assertRaisesRegex(ValueError, "differs from prepared inputs"):
                build.run(self.root, java_home=self.java, validations=[self.proof])
            child.assert_not_called()

    def test_changed_diagnostic_stops_install_before_copy(self):
        self.write_evidence(covered=True)
        self.release()
        self.diagnostic.write_text("changed after probe")
        with patch.object(disk_libraries, "ROOT", self.root), \
                patch.object(disk_libraries, "verify_release",
                             side_effect=lambda mod: evidence.verify_release(mod, self.root)), \
                patch.object(disk_libraries, "atomic_copy") as copy:
            with self.assertRaisesRegex(ValueError, "diagnostic inputs changed"):
                disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                       self.mod, self.root / "instance/mods")
            copy.assert_not_called()


if __name__ == "__main__":
    unittest.main()
