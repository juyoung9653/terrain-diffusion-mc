"""Exercise failed/stale realism evidence through actual release/install guards."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from biome_review import contract
import build_evidence
import build_neoforge
from memory_probe import disk_libraries


class ReviewContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=ROOT / ".tmp")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.ecology = self.root / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/ecology"
        self.ecology.mkdir(parents=True)
        (self.ecology / "Example.java").write_text("candidate")
        self.fp = build_evidence.source_fingerprint(self.root)
        self.tool = contract.review_tool_fingerprint(self.root)
        self.jar = self.root / "candidate.jar"
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("example.class", "candidate")
        self.sha = contract.digest(self.jar)

    def write(self, name, value):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(value), encoding="utf-8")
        return p

    def evidence(self):
        biome = "example:forest"
        sites, images = [], []
        for i, reason in enumerate(("interior", "boundary", "random")):
            site = {"id": str(i), "x": i * 32, "z": 0, "biome": biome,
                    "reasons": [reason], "reachedServerTicks": 100,
                    "tickEligible": True,
                    "settledColumn": {"groundY": 63, "waterDepth": 0, "bedBlock": "minecraft:dirt"},
                    "images": []}
            for view in sorted(contract.VIEWS):
                path = self.root / f"{i}-{view}.png"
                path.write_bytes(b"image" * 300)
                site["images"].append({"view": view, "path": str(path)})
                images.append(dict(contract.file_ref(path), id=f"{i}-{view}", siteId=str(i),
                                   biome=biome, view=view))
            sites.append(site)
        plan = self.write("plan.json", {"sourceFingerprint": self.fp, "artifactSha256": self.sha,
                                      "requiredBiomes": [biome], "regressionSites": [{"x": 0, "z": 0}]})
        capture = self.write("receipt.json", {"status": "success", "biomeVisualReview": {
            "status": "captured", "sourceFingerprint": self.fp, "artifactSha256": self.sha,
            "captureToolFingerprint": contract.capture_tool_fingerprint(self.root),
            "requiredBiomes": [biome], "coverageGaps": [], "seed": 5, "scale": 1, "sites": sites}})
        manifest = self.write("input-manifest.json", {"seed": 5, "scale": 1,
                 "terrain_diffusion_artifact": {"sha256": self.sha}})
        launch = self.write("launch-result.json", {"passed": True, "exit_code": 0,
                                                   "source_world_metadata_unchanged": True})
        census = self.write("columns.json", {"columns": [{"bedBlock": "minecraft:dirt"}]})
        candidate = self.write("candidate-build.json", {"kind": "validated-neoforge-build",
                              "modSha256": self.sha, "sourceFingerprint": self.fp})
        bundle = self.write("bundle.json", {"schema": 2, "kind": "biome-review-bundle",
            "sourceFingerprint": self.fp, "artifactSha256": self.sha,
            "bundleToolFingerprint": contract.bundle_tool_fingerprint(self.root),
            "fixture": contract.file_ref(manifest), "launch": contract.file_ref(launch),
            "capture": contract.file_ref(capture), "plan": contract.file_ref(plan),
            "candidateBuild": contract.file_ref(candidate),
            "requiredBiomes": [biome], "coverage": [], "images": images,
            "evidence": [contract.file_ref(census)]})
        ids = [i["id"] for i in images]
        raw = self.write("batch-00/response.json", {"verdict": "pass", "summary": "Reviewed all scenes",
            "inspectedImageIds": ids, "findings": [], "coverageGaps": [], "additionalCaptures": []})
        semantic = contract.semantic_evidence_identity(contract.load(bundle))
        started = self.write("batch-00/started.json", {"schema": 1, "kind": "independent-biome-review-attempt",
            "index": 0, "imageIds": ids, "overviewImageIds": [], "bundleSha256": contract.digest(bundle),
            "semanticEvidenceIdentity": semantic, "toolFingerprint": self.tool})
        command = self.write("batch-00/command.json", {"command": ["codex.exe", "exec", "--ephemeral", "--sandbox", "read-only",
            "--output-schema", "schema.json", "--output-last-message", str(raw),
            *sum((["--image", str(self.root / f"{i}-{view}.png")] for i in range(3) for view in sorted(contract.VIEWS)), [])],
            "imageIds": ids, "overviewImageIds": []})
        stdout = self.root / "batch-00/stdout.txt"; stdout.parent.mkdir(parents=True, exist_ok=True); stdout.write_text("", encoding="utf-8")
        stderr = self.root / "batch-00/stderr.txt"; stderr.write_text("", encoding="utf-8")
        result = self.write("batch-00/result.json", {"index": 0, "imageIds": ids, "overviewImageIds": [],
                                                       "processExit": 0, "verdict": "pass"})
        history = self.write("history.json", {"kind": "independent-biome-review-history",
            "bundleSha256": contract.digest(bundle), "sourceFingerprint": self.fp,
            "artifactSha256": self.sha, "semanticEvidenceIdentity": semantic,
            "toolFingerprint": self.tool, "outputPath": str(self.root)})
        return self.write("review.json", {"schema": 1, "kind": "independent-biome-review",
            "verdict": "pass", "processExit": 0, "sourceFingerprint": self.fp,
            "artifactSha256": self.sha, "toolFingerprint": self.tool,
            "bundlePath": str(bundle), "bundleSha256": contract.digest(bundle), "semanticEvidenceIdentity": semantic,
            "inspectedImageIds": ids, "findings": [], "batches": [{"index": 0, "imageIds": ids, "processExit": 0}],
            "historyPath": str(history), "historySha256": contract.digest(history),
            "coverageGaps": [], "additionalCaptures": [], "evidence": [contract.file_ref(raw), contract.file_ref(command),
                contract.file_ref(started), contract.file_ref(stdout), contract.file_ref(stderr), contract.file_ref(result), contract.file_ref(history)]})

    def test_missing_review_stops_build_before_gradle(self):
        with patch.object(build_neoforge, "build_plan", return_value=(["gradle"], {})), \
                patch.object(build_neoforge, "check_validations", return_value=[{"test": True}]), \
                patch.object(build_neoforge.subprocess, "run") as child:
            with self.assertRaisesRegex(ValueError, "requires --biome-review"):
                build_neoforge.run(self.root, validations=[self.root / "probe.json"])
            child.assert_not_called()

    def test_install_cannot_copy_without_independent_review(self):
        self.write("candidate.jar.validation.json", {"schema": 1, "kind": "validated-neoforge-build",
            "modSha256": self.sha, "sourceFingerprint": self.fp, "validations": [{"path": "unused"}]})
        # Enter the actual installer; old sidecars are deliberately not granted
        # realism approval by a generic successful probe.
        with patch.object(disk_libraries, "verify_release",
                          side_effect=lambda _: build_evidence.check_biome_review(self.root, None, releasing=True)), \
                patch.object(disk_libraries, "atomic_copy") as copy:
            with self.assertRaisesRegex(ValueError, "requires --biome-review"):
                disk_libraries.install(self.root / "onnx.properties", self.root / "lib.jar",
                                       self.jar, self.root / "mods")
            copy.assert_not_called()

    def test_pass_is_exact_to_artifact_and_images(self):
        review = self.evidence()
        contract.validate_review(review, self.root, self.sha)
        with self.assertRaisesRegex(ValueError, "release artifact"):
            contract.validate_review(review, self.root, "other")
        (self.root / "0-aerial.png").write_bytes(b"different")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            contract.validate_review(review, self.root)

    def test_release_guard_accepts_resume_and_rejudges_the_same_bundle_after_rule_change(self):
        from biome_review import runner

        self.evidence()
        bundle_path = self.root / "bundle.json"
        bundle = contract.load(bundle_path)
        plan = self.root / "plan.json"
        self.write("plan.json", dict(contract.load(plan), reviewTask="site-repair", seed=5))
        bundle["plan"] = contract.file_ref(plan)
        self.write("bundle.json", bundle)
        reviewer = self.root / "codex.exe"
        reviewer.touch()
        output = self.root / "independent-review"

        def complete_batch(argv, **kwargs):
            ids = [row["id"] for row in bundle["images"]]
            response = Path(argv[argv.index("--output-last-message") + 1])
            response.write_text(json.dumps({"verdict": "pass", "summary": "Inspected all views",
                                "inspectedImageIds": ids, "findings": [], "coverageGaps": [],
                                "additionalCaptures": []}), encoding="utf-8")
            return subprocess.CompletedProcess(argv, 0, "ok", "")

        with patch.dict(sys.modules, {"contract": contract}), patch.object(runner, "ROOT", self.root), \
                patch.object(runner, "HISTORY_ROOT", self.root / "history"):
            with patch.object(runner.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "offline")):
                first = runner.run(bundle_path, output, codex=reviewer)
            self.assertNotEqual(first["verdict"], "pass")
            with patch.object(runner.subprocess, "run", side_effect=complete_batch):
                final = runner.run(bundle_path, output, codex=reviewer, resume=True)
                contract.validate_review(output / "review.json", self.root, self.sha)
                self.write("tools/biome_review/reviewer.schema.json", {"description": "updated rules"})
                with self.assertRaisesRegex(ValueError, "review tools changed"):
                    contract.validate_review(output / "review.json", self.root, self.sha)
                second_output = self.root / "second-review"
                second = runner.run(bundle_path, second_output, codex=reviewer)
        self.assertEqual((first["reviewRound"], final["reviewRound"], second["reviewRound"]), (1, 1, 2))
        self.assertEqual(first["bundleSha256"], second["bundleSha256"])
        accepted = contract.validate_review(second_output / "review.json", self.root, self.sha)
        self.assertEqual(accepted["artifactSha256"], self.sha)

    def test_translation_carry_forward_still_checks_original_reviewer_response(self):
        review = self.evidence()
        rel = "assets/example/lang/ko_kr.json"
        self.write("common/src/main/resources/" + rel, {"key": "translation"})
        candidate = self.root / "translated.jar"
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("example.class", "candidate")
            archive.writestr(rel, '{"key":"translation"}')
        with self.assertRaisesRegex(ValueError, "sources changed"):
            contract.validate_review(review, self.root, self.sha)
        def check():
            return build_evidence.check_biome_review(
                self.root, None, releasing=True, artifact=contract.digest(candidate),
                candidate_jar=candidate, baseline_review=review, baseline_jar=self.jar)
        self.assertEqual(check()["exemption"]["changedJarEntries"], [rel])
        raw = self.root / "batch-00/response.json"
        self.write("batch-00/response.json", dict(contract.load(raw), verdict="revise"))
        row = contract.load(review)
        row["evidence"] = [contract.file_ref(raw) if ref["path"] == str(raw) else ref
                           for ref in row["evidence"]]
        self.write("review.json", row)
        with self.assertRaisesRegex(ValueError, "raw response did not pass"):
            check()

    def test_exit_zero_does_not_override_findings_or_missing_views(self):
        review = self.evidence()
        row = contract.load(review)
        for field, value in (("findings", [{"severity": "major"}]),
                             ("additionalCaptures", [{"reason": "unseen bed"}]),
                             ("coverageGaps", ["coast"]), ("inspectedImageIds", [])):
            self.write("review.json", dict(row, **{field: value}))
            with self.assertRaises(ValueError, msg=field):
                contract.validate_review(review, self.root)

    def test_source_and_review_tool_edits_invalidate_approval(self):
        review = self.evidence()
        (self.ecology / "NewBiome.java").write_text("untracked addition")
        with self.assertRaisesRegex(ValueError, "sources changed"):
            contract.validate_review(review, self.root)
        (self.ecology / "NewBiome.java").unlink()
        self.write("tools/biome_review/reviewer.schema.json", {})
        contract.validate_bundle(self.root / "bundle.json", self.root)
        with self.assertRaisesRegex(ValueError, "tools changed"):
            contract.validate_review(review, self.root)

    def test_changes_invalidate_only_the_stage_that_produced_the_evidence(self):
        review = self.evidence()
        bundle = self.root / "bundle.json"
        self.write("tools/biome_review/test_runner.py", {"new": "test"})
        contract.validate_review(review, self.root, self.sha)
        packaging = self.write("tools/biome_review/bundle.py", {"new": "overview"})
        with self.assertRaisesRegex(ValueError, "rebuild the bundle"):
            contract.validate_bundle(bundle, self.root)
        packaging.unlink()
        self.write("tools/chunk_profile/resources/td-chunk-profile.mixins.json", {"new": "capture"})
        with self.assertRaisesRegex(ValueError, "recapture"):
            contract.validate_bundle(bundle, self.root)

    def test_legacy_capture_cannot_be_relabelled_by_repackaging(self):
        self.evidence()
        bundle, capture = contract.load(self.root / "bundle.json"), contract.load(self.root / "receipt.json")
        native = capture["biomeVisualReview"]
        native["reviewToolFingerprint"] = native.pop("captureToolFingerprint")
        bundle["capture"] = contract.file_ref(self.write("receipt.json", capture))
        with self.assertRaisesRegex(ValueError, "separate provenance"):
            contract.validate_bundle(self.write("bundle.json", bundle), self.root)

    def test_semantic_identity_does_not_depend_on_bundle_output_paths(self):
        review = self.evidence()
        bundle = contract.load(contract.load(review)["bundlePath"])
        repackaged = json.loads(json.dumps(bundle))
        for name in ("fixture", "launch", "capture", "plan", "candidateBuild"):
            repackaged[name]["path"] = "another-output/" + Path(repackaged[name]["path"]).name
        for item in [*repackaged["images"], *repackaged["evidence"]]:
            item["path"] = "another-output/" + Path(item["path"]).name
        self.assertEqual(contract.semantic_evidence_identity(bundle),
                         contract.semantic_evidence_identity(repackaged))

    def test_passing_summary_requires_command_start_and_log_evidence(self):
        review = self.evidence()
        row = contract.load(review)
        reduced = [item for item in row["evidence"] if not item["path"].endswith("started.json")]
        self.write("review.json", dict(row, evidence=reduced))
        with self.assertRaisesRegex(ValueError, "raw response"):
            contract.validate_review(review, self.root)

    def test_changed_biome_resources_include_edits_and_untracked(self):
        (self.root / ".git").mkdir()

        def git(command, **kwargs):
            arguments = command[3:]
            output = {
                ("rev-parse", "--is-inside-work-tree"): "true\n",
                ("diff", "--name-only", "--diff-filter=ACMR", "HEAD"):
                    "common/src/main/resources/data/example/worldgen/biome/fresh/swamp.json\n",
                ("ls-files", "--others", "--exclude-standard"):
                    "neoforge/src/generated/resources/data/example/worldgen/biome/mangrove.json\n"
                    "README.md\n",
            }[tuple(arguments)]
            return subprocess.CompletedProcess(command, 0, output, "")

        with patch.object(contract.subprocess, "run", side_effect=git):
            self.assertEqual({"example:fresh/swamp", "example:mangrove"},
                             contract.changed_biome_resource_ids(self.root))

    def test_real_checkout_git_failure_fails_closed(self):
        (self.root / ".git").mkdir()
        with patch.object(contract.subprocess, "run",
                          return_value=subprocess.CompletedProcess(["git"], 1, "", "bad git")):
            with self.assertRaisesRegex(ValueError, "Cannot inspect changed biome resources"):
                contract.changed_biome_resource_ids(self.root)

    def test_changed_biome_must_be_in_required_plan_coverage(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        with patch.object(contract, "changed_biome_resource_ids", return_value={"example:new_wetland"}):
            with self.assertRaisesRegex(ValueError, "omits changed biome resources: example:new_wetland"):
                contract.validate_bundle(bundle_path, self.root)

    def test_bundle_cannot_repeat_a_native_view(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        bundle = contract.load(bundle_path)
        duplicate = dict(bundle["images"][0], id="duplicate-native-view")
        bundle["images"][-1] = duplicate
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "duplicated a native"):
            contract.validate_bundle(bundle_path, self.root)

    def scoped_bundle(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        bundle = contract.load(bundle_path)
        plan_path, capture_path = Path(bundle["plan"]["path"]), Path(bundle["capture"]["path"])
        plan, capture = contract.load(plan_path), contract.load(capture_path)
        plan["seed"], plan["scale"] = 5, 1
        plan["regressionSites"] = [{"id": "0", "x": 0, "z": 0}]
        baseline = {"schema": 1, "kind": "scoped-biome-baseline-facts", "seed": 5, "scale": 1,
                    "artifactSha256": "b" * 64, "sourceFingerprint": "c" * 64,
                    "sites": [{"id": "0", "x": 0, "z": 0, "bounds": [0, 0, 0, 0], "metrics": {"bed": "minecraft:dirt"}}]}
        baseline_path = self.write("baseline-facts.json", baseline)
        plan["reviewScope"] = {"schema": 1, "id": "scoped-test", "roundLimit": 1,
            "criteria": [{"id": "reported", "siteIds": ["0"], "description": "reported site", "acceptance": "is repaired"}],
            "baseline": {"bundleSha256": "d" * 64, "artifactSha256": "b" * 64,
                         "sourceFingerprint": "c" * 64, "facts": contract.file_ref(baseline_path)}}
        census_path = Path(bundle["evidence"][0]["path"])
        candidate = dict(baseline, artifactSha256=self.sha, sourceFingerprint=self.fp,
                         source={"columnsSha256": contract.digest(census_path)})
        candidate_path = self.write("candidate-facts.json", candidate)
        capture["biomeVisualReview"]["sites"] = [capture["biomeVisualReview"]["sites"][0]]
        capture["biomeVisualReview"]["columnsPath"] = str(census_path)
        bundle["images"] = [image for image in bundle["images"] if image["siteId"] == "0"]
        self.write("plan.json", plan); self.write("receipt.json", capture)
        bundle["plan"], bundle["capture"] = contract.file_ref(plan_path), contract.file_ref(capture_path)
        bundle["scopeFacts"] = contract.file_ref(candidate_path)
        self.write("bundle.json", bundle)
        return bundle_path

    def test_scoped_bundle_accepts_only_frozen_site_and_rejects_missing_site(self):
        bundle_path = self.scoped_bundle()
        contract.validate_bundle(bundle_path, self.root)
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["sites"] = []
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "Scoped native capture differs"):
            contract.validate_bundle(bundle_path, self.root)

    def test_bundle_cannot_omit_a_native_view(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        bundle = contract.load(bundle_path)
        bundle["images"].pop()
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "omitted native"):
            contract.validate_bundle(bundle_path, self.root)

    def inspection_bundle(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        bundle = contract.load(bundle_path)
        plan_path, capture_path = Path(bundle["plan"]["path"]), Path(bundle["capture"]["path"])
        plan, capture = contract.load(plan_path), contract.load(capture_path)
        request = {"id": "0", "x": 0, "z": 0, "kind": "root"}
        plan["windows"] = [{"minX": -12, "maxX": 12, "minZ": -12, "maxZ": 12}]
        plan["inspectionRequests"] = [request]
        capture["biomeVisualReview"]["inspectionRequests"] = [{
            "id": "0", "kind": "root", "requested": {"x": 0, "z": 0},
            "resolved": {"x": 0, "z": 0}, "candidateCount": 0,
            "reason": "no-target at requested point", "endpoints": [],
        }]
        self.write("plan.json", plan)
        self.write("receipt.json", capture)
        bundle["plan"], bundle["capture"] = contract.file_ref(plan_path), contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        return bundle_path

    def test_inspection_request_must_be_recorded_by_native_capture(self):
        bundle_path = self.inspection_bundle()
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["inspectionRequests"] = []
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "omitted or added inspection"):
            contract.validate_bundle(bundle_path, self.root)

    def test_inspection_resolution_cannot_escape_radius_or_window(self):
        bundle_path = self.inspection_bundle()
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["inspectionRequests"][0]["resolved"] = {"x": 13, "z": 0}
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "outside bounded radius/window"):
            contract.validate_bundle(bundle_path, self.root)

    def test_no_target_inspection_requires_explicit_reason(self):
        bundle_path = self.inspection_bundle()
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["inspectionRequests"][0]["reason"] = "scan inconclusive"
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "No-target inspection"):
            contract.validate_bundle(bundle_path, self.root)

    def test_capture_cannot_add_inspection_request_without_plan_request(self):
        review = self.evidence()
        bundle_path = Path(contract.load(review)["bundlePath"])
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["inspectionRequests"] = [{"unexpected": True}]
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        with self.assertRaisesRegex(ValueError, "added unexpected inspection"):
            contract.validate_bundle(bundle_path, self.root)

    def test_inspection_record_allows_additional_native_observation_metadata(self):
        bundle_path = self.inspection_bundle()
        bundle = contract.load(bundle_path)
        capture_path = Path(bundle["capture"]["path"])
        capture = contract.load(capture_path)
        capture["biomeVisualReview"]["inspectionRequests"][0]["endpointObservations"] = [
            {"x": 0, "z": 0, "observedBlock": "minecraft:mud"}
        ]
        self.write("receipt.json", capture)
        bundle["capture"] = contract.file_ref(capture_path)
        self.write("bundle.json", bundle)
        contract.validate_bundle(bundle_path, self.root)


if __name__ == "__main__":
    unittest.main()
