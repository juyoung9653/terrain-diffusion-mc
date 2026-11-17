"""Unit tests for the one-version managed Terrain Diffusion backup."""

import json
import shutil
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

import jar_backups
import disk_libraries


SCRATCH = Path(__file__).resolve().parents[2] / ".tmp"
SCRATCH.mkdir(exist_ok=True)


class JarBackupTest(unittest.TestCase):
    def layout(self, root: Path) -> Path:
        mods = root / "instance" / "minecraft" / "mods"
        mods.mkdir(parents=True)
        return mods

    def write_old(self, mods: Path, *, body: bytes = b"old", library: bytes = b"old-lib") -> tuple[Path, Path]:
        mod = mods / "NeoForge-terrain-diffusion-mc.jar"
        lib = mods / "onnx.jar"
        mod.write_bytes(body)
        lib.write_bytes(library)
        (mods / (mod.name + ".onnx-receipt.properties")).write_text(
            f"libraryFile={lib.name}\nlibrarySha256={jar_backups.sha256(lib)}\n"
            f"modSha256={jar_backups.sha256(mod)}\n", encoding="utf-8")
        return mod, lib

    def published_backup(self, mods: Path, *, contents: bytes = b"published") -> Path:
        previous = jar_backups.backup_root(mods) / "previous"; previous.mkdir(parents=True)
        mod = previous / "previous.jar"; mod.write_bytes(contents)
        current_mod = mods / "NeoForge-terrain-diffusion-mc.jar"
        library = mods / "onnx.jar"
        metadata = {"schema": 1, "kind": "terrain-diffusion-previous-install",
                    "previous": {"mod": {"name": mod.name, "sha256": jar_backups.sha256(mod),
                                            "bytes": mod.stat().st_size}},
                    "supersededBy": {
                        "mod": {"name": current_mod.name, "sha256": jar_backups.sha256(current_mod),
                                "bytes": current_mod.stat().st_size},
                        "library": {"name": library.name, "sha256": jar_backups.sha256(library),
                                    "bytes": library.stat().st_size}}}
        (previous / "backup.json").write_text(json.dumps(metadata), encoding="utf-8")
        return previous

    def test_commit_replaces_the_only_previous_version(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            incoming_mod, incoming_library = root / "next.jar", root / "next-lib.jar"
            incoming_mod.write_bytes(b"new"); incoming_library.write_bytes(b"new-lib")
            first = jar_backups.stage_previous(mods, old_mod.name, incoming_mod, incoming_library)
            self.assertIsNotNone(first)
            incoming_mod.replace(old_mod); incoming_library.replace(old_library)
            (mods / (old_mod.name + ".onnx-receipt.properties")).write_text(
                f"libraryFile={old_library.name}\nlibrarySha256={jar_backups.sha256(old_library)}\n"
                f"modSha256={jar_backups.sha256(old_mod)}\n", encoding="utf-8")
            jar_backups.commit_previous(first, old_mod, old_library)
            previous = jar_backups.backup_root(mods) / "previous"
            metadata = json.loads((previous / "backup.json").read_text(encoding="utf-8"))
            self.assertEqual((previous / old_mod.name).read_bytes(), b"old")
            self.assertEqual((previous / "onnx.jar").read_bytes(), b"old-lib")
            self.assertEqual(metadata["previous"]["mod"]["sha256"], jar_backups.sha256(previous / old_mod.name))
            self.assertEqual(metadata["previous"]["library"]["sha256"],
                             jar_backups.sha256(previous / "onnx.jar"))
            self.assertEqual(metadata["supersededBy"]["mod"]["sha256"], jar_backups.sha256(old_mod))
            # A later update replaces, rather than accumulates, the managed previous copy.
            later = root / "later.jar"; later.write_bytes(b"later")
            staged = jar_backups.stage_previous(mods, old_mod.name, later, old_library)
            later.replace(old_mod)
            jar_backups.commit_previous(staged, old_mod, old_library)
            self.assertEqual((previous / old_mod.name).read_bytes(), b"new")
            self.assertEqual([p.name for p in jar_backups.backup_root(mods).iterdir()], ["previous"])

    def test_same_hash_and_missing_current_do_not_create_backup(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            same = root / "same.jar"; same.write_bytes(old_mod.read_bytes())
            self.assertIsNone(jar_backups.stage_previous(mods, old_mod.name, same, old_library))
            self.assertFalse(jar_backups.backup_root(mods).exists())
            self.assertIsNone(jar_backups.stage_previous(mods, "missing.jar", same, old_library))

    def test_stage_failure_keeps_existing_previous(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            backup = self.published_backup(mods, contents=b"keep")
            incoming = root / "incoming.jar"; incoming.write_bytes(b"new")
            old_mod.unlink()
            old_mod.mkdir()
            with self.assertRaisesRegex(ValueError, "regular file"):
                jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)
            self.assertEqual((backup / "previous.jar").read_bytes(), b"keep")

    def test_unfinished_stage_preserves_published_previous_and_blocks_updates(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            incoming = root / "incoming.jar"; incoming.write_bytes(b"new")
            published = self.published_backup(mods)
            staged = jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)
            self.assertEqual((published / "previous.jar").read_bytes(), b"published")
            self.assertTrue(staged.stage.is_dir())
            with self.assertRaisesRegex(ValueError, "unfinished"):
                jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)

    def test_refuses_changed_companion_for_same_mod_or_bad_old_receipt(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            same = root / "same.jar"; same.write_bytes(old_mod.read_bytes())
            changed_library = root / "changed-lib.jar"; changed_library.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "same Terrain"):
                jar_backups.stage_previous(mods, old_mod.name, same, changed_library)
            sidecar = mods / (old_mod.name + ".onnx-receipt.properties")
            sidecar.write_text("libraryFile=onnx.jar\nlibrarySha256=bad\nmodSha256=bad\n",
                               encoding="utf-8")
            incoming = root / "incoming.jar"; incoming.write_bytes(b"new")
            with self.assertRaisesRegex(ValueError, "old ONNX receipt"):
                jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)

    def test_refuses_linked_inputs_and_unsafe_managed_previous(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            incoming = root / "incoming.jar"; incoming.write_bytes(b"new")
            link = mods / "linked.jar"
            try:
                link.symlink_to(old_mod)
            except OSError as failure:
                self.skipTest(f"symlinks unavailable: {failure}")
            with self.assertRaisesRegex(ValueError, "regular file"):
                jar_backups.stage_previous(mods, link.name, incoming, old_library)
            staged = jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)
            previous = jar_backups.backup_root(mods) / "previous"
            previous.symlink_to(root, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "unsafe managed"):
                jar_backups.commit_previous(staged, incoming, old_library)

    def test_refuses_malformed_published_backup(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            previous = jar_backups.backup_root(mods) / "previous"; previous.mkdir(parents=True)
            (previous / "backup.json").write_text("{}", encoding="utf-8")
            incoming = root / "incoming.jar"; incoming.write_bytes(b"new")
            with self.assertRaisesRegex(ValueError, "invalid managed backup"):
                jar_backups.stage_previous(mods, old_mod.name, incoming, old_library)

    def test_install_rotates_or_preserves_recovery_stage(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            old_mod, old_library = self.write_old(mods)
            mod = root / old_mod.name; library = root / "onnx.jar"; receipt = root / "receipt.properties"
            mod.write_bytes(b"new"); library.write_bytes(b"new-lib")
            receipt.write_text(f"libraryFile={library.name}\nlibrarySha256={disk_libraries.sha256(library)}\n"
                               f"modSha256={disk_libraries.sha256(mod)}\n", encoding="utf-8")
            verified = {"modSha256": disk_libraries.sha256(mod),
                        "librarySha256": disk_libraries.sha256(library)}
            with patch.object(disk_libraries, "ROOT", root), \
                    patch.object(disk_libraries, "verify_release", return_value={"validations": []}), \
                    patch("streams_probe.release_checks.require_streams_coverage"), \
                    patch.object(disk_libraries, "verify", return_value=verified):
                disk_libraries.install(receipt, library, mod, mods)
                previous = jar_backups.backup_root(mods) / "previous"
                self.assertEqual((previous / old_mod.name).read_bytes(), b"old")
                disk_libraries.install(receipt, library, mod, mods)
                self.assertEqual((previous / old_mod.name).read_bytes(), b"old")

            # A failure after library and mod replacement leaves its stage for recovery.
            mod.write_bytes(b"newer"); library.write_bytes(b"newer-lib")
            receipt.write_text(f"libraryFile={library.name}\nlibrarySha256={disk_libraries.sha256(library)}\n"
                               f"modSha256={disk_libraries.sha256(mod)}\n", encoding="utf-8")
            verified = {"modSha256": disk_libraries.sha256(mod),
                        "librarySha256": disk_libraries.sha256(library)}
            calls = 0
            def fail_after_mod(source: Path, target: Path) -> None:
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise OSError("sidecar failure")
                shutil.copy2(source, target)
            with patch.object(disk_libraries, "ROOT", root), \
                    patch.object(disk_libraries, "verify_release", return_value={"validations": []}), \
                    patch("streams_probe.release_checks.require_streams_coverage"), \
                    patch.object(disk_libraries, "verify", return_value=verified), \
                    patch.object(disk_libraries, "atomic_copy", side_effect=fail_after_mod):
                with self.assertRaisesRegex(RuntimeError, "recovery backup retained"):
                    disk_libraries.install(receipt, library, mod, mods)
                self.assertEqual((mods / old_mod.name).read_bytes(), b"newer")
                pending = [p for p in jar_backups.backup_root(mods).iterdir()
                           if p.name.startswith(".staging-")]
                self.assertEqual(len(pending), 1)
                self.assertEqual((pending[0] / old_mod.name).read_bytes(), b"new")
                self.assertEqual((pending[0] / old_library.name).read_bytes(), b"new-lib")
                self.assertEqual((previous / old_mod.name).read_bytes(), b"old")
                with self.assertRaisesRegex(ValueError, "unfinished"):
                    disk_libraries.install(receipt, library, mod, mods)

    def test_first_install_checks_destination_without_a_backup(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw); mods = self.layout(root)
            mod = root / "NeoForge-terrain-diffusion-mc.jar"
            library = root / "onnx.jar"; receipt = root / "receipt.properties"
            mod.write_bytes(b"new"); library.write_bytes(b"library")
            receipt.write_bytes(b"receipt")
            expected = {"modSha256": disk_libraries.sha256(mod),
                        "librarySha256": disk_libraries.sha256(library)}

            def corrupt_mod(source: Path, target: Path) -> None:
                shutil.copy2(source, target)
                if source == mod:
                    target.write_bytes(b"corrupt")

            with patch.object(disk_libraries, "ROOT", root), \
                    patch.object(disk_libraries, "verify_release", return_value={"validations": []}), \
                    patch("streams_probe.release_checks.require_streams_coverage"), \
                    patch.object(disk_libraries, "verify", return_value=expected), \
                    patch.object(disk_libraries, "atomic_copy", side_effect=corrupt_mod):
                with self.assertRaisesRegex(ValueError, "installed NeoForge mod hash"):
                    disk_libraries.install(receipt, library, mod, mods)
            self.assertFalse(jar_backups.backup_root(mods).exists())


if __name__ == "__main__":
    unittest.main()
