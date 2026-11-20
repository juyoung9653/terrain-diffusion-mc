"""No Minecraft: exercise stale evidence through the actual build/install entry points."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_evidence as evidence
from memory_probe import disk_libraries


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=evidence.ROOT / ".tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "common/src/main/java/Main.java"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("original")
        self.run = self.root / "probe"
        self.run.mkdir()
        self.receipt = self.run / "result.json"
        self.receipt.write_text('{"success":true}')
        self.proof = self.run / evidence.PROBE_RECEIPT
        self.fingerprint = evidence.source_fingerprint(self.root)
        self.proof.write_text(json.dumps({
            "schema": 1, "kind": "probe-validation", "success": True,
            "processExit": 0, "probeStatus": "success", "sourceFingerprint": self.fingerprint,
            "receipt": "result.json", "receiptSha256": evidence.sha256(self.receipt),
            "inputManifestSha256": None,
        }))
        self.mod = self.root / "td.jar"
        with zipfile.ZipFile(self.mod, "w") as archive:
            archive.writestr("example/Main.class", b"original")

    def release(self):
        return evidence.write_release(self.root, self.mod, self.fingerprint,
                                      evidence.check_validations([self.proof], self.fingerprint), ["build"])

    def test_unchanged_evidence_binds_exact_jar(self):
        self.release()
        self.assertEqual(evidence.verify_release(self.mod, self.root)["modSha256"], evidence.sha256(self.mod))
        with self.mod.open("ab") as stream:
            stream.write(b"different artifact")
        with self.assertRaisesRegex(ValueError, "JAR differs"):
            evidence.verify_release(self.mod, self.root)

    def test_source_added_changed_or_deleted_invalidates_evidence(self):
        self.release()
        self.source.write_text("changed")
        with self.assertRaisesRegex(ValueError, "sources changed"):
            evidence.verify_release(self.mod, self.root)
        self.source.write_text("original")
        extra = self.source.with_name("Added.java")
        extra.write_text("untracked input")
        with self.assertRaisesRegex(ValueError, "source differs"):
            evidence.check_validations([self.proof], evidence.source_fingerprint(self.root))
        extra.unlink()
        self.source.unlink()
        with self.assertRaisesRegex(ValueError, "source differs"):
            evidence.check_validations([self.proof], evidence.source_fingerprint(self.root))

    def test_failed_probe_and_replaced_result_cannot_be_released(self):
        row = json.loads(self.proof.read_text())
        row["success"] = False
        self.proof.write_text(json.dumps(row))
        with self.assertRaisesRegex(ValueError, "successful probe"):
            self.release()
        row["success"] = True
        self.proof.write_text(json.dumps(row))
        self.receipt.write_text('{"success":false}')
        with self.assertRaisesRegex(ValueError, "replaced or changed"):
            self.release()

    def test_diagnostic_jar_rejected_and_during_build_mutation_rejected(self):
        validations = evidence.check_validations([self.proof], self.fingerprint)
        self.source.write_text("changed during build")
        with self.assertRaisesRegex(ValueError, "during build"):
            evidence.write_release(self.root, self.mod, self.fingerprint, validations, [])
        self.source.write_text("original")
        with zipfile.ZipFile(self.mod, "a") as archive:
            archive.writestr("com/example/diagnostic/Probe.class", b"probe")
        with self.assertRaisesRegex(ValueError, "diagnostic output"):
            self.release()

    def test_install_fails_before_any_copy_when_evidence_missing_or_stale(self):
        destination = self.root / "instance/mods"
        with patch.object(disk_libraries, "atomic_copy") as copy:
            with self.assertRaises(FileNotFoundError):
                disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                       self.mod, destination)
            copy.assert_not_called()
        self.release()
        self.source.write_text("changed")
        with patch.object(disk_libraries, "verify_release",
                          side_effect=lambda mod: evidence.verify_release(mod, self.root)), \
                patch.object(disk_libraries, "atomic_copy") as copy:
            with self.assertRaisesRegex(ValueError, "sources changed"):
                disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                       self.mod, destination)
            copy.assert_not_called()
        self.assertFalse(destination.exists())
        self.source.write_text("original")
        with self.mod.open("ab") as stream:
            stream.write(b"other jar")
        with patch.object(disk_libraries, "verify_release",
                          side_effect=lambda mod: evidence.verify_release(mod, self.root)), \
                patch.object(disk_libraries, "atomic_copy") as copy:
            with self.assertRaisesRegex(ValueError, "JAR differs"):
                disk_libraries.install(self.root / "onnx.properties", self.root / "library.jar",
                                       self.mod, destination)
            copy.assert_not_called()
        self.assertFalse(destination.exists())

    @patch("biome_review.contract.validate_reviewed_baseline")
    def test_translation_only_review_baseline_is_bound_to_exact_jars(self, validated):
        from biome_review import contract
        ecology = self.root / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/ecology"
        ecology.mkdir(parents=True)
        ecology.joinpath("Biome.java").write_text("reviewed", encoding="utf-8")
        self.fingerprint = evidence.source_fingerprint(self.root)
        row = json.loads(self.proof.read_text(encoding="utf-8"))
        row["sourceFingerprint"] = self.fingerprint
        self.proof.write_text(json.dumps(row), encoding="utf-8")
        baseline = self.root / "baseline.jar"
        candidate = self.root / "candidate.jar"
        validated.return_value = {"sourceFingerprint": "baseline-source"}
        def write_jar(path, language, body=b"same"):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("example/Main.class", body)
                archive.writestr("assets/example/lang/en_us.json", language)
        write_jar(baseline, b'{"key":"old"}')
        write_jar(candidate, b'{"key":"new"}')
        source = "baseline-source"
        candidate_build = self.root / "baseline-candidate-build.json"
        candidate_build.write_text(json.dumps({"kind": "validated-neoforge-build",
            "modSha256": evidence.sha256(baseline), "sourceFingerprint": source}), encoding="utf-8")
        bundle = self.root / "baseline-bundle.json"
        bundle.write_text(json.dumps({"sourceFingerprint": source, "artifactSha256": evidence.sha256(baseline),
            "candidateBuild": {"path": str(candidate_build), "sha256": evidence.sha256(candidate_build)}}),
            encoding="utf-8")
        review = self.root / "baseline-review.json"
        review.write_text(json.dumps({"schema": 1, "kind": "independent-biome-review",
            "verdict": "pass", "processExit": 0, "sourceFingerprint": source,
            "artifactSha256": evidence.sha256(baseline),
            "toolFingerprint": contract.review_tool_fingerprint(self.root),
            "bundlePath": str(bundle), "bundleSha256": contract.digest(bundle)}), encoding="utf-8")

        approval = evidence.check_biome_review(self.root, None, releasing=True,
            artifact=evidence.sha256(candidate), baseline_review=review, baseline_jar=baseline,
            candidate_jar=candidate)
        self.assertEqual(approval["exemption"]["changedJarEntries"], ["assets/example/lang/en_us.json"])
        evidence.write_release(self.root, candidate, self.fingerprint,
            evidence.check_validations([self.proof], self.fingerprint), ["build"],
            biome_review_baseline=review, biome_review_baseline_jar=baseline)
        self.assertEqual(evidence.verify_release(candidate, self.root)["biomeReview"], approval)
        write_jar(candidate, b'{"key":"new"}', b"changed")
        with self.assertRaisesRegex(ValueError, "translation JSON"):
            evidence.check_biome_review(self.root, None, releasing=True,
                artifact=evidence.sha256(candidate), baseline_review=review, baseline_jar=baseline,
            candidate_jar=candidate)


    def test_translation_exemption_rejects_duplicate_unsafe_and_nested_entries(self):
        baseline = self.root / "baseline.jar"
        candidate = self.root / "candidate.jar"
        def archive(path, language=b'{"key":"old"}', extra=None):
            with zipfile.ZipFile(path, "w") as value:
                value.writestr("example/Main.class", b"same")
                value.writestr("assets/example/lang/en_us.json", language)
                if extra:
                    for name, contents in extra:
                        value.writestr(name, contents)
        archive(baseline)
        baseline_info = {"jarPath": str(baseline)}
        archive(candidate, extra=[("assets/example/lang/en_us.json", b'{"key":"new"}')])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            evidence._translation_only_jar_change(baseline_info, candidate)
        archive(candidate, b'{"key":"new"}', [("../assets/example/lang/en_us.json", b'{"key":"new"}')])
        with self.assertRaisesRegex(ValueError, "unsafe"):
            evidence._translation_only_jar_change(baseline_info, candidate)
        archive(candidate, b'{"nested":{"key":"new"}}')
        with self.assertRaisesRegex(ValueError, "flat"):
            evidence._translation_only_jar_change(baseline_info, candidate)


    def test_unvalidated_build_is_not_installable(self):
        evidence.write_release(self.root, self.mod, self.fingerprint, [], [])
        with self.assertRaisesRegex(ValueError, "requires a build"):
            evidence.verify_release(self.mod, self.root)


if __name__ == "__main__":
    unittest.main()
