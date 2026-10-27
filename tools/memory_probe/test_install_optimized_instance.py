from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import externalize_nested as ext
import install_optimized_instance as installer


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def jar(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


class InstallerTest(unittest.TestCase):
    def setUp(self):
        # The Windows sandbox denies writes below the process Temp redirect.
        # Keep this disposable fixture under the writable repository instead.
        self.root = Path(tempfile.mkdtemp(prefix="installer-test-", dir=Path(__file__).parent))
        root = self.root
        self.minecraft = root / "instance" / "minecraft"
        self.mods = self.minecraft / "mods"
        self.mods.mkdir(parents=True)
        (self.minecraft.parent / "instance.cfg").write_text(
            "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC -XX:+ZGenerational -Dkeep=this\n"
            "MinMemAlloc=2048\nMaxMemAlloc=12000\n", encoding="utf-8")
        self.release = root / "release"; self.release.mkdir()
        self.stage = root / "stage"; self.stage.mkdir()
        self._make_inputs()

    def tearDown(self): shutil.rmtree(self.root)

    def _make_inputs(self):
        onnx = b"original-onnx"
        original_td = self.mods / "NeoForge-terrain-diffusion-mc-2.3.0-cuda+1.21.1.jar"
        jar(original_td, {"META-INF/jarjar/onnxruntime_gpu-1.20.0.jar": onnx})
        slim = self.release / original_td.name
        jar(slim, {"net/example/Slim.class": b"slim"})
        library = self.release / "terrain-diffusion-onnxruntime-library-1.20.0-cuda+1.21.1.jar"
        jar(library, {"META-INF/MANIFEST.MF": b"FMLModType: LIBRARY\nImplementation-Version: 1.20.0\n\n",
                      "ai/onnxruntime/OrtEnvironment.class": b"x",
                      "ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll": b"cuda"})
        (self.release / installer.RECEIPT_NAME).write_text("\n".join((
            "formatVersion=1", "mode=neoforge-disk-library", "module=com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0",
            f"sourceSha256={hashlib.sha256(onnx).hexdigest()}", f"libraryFile={library.name}",
            f"librarySha256={digest(library)}", f"modFile={slim.name}", f"modSha256={digest(slim)}", "")), encoding="utf-8")
        inventory=[]; sources={}
        for name, owner in ((ext.VOXY, "voxy"), (ext.BURNT, "burnt")):
            selected = [target for target in ext.TARGETS if target.owner == owner]
            current = self.mods / name
            metadata = {"jars": [{"path": target.nested_path,
                                   "identifier": {"artifact": target.artifact},
                                   "version": {"artifactVersion": target.version}}
                                  for target in selected]}
            entries = {"META-INF/jarjar/metadata.json": json.dumps(metadata).encode(),
                       "assets/test.txt": b"unchanged"}
            if owner == "voxy":
                entries.update({
                    "META-INF/neoforge.mods.toml": b'[[mods]]\nmodId = "voxy"\nversion = "${file.jarVersion}"\n',
                    "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nImplementation-Version: 0.2.12-alpha\r\n\r\n",
                })
            entries.update({target.nested_path: (target.companion + " nested").encode()
                            for target in selected})
            jar(current, entries); sources[name] = digest(current)
            ext.stripped_parent(current, self.stage / name, owner)
            inventory.append({"file":name,"sha256":digest(self.stage / name)})
        for target in ext.TARGETS:
            companion=self.stage/target.companion; companion.write_bytes((target.companion+" bytes").encode())
            inventory.append({"file":target.companion,"sha256":digest(companion)})
        (self.stage/installer.PLAN_NAME).write_text(json.dumps({"formatVersion":1,"mode":"workspace-staged-externalization","sources":sources,"inventory":inventory}), encoding="utf-8")

    def test_backup_and_install_preserve_originals(self):
        original_voxy = (self.mods / ext.VOXY).read_bytes()
        prepared=installer.preflight(self.minecraft,self.release,self.stage)
        receipt=installer.apply(prepared,self.minecraft)
        self.assertTrue(receipt.is_file())
        backup=receipt.parent
        self.assertEqual((backup/"minecraft/mods"/ext.VOXY).read_bytes(), original_voxy)
        self.assertEqual((backup/"instance.cfg").read_text(encoding="utf-8"),
                         "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC -XX:+ZGenerational -Dkeep=this\n"
                         "MinMemAlloc=2048\nMaxMemAlloc=12000\n")
        self.assertIn("MinMemAlloc=4096", (self.minecraft.parent/"instance.cfg").read_text(encoding="utf-8"))
        self.assertIn("MaxMemAlloc=12000", (self.minecraft.parent/"instance.cfg").read_text(encoding="utf-8"))
        self.assertEqual((self.mods/ext.VOXY).read_bytes(), (self.stage/ext.VOXY).read_bytes())
        self.assertTrue((self.mods/"terrain-diffusion-onnxruntime-library-1.20.0-cuda+1.21.1.jar").is_file())

    def test_optional_g1_keeps_override_boolean_and_custom_args(self):
        prepared = installer.preflight(self.minecraft, self.release, self.stage)
        installer.apply(prepared, self.minecraft, collector="g1", heap_max_mib=8192)
        cfg = (self.minecraft.parent / "instance.cfg").read_text(encoding="utf-8")
        self.assertIn("OverrideJavaArgs=true", cfg)
        self.assertIn("JvmArgs=-Dkeep=this -XX:+UseG1GC", cfg)
        self.assertNotIn("UseZGC", cfg)
        self.assertNotIn("UseZGenerational", cfg)
        self.assertIn("MaxMemAlloc=8192", cfg)

    def test_invalid_heap_max_refuses_before_backup_or_write(self):
        prepared = installer.preflight(self.minecraft, self.release, self.stage)
        before = (self.minecraft.parent / "instance.cfg").read_bytes()
        with self.assertRaises(installer.InstallError):
            installer.apply(prepared, self.minecraft, heap_max_mib=4095)
        self.assertEqual((self.minecraft.parent / "instance.cfg").read_bytes(), before)
        self.assertFalse((self.minecraft.parent / ".terrain-diffusion-backups").exists())

    def test_optional_dynamic_resources_uses_mixins_properties_and_backs_it_up(self):
        config = self.minecraft / "config"; config.mkdir()
        modernfix = config / "modernfix-mixins.properties"
        modernfix.write_text("#  mixin.perf.dynamic_resources=false # (default)\n", encoding="utf-8")
        prepared = installer.preflight(self.minecraft, self.release, self.stage)
        receipt = installer.apply(prepared, self.minecraft, dynamic_resources=True)
        self.assertIn("mixin.perf.dynamic_resources=true", modernfix.read_text(encoding="utf-8"))
        self.assertEqual((receipt.parent / "minecraft" / "config" / "modernfix-mixins.properties").read_text(encoding="utf-8"),
                         "#  mixin.perf.dynamic_resources=false # (default)\n")

    def test_hash_mismatch_refuses_without_writes(self):
        before={p: p.read_bytes() for p in self.mods.iterdir()}
        (self.mods/ext.VOXY).write_bytes(b"changed")
        with self.assertRaises(installer.InstallError): installer.preflight(self.minecraft,self.release,self.stage)
        self.assertFalse((self.minecraft.parent/".terrain-diffusion-backups").exists())
        self.assertEqual((self.mods/ext.BURNT).read_bytes(), before[self.mods/ext.BURNT])

    def test_failure_rolls_back_new_and_replaced_files(self):
        prepared=installer.preflight(self.minecraft,self.release,self.stage)
        original={p.name:p.read_bytes() for p in self.mods.iterdir()}
        with self.assertRaises(OSError): installer.apply(prepared,self.minecraft,fail_after=5)
        self.assertEqual({p.name:p.read_bytes() for p in self.mods.iterdir()}, original)

    def test_110_plan_selects_110_parent_and_preserves_legacy_profile(self):
        old, new_name = self.mods / ext.VOXY, ext.voxy_filename("1.1.0")
        with zipfile.ZipFile(old) as source:
            entries = {info.filename: source.read(info) for info in source.infolist()}
        entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\nImplementation-Version: 1.1.0\r\n\r\n"
        new = self.mods / new_name
        jar(new, entries); old.unlink()
        staged_old, staged_new = self.stage / ext.VOXY, self.stage / new_name
        staged_old.unlink()
        ext.stripped_parent(new, staged_new, "voxy")
        plan_path = self.stage / installer.PLAN_NAME
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        plan["sources"].pop(ext.VOXY)
        plan["sources"][new_name] = digest(new)
        for item in plan["inventory"]:
            if item["file"] == ext.VOXY:
                item["file"] = new_name
                item["sha256"] = digest(staged_new)
        plan_path.write_text(json.dumps(plan), encoding="utf-8")
        prepared = installer.preflight(self.minecraft, self.release, self.stage)
        self.assertEqual(new_name, prepared["voxy_file"])
        receipt = installer.apply(prepared, self.minecraft)
        self.assertEqual(staged_new.read_bytes(), new.read_bytes())
        self.assertTrue((receipt.parent / "minecraft" / "mods" / new_name).is_file())


if __name__ == "__main__": unittest.main()
