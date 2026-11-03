"""Deduplicate completed Photon client-probe inputs with same-volume hardlinks.

Only successful, finished runs below ``WORK/runs`` qualify.  A completed clone is
an evidence artifact, never a writable fixture: this tool deliberately excludes
its saves, configuration, logs, screenshots, receipts, shader packs and probe
JAR.  The default is a dry run; ``--apply`` must be explicit.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/photon-waving-9chunks-20261029/client-probe"
RUNS = WORK / "runs"
TARGETS = ("mods", "terrain-diffusion-models")
CHUNK = 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def inside(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def reparse_point(path: Path) -> bool:
    """Windows junctions and symlinks both escape clone ownership boundaries."""
    try:
        attributes = os.lstat(path).st_file_attributes
    except OSError:
        return True
    return bool(attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def safe_ancestors(path: Path, stop: Path) -> bool:
    """Reject every symlink/junction from a candidate through its direct run."""
    current = path
    while True:
        if reparse_point(current):
            return False
        if current == stop:
            return True
        if current.parent == current:
            return False
        current = current.parent


def regular_inside(path: Path, run: Path, runs: Path) -> bool:
    """Reject links/junctions and every path that escapes its completed run."""
    if not safe_ancestors(path, run) or not path.is_file():
        return False
    try:
        resolved, run_resolved = path.resolve(strict=True), run.resolve(strict=True)
    except OSError:
        return False
    return inside(resolved, run_resolved) and run_resolved.parent == runs


def owner_run(path: Path, runs: Path) -> Path:
    """Return the direct run child containing a permitted file, or fail closed."""
    try:
        relative = path.relative_to(runs)
    except ValueError as error:
        raise RuntimeError("candidate is outside the fixed runs root") from error
    if len(relative.parts) < 2:
        raise RuntimeError("candidate is not beneath a direct run child")
    candidate = runs / relative.parts[0]
    if not regular_inside(path, candidate, runs):
        raise RuntimeError("candidate is not a regular file in a direct completed run")
    return candidate


def completed(run: Path, runs: Path) -> tuple[bool, str]:
    if reparse_point(run) or not run.is_dir() or run.resolve().parent != runs:
        return False, "unsafe_run_path"
    launch, receipt = run / "launch-receipt.json", run / "receipt.json"
    if not regular_inside(launch, run, runs) or not regular_inside(receipt, run, runs):
        return False, "missing_or_unsafe_receipt"
    try:
        launch_value = json.loads(launch.read_text(encoding="utf-8"))
        receipt_value = json.loads(receipt.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False, "invalid_receipt_json"
    if launch_value.get("exit_code") != 0 or launch_value.get("timed_out") is not False:
        return False, "launch_not_successful"
    if receipt_value.get("success") is not True:
        return False, "probe_not_successful"
    return True, "completed_successfully"


def files_for(run: Path, runs: Path):
    probe_name = None
    prepared = run / "prepare-receipt.json"
    if regular_inside(prepared, run, runs):
        try:
            candidate = json.loads(prepared.read_text(encoding="utf-8")).get("probe_jar")
            if isinstance(candidate, str) and Path(candidate).name == candidate:
                probe_name = candidate
        except (OSError, json.JSONDecodeError):
            pass
    mods = run / "mods"
    if mods.is_dir() and safe_ancestors(mods, run):
        for path in sorted(mods.iterdir()):
            if (path.suffix.lower() == ".jar" and path.name != probe_name
                    and not path.name.startswith("photon-waving-probe-")
                    and regular_inside(path, run, runs)):
                yield path
    models = run / "terrain-diffusion-models"
    if not models.is_dir() or not safe_ancestors(models, run):
        return
    for directory, names, files in os.walk(models, followlinks=False):
        directory_path = Path(directory)
        names[:] = [name for name in names if not (directory_path / name).is_symlink()]
        for name in sorted(files):
            path = directory_path / name
            if regular_inside(path, run, runs):
                yield path


def inventory(runs: Path):
    runs = runs.resolve(strict=True)
    if runs.name != "runs" or runs.parent != WORK.resolve():
        raise RuntimeError("runs directory is not the fixed client-probe runs root")
    groups: dict[tuple[int, str], list[Path]] = {}
    # Completed evidence may already share storage. Hash each physical file once
    # per inventory, never reuse a verdict from an earlier invocation.
    physical_hashes: dict[tuple[int, int, int, int], str] = {}
    excluded: dict[str, str] = {}
    eligible: list[str] = []
    for run in sorted(runs.iterdir()):
        ok, reason = completed(run, runs)
        if not ok:
            excluded[run.name] = reason
            continue
        eligible.append(run.name)
        for path in files_for(run, runs):
            stat = path.stat()
            key = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns)
            digest = physical_hashes.get(key)
            if digest is None or stat.st_ino == 0:
                digest = sha256(path)
                physical_hashes[key] = digest
            groups.setdefault((stat.st_size, digest), []).append(path)
    return runs, groups, eligible, excluded


def plan(runs: Path):
    runs, groups, eligible, excluded = inventory(runs)
    replacements = []
    logical_bytes = 0
    counted_allocations = set()
    for (size, digest), paths in sorted(groups.items(), key=lambda item: (item[0][1], item[0][0])):
        # Keep the largest existing link group. A newly completed run whose name
        # sorts first must not relink and rehash every archived copy again.
        canonical = max(paths, key=lambda path: path.stat().st_nlink)
        for duplicate in paths:
            # Existing hardlinks do not consume another allocation; do not claim them.
            if os.path.samefile(canonical, duplicate):
                continue
            replacements.append((canonical, duplicate, size, digest))
            stat = duplicate.stat()
            allocation = (stat.st_dev, stat.st_ino)
            if allocation not in counted_allocations:
                logical_bytes += size
                counted_allocations.add(allocation)
    return {
        "runs": runs,
        "eligible": eligible,
        "excluded": excluded,
        "groups": groups,
        "replacements": replacements,
        "logical_reclaimable_bytes": logical_bytes,
    }


def replace_with_link(canonical: Path, duplicate: Path, size: int, digest: str, runs: Path):
    canonical_run, duplicate_run = owner_run(canonical, runs), owner_run(duplicate, runs)
    if not completed(canonical_run, runs)[0] or not completed(duplicate_run, runs)[0]:
        raise RuntimeError("candidate run is no longer a completed success")
    if canonical.stat().st_size != size or duplicate.stat().st_size != size:
        raise RuntimeError("candidate size changed")
    if sha256(canonical) != digest or sha256(duplicate) != digest:
        raise RuntimeError("candidate hash changed")
    temporary = duplicate.with_name(duplicate.name + ".dedup-" + uuid.uuid4().hex + ".tmp")
    try:
        os.link(canonical, temporary)
        if sha256(temporary) != digest:
            raise RuntimeError("temporary hardlink hash mismatch")
        os.replace(temporary, duplicate)
        if not os.path.samefile(canonical, duplicate) or sha256(duplicate) != digest:
            raise RuntimeError("replacement hardlink verification failed")
    finally:
        if temporary.exists():
            temporary.unlink()


def report_value(result, applied: bool, changes: int = 0):
    groups = []
    for (size, digest), paths in result["groups"].items():
        if len(paths) > 1:
            groups.append({"sha256": digest, "bytes": size, "copies": len(paths),
                           "paths": [str(path.relative_to(result["runs"])) for path in paths]})
    return {
        "scope": "Only completed successful client-probe runs. Do not use these hardlinked evidence clones as writable fixtures.",
        "applied": applied,
        "eligible_runs": result["eligible"],
        "excluded_runs": result["excluded"],
        "duplicate_groups": groups,
        "logical_reclaimable_bytes": result["logical_reclaimable_bytes"],
        "hardlinks_created": changes,
    }


def execute(runs: Path, apply: bool) -> dict:
    result = plan(runs)
    changes = 0
    if apply:
        for canonical, duplicate, size, digest in result["replacements"]:
            replace_with_link(canonical, duplicate, size, digest, result["runs"])
            changes += 1
    return report_value(result, apply, changes)


def self_test(actual_hardlinks: bool):
    test_root = ROOT / ".tmp"
    test_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=test_root) as temporary:
        root = Path(temporary) / "client-probe"; runs = root / "runs"
        for name, success in (("good-a", True), ("good-b", True), ("failed", False)):
            run = runs / name; (run / "mods").mkdir(parents=True); (run / "terrain-diffusion-models").mkdir()
            (run / "mods/example.jar").write_bytes(b"same mod")
            (run / "mods/photon-waving-probe-test.jar").write_bytes(b"probe " + name.encode())
            (run / "terrain-diffusion-models/model.onnx").write_bytes(b"same model")
            (run / "launch-receipt.json").write_text(json.dumps({"exit_code": 0 if success else 1, "timed_out": False}))
            (run / "receipt.json").write_text(json.dumps({"success": success}))
            (run / "prepare-receipt.json").write_text(json.dumps({"probe_jar": "photon-waving-probe-test.jar"}))
        # Exercise fixed-root validation through a locally substituted module root.
        global WORK
        original = WORK; WORK = root
        try:
            before = execute(runs, False)
            assert before["logical_reclaimable_bytes"] == len(b"same mod") + len(b"same model")
            assert all("photon-waving-probe" not in path for group in before["duplicate_groups"] for path in group["paths"])
            try:
                owner_run(Path(temporary) / "outside.bin", runs)
                raise AssertionError("outside candidate was accepted")
            except RuntimeError:
                pass
            # The default test is safe on sandboxed hosts that deny os.link.  The
            # explicit physical mode executes the real replacement/verification.
            global replace_with_link
            actual_replace = replace_with_link
            called = []
            def fake_replace(canonical, duplicate, size, digest, roots):
                assert canonical.read_bytes() == duplicate.read_bytes()
                assert sha256(canonical) == digest and canonical.stat().st_size == size
                assert owner_run(canonical, roots).name == "good-a"
                assert owner_run(duplicate, roots).name == "good-b"
                called.append((canonical, duplicate))
            if actual_hardlinks:
                expected_hashes = {
                    "mod": sha256(runs / "good-a/mods/example.jar"),
                    "model": sha256(runs / "good-a/terrain-diffusion-models/model.onnx"),
                    "failed_mod": sha256(runs / "failed/mods/example.jar"),
                    "failed_model": sha256(runs / "failed/terrain-diffusion-models/model.onnx"),
                }
                after = execute(runs, True)
                assert os.path.samefile(runs / "good-a/mods/example.jar", runs / "good-b/mods/example.jar")
                assert os.path.samefile(runs / "good-a/terrain-diffusion-models/model.onnx", runs / "good-b/terrain-diffusion-models/model.onnx")
                assert sha256(runs / "good-a/mods/example.jar") == expected_hashes["mod"]
                assert sha256(runs / "good-b/mods/example.jar") == expected_hashes["mod"]
                assert sha256(runs / "good-a/terrain-diffusion-models/model.onnx") == expected_hashes["model"]
                assert sha256(runs / "good-b/terrain-diffusion-models/model.onnx") == expected_hashes["model"]
                assert sha256(runs / "failed/mods/example.jar") == expected_hashes["failed_mod"]
                assert sha256(runs / "failed/terrain-diffusion-models/model.onnx") == expected_hashes["failed_model"]
            else:
                replace_with_link = fake_replace
                try:
                    after = execute(runs, True)
                finally:
                    replace_with_link = actual_replace
            assert after["hardlinks_created"] == 2
            if not actual_hardlinks:
                assert len(called) == 2
            assert not os.path.samefile(runs / "good-a/mods/example.jar", runs / "failed/mods/example.jar")
            assert not os.path.samefile(runs / "good-a/terrain-diffusion-models/model.onnx", runs / "failed/terrain-diffusion-models/model.onnx")
        finally:
            WORK = original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="replace only eligible duplicate files with verified hardlinks")
    parser.add_argument("--self-test", action="store_true", help="run isolated tempfile invariants")
    parser.add_argument("--self-test-hardlinks", action="store_true", help="also perform actual hardlink replacement in the isolated self-test")
    parser.add_argument("--report", type=Path, help="write a JSON report; default is a timestamped report under WORK")
    args = parser.parse_args()
    if args.self_test or args.self_test_hardlinks:
        self_test(args.self_test_hardlinks)
        print("self-test passed" + (" with physical hardlinks" if args.self_test_hardlinks else " (selection only)"))
        return
    result = execute(RUNS, args.apply)
    output = args.report or WORK / ("deduplicate-applied-" if args.apply else "deduplicate-dry-run-")
    if args.report is None:
        output = Path(str(output) + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + ".json")
    output = output.resolve()
    if output.parent != WORK.resolve() or output.suffix != ".json":
        raise RuntimeError("report must be a direct JSON file under the fixed client-probe work directory")
    output.write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(json.dumps({"applied": args.apply, "report": str(output), "eligible_runs": len(result["eligible_runs"]),
                      "excluded_runs": len(result["excluded_runs"]), "logical_reclaimable_bytes": result["logical_reclaimable_bytes"],
                      "hardlinks_created": result["hardlinks_created"]}))


if __name__ == "__main__":
    main()
