"""Fail-closed provenance and coverage checks for independent biome review.

This is a local workflow guard, not protection against a writer deliberately
changing the guard or forging all its records. Development builds remain usable
to produce the world that must be reviewed before release.
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from build_evidence import source_fingerprint, input_fingerprint
from biome_review import budget

VIEWS = {"aerial", "side", "detail"}


def fixed_camera_poses(plan):
    poses = {}
    for field in ("explicitSites", "regressionSites"):
        for site in plan.get(field, []):
            if "fixedCamera" not in site:
                continue
            pose = site["fixedCamera"]
            if (not isinstance(pose, dict) or set(pose) != {"x", "y", "z", "yaw", "pitch"}
                    or not all(type(v) in (float, int) and math.isfinite(v) for v in pose.values())
                    or abs(pose["pitch"]) > 90 or abs(pose["yaw"]) > 360
                    or math.floor(pose["x"]) != site["x"] or math.floor(pose["z"]) != site["z"]):
                raise ValueError("Invalid fixed camera at " + site["id"])
            poses[site["id"]] = pose
    return poses


def matches_site_views(site, poses):
    images = site.get("images", [])
    pose = poses.get(site.get("id"))
    expected = VIEWS | ({"reference"} if pose is not None else set())
    if (not isinstance(images, list) or len(images) != len(expected)
            or {i.get("view") for i in images if isinstance(i, dict)} != expected):
        return False
    if pose is None:
        return True
    actual = next(i for i in images if i["view"] == "reference").get("camera", {})
    return isinstance(actual, dict) and all(type(actual.get(k)) in (float, int)
            and math.isclose(v, actual[k], rel_tol=0., abs_tol=1e-4) for k, v in pose.items())

REASONS = {"interior", "boundary", "random"}
INSPECTION_KINDS = {"root", "bank", "underwater", "boundary"}
# Hash the producers of each artifact. Tests and offline verdict rules never
# run inside the capture client. Acceptance checks still run on every use.
CAPTURE_INPUTS = (
    Path("tools/chunk_profile/java"), Path("tools/chunk_profile/resources"),
    Path("tools/chunk_profile/run.py"), Path("tools/optimization_bench/launch.py"),
    Path("tools/river_planner/prepare-playtest.py"),
)
BUNDLE_INPUTS = (
    Path("tools/biome_review/bundle.py"), Path("tools/biome_iteration/shore_check.py"),
    Path("tools/biome_iteration/measure.py"),
)
REVIEW_INPUTS = (
    Path("tools/biome_review/runner.py"), Path("tools/biome_review/reviewer.schema.json"),
    Path("tools/biome_review/scoped-reviewer.schema.json"),
)


def digest(path: Path | str) -> str:
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def capture_tool_fingerprint(root: Path = ROOT) -> str:
    return input_fingerprint(root, CAPTURE_INPUTS)


def bundle_tool_fingerprint(root: Path = ROOT) -> str:
    return input_fingerprint(root, BUNDLE_INPUTS)


def review_tool_fingerprint(root: Path = ROOT) -> str:
    return input_fingerprint(root, REVIEW_INPUTS)


def required(root: Path) -> bool:
    # Conservative: production changes outside a short allowlist can also change
    # terrain. All releases of this ecology-enabled project require an exact
    # reviewed artifact; additions of new biome files cannot bypass a path diff.
    return (root / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/ecology").is_dir()


def _biome_resource_id(relative: str) -> str | None:
    """Return the registered ID for a tracked worldgen biome resource path."""
    parts = Path(relative.replace("\\", "/")).parts
    # Generated resources can be under either common or neoforge and under the
    # normal main or generated source set.  Deliberately do not guess Java
    # registration names: this guard only closes the JSON-resource omission.
    source_root = parts[1:5]
    if (len(parts) < 9 or parts[0] not in {"common", "neoforge"}
            or source_root not in {("src", "main", "resources", "data"),
                                   ("src", "generated", "resources", "data")}
            or parts[6:8] != ("worldgen", "biome")
            or Path(parts[-1]).suffix != ".json"):
        return None
    path = "/".join(parts[8:])
    if not path or path == ".json":
        return None
    return f"{parts[5]}:{path[:-5]}"


def changed_biome_resource_ids(root: Path = ROOT) -> set[str]:
    """Find added/edited biome JSON IDs that must be present in a review plan.

    Synthetic unit-test roots are allowed to have no Git metadata.  A real
    checkout must provide a usable Git view; otherwise release validation fails
    rather than silently reviewing an older biome list.
    """
    root = root.resolve()
    if not (root / ".git").exists():
        return set()

    def git(*arguments: str) -> str:
        try:
            result = subprocess.run(["git", "-C", str(root), *arguments],
                                    capture_output=True, text=True,
                                    encoding="utf-8", errors="replace",
                                    check=False,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError as error:
            raise ValueError(f"Cannot inspect changed biome resources with Git: {error}") from error
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
            raise ValueError(f"Cannot inspect changed biome resources with Git: {detail}")
        return result.stdout

    if git("rev-parse", "--is-inside-work-tree").strip() != "true":
        raise ValueError("Cannot inspect changed biome resources with Git: not a work tree")
    # `diff HEAD` includes both staged and unstaged edits.  Its filter omits
    # deleted resources, which have no possible current-world coverage target.
    paths = set(git("diff", "--name-only", "--diff-filter=ACMR", "HEAD").splitlines())
    paths.update(git("ls-files", "--others", "--exclude-standard").splitlines())
    return {biome for raw in paths if (biome := _biome_resource_id(raw)) is not None}


def load(path: Path | str) -> dict:
    value = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return value


def checked_file(item: dict, root: Path) -> Path:
    if not isinstance(item, dict) or not isinstance(item.get("path"), str):
        raise ValueError("Missing review evidence file")
    path = (root / item["path"]).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Review evidence must be a workspace file: {path}")
    if digest(path) != item.get("sha256"):
        raise ValueError(f"Review evidence changed: {path}")
    return path


def file_ref(path: Path | str) -> dict:
    path = Path(path).resolve()
    return {"path": str(path), "sha256": digest(path)}


def semantic_evidence_identity(bundle: dict) -> str:
    """Identity for the observed world, independent of bundle output paths.

    A new bundle directory may copy the same screenshots and receipts.  Its
    filename must not make a failed independent review eligible for a reroll.
    """
    def ref(name: str) -> str:
        value = bundle.get(name, {})
        return value.get("sha256", "") if isinstance(value, dict) else ""
    images = [{key: image.get(key) for key in ("id", "sha256", "siteId", "biome", "view", "x", "z")}
              for image in bundle.get("images", [])]
    overview = [{key: image.get(key) for key in ("id", "sha256")}
                for image in bundle.get("overviewImages", [])]
    evidence = sorted(item.get("sha256", "") for item in bundle.get("evidence", [])
                      if isinstance(item, dict))
    value = {"schema": 1, "artifactSha256": bundle.get("artifactSha256"),
             "sourceFingerprint": bundle.get("sourceFingerprint"),
             "bundleToolFingerprint": bundle.get("bundleToolFingerprint"),
             "fixture": ref("fixture"), "launch": ref("launch"), "capture": ref("capture"),
             "plan": ref("plan"), "candidateBuild": ref("candidateBuild"), "scopeFacts": ref("scopeFacts"),
             "images": sorted(images, key=lambda row: json.dumps(row, sort_keys=True)),
             "overviewImages": sorted(overview, key=lambda row: json.dumps(row, sort_keys=True)),
             "evidence": evidence}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _coordinate(value: object, label: str) -> tuple[int, int]:
    if (not isinstance(value, dict) or set(value) != {"x", "z"}
            or any(not isinstance(value[key], int) or isinstance(value[key], bool) for key in ("x", "z"))):
        raise ValueError(f"Inspection request {label} must be an exact integer x/z coordinate")
    return value["x"], value["z"]


def _inspection_windows(plan: dict) -> list[dict]:
    windows = plan.get("windows")
    if not isinstance(windows, list) or not windows:
        raise ValueError("Inspection requests require nonempty plan windows")
    required = {"minX", "maxX", "minZ", "maxZ"}
    if any(not isinstance(window, dict) or not required.issubset(window)
           or any(not isinstance(window[key], int) or isinstance(window[key], bool) for key in required)
           or window["minX"] > window["maxX"] or window["minZ"] > window["maxZ"]
           for window in windows):
        raise ValueError("Inspection request plan window is invalid")
    return windows


def _within_windows(point: tuple[int, int], windows: list[dict]) -> bool:
    x, z = point
    return any(window["minX"] <= x <= window["maxX"] and window["minZ"] <= z <= window["maxZ"]
               for window in windows)


def validate_inspection_requests(plan: dict, capture: dict, native_sites: dict[str, dict],
                                 selected_ids: set[str] | None = None) -> None:
    """Bind optional capture requests to exact, bounded, rendered native sites."""
    requested = plan.get("inspectionRequests", [])
    if requested is None:
        requested = []
    if not isinstance(requested, list):
        raise ValueError("Inspection requests must be a list")
    resolved = capture.get("inspectionRequests")
    if not requested:
        if resolved not in (None, []):
            raise ValueError("Native capture added unexpected inspection requests")
        return
    windows = _inspection_windows(plan)
    plan_by_id: dict[str, dict] = {}
    for row in requested:
        if (not isinstance(row, dict) or set(row) - {"id", "x", "z", "kind", "detailView"}
                or not isinstance(row.get("id"), str) or not row["id"]
                or row.get("kind") not in INSPECTION_KINDS
                or any(not isinstance(row[key], int) or isinstance(row[key], bool) for key in ("x", "z"))):
            raise ValueError("Inspection request plan entry is invalid")
        detail_view = row.get("detailView", "bed")
        if (detail_view not in {"bed", "waterline"}
                or (detail_view == "waterline" and row["kind"] != "underwater")):
            raise ValueError("Inspection waterline detail requires underwater request")
        if row["id"] in plan_by_id:
            raise ValueError("Duplicate inspection request ID")
        point = row["x"], row["z"]
        if not _within_windows(point, windows):
            raise ValueError("Inspection request lies outside plan windows")
        plan_by_id[row["id"]] = row
    if selected_ids is not None:
        plan_by_id = {request_id: row for request_id, row in plan_by_id.items()
                      if request_id in selected_ids}
    if not isinstance(resolved, list):
        raise ValueError("Native capture omitted inspection requests")
    resolved_by_id: dict[str, dict] = {}
    expected_keys = {"id", "kind", "requested", "resolved", "candidateCount", "reason", "endpoints"}
    for row in resolved:
        if (not isinstance(row, dict) or not expected_keys.issubset(row)
                or not isinstance(row.get("id"), str) or row["id"] in resolved_by_id
                or row.get("kind") not in INSPECTION_KINDS):
            raise ValueError("Native inspection request record is invalid")
        if (not isinstance(row.get("candidateCount"), int) or isinstance(row["candidateCount"], bool)
                or row["candidateCount"] < 0 or not isinstance(row.get("reason"), str) or not row["reason"].strip()
                or not isinstance(row.get("endpoints"), list)):
            raise ValueError("Native inspection request count or reason is invalid")
        resolved_by_id[row["id"]] = row
    if set(resolved_by_id) != set(plan_by_id):
        raise ValueError("Native capture omitted or added inspection requests")
    for request_id, planned in plan_by_id.items():
        row = resolved_by_id[request_id]
        requested_point = _coordinate(row["requested"], "requested")
        resolved_point = _coordinate(row["resolved"], "resolved")
        planned_point = planned["x"], planned["z"]
        if (row["kind"] != planned["kind"] or requested_point != planned_point
                or row.get("detailView", "bed") != planned.get("detailView", "bed")):
            raise ValueError("Native inspection request differs from the plan")
        if (not _within_windows(resolved_point, windows)
                or max(abs(resolved_point[0] - planned_point[0]), abs(resolved_point[1] - planned_point[1])) > 12):
            raise ValueError("Inspection request resolution is outside bounded radius/window")
        endpoints = [_coordinate(point, "endpoint") for point in row["endpoints"]]
        if any(not _within_windows(point, windows)
               or max(abs(point[0] - planned_point[0]), abs(point[1] - planned_point[1])) > 12
               for point in endpoints):
            raise ValueError("Inspection request endpoint is outside bounded radius/window")
        if row["candidateCount"] == 0:
            if resolved_point != planned_point or endpoints or "no-target" not in row["reason"].lower():
                raise ValueError("No-target inspection request must use requested-point fallback")
        else:
            minimum = 2 if planned["kind"] in {"bank", "boundary"} else 1
            if len(endpoints) < minimum:
                raise ValueError("Inspection request lacks required resolved endpoints")
        site = native_sites.get(request_id)
        if (not isinstance(site, dict) or (site.get("x"), site.get("z")) != resolved_point
                or {image.get("view") for image in site.get("images", []) if isinstance(image, dict)} != VIEWS):
            raise ValueError("Inspection request lacks a resolved three-view native site")


def _scope_fact_rows(value: dict, label: str) -> dict[str, dict]:
    if (value.get("schema") != 1 or value.get("kind") != "scoped-biome-baseline-facts"
            or not isinstance(value.get("seed"), int) or not isinstance(value.get("scale"), int)
            or not isinstance(value.get("artifactSha256"), str)
            or not isinstance(value.get("sourceFingerprint"), str)
            or not isinstance(value.get("sites"), list)):
        raise ValueError(f"Malformed {label} scoped facts")
    rows: dict[str, dict] = {}
    for row in value["sites"]:
        if (not isinstance(row, dict) or set(row) != {"id", "x", "z", "bounds", "metrics"}
                or not isinstance(row["id"], str) or not isinstance(row["x"], int)
                or not isinstance(row["z"], int) or not isinstance(row["bounds"], list)
                or len(row["bounds"]) != 4 or any(type(v) is not int for v in row["bounds"])
                or not isinstance(row["metrics"], dict)
                or any((type(v) not in (int, float) or isinstance(v, bool))
                       and not (isinstance(v, str) and v.strip()) for v in row["metrics"].values())
                or row["id"] in rows):
            raise ValueError(f"Malformed {label} scoped fact row")
        rows[row["id"]] = row
    return rows


def _validate_scope_facts(bundle: dict, scoped: dict, root: Path, plan: dict, capture: dict) -> None:
    baseline_ref = scoped["baseline"]["facts"]
    baseline_path = Path(baseline_ref["path"])
    if not baseline_path.is_absolute():
        baseline_path = root / baseline_path
    if not baseline_path.is_file() or digest(baseline_path) != baseline_ref["sha256"]:
        raise ValueError("Scoped baseline facts changed or are unavailable")
    baseline = load(baseline_path)
    candidate_path = checked_file(bundle.get("scopeFacts"), root)
    candidate = load(candidate_path)
    baseline_rows = _scope_fact_rows(baseline, "baseline")
    candidate_rows = _scope_fact_rows(candidate, "candidate")
    expected_ids = {site for criterion in scoped["criteria"] for site in criterion["siteIds"]}
    if not expected_ids.issubset(baseline_rows) or not expected_ids.issubset(candidate_rows):
        raise ValueError("Scoped facts omit a declared criterion site")
    if (baseline.get("artifactSha256") != scoped["baseline"]["artifactSha256"]
            or baseline.get("sourceFingerprint") != scoped["baseline"]["sourceFingerprint"]
            or candidate.get("artifactSha256") != bundle["artifactSha256"]
            or candidate.get("sourceFingerprint") != bundle["sourceFingerprint"]):
        raise ValueError("Scoped facts do not match bound baseline/candidate provenance")
    if (baseline["seed"], baseline["scale"]) != (candidate["seed"], candidate["scale"]):
        raise ValueError("Scoped candidate facts do not match baseline seed/scale")
    if (candidate["seed"], candidate["scale"]) != (plan.get("seed"), plan.get("scale")):
        raise ValueError("Scoped candidate facts do not match the review plan")
    columns_path = capture.get("columnsPath")
    if not isinstance(columns_path, str) or not Path(columns_path).is_file():
        raise ValueError("Scoped review lacks the captured columns source")
    if candidate.get("source", {}).get("columnsSha256") != digest(Path(columns_path)):
        raise ValueError("Scoped candidate facts do not match captured columns")
    for site_id in expected_ids:
        left, right = baseline_rows[site_id], candidate_rows[site_id]
        if (left["x"], left["z"], left["bounds"]) != (right["x"], right["z"], right["bounds"]):
            raise ValueError("Scoped candidate facts do not measure frozen baseline coordinates")
    for field in ("regressionSites", "explicitSites", "inspectionRequests"):
        for site in plan.get(field, []):
            if site["id"] in expected_ids:
                fact = baseline_rows[site["id"]]
                if (fact["x"], fact["z"]) != (site["x"], site["z"]):
                    raise ValueError("Scoped capture plan moved a frozen baseline site")
    bundle["scopeFacts"]["path"] = str(candidate_path)
    bundle["scopedFactSiteIds"] = sorted(expected_ids)


def validate_bundle(bundle_path: Path | str, root: Path = ROOT) -> dict:
    return _validate_bundle(bundle_path, root, historical_source=None)


def _validate_bundle(bundle_path: Path | str, root: Path, *, historical_source: str | None) -> dict:
    root = root.resolve()
    bundle = load(bundle_path)
    if bundle.get("schema") != 2 or bundle.get("kind") != "biome-review-bundle":
        raise ValueError("A bundle with separate capture provenance is required; recapture legacy evidence")
    expected_source = historical_source if historical_source is not None else source_fingerprint(root)
    if bundle.get("sourceFingerprint") != expected_source:
        raise ValueError("Biome review sources changed")
    if bundle.get("bundleToolFingerprint") != bundle_tool_fingerprint(root):
        raise ValueError("Biome evidence packaging changed; rebuild the bundle from the capture")
    manifest = load(checked_file(bundle.get("fixture"), root))
    launch = load(checked_file(bundle.get("launch"), root))
    receipt = load(checked_file(bundle.get("capture"), root))
    plan = load(checked_file(bundle.get("plan"), root))
    scoped = budget.scope(plan)
    capture = receipt.get("biomeVisualReview", {})
    if not launch.get("passed") or launch.get("exit_code") != 0 or receipt.get("status") != "success":
        raise ValueError("Biome review requires a successful native process and capture receipt")
    if launch.get("source_world_metadata_unchanged") is not True:
        raise ValueError("Source world preservation was not verified")
    if capture.get("captureToolFingerprint") != capture_tool_fingerprint(root):
        raise ValueError("Biome capture tools changed or lack separate provenance; recapture this candidate")
    if (capture.get("status") != "captured" or capture.get("coverageGaps")
            or capture.get("sourceFingerprint") != bundle["sourceFingerprint"]):
        raise ValueError("Incomplete or stale native biome capture")
    artifact = bundle.get("artifactSha256")
    candidate = load(checked_file(bundle.get("candidateBuild"), root))
    if (candidate.get("kind") != "validated-neoforge-build"
            or candidate.get("modSha256") != artifact
            or candidate.get("sourceFingerprint") != bundle["sourceFingerprint"]):
        raise ValueError("Captured candidate lacks matching source/build provenance")
    if (not artifact or manifest.get("terrain_diffusion_artifact", {}).get("sha256") != artifact
            or capture.get("artifactSha256") != artifact or plan.get("artifactSha256") != artifact
            or plan.get("sourceFingerprint") != bundle["sourceFingerprint"]):
        raise ValueError("Review artifact differs from captured world")
    required_biomes = bundle.get("requiredBiomes")
    if (not isinstance(required_biomes, list) or not required_biomes
            or set(required_biomes) != set(plan.get("requiredBiomes", []))
            or set(required_biomes) != set(capture.get("requiredBiomes", []))):
        raise ValueError("Missing required biome coverage")
    # A historical review describes its own frozen plan and artifact. The
    # current working diff is only meaningful for a review of the current JAR.
    changed_biomes = changed_biome_resource_ids(root) if historical_source is None else set()
    omitted_changed_biomes = changed_biomes - set(required_biomes)
    if omitted_changed_biomes:
        raise ValueError("Required biome coverage omits changed biome resources: "
                         + ", ".join(sorted(omitted_changed_biomes)))
    if capture.get("seed") != manifest.get("seed") or capture.get("scale") != manifest.get("scale"):
        raise ValueError("Capture world identity differs")
    images = bundle.get("images")
    if not isinstance(images, list) or not images:
        raise ValueError("Review requires rendered images")
    captured_sites = capture.get("sites", [])
    if (not isinstance(captured_sites, list) or any(not isinstance(site, dict) or not isinstance(site.get("id"), str)
                                                    for site in captured_sites)):
        raise ValueError("Native capture sites are malformed")
    native_sites = {site["id"]: site for site in captured_sites}
    if len(native_sites) != len(captured_sites):
        raise ValueError("Native capture has duplicate site IDs")
    scoped_ids = ({site_id for criterion in scoped["criteria"] for site_id in criterion["siteIds"]}
                  if scoped else None)
    # A reduced scoped capture must contain precisely the frozen review sites.
    # Older scoped bundles had broad sampling; accept them only if they retain
    # the former complete biome coverage contract below.
    reduced = scoped_ids is not None and set(native_sites) == scoped_ids
    if scoped_ids is not None:
        if not scoped_ids.issubset(native_sites):
            raise ValueError("Scoped native capture differs from frozen criterion sites")
        fixed = {row["id"]: (row["x"], row["z"]) for field in ("regressionSites", "explicitSites")
                 for row in plan.get(field, [])}
        inspections = {row["id"] for row in plan.get("inspectionRequests", [])}
        if (not scoped_ids.issubset(fixed.keys() | inspections)
                or any(row["id"] not in scoped_ids for row in plan.get("regressionSites", []))):
            raise ValueError("Scoped criteria must bind every reported site and use declared IDs")
        if any((native_sites[key]["x"], native_sites[key]["z"]) != fixed[key]
               for key in scoped_ids.intersection(fixed)):
            raise ValueError("Scoped capture moved a fixed report or control")
    native_images = {
        (site["id"], image["view"]): str(Path(image["path"]).resolve())
        for site in native_sites.values() for image in site.get("images", [])
    }
    validate_inspection_requests(plan, capture, native_sites, scoped_ids if reduced else None)
    seen = set()
    seen_native_pairs = set()
    for image in images:
        if not image.get("id") or image["id"] in seen:
            raise ValueError("Duplicate or absent review image ID")
        seen.add(image["id"])
        path = checked_file(image, root)
        if path.stat().st_size < 1024 or image.get("view") not in VIEWS | {"reference"}:
            raise ValueError("Missing usable rendered view")
        native_pair = (image.get("siteId"), image["view"])
        if native_images.get(native_pair) != str(path):
            raise ValueError("Review image is not from the native capture")
        if native_pair in seen_native_pairs:
            raise ValueError("Review duplicated a native captured image view")
        seen_native_pairs.add(native_pair)
        site = native_sites[image["siteId"]]
        if image.get("biome") != site.get("biome"):
            raise ValueError("Image biome differs from measured site")
        image["path"] = str(path)
    if seen_native_pairs != set(native_images):
        raise ValueError("Review omitted native captured images")
    poses = fixed_camera_poses(plan)
    for site in native_sites.values():
        if site.get("reachedServerTicks", 0) < 100 or site.get("tickEligible") is not True:
            raise ValueError("Review site did not settle under ticking tickets")
        if not matches_site_views(site, poses):
            raise ValueError("Review site lacks required viewpoints")
    if not reduced:
        for biome in required_biomes:
            reasons = {reason for site in native_sites.values() if site.get("biome") == biome
                       for reason in site.get("reasons", [])}
            if not REASONS.issubset(reasons):
                raise ValueError(f"Biome lacks interior/boundary/random coverage: {biome}")
    # Every reported regression position must survive selection, not just a
    # favourable replacement bearing the same label.
    coordinates = {(s.get("x"), s.get("z")) for s in native_sites.values()}
    if any((s["x"], s["z"]) not in coordinates for s in plan.get("regressionSites", [])):
        raise ValueError("A required regression site was omitted")
    if not bundle.get("evidence"):
        raise ValueError("Review lacks physical census evidence")
    for item in bundle["evidence"]:
        item["path"] = str(checked_file(item, root))
    if scoped:
        _validate_scope_facts(bundle, scoped, root, plan, capture)
    for overview in bundle.get("overviewImages", []):
        if not overview.get("id") or overview["id"] in seen:
            raise ValueError("Duplicate overview image ID")
        seen.add(overview["id"])
        overview["path"] = str(checked_file(overview, root))
    bundle["semanticEvidenceIdentity"] = semantic_evidence_identity(bundle)
    return bundle


def validate_review(path: Path | str, root: Path = ROOT, artifact: str | None = None) -> dict:
    return _validate_review(path, root, artifact, historical=False)


def validate_reviewed_baseline(path: Path | str, root: Path, artifact: str) -> dict:
    """Verify a prior direct review for this exact baseline JAR, not the candidate."""
    if not isinstance(artifact, str) or len(artifact) != 64:
        raise ValueError("Historical review requires the baseline artifact digest")
    return _validate_review(path, root, artifact, historical=True)


def _validate_review(path: Path | str, root: Path, artifact: str | None, *, historical: bool) -> dict:
    path = Path(path).resolve()
    row = load(path)
    if (row.get("schema") != 1 or row.get("kind") != "independent-biome-review"
            or row.get("verdict") != "pass" or row.get("processExit") != 0):
        raise ValueError("A passing independent biome review is required")
    if row.get("toolFingerprint") != review_tool_fingerprint(root):
        raise ValueError("Independent review tools changed")
    bundle_path = checked_file({"path": row.get("bundlePath"), "sha256": row.get("bundleSha256")}, root)
    historical_source = row.get("sourceFingerprint") if historical else None
    if historical and (not isinstance(historical_source, str) or len(historical_source) != 64):
        raise ValueError("Historical review lacks its source fingerprint")
    bundle = _validate_bundle(bundle_path, root, historical_source=historical_source)
    if (row.get("sourceFingerprint") != bundle["sourceFingerprint"]
            or row.get("artifactSha256") != bundle["artifactSha256"]
            or artifact is not None and artifact != row["artifactSha256"]):
        raise ValueError("Independent review does not match the release artifact")
    if row.get("coverageGaps") or row.get("additionalCaptures"):
        raise ValueError("Independent review has unresolved evidence gaps")
    scoped = budget.scope(load(Path(bundle["plan"]["path"])))
    blocking = row.get("blockingFindings", row.get("findings", [])) if scoped else row.get("findings", [])
    if any(f.get("severity") in ("blocker", "major") for f in blocking):
        raise ValueError("Independent review has unresolved realism defects")
    expected = {i["id"] for i in bundle["images"] + bundle.get("overviewImages", [])}
    if set(row.get("inspectedImageIds", [])) != expected:
        raise ValueError("Independent reviewer did not inspect all captured images")
    if not row.get("evidence"):
        raise ValueError("Independent reviewer process evidence is absent")
    for item in row["evidence"]:
        checked_file(item, root)
    history_path = checked_file({"path": row.get("historyPath"), "sha256": row.get("historySha256")}, root)
    history = load(history_path)
    if (history.get("kind") != "independent-biome-review-history"
            or history.get("bundleSha256") != row["bundleSha256"]
            or history.get("semanticEvidenceIdentity") != bundle["semanticEvidenceIdentity"]
            or history.get("sourceFingerprint") != row["sourceFingerprint"]
            or history.get("artifactSha256") != row["artifactSha256"]
            or history.get("toolFingerprint") != row["toolFingerprint"]
            or Path(history.get("outputPath", "")).resolve() != path.parent):
        raise ValueError("Independent reviewer attempt history differs")
    # Re-read actual individual model responses; an aggregate label alone is
    # not approval. A missing/failed batch can never disappear in summarization.
    from biome_review.runner import read_report, scoped_fact_pairs
    fact_pairs = scoped_fact_pairs(bundle, scoped)
    batches = row.get("batches", [])
    if not batches:
        raise ValueError("Independent reviewer batch results absent")
    inspected = set()
    evidence_paths = {str(checked_file(item, root)) for item in row["evidence"]}
    for batch in batches:
        target = path.parent / f"batch-{batch['index']:02d}"
        report_path = target / "response.json"
        command_path, started_path = target / "command.json", target / "started.json"
        raw_paths = (report_path, command_path, started_path, target / "stdout.txt", target / "stderr.txt", target / "result.json")
        if (any(str(raw.resolve()) not in evidence_paths for raw in raw_paths)
                or batch.get("processExit") != 0):
            raise ValueError("Independent reviewer raw response is missing or failed")
        ids = set(batch.get("imageIds", [])) | set(batch.get("overviewImageIds", []))
        command, started, result = load(command_path), load(started_path), load(target / "result.json")
        if (started.get("kind") != "independent-biome-review-attempt"
                or started.get("index") != batch.get("index")
                or started.get("bundleSha256") != row["bundleSha256"]
                or started.get("semanticEvidenceIdentity") != bundle["semanticEvidenceIdentity"]
                or started.get("toolFingerprint") != row["toolFingerprint"]
                or set(started.get("imageIds", [])) != set(batch.get("imageIds", []))
                or set(started.get("overviewImageIds", [])) != set(batch.get("overviewImageIds", []))):
            raise ValueError("Independent reviewer start record differs")
        args = command.get("command") if isinstance(command, dict) else None
        required_args = {"exec", "--ephemeral", "--sandbox", "read-only", "--output-schema", "--output-last-message"}
        image_paths = [str(i["path"]) for i in bundle["images"] + bundle.get("overviewImages", [])
                       if i["id"] in ids]
        if (not isinstance(args, list) or not required_args.issubset(set(args))
                or args.count("--image") != len(image_paths)
                or any(path_value not in args for path_value in image_paths)
                or command.get("imageIds") != batch.get("imageIds", [])
                or command.get("overviewImageIds", []) != batch.get("overviewImageIds", [])
                or result.get("index") != batch.get("index") or result.get("processExit") != 0):
            raise ValueError("Independent reviewer command/result record differs")
        report, error = read_report(report_path, ids, scoped, fact_pairs,
                                    {item["siteId"] for item in bundle["images"] if item["id"] in ids})
        if error or report["verdict"] != "pass":
            raise ValueError("Independent reviewer raw response did not pass")
        inspected.update(ids)
    if inspected != expected:
        raise ValueError("Independent reviewer batch coverage differs")
    return {"path": str(path), "sha256": digest(path), "artifactSha256": row["artifactSha256"],
            "sourceFingerprint": row["sourceFingerprint"], "verdict": "pass"}
