"""One local three-round budget per biome change, shared by capture and review."""
from __future__ import annotations

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
    for number in range(1 + old, LIMIT + 1):
        yield number, directory / f"round-{number}.json"


def exhausted(plan: dict) -> ValueError:
    return ValueError(f"Biome review limit ({LIMIT} total rounds) reached for {task_id(plan)}. "
                      "Stop; report resolved and outstanding findings. No further automatic review.")


def check_budget(plan: dict, history: Path = HISTORY) -> None:
    if not any(not path.exists() for _, path in slots(plan, history)):
        raise exhausted(plan)


def claim_round(plan: dict, history: Path, identity: str) -> tuple[int, Path]:
    for number, path in slots(plan, history):
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            stream = path.open("x", encoding="utf-8")
        except FileExistsError:
            continue
        with stream:
            json.dump({"round": number, "semanticEvidenceIdentity": identity}, stream)
        return number, path
    raise exhausted(plan)
