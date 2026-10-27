"""Offline fixtures for selected Voxy/Burnt nested-library staging."""

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import externalize_nested as external


SCRATCH = Path(__file__).resolve().parents[2] / ".tmp"
SCRATCH.mkdir(exist_ok=True)


def jar_bytes(entries: dict[str, bytes]) -> bytes:
    import io
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return output.getvalue()


def metadata(targets: list[external.Target]) -> bytes:
    return json.dumps({"jars": [
        {"identifier": {"group": "fixture", "artifact": target.artifact},
         "version": {"range": "*", "artifactVersion": target.version},
         "path": target.nested_path, "isObfuscated": False}
        for target in targets
    ]}, indent=2).encode()


class ExternalizeNestedTest(unittest.TestCase):
    def build_mods(self, root: Path, signed: bool = False,
                   voxy_version: str = "0.2.12-alpha") -> Path:
        mods = root / "mods"
        mods.mkdir()
        grouped = {"voxy": [], "burnt": []}
        for target in external.TARGETS:
            grouped[target.owner].append(target)
        libraries = {}
        for target in grouped["voxy"]:
            libraries[target.nested_path] = jar_bytes({
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
                "vendor/unchanged.bin": target.artifact.encode(),
            })
        basic = jar_bytes({
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
            "META-INF/neoforge.mods.toml": b'[[mods]]\nmodId = "burnt"\n',
            "assets/burnt/data.bin": b"keep exact nested bytes",
        })
        libraries[grouped["burnt"][0].nested_path] = basic
        for filename, owner in ((external.voxy_filename(voxy_version), "voxy"), (external.BURNT, "burnt")):
            entries = {external.METADATA: metadata(grouped[owner]), "assets/unchanged.bin": b"same"}
            if owner == "voxy":
                entries["META-INF/neoforge.mods.toml"] = b'[[mods]]\nmodId = "voxy"\nversion = "${file.jarVersion}"\n'
                entries["META-INF/MANIFEST.MF"] = (
                    f"Manifest-Version: 1.0\r\nImplementation-Version: {voxy_version}\r\n\r\n".encode())
            entries.update({target.nested_path: libraries[target.nested_path] for target in grouped[owner]})
            if signed:
                entries["META-INF/SIGNATURE.SF"] = b"signature"
            with zipfile.ZipFile(mods / filename, "w") as archive:
                for name, content in entries.items():
                    archive.writestr(name, content)
        return mods

    def test_stage_110_preserves_unselected_nested_members(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root, voxy_version="1.1.0")
            voxy = mods / external.voxy_filename("1.1.0")
            extra_path = "META-INF/jarjar/jedis-5.1.0.jar"
            with zipfile.ZipFile(voxy) as source:
                entries = {info.filename: source.read(info) for info in source.infolist()}
            entries[extra_path] = b"leave this 1.1.0 dependency nested"
            metadata_json = json.loads(entries[external.METADATA])
            metadata_json["jars"].append({"identifier": {"artifact": "jedis"},
                                          "version": {"artifactVersion": "5.1.0"},
                                          "path": extra_path})
            entries[external.METADATA] = json.dumps(metadata_json).encode()
            with zipfile.ZipFile(voxy, "w") as output:
                for name, content in entries.items(): output.writestr(name, content)
            plan_path = external.stage(mods, root / "staged", "1.1.0")
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            self.assertEqual([external.voxy_filename("1.1.0"), external.BURNT], plan["installPlan"]["replace"])
            with zipfile.ZipFile(root / "staged" / voxy.name) as staged:
                self.assertEqual(b"leave this 1.1.0 dependency nested", staged.read(extra_path))
                self.assertIn(extra_path, {item["path"] for item in json.loads(staged.read(external.METADATA))["jars"]})

    def test_unknown_or_wrong_profile_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root)
            with self.assertRaisesRegex(external.ExternalizationError, "unsupported"):
                external.stage(mods, root / "staged", "9.9.9")
            with self.assertRaisesRegex(external.ExternalizationError, "missing"):
                external.stage(mods, root / "staged", "1.1.0")

    def test_profile_rejects_wrong_or_missing_toml_version(self) -> None:
        for declared, expected in (("1.1.1", "unexpected version"), (None, "unexpected version")):
            with self.subTest(declared=declared), tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
                root = Path(raw)
                mods = self.build_mods(root, voxy_version="1.1.0")
                voxy = mods / external.voxy_filename("1.1.0")
                with zipfile.ZipFile(voxy) as source:
                    entries = {info.filename: source.read(info) for info in source.infolist()}
                suffix = "" if declared is None else f'version = "{declared}"\n'
                entries["META-INF/neoforge.mods.toml"] = (
                    '[[mods]]\nmodId = "voxy"\n' + suffix).encode()
                with zipfile.ZipFile(voxy, "w") as output:
                    for name, content in entries.items(): output.writestr(name, content)
                with self.assertRaisesRegex(external.ExternalizationError, expected):
                    external.stage(mods, root / "staged", "1.1.0")

    def test_profile_rejects_renamed_parent(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root, voxy_version="1.1.0")
            original = mods / external.voxy_filename("1.1.0")
            renamed = mods / "voxy-1.1.1.jar"
            original.replace(renamed)
            with self.assertRaisesRegex(external.ExternalizationError, "filename"):
                external.verify_voxy_parent(renamed, "1.1.0")

    def test_stage_preserves_non_targets_and_exact_inventory(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root)
            originals = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in mods.glob("*.jar")}
            plan_path = external.stage(mods, root / "staged")
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            self.assertEqual([external.VOXY, external.BURNT], plan["installPlan"]["replace"])
            self.assertEqual([target.companion for target in external.TARGETS], plan["installPlan"]["add"])
            self.assertEqual(5, len(plan["inventory"]))
            self.assertEqual(originals, {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in mods.glob("*.jar")})
            for filename, owner in ((external.VOXY, "voxy"), (external.BURNT, "burnt")):
                with zipfile.ZipFile(mods / filename) as source, zipfile.ZipFile(root / "staged" / filename) as staged:
                    selected = {target.nested_path for target in external.TARGETS if target.owner == owner}
                    self.assertEqual(b"same", staged.read("assets/unchanged.bin"))
                    self.assertEqual(source.read("assets/unchanged.bin"), staged.read("assets/unchanged.bin"))
                    before, after = source.getinfo("assets/unchanged.bin"), staged.getinfo("assets/unchanged.bin")
                    self.assertEqual((before.date_time, before.comment, before.extra, before.internal_attr,
                                      before.external_attr, before.create_system, before.create_version,
                                      before.extract_version, before.flag_bits),
                                     (after.date_time, after.comment, after.extra, after.internal_attr,
                                      after.external_attr, after.create_system, after.create_version,
                                      after.extract_version, after.flag_bits))
                    self.assertFalse(selected & set(staged.namelist()))
                    staged_metadata = json.loads(staged.read(external.METADATA))
                    self.assertFalse({item["path"] for item in staged_metadata["jars"]} & selected)
            basic_target = external.TARGETS[2]
            with zipfile.ZipFile(mods / external.BURNT) as source:
                self.assertEqual(source.read(basic_target.nested_path),
                                 (root / "staged" / basic_target.companion).read_bytes())
            for target in external.TARGETS[:2]:
                with zipfile.ZipFile(root / "staged" / target.companion) as companion:
                    manifest = companion.read("META-INF/MANIFEST.MF")
                    self.assertIn(b"FMLModType: LIBRARY", manifest)
                    self.assertIn(target.module_name.encode(), manifest)

    def test_refuses_signed_parent_or_duplicate_companion(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root, signed=True)
            with self.assertRaisesRegex(external.ExternalizationError, "signed parent"):
                external.stage(mods, root / "staged")
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root)
            with zipfile.ZipFile(mods / external.TARGETS[0].companion, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
            with self.assertRaisesRegex(external.ExternalizationError, "already provide"):
                external.stage(mods, root / "staged")

    def test_refuses_nested_module_info(self) -> None:
        with tempfile.TemporaryDirectory(dir=SCRATCH) as raw:
            root = Path(raw)
            mods = self.build_mods(root)
            voxy = mods / external.VOXY
            with zipfile.ZipFile(voxy) as source:
                entries = {info.filename: source.read(info) for info in source.infolist()}
            target = external.TARGETS[0]
            with zipfile.ZipFile(__import__("io").BytesIO(entries[target.nested_path])) as library:
                library_entries = {info.filename: library.read(info) for info in library.infolist()}
            library_entries["module-info.class"] = b"unreviewed"
            entries[target.nested_path] = jar_bytes(library_entries)
            with zipfile.ZipFile(voxy, "w") as output:
                for name, content in entries.items():
                    output.writestr(name, content)
            with self.assertRaisesRegex(external.ExternalizationError, "module-info"):
                external.stage(mods, root / "staged")


if __name__ == "__main__":
    unittest.main()
