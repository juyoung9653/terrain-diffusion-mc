"""GPU transform-feedback check for final Photon shadow caster clipping.

This runs generated shader vertex programs in a hidden standalone OpenGL context.
It verifies pack code only; it does not prove Iris runtime block-state binding.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.photon_waving.validate import expand
from tools.photon_dof.validate_gl import jar, java_tool
from tools.photon_dof.build import apply_setting

POSITIONS = {"near_positive_chunk_1": True, "near_negative_chunk_minus1": True,
             "far_positive_chunk_2": False, "far_negative_chunk_minus2": False}


def prepare(pack: Path, settings: str, folder: Path, programs: list[str]) -> list[Path]:
    folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pack) as archive:
        files = {name[8:]: archive.read(name) for name in archive.namelist()
                 if name.startswith("shaders/") and not name.endswith("/")}
    text = files["settings.glsl"].decode("utf-8-sig")
    for line in settings.splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            name, value = line.split("=", 1)
            text = apply_setting(text, name.strip(), value.strip())
    files["settings.glsl"] = text.encode("utf-8")
    head = "#define MC_VERSION 12101\n#define MC_HAND_DEPTH 0.125\n#define MC_SHADOW_QUALITY 1.0\n#define IS_IRIS\n#define VOXY\n"
    output = []
    for program in programs:
        source = expand(files, f"world0/{program}.vsh")
        source = re.sub(r"(?m)^(#version[^\n]*\n)", r"\1" + head, source, count=1)
        path = folder / f"{program}.vsh"
        path.write_text(source, encoding="utf-8")
        output.append(path)
    return output


def verify_source_contract(sources: list[Path]) -> None:
    """Reject an accidental probe run against a pack that lacks the generated shadow gate."""
    for source in sources:
        text = source.read_text(encoding="utf-8")
        required = ("get_caster_class(mc_Entity.x)", "caster_class == 0u",
                    "gl_Position = vec4(2.0, 2.0, 2.0, 1.0)", "raw_id >= 22000.0", "raw_id >= 26000.0")
        missing = [part for part in required if part not in text]
        if missing:
            raise RuntimeError(f"{source.name} lacks generated caster clip contract: {missing}")


def expected(mode: str, row: dict) -> bool:
    if row["near"]:
        return False
    if row["material"] == "full_cube":
        return False
    return mode == "cube" or row["material"] != "stairs_slab"


def materials(mode: str) -> dict[str, int]:
    # The builder does not encode stairs/slabs in cube mode: they retain base 10001.
    return {"full_cube": 22001, "stairs_slab": 10001 if mode == "cube" else 26001,
            "leaf": 10005, "unassigned": -1}


def self_test() -> None:
    near = {"near": True, "material": "leaf"}
    assert not expected("cube", near)
    assert not expected("cube", {"near": False, "material": "full_cube"})
    assert expected("cube", {"near": False, "material": "stairs_slab"})
    assert not expected("cube-stairs-slabs", {"near": False, "material": "stairs_slab"})
    assert expected("cube-stairs-slabs", {"near": False, "material": "leaf"})
    assert materials("cube")["stairs_slab"] == 10001
    assert materials("cube-stairs-slabs")["stairs_slab"] == 26001


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shader_zip", type=Path, nargs="?", help="generated caster-mode Photon ZIP")
    parser.add_argument("--mode", choices=("cube", "cube-stairs-slabs"), default="cube")
    parser.add_argument("--settings", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--all-shadow-programs", action="store_true",
                        help="also run shadow_solid and shadow_cutout")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print(json.dumps({"passed": True, "scope": "CPU expectation matrix only"}))
        return
    if args.shader_zip is None or args.settings is None or args.output is None:
        parser.error("shader_zip, --settings, and --output are required unless --self-test is used")
    args.output.mkdir(parents=True, exist_ok=True)
    programs = ["shadow"] + (["shadow_solid", "shadow_cutout"] if args.all_shadow_programs else [])
    sources = prepare(args.shader_zip, args.settings.read_text(encoding="utf-8-sig"), args.output / "sources", programs)
    verify_source_contract(sources)
    classes = args.output / "classes"; classes.mkdir(exist_ok=True)
    libraries = [jar(name) for name in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl")]
    flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
    subprocess.run([java_tool("javac"), "-cp", ";".join(map(str, libraries)), "-d", str(classes),
                    str(ROOT / "tools/photon_waving/CasterClipProbe.java")], check=True, **flags)
    classpath = ";".join(map(str, [classes, *libraries,
                                    *[jar(name, True) for name in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl")]]))
    result = subprocess.run([java_tool("java"), "-cp", classpath, "CasterClipProbe", args.mode, *map(str, sources)],
                            capture_output=True, text=True, timeout=240, **flags)
    (args.output / "stdout.log").write_text(result.stdout, encoding="utf-8")
    (args.output / "stderr.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"caster GPU probe failed: {result.stderr[-4000:]}")
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    mode_materials = materials(args.mode)
    expected_count = len(programs) * len(mode_materials) * len(POSITIONS)
    if len(rows) != expected_count:
        raise RuntimeError(f"incomplete capture: got {len(rows)}, expected {expected_count}")
    for row in rows:
        if row["raw_id"] != mode_materials.get(row["material"]) or row["near"] != POSITIONS.get(row["position"]):
            raise RuntimeError(f"unexpected probe case: {row}")
        row["expected_clipped"] = expected(args.mode, row)
        row["passed"] = row["clipped"] == row["expected_clipped"]
    report = {"scope": __doc__.strip(), "shader_sha256": hashlib.sha256(args.shader_zip.read_bytes()).hexdigest(),
              "settings_sha256": hashlib.sha256(args.settings.read_bytes()).hexdigest(), "mode": args.mode,
              "programs": programs, "cases": rows, "passed": all(row["passed"] for row in rows)}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "cases": len(rows), "mode": args.mode}))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
