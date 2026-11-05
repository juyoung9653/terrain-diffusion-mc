"""Focused offline checks for disposable selected-shader staging."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("current_run", ROOT / "current_run.py")
current = importlib.util.module_from_spec(spec); spec.loader.exec_module(current)

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def make_zip(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, value in entries.items(): archive.writestr(name, value)

def expect_error(action, text):
    try: action()
    except RuntimeError as error: assert text in str(error), error
    else: raise AssertionError("expected RuntimeError containing " + text)

def main():
    # Keep fixtures under the writable repository rather than Windows sandbox Temp.
    with tempfile.TemporaryDirectory(dir=ROOT / "tests") as temporary:
        root = Path(temporary); target = root / "trial"; selected = "photon.zip"
        (target / "config").mkdir(parents=True); (target / "shaderpacks").mkdir()
        (target / "config/iris.properties").write_text("shaderPack=photon.zip\n", encoding="utf-8")
        original = target / "shaderpacks" / selected; make_zip(original, {"shaders/composite.fsh": "original"})
        manifest = {"selected_shader": selected, "files_sha256": {"shaderpacks/photon.zip": digest(original)}}
        valid = root / "candidate.zip"; make_zip(valid, {"shaders/composite.fsh": "replacement"})
        result = current.stage_shader_override(valid, target, manifest, target / "settings-before")
        assert digest(target / "shaderpacks" / selected) == digest(valid)
        assert digest(target / "settings-before/shaderpacks" / selected) == manifest["files_sha256"]["shaderpacks/photon.zip"]
        assert result["destination"] == "shaderpacks/photon.zip" and result["original_sha256"] == manifest["files_sha256"]["shaderpacks/photon.zip"]
        malformed = root / "not-a-zip.zip"; malformed.write_bytes(b"not zip")
        expect_error(lambda: current.stage_shader_override(malformed, target, manifest, target / "settings-before-2"), "valid ZIP")
        no_root = root / "no-root.zip"; make_zip(no_root, {"nested/shaders/test.fsh": "x"})
        expect_error(lambda: current.stage_shader_override(no_root, target, manifest, target / "settings-before-3"), "top-level shaders/")
    print("shader override staging checks passed")

if __name__ == "__main__": main()
