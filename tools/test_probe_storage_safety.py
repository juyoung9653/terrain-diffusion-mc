"""Failure-boundary checks for probe input storage; no Minecraft runtime."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parent))

import probe_storage
from build_evidence import PROBE_RECEIPT, sha256


class ProbeStorageSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        scratch = Path(__file__).resolve().parents[1] / "build"
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build/structure-density").mkdir(parents=True)

    def make_run(self, name: str, models: tuple[str, ...] = ("base_model.onnx", "coarse_model.onnx")) -> Path:
        run = self.root / "build/structure-density" / name
        (run / "world").mkdir(parents=True)
        model_dir = run / "terrain-diffusion-models"
        model_dir.mkdir()
        hashes: dict[str, str] = {}
        for model in models:
            path = model_dir / model
            path.write_bytes((name + ":" + model).encode())
            hashes["terrain-diffusion-models/" + model] = sha256(path)
        manifest = run / "input-manifest.json"
        manifest.write_text(json.dumps({"sha256": hashes}), encoding="utf-8")
        receipt = run / "world/density-audit.json"
        receipt.write_text('{"success":true}', encoding="utf-8")
        validation = {
            "schema": 1,
            "kind": "probe-validation",
            "success": True,
            "processExit": 0,
            "probeStatus": "success",
            "receipt": "world/density-audit.json",
            "receiptSha256": sha256(receipt),
            "inputManifestSha256": sha256(manifest),
        }
        (run / PROBE_RECEIPT).write_text(json.dumps(validation), encoding="utf-8")
        return run

    def archive(self, run: Path) -> dict:
        return probe_storage.maintain(self.root, "structure-density", [run], keep_latest=0)

    def test_linked_nested_receipt_ancestor_is_rejected(self) -> None:
        run = self.make_run("nested-receipt")
        world = run / "world"
        original = probe_storage._link

        def links(path: Path) -> bool:
            return path == world or original(path)

        with mock.patch.object(probe_storage, "_link", side_effect=links):
            result = self.archive(run)
        self.assertFalse((run / probe_storage.MANAGED).exists())
        self.assertTrue(result["skipped"])

    def test_manifest_declared_missing_model_does_not_register(self) -> None:
        run = self.make_run("missing-model", ("base_model.onnx",))
        manifest = run / "input-manifest.json"
        data = json.loads(manifest.read_text(encoding="utf-8"))
        data["sha256"]["terrain-diffusion-models/decoder_model.onnx"] = "0" * 64
        manifest.write_text(json.dumps(data), encoding="utf-8")
        evidence = json.loads((run / PROBE_RECEIPT).read_text(encoding="utf-8"))
        evidence["inputManifestSha256"] = sha256(manifest)
        (run / PROBE_RECEIPT).write_text(json.dumps(evidence), encoding="utf-8")
        result = self.archive(run)
        self.assertFalse((run / probe_storage.MANAGED).exists())
        self.assertTrue(result["skipped"])

    def test_changed_validation_evidence_skips_registered_old_run(self) -> None:
        run = self.make_run("changed-evidence")
        self.assertIn("changed-evidence", probe_storage.maintain(
            self.root, "structure-density", [run], keep_latest=2)["registered"])
        evidence = json.loads((run / PROBE_RECEIPT).read_text(encoding="utf-8"))
        evidence["note"] = "post-validation user edit"
        (run / PROBE_RECEIPT).write_text(json.dumps(evidence), encoding="utf-8")
        result = probe_storage.maintain(self.root, "structure-density", [], keep_latest=0)
        self.assertTrue((run / "terrain-diffusion-models/base_model.onnx").exists())
        self.assertIn("evidence_changed", [row["reason"] for row in result["skipped"]])

    def test_pinned_archived_and_changed_records_do_not_consume_latest_slots(self) -> None:
        pinned, archived, oldest, old, newest, changed = [self.make_run(name) for name in
                                                           ("pinned", "archived", "oldest", "old", "newest", "changed")]
        for run in (pinned, archived, oldest, old, newest, changed):
            self.assertIn(run.name, probe_storage.maintain(
                self.root, "structure-density", [run], keep_latest=99)["registered"])
        (pinned / probe_storage.PIN).write_text("{}", encoding="utf-8")
        (archived / probe_storage.GUARD).write_text("{}", encoding="utf-8")
        (changed / "terrain-diffusion-models/base_model.onnx").write_bytes(b"user changed")
        result = probe_storage.maintain(self.root, "structure-density", [], keep_latest=2)
        self.assertTrue((oldest / probe_storage.GUARD).exists())
        self.assertFalse((old / probe_storage.GUARD).exists())
        self.assertFalse((newest / probe_storage.GUARD).exists())
        self.assertTrue((pinned / "terrain-diffusion-models/base_model.onnx").exists())
        self.assertTrue((changed / "terrain-diffusion-models/base_model.onnx").exists())
        self.assertTrue(result["skipped"])

    def test_invalid_guards_refuse_before_any_restore_write(self) -> None:
        run = self.make_run("bad-guard")
        model = run / "terrain-diffusion-models/base_model.onnx"
        original = model.read_bytes()
        valid = {
            "path": "build/structure-density/bad-guard/terrain-diffusion-models/base_model.onnx",
            "name": "base_model.onnx",
            "bytes": len(original),
            "mtime_ns": model.stat().st_mtime_ns,
            "sha256": hashlib.sha256(original).hexdigest(),
        }
        for marker in (
            {"schema": 1, "kind": "probe-input-archive", "fixture": "build/structure-density/bad-guard", "models": []},
            {"schema": 1, "kind": "probe-input-archive", "fixture": "build/structure-density/bad-guard", "models": [valid, valid]},
            {"schema": 1, "kind": "probe-input-archive", "fixture": "build/structure-density/bad-guard", "models": [{**valid, "sha256": "z" * 64}]},
            {"schema": 1, "kind": "probe-input-archive", "fixture": "build/structure-density/elsewhere", "models": [valid]},
        ):
            with self.subTest(marker=marker):
                (run / probe_storage.GUARD).write_text(json.dumps(marker), encoding="utf-8")
                with self.assertRaises(ValueError):
                    probe_storage.restore(self.root, run)
                self.assertEqual(model.read_bytes(), original)
                self.assertTrue((run / probe_storage.GUARD).exists())

    def test_restore_rejects_linked_model_directory_and_object_blob(self) -> None:
        run = self.make_run("linked-restore")
        self.archive(run)
        marker = json.loads((run / probe_storage.GUARD).read_text(encoding="utf-8"))
        blob = self.root / "build/probe-inputs/models" / (marker["models"][0]["sha256"] + ".onnx")
        model_dir = run / "terrain-diffusion-models"
        original = probe_storage._link
        for linked in (model_dir, blob):
            with self.subTest(linked=linked.name), mock.patch.object(probe_storage, "_link", side_effect=lambda path, linked=linked: path == linked or original(path)):
                with self.assertRaises(ValueError):
                    probe_storage.restore(self.root, run)
                self.assertTrue((run / probe_storage.GUARD).exists())

    def test_corrupt_second_object_blocks_all_restore_writes(self) -> None:
        run = self.make_run("corrupt-object")
        self.archive(run)
        marker = json.loads((run / probe_storage.GUARD).read_text(encoding="utf-8"))
        second = self.root / "build/probe-inputs/models" / (marker["models"][1]["sha256"] + ".onnx")
        second.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "retained model mismatch"):
            probe_storage.restore(self.root, run)
        for item in marker["models"]:
            self.assertFalse((self.root / item["path"]).exists())
        self.assertTrue((run / probe_storage.GUARD).exists())

    def test_concurrent_destination_creation_never_overwrites_user_file(self) -> None:
        run = self.make_run("racing-restore")
        self.archive(run)
        marker = json.loads((run / probe_storage.GUARD).read_text(encoding="utf-8"))
        target = self.root / marker["models"][0]["path"]
        original_copy = probe_storage.shutil.copyfileobj
        created = False

        def race(source, destination, length=0):
            nonlocal created
            original_copy(source, destination, length)
            if not created:
                target.write_bytes(b"concurrent user file")
                created = True

        with mock.patch.object(probe_storage.shutil, "copyfileobj", side_effect=race):
            with self.assertRaises(FileExistsError):
                probe_storage.restore(self.root, run)
        self.assertEqual(target.read_bytes(), b"concurrent user file")
        self.assertTrue((run / probe_storage.GUARD).exists())

    def test_inventory_accounts_for_unmanaged_and_hardlinked_files_without_writing(self) -> None:
        unmanaged = self.root / "build/structure-density/unmanaged/world/region"
        unmanaged.mkdir(parents=True)
        source = unmanaged / "a.mca"
        source.write_bytes(b"x" * 19)
        duplicate = unmanaged / "b.mca"
        try:
            os.link(source, duplicate)
        except PermissionError:
            self.skipTest("workspace filesystem does not allow hard links")
        model = self.root / "build/structure-density/unmanaged/terrain-diffusion-models/base_model.onnx"
        model.parent.mkdir(parents=True)
        model.write_bytes(b"m" * 23)
        retained = self.root / "build/probe-inputs/models/retained.onnx"
        retained.parent.mkdir(parents=True)
        retained.write_bytes(b"r" * 29)
        before = {path.relative_to(self.root).as_posix(): path.read_bytes()
                  for path in self.root.rglob("*") if path.is_file()}
        result = probe_storage.inventory(self.root)
        after = {path.relative_to(self.root).as_posix(): path.read_bytes()
                 for path in self.root.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        self.assertEqual(result["categories"]["logical_bytes"]["world"], 38)
        self.assertEqual(result["categories"]["unique_file_bytes"]["world"], 19)
        self.assertEqual(result["categories"]["logical_bytes"]["models"], 23)
        self.assertEqual(result["categories"]["logical_bytes"]["other"], 29)

    def test_inventory_reports_linked_directory_without_descending(self) -> None:
        linked_world = self.root / "build/structure-density/linked/world"
        linked_world.mkdir(parents=True)
        (linked_world / "region.mca").write_bytes(b"unscanned")
        original = probe_storage._link

        def links(path: Path) -> bool:
            return path == linked_world or original(path)

        with mock.patch.object(probe_storage, "_link", side_effect=links):
            result = probe_storage.inventory(self.root)
        self.assertIn("build/structure-density/linked/world",
                      result["linked_paths_skipped"])
        self.assertEqual(result["linked_path_count"], 1)
        self.assertEqual(result["categories"]["logical_bytes"]["world"], 0)

    def test_inventory_refuses_linked_build_root(self) -> None:
        build = self.root / "build"
        original = probe_storage._link
        with mock.patch.object(probe_storage, "_link", side_effect=lambda path: path == build or original(path)):
            with self.assertRaisesRegex(ValueError, "linked"):
                probe_storage.inventory(self.root)


if __name__ == "__main__":
    unittest.main()
