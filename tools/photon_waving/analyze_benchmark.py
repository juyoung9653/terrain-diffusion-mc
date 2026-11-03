"""Validate and summarize prepared Photon shadow-GPU benchmark repetitions.

This tool only reads completed clone-run directories.  It does not start
Minecraft or infer GPU-busy time from OpenGL timestamp intervals.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
import sys
from pathlib import Path


MODES = ("baseline", "cube", "cube-stairs-slabs")
WALL_COLUMN = "frame_wall_elapsed_ms"
TIMER_SOURCE = Path(__file__).with_name("client_src") / "com" / "terrain" / "photonwavingprobe" / "ShadowGpuTimer.java"
IRIS_SODIUM_PROFILE = "iris-sodium"


class ValidationError(RuntimeError):
    pass


def fail(run: Path, message: str) -> None:
    raise ValidationError(f"{run}: {message}")


def read_json(path: Path, run: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(run, f"cannot read {path.name}: {error}")
    if not isinstance(value, dict): fail(run, f"{path.name} must be a JSON object")
    return value


def require(value: bool, run: Path, message: str) -> None:
    if not value: fail(run, message)


def number(value: object, run: Path, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        fail(run, f"{label} must be a finite number")
    return float(value)


def positive_int(value: object, run: Path, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        fail(run, f"{label} must be a non-negative integer")
    return value


def sha256(path: Path, run: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        fail(run, f"cannot hash {path}: {error}")
    raise AssertionError("fail exits")


def normalized_manifest(raw: object, run: Path, probe_jar: str, probe_sha: str) -> dict[str, str]:
    if not isinstance(raw, dict): fail(run, "prepare files_sha256 manifest missing")
    probe_key = "mods/" + probe_jar
    result: dict[str, str] = {}
    found_probe = False
    for raw_name, digest in raw.items():
        if not isinstance(raw_name, str) or not isinstance(digest, str): fail(run, "files_sha256 keys and values must be strings")
        name = raw_name.replace("\\", "/")
        if name == probe_key:
            require(digest == probe_sha, run, "probe manifest entry does not match probe_sha256")
            name = "mods/<diagnostic-probe>"
            found_probe = True
        if name in result and result[name] != digest: fail(run, f"normalized manifest key collision: {name}")
        result[name] = digest
    require(found_probe, run, "probe JAR is absent from files_sha256")
    return result


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * fraction) - 1]


def stats(values: list[float]) -> dict:
    if not values: raise ValueError("empty measurement stream")
    return {
        "count": len(values),
        "mean_ms": statistics.fmean(values),
        "median_ms": statistics.median(values),
        "p95_ms": percentile(values, .95),
    }


def csv_rows(path: Path, run: Path, expected_fields: tuple[str, ...]) -> list[dict[str, str]]:
    try:
        with path.open("r", encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source)
            if tuple(reader.fieldnames or ()) != expected_fields:
                fail(run, f"{path.name} header {reader.fieldnames!r}, expected {expected_fields!r}")
            rows = list(reader)
    except OSError as error:
        fail(run, f"cannot read {path.name}: {error}")
    if not rows: fail(run, f"{path.name} has no samples")
    return rows


def timestamp_stream(path: Path, run: Path, elapsed_column: str, shadow: bool) -> list[float]:
    rows = csv_rows(path, run, ("frame", "pass", "start_timestamp_ns", "end_timestamp_ns", elapsed_column))
    result: list[float] = []
    for index, row in enumerate(rows, 2):
        try:
            frame, passed = int(row["frame"]), int(row["pass"])
            start, end = int(row["start_timestamp_ns"]), int(row["end_timestamp_ns"])
            elapsed = float(row[elapsed_column])
        except (TypeError, ValueError) as error:
            fail(run, f"{path.name}:{index} has invalid numeric fields: {error}")
        if frame <= 0 or start <= 0 or end <= 0 or end < start or not math.isfinite(elapsed) or elapsed < 0:
            fail(run, f"{path.name}:{index} has non-positive or unmatched timestamps")
        if shadow and passed <= 0: fail(run, f"{path.name}:{index} shadow pass must be positive")
        if not shadow and passed != 0: fail(run, f"{path.name}:{index} frame interval pass must be zero")
        expected = (end - start) / 1_000_000.0
        if not math.isclose(elapsed, expected, rel_tol=0.0, abs_tol=0.000001):
            fail(run, f"{path.name}:{index} elapsed does not match raw timestamps")
        result.append(elapsed)
    return result


def wall_stream(path: Path, run: Path) -> list[float]:
    rows = csv_rows(path, run, ("frame", WALL_COLUMN))
    values: list[float] = []
    for index, row in enumerate(rows, 2):
        try:
            frame, elapsed = int(row["frame"]), float(row[WALL_COLUMN])
        except (TypeError, ValueError) as error:
            fail(run, f"{path.name}:{index} has invalid numeric fields: {error}")
        if frame <= 1 or not math.isfinite(elapsed) or elapsed <= 0:
            fail(run, f"{path.name}:{index} must contain a positive post-origin frame interval")
        values.append(elapsed)
    return values


def validate_timer_source() -> str:
    try:
        source = TIMER_SOURCE.read_text(encoding="utf-8")
    except OSError as error:
        raise ValidationError(f"timer source unavailable for wall-column audit: {error}") from error
    required = f'writer.write("frame,{WALL_COLUMN}\\n")'
    if required not in source:
        raise ValidationError(f"timer source does not define expected wall CSV column {WALL_COLUMN!r}")
    return str(TIMER_SOURCE)


def run_relative_path(value: object, run: Path, label: str) -> Path:
    if not isinstance(value, str) or not value:
        fail(run, f"{label} is missing")
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        fail(run, f"{label} is unsafe")
    resolved = (run / relative).resolve()
    if resolved.parent != run and run not in resolved.parents:
        fail(run, f"{label} escapes run directory")
    return resolved


def sha256_text(value: object, run: Path, label: str) -> str:
    require(isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value),
            run, f"{label} must be a lowercase SHA-256")
    return value


def existing_scene_contract(run: Path, prepare: dict, receipt: dict, manifest: dict[str, str]) -> dict | None:
    """Validate the immutable copied-world description used by minimal runs."""
    scene = prepare.get("scene")
    if scene is None:
        return None
    require(isinstance(scene, dict), run, "prepare scene must be an object")
    kind = scene.get("kind")
    require(kind == "existing", run, "only existing-world scene contracts are supported")
    camera = scene.get("camera")
    require(isinstance(camera, dict), run, "existing scene camera is missing")
    expected_camera = {key: number(camera.get(key), run, f"scene camera.{key}")
                       for key in ("x", "y", "z", "yaw", "pitch")}
    profile = scene.get("renderer_profile")
    require(profile in ("fullpack", "original-modpack", IRIS_SODIUM_PROFILE), run,
            "unknown existing-world renderer profile")
    world_manifest = sha256_text(scene.get("world_manifest_sha256"), run, "scene world_manifest_sha256")
    scene_path = run / "scene-config.json"
    require(manifest.get("scene-config.json") == sha256(scene_path, run), run,
            "scene-config.json hash differs from prepared manifest")
    require(read_json(scene_path, run) == scene, run, "scene-config.json differs from prepare scene")
    require(receipt.get("scene_kind") == "existing", run, "receipt did not identify existing-world scene")
    require(receipt.get("inside_cluster") is None and receipt.get("outside_cluster") is None, run,
            "existing-world receipt contains synthetic scene markers")
    requested_distance = positive_int(prepare.get("render_distance"), run, "prepare render_distance")
    require(requested_distance > 0, run, "prepare render_distance must be positive")
    require(receipt.get("render_distance_chunks") == requested_distance, run,
            "receipt render distance differs from prepared profile")
    return {"kind": kind, "camera": expected_camera, "renderer_profile": profile,
            "world_manifest_sha256": world_manifest, "render_distance": requested_distance}


def validate_iris_sodium_mods(run: Path, manifest: dict[str, str]) -> None:
    """Minimal renderer clones may contain only Iris, Sodium, and the probe JAR."""
    mod_entries = {name: digest for name, digest in manifest.items() if name.startswith("mods/")}
    expected_names = {"mods/<diagnostic-probe>"}
    iris = [name for name in mod_entries if name.startswith("mods/iris-neoforge-") and name.endswith(".jar")]
    sodium = [name for name in mod_entries if name.startswith("mods/sodium-neoforge-") and name.endswith(".jar")]
    require(len(iris) == 1 and len(sodium) == 1, run, "minimal renderer manifest must contain one Iris and one Sodium JAR")
    expected_names.update(iris); expected_names.update(sodium)
    require(set(mod_entries) == expected_names, run, "minimal renderer manifest contains non-Iris/Sodium mods")
    actual_names = {"mods/" + path.name for path in (run / "mods").iterdir() if path.is_file()}
    actual_names_normalized = {"mods/<diagnostic-probe>" if name.startswith("mods/photon-waving-probe-") else name
                               for name in actual_names}
    require(actual_names_normalized == expected_names, run, "minimal renderer mods directory differs from manifest")
    for name, digest in mod_entries.items():
        if name == "mods/<diagnostic-probe>":
            continue
        require(sha256(run / name, run) == digest, run, f"minimal renderer mod changed: {name}")


def verify_caster_runtime(run: Path, prepare: dict, launch: dict, receipt: dict,
                          mode: str, source_shader: str, caster_build: object) -> dict | None:
    """Cross-check the pre-launch frozen metadata and the in-client Iris verification."""
    requested = prepare.get("caster_verification")
    if mode == "baseline":
        require(not isinstance(requested, dict) or requested.get("mode") == "baseline", run,
                "baseline caster verification mode is not baseline")
        return None
    require(isinstance(requested, dict), run, "caster variant has no caster_verification contract")
    require(requested.get("required") is True, run, "caster variant verification was not required")
    require(requested.get("mode") == mode, run, "caster verification mode differs from caster_mode")
    metadata_path = run_relative_path(requested.get("metadata_path"), run, "caster metadata_path")
    report_path = run_relative_path(requested.get("report_path"), run, "caster report_path")
    expected_metadata_sha = sha256_text(requested.get("metadata_sha256"), run, "caster metadata_sha256")
    require(sha256(metadata_path, run) == expected_metadata_sha, run,
            "staged caster metadata hash differs from prepare receipt")
    metadata = read_json(metadata_path, run)
    require(metadata.get("source_shader_sha256") == source_shader, run,
            "caster metadata source hash differs from source shader")
    require(isinstance(caster_build, dict), run, "caster variant has no caster-build metadata")
    require(caster_build.get("source_sha256") == source_shader, run,
            "caster-build source hash differs from source shader")
    require(caster_build.get("metadata_sha256") == expected_metadata_sha, run,
            "caster-build metadata hash differs from prepare verification metadata")
    require(caster_build.get("mode") == mode, run, "caster-build mode differs from caster_mode")

    launch_verification = launch.get("caster_verification")
    require(isinstance(launch_verification, dict), run, "launch receipt has no caster verification")
    require(launch_verification.get("mode") == mode, run, "launch caster verification mode differs")
    require(launch_verification.get("metadata_path") == requested.get("metadata_path"), run,
            "launch caster metadata path differs from prepare receipt")
    require(launch_verification.get("report_path") == requested.get("report_path"), run,
            "launch caster report path differs from prepare receipt")
    require(launch_verification.get("metadata_sha256") == expected_metadata_sha, run,
            "launch caster metadata hash differs from prepare receipt")
    report = read_json(report_path, run)
    require(report.get("mode") == mode, run, "caster verification report mode differs")
    report_metadata = report.get("metadata")
    require(isinstance(report_metadata, str) and Path(report_metadata).resolve() == metadata_path,
            run, "caster verification report used unexpected metadata")
    expected_states = positive_int(report.get("expectedStates"), run, "caster verification expectedStates")
    checked_states = positive_int(report.get("checkedStates"), run, "caster verification checkedStates")
    require(expected_states > 0 and checked_states >= expected_states, run,
            "caster verification state counts are invalid")
    require(report.get("mismatches") == 0, run, "caster verification reported mapping mismatches")
    require(launch_verification.get("expected_states") == expected_states, run,
            "launch caster expected-state count differs from report")
    require(launch_verification.get("checked_states") == checked_states, run,
            "launch caster checked-state count differs from report")
    require(launch_verification.get("mismatches") == 0, run,
            "launch caster verification reported mapping mismatches")
    require(receipt.get("caster_verification_requested") is True, run,
            "probe receipt did not request caster verification")
    require(receipt.get("caster_verification_passed") is True, run,
            "probe receipt did not pass caster verification")
    require(receipt.get("caster_verification_mode") == mode, run,
            "probe receipt caster verification mode differs")
    return {"metadata_sha256": expected_metadata_sha, "expected_states": expected_states,
            "checked_states": checked_states, "report_path": str(report_path.relative_to(run))}


def validated_run(path: Path, settings_reference: Path | None = None) -> dict:
    run = path.resolve()
    prepare = read_json(run / "prepare-receipt.json", run)
    launch = read_json(run / "launch-receipt.json", run)
    receipt = read_json(run / "receipt.json", run)
    require(launch.get("exit_code") == 0, run, "launch exit_code must be 0")
    require(not launch.get("timed_out"), run, "launch timed out")
    require(not launch.get("crash_reports"), run, "launch recorded crash reports")
    require(receipt.get("success") is True, run, "probe receipt is unsuccessful")
    require(receipt.get("benchmark") is True, run, "receipt does not identify a benchmark run")
    benchmark_seconds = positive_int(prepare.get("benchmark_seconds"), run, "prepare benchmark_seconds")
    require(benchmark_seconds >= 30, run, "benchmark_seconds must be at least 30")
    require(receipt.get("benchmark_seconds") == benchmark_seconds, run, "receipt benchmark_seconds differs from prepared value")
    width, height = positive_int(prepare.get("width"), run, "prepare width"), positive_int(prepare.get("height"), run, "prepare height")
    require(receipt.get("window") == {"width": width, "height": height}, run, "requested and actual viewport differ")
    captures = receipt.get("captures")
    require(isinstance(captures, list) and len(captures) == 3, run, "receipt must contain exactly three captures")
    settle_ms, settle_frames = positive_int(receipt.get("settle_ms"), run, "receipt settle_ms"), positive_int(receipt.get("settle_frames"), run, "receipt settle_frames")
    prepared_scene = prepare.get("scene")
    camera_raw = (prepared_scene.get("camera") if isinstance(prepared_scene, dict) else
                  {"x": 40.5, "y": 166.0, "z": -572.5, "yaw": -90.0, "pitch": 7.0})
    require(isinstance(camera_raw, dict), run, "prepared scene camera is invalid")
    camera = {key: number(camera_raw.get(key), run, f"prepared scene camera.{key}")
              for key in ("x", "y", "z", "yaw", "pitch")}
    previous_elapsed = -1
    previous_rendered = -1
    for index, capture in enumerate(captures):
        require(isinstance(capture, dict), run, f"capture {index} must be an object")
        require(capture.get("day_time") == 6000, run, f"capture {index} has non-frozen day time")
        actual_camera = capture.get("camera")
        require(isinstance(actual_camera, dict), run, f"capture {index} camera is missing")
        for key, expected in camera.items():
            require(abs(number(actual_camera.get(key), run, f"capture {index} camera.{key}") - expected) < 0.01, run,
                    f"capture {index} camera.{key} differs from measured scene")
        elapsed = positive_int(capture.get("elapsed_ms"), run, f"capture {index} elapsed_ms")
        rendered = positive_int(capture.get("rendered_frames"), run, f"capture {index} rendered_frames")
        stable = positive_int(capture.get("stable_frames"), run, f"capture {index} stable_frames")
        require(rendered > previous_rendered, run, f"capture {index} rendered_frames did not advance")
        if index == 0:
            require(elapsed >= benchmark_seconds * 1000, run, "first capture predates benchmark duration")
            require(positive_int(capture.get("scene_ms"), run, "capture 0 scene_ms") >= settle_ms, run, "capture 0 predates warm-up")
            require(stable >= settle_frames, run, "capture 0 predates stable-frame warm-up")
        else:
            require(elapsed - previous_elapsed >= 6000, run, f"capture {index} did not preserve screenshot gap")
        previous_elapsed, previous_rendered = elapsed, rendered
    log_path = run / "logs" / "latest.log"
    try: log = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError as error: fail(run, f"cannot read latest.log: {error}")
    mode = prepare.get("caster_mode")
    require(mode in MODES, run, f"unsupported caster_mode {mode!r}")
    source_shader = prepare.get("source_shader_sha256")
    probe = prepare.get("probe_sha256")
    require(isinstance(source_shader, str) and len(source_shader) == 64, run, "source_shader_sha256 is missing")
    require(isinstance(probe, str) and len(probe) == 64, run, "probe_sha256 is missing")
    shader_zip, probe_jar = prepare.get("shader_zip"), prepare.get("probe_jar")
    require(isinstance(shader_zip, str) and Path(shader_zip).name == shader_zip, run, "shader_zip is missing or unsafe")
    require(isinstance(probe_jar, str) and Path(probe_jar).name == probe_jar, run, "probe_jar is missing or unsafe")
    shader_sha, settings_sha = prepare.get("shader_sha256"), prepare.get("settings_sha256")
    require(isinstance(shader_sha, str) and len(shader_sha) == 64, run, "shader_sha256 is missing")
    require(isinstance(settings_sha, str) and len(settings_sha) == 64, run, "settings_sha256 is missing")
    require(launch.get("shader_sha256") == shader_sha, run, "launch shader_sha256 differs from prepare receipt")
    require(sha256(run / "shaderpacks" / shader_zip, run) == shader_sha, run, "staged shader hash differs from prepare receipt")
    settings_path = run / "shaderpacks" / (shader_zip + ".txt")
    runtime_settings_sha = sha256(settings_path, run)
    if runtime_settings_sha != settings_sha:
        require(settings_reference is not None, run, "staged settings changed; supply the hash-matched original --settings-reference")
        require(sha256(settings_reference, run) == settings_sha, run, "settings reference differs from prepared input hash")
        def option_lines(path):
            return [line for line in path.read_text(encoding="utf-8-sig").splitlines()
                    if line.strip() and not line.lstrip().startswith(("#", "!"))]
        require(option_lines(settings_path) == option_lines(settings_reference), run,
                "runtime settings differ beyond comments/blank lines")
    require(sha256(run / "mods" / probe_jar, run) == probe, run, "staged probe hash differs from prepare receipt")
    manifest = normalized_manifest(prepare.get("files_sha256"), run, probe_jar, probe)
    require(manifest.get("shaderpacks/" + shader_zip) == shader_sha, run, "shader manifest entry differs from shader_sha256")
    require(manifest.get("shaderpacks/" + shader_zip + ".txt") == settings_sha, run, "settings manifest entry differs from settings_sha256")
    scene = existing_scene_contract(run, prepare, receipt, manifest)
    renderer_profile = scene["renderer_profile"] if scene else "fullpack"
    if renderer_profile == IRIS_SODIUM_PROFILE:
        require(prepare.get("require_dlss_bridge") is False, run,
                "minimal Iris/Sodium renderer must not require native DLSS")
        require("OpenGL D3D12 SR/FG bridge initialized" not in log, run,
                "minimal Iris/Sodium renderer unexpectedly initialized DLSS bridge")
        validate_iris_sodium_mods(run, manifest)
    else:
        require(prepare.get("require_dlss_bridge") is True, run, "native DLSS bridge is required for this benchmark")
        require("OpenGL D3D12 SR/FG bridge initialized" in log, run, "DLSS D3D12 bridge did not initialize")
        require("DLSS native bridge not installed" not in log, run, "DLSS bridge fell back because native bridge is missing")
    caster_build = prepare.get("caster_build")
    caster_build_verified = False
    if "caster-build.json" in manifest:
        build_path = run / "caster-build.json"
        require(sha256(build_path, run) == manifest["caster-build.json"], run, "caster-build bookkeeping hash differs from manifest")
        actual_build = read_json(build_path, run)
        require(isinstance(caster_build, dict) and actual_build == caster_build, run, "caster-build bookkeeping differs from prepare receipt")
        require(actual_build.get("source_sha256") == source_shader, run, "caster-build source hash differs from source shader")
        require(actual_build.get("mode") == mode, run, "caster-build mode differs from caster_mode")
        caster_build_verified = True
    elif caster_build is not None:
        fail(run, "prepare has caster_build metadata but manifest omits caster-build.json")
    caster_runtime_verification = verify_caster_runtime(run, prepare, launch, receipt, mode, source_shader,
                                                        caster_build)
    timing = read_json(run / "timing" / "shadow-gpu-timing.json", run)
    require(timing.get("gl_timestamp_supported") is True, run, "OpenGL timestamp timing is unsupported")
    require(timing.get("query_failures") == 0, run, "timestamp query failures occurred")
    require(receipt.get("benchmark_glfw_focused_at_start") is True, run, "benchmark did not start with GLFW focus")
    focused_frames = positive_int(timing.get("glfw_focused_frame_count"), run, "glfw_focused_frame_count")
    require(focused_frames > 0 and timing.get("glfw_unfocused_frame_count") == 0, run,
            "benchmark contains unfocused frames or lacks focus evidence")
    require(focused_frames == timing.get("frame_interval", {}).get("submitted"), run,
            "focused frame count does not match submitted frame intervals")
    streams = {"frame_interval": ("frame-gpu-timestamps.csv", "interval_elapsed_ms", False),
               "shadow_pass": ("shadow-gpu-timestamps.csv", "shadow_elapsed_ms", True)}
    values: dict[str, list[float]] = {}
    for key, (filename, column, is_shadow) in streams.items():
        status = timing.get(key)
        require(isinstance(status, dict), run, f"timing {key} status missing")
        completed = positive_int(status.get("completed"), run, f"{key}.completed")
        submitted = positive_int(status.get("submitted"), run, f"{key}.submitted")
        pending = positive_int(status.get("pending"), run, f"{key}.pending")
        require(completed >= 100, run, f"{key}.completed is below 100")
        require(status.get("dropped") == 0, run, f"{key}.dropped is nonzero")
        require(status.get("unbalanced") == 0, run, f"{key}.unbalanced is nonzero")
        require(submitted == completed + pending, run, f"{key}.submitted must equal completed + pending when query_failures is zero")
        stream = timestamp_stream(run / "timing" / filename, run, column, is_shadow)
        require(len(stream) == completed, run, f"{filename} row count does not match timing completed count")
        values[key] = stream
    wall = wall_stream(run / "timing" / "frame-wall-times.csv", run)
    require(len(wall) == positive_int(timing.get("frame_wall_sample_count"), run, "frame_wall_sample_count"), run,
            "frame-wall-times.csv row count does not match frame_wall_sample_count")
    return {
        "run": run.name, "path": str(run), "caster_mode": mode, "viewport": {"width": width, "height": height},
        "benchmark_seconds": benchmark_seconds, "probe_sha256": probe, "source_shader_sha256": source_shader,
        "source_settings_sha256": prepare.get("source_settings_sha256"), "settings_sha256": prepare.get("settings_sha256"),
        "scene": scene, "renderer_profile": renderer_profile,
        "shader_sha256": prepare.get("shader_sha256"), "export_registry": prepare.get("export_registry"),
        "files_sha256": manifest, "caster_build": caster_build, "caster_build_verified": caster_build_verified,
        "caster_runtime_verification": caster_runtime_verification, "probe_jar": probe_jar,
        "timing_status": {key: timing[key] for key in streams},
        "runtime_settings_sha256": runtime_settings_sha,
        "runtime_settings_comment_only_rewrite": runtime_settings_sha != settings_sha,
        "shadow_elapsed_ms": stats(values["shadow_pass"]), "frame_interval_elapsed_ms": stats(values["frame_interval"]),
        "frame_wall_ms": stats(wall), "frame_wall_fps": 1000.0 / statistics.median(wall),
    }


def manifest_summary(baseline: dict, current: dict) -> dict:
    first, other = baseline["files_sha256"], current["files_sha256"]
    if not isinstance(first, dict) or not isinstance(other, dict):
        raise ValidationError("prepare files_sha256 manifest missing")
    changed = [name for name in sorted(set(first) | set(other)) if first.get(name) != other.get(name)]
    bookkeeping_names = {"caster-build.json", "caster-source-metadata.json"}
    bookkeeping = [name for name in changed if name in bookkeeping_names]
    allowed_bookkeeping = not bookkeeping or (current["caster_build_verified"] and
                                              current["caster_runtime_verification"] is not None)
    non_pack = [name for name in changed if not name.startswith("shaderpacks/") and name not in bookkeeping]
    config_changed = [name for name in changed if name.startswith(("config/", "defaultconfigs/", "options.txt"))]
    return {
        "changed_prepared_files": changed,
        "only_shaderpack_file_differences": not non_pack,
        "non_shaderpack_differences": non_pack,
        "caster_build_bookkeeping_differences": bookkeeping,
        "caster_build_bookkeeping_verified": allowed_bookkeeping,
        "source_config_hashes_match": baseline["source_settings_sha256"] == current["source_settings_sha256"],
        "staged_config_file_differences": config_changed,
        "export_registry_property_difference_intentional": baseline["export_registry"] != current["export_registry"],
        "registry_metadata": current["caster_build"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, help="completed clone-run directories")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--settings-reference", type=Path, help="Original prepared settings bytes; only comment/blank-line rewrites are allowed")
    args = parser.parse_args()
    timer_source = validate_timer_source()
    runs = [validated_run(path, args.settings_reference) for path in args.runs]
    reference = runs[0]
    for run in runs[1:]:
        if run["viewport"] != reference["viewport"]: raise ValidationError("all runs must have the same actual viewport")
        if run["probe_sha256"] != reference["probe_sha256"]: raise ValidationError("all runs must have the same probe_sha256")
        if run["source_shader_sha256"] != reference["source_shader_sha256"]: raise ValidationError("all runs must have the same source_shader_sha256")
        if run["source_settings_sha256"] != reference["source_settings_sha256"]: raise ValidationError("source shader configurations differ")
        if run["renderer_profile"] != reference["renderer_profile"]: raise ValidationError("renderer profiles differ")
        if run["scene"] != reference["scene"]: raise ValidationError("prepared scene/camera/world fingerprint differs")
    by_mode = {mode: [run for run in runs if run["caster_mode"] == mode] for mode in MODES}
    if not by_mode["baseline"]: raise ValidationError("at least one baseline run is required")
    mode_summary = {}
    baseline_median = statistics.median(run["shadow_elapsed_ms"]["median_ms"] for run in by_mode["baseline"])
    for mode, members in by_mode.items():
        if not members: continue
        median_of_run_medians = statistics.median(member["shadow_elapsed_ms"]["median_ms"] for member in members)
        mode_summary[mode] = {
            "repeat_count": len(members), "provisional": len(members) < 2,
            "shadow_median_of_run_medians_ms": median_of_run_medians,
            "shadow_relative_to_baseline_percent": (median_of_run_medians / baseline_median - 1.0) * 100.0,
        }
        for metric in ("frame_interval_elapsed_ms", "frame_wall_ms"):
            measured = statistics.median(member[metric]["median_ms"] for member in members)
            reference_median = statistics.median(member[metric]["median_ms"] for member in by_mode["baseline"])
            mode_summary[mode][metric + "_median_of_run_medians"] = measured
            mode_summary[mode][metric + "_relative_to_baseline_percent"] = (measured / reference_median - 1.0) * 100.0
        mode_summary[mode]["fps_from_median_wall_frame"] = 1000.0 / mode_summary[mode]["frame_wall_ms_median_of_run_medians"]
    for run in runs:
        difference = manifest_summary(reference, run)
        if not difference["only_shaderpack_file_differences"]:
            raise ValidationError(f"{run['run']}: prepared input changed outside shaderpacks: {difference['non_shaderpack_differences']}")
        if not difference["caster_build_bookkeeping_verified"]:
            raise ValidationError(f"{run['run']}: caster-build bookkeeping difference was not verified")
        if not difference["source_config_hashes_match"] or difference["staged_config_file_differences"]:
            raise ValidationError(f"{run['run']}: prepared source/configuration differs from baseline")
        # Registry exports are generated output, absent from the immutable input
        # manifest. A baseline-only export JVM property is explicitly reported.
        run["prepared_input_difference_from_first"] = difference
    scene_scope = ("Prepared copied existing-world fixed-camera caster scene."
                   if reference["scene"] else "Prepared fixed-camera dense synthetic caster scene.")
    report = {
        "scope": scene_scope + " Measurements are OpenGL timestamp timeline elapsed and wall-clock frame pacing; neither is GPU-busy time.",
        "limitations": [
            *( ["The copied existing-world fingerprint establishes matched copies, not an assertion about the original world after copying."]
               if reference["scene"] else ["Dense synthetic scene is not a claim about every Minecraft world."] ),
            "Shader clip/culling can reduce raster work; it does not prove reduced submission or vertex-fetch work.",
            "PureScale OpenGL-to-D3D12 bridge work is not necessarily covered by the OpenGL timestamp streams.",
            "Independent run repetitions, not individual frame rows, are the comparison units; this report does not claim statistical significance.",
        ],
        "timer_source_audited": timer_source, "wall_csv_column": WALL_COLUMN,
        "runs": runs, "modes": mode_summary,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"runs": len(runs), "modes": mode_summary}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except ValidationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
