"""One local three-round budget per biome change, shared by capture and review."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

LIMIT = 3
HISTORY = Path(__file__).resolve().parents[2] / "build/biome-review-history"


def task_id(plan: dict) -> str:
    if "reviewTask" not in plan:
        # Older captures predate named tasks. Keep their attempts, even when
        # revisions changed the biome/window list, bundle name, or build.
        if type(plan.get("seed")) is not int:
            raise ValueError("Biome review plans need a reviewTask or legacy integer seed")
        return f"legacy-seed-{plan['seed']}"
    task = plan["reviewTask"]
    if not isinstance(task, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", task):
        raise ValueError("reviewTask must be a stable lowercase task ID (1-80 characters)")
    return task


def scope(plan: dict) -> dict | None:
    value = plan.get("reviewScope")
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) != {"schema", "id", "roundLimit", "criteria", "baseline"}:
        raise ValueError("reviewScope must contain exactly schema, id, roundLimit, criteria, baseline")
    if value["schema"] != 1 or not isinstance(value["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", value["id"]):
        raise ValueError("reviewScope needs schema 1 and a stable lowercase id")
    if type(value["roundLimit"]) is not int or not 1 <= value["roundLimit"] <= 2:
        raise ValueError("scoped biome review limit must be 1 or 2")
    criteria, ids = value["criteria"], set()
    if not isinstance(criteria, list) or not criteria:
        raise ValueError("reviewScope needs one or more criteria")
    for criterion in criteria:
        if (not isinstance(criterion, dict) or not {"id", "siteIds", "description", "acceptance"}.issubset(criterion)
                or not isinstance(criterion["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", criterion["id"])
                or criterion["id"] in ids or not isinstance(criterion["siteIds"], list) or not criterion["siteIds"]
                or any(not isinstance(site, str) or not site for site in criterion["siteIds"])
                or any(not isinstance(criterion[key], str) or not criterion[key].strip() for key in ("description", "acceptance"))):
            raise ValueError("reviewScope criterion is malformed")
        ids.add(criterion["id"])
    baseline = value["baseline"]
    if (not isinstance(baseline, dict) or set(baseline) != {"bundleSha256", "artifactSha256", "sourceFingerprint", "facts"}
            or any(not isinstance(baseline.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", baseline[key])
                   for key in ("bundleSha256", "artifactSha256", "sourceFingerprint"))
            or not isinstance(baseline["facts"], dict) or set(baseline["facts"]) != {"path", "sha256"}
            or not isinstance(baseline["facts"].get("path"), str)
            or not isinstance(baseline["facts"].get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", baseline["facts"]["sha256"])):
        raise ValueError("reviewScope baseline must bind exact bundle, artifact, sources and facts")
    return value


def scope_identity(plan: dict) -> str | None:
    value = scope(plan)
    return None if value is None else hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def limit(plan: dict) -> int:
    value = scope(plan)
    return value["roundLimit"] if value is not None else LIMIT


def _legacy_count(task: str, history: Path) -> int:
    if not task.startswith("legacy-seed-"):
        return 0
    count = 0
    for marker in history.glob("*.json"):
        old = json.loads(marker.read_text(encoding="utf-8"))
        if "reviewTask" in old:
            continue  # New attempts already have a numbered slot.
        bundle = json.loads(Path(old["bundlePath"]).read_text(encoding="utf-8"))
        plan = json.loads(Path(bundle["plan"]["path"]).read_text(encoding="utf-8"))
        count += task_id(plan) == task
    return count


def slots(plan: dict, history: Path):
    task = task_id(plan)
    directory = history / ("task-" + task)
    old = _legacy_count(task, history)
    if "reviewTask" not in plan and not old:
        raise ValueError("New biome review tasks must set a stable reviewTask in the plan")
    for number in range(1 + old, limit(plan) + 1):
        yield number, directory / f"round-{number}.json"


def exhausted(plan: dict) -> ValueError:
    return ValueError(f"Biome review limit ({limit(plan)} total rounds) reached for {task_id(plan)}. "
                      "Stop; report resolved and outstanding findings. No further automatic review.")


def check_budget(plan: dict, history: Path = HISTORY) -> None:
    scoped = scope_identity(plan)
    marker = history / task_id(plan) / "scope.json"
    if marker.exists() and (not scoped or load_scope_identity(marker) != scoped):
        raise ValueError("reviewScope changed after its first attempted round")
    if not any(not path.exists() for _, path in slots(plan, history)):
        raise exhausted(plan)


def claim_round(plan: dict, history: Path, identity: str) -> tuple[int, Path]:
    scoped = scope_identity(plan)
    directory = history / task_id(plan)
    directory.mkdir(parents=True, exist_ok=True)
    marker = directory / "scope.json"
    if scoped:
        marker_value = {"scopeIdentity": scoped, "scope": scope(plan)}
        try:
            with marker.open("x", encoding="utf-8") as scope_stream:
                json.dump(marker_value, scope_stream, ensure_ascii=False, indent=2)
        except FileExistsError:
            if load_scope_identity(marker) != scoped:
                raise ValueError("reviewScope changed after its first attempted round")
    for number, path in slots(plan, history):
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            stream = path.open("x", encoding="utf-8")
        except FileExistsError:
            continue
        with stream:
            json.dump({"round": number, "semanticEvidenceIdentity": identity, "scopeIdentity": scoped}, stream)
        return number, path
    raise exhausted(plan)


def load_scope_identity(marker: Path) -> str | None:
    try:
        value = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("scoped review history marker is malformed") from error
    identity = value.get("scopeIdentity") if isinstance(value, dict) else None
    if not isinstance(identity, str) or not re.fullmatch(r"[0-9a-f]{64}", identity):
        raise ValueError("scoped review history marker is malformed")
    return identity
