"""Describe a completed native capture without starting a client or assuming causality.

The profiler receipt is authoritative for chunk phases. The launcher only records
a process start, so this helper also reads stable client log markers to split
bootstrap time from world entry and settling. Log times are local timestamps;
use ``--log-timezone`` for fixtures made in another timezone.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
from statistics import median


KOREAN_LOG_TIME = re.compile(
    r"^\[(?P<day>\d{1,2})(?P<month>\d{1,2})월(?P<year>\d{4}) "
    r"(?P<time>\d{2}:\d{2}:\d{2}\.\d{3})\]")
ISO_LOG_TIME = re.compile(
    r"^\[(?P<year>\d{4})-(?P<month>\d{1,2})-(?P<day>\d{1,2})[ T]"
    r"(?P<time>\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?)\]")
MARKERS = {
    "worldOpen": "Chunk profile opening cloned world ",
    "settleStart": "CHUNK_PROFILE_SETTLE_START",
}


def log_timezone(value: str) -> timezone:
    """Parse an explicit fixed offset for timestamps without offsets in latest.log."""
    match = re.fullmatch(r"([+-])(\d{2}):(\d{2})", value)
    if not match:
        raise ValueError("log timezone must use ±HH:MM, for example +09:00")
    hours, minutes = int(match[2]), int(match[3])
    if hours > 23 or minutes > 59:
        raise ValueError("log timezone offset is out of range")
    seconds = (hours * 60 + minutes) * 60
    return timezone(timedelta(seconds=-seconds if match[1] == "-" else seconds))


def log_epoch(line: str, zone: timezone) -> float | None:
    """Read supported NeoForge local timestamp formats; leave unknown formats absent."""
    match = KOREAN_LOG_TIME.match(line) or ISO_LOG_TIME.match(line)
    if not match:
        return None
    try:
        stamp = datetime.fromisoformat(
            f"{match['year']}-{int(match['month']):02d}-{int(match['day']):02d}T{match['time']}"
        ).replace(tzinfo=zone)
    except ValueError:
        return None
    return stamp.timestamp()


def log_markers(path: Path, zone: timezone, *, after: float | None = None,
                before: float | None = None) -> tuple[dict[str, float], list[str]]:
    """Return first in-range marker occurrences and marker names from other runs."""
    found: dict[str, float] = {}
    ignored: set[str] = set()
    if not path.is_file():
        return found, []
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            for key, marker in MARKERS.items():
                if key not in found and marker in line:
                    stamp = log_epoch(line, zone)
                    if stamp is not None:
                        if ((after is not None and stamp < after)
                                or (before is not None and stamp > before)):
                            ignored.add(key)
                        else:
                            found[key] = stamp
    return found, sorted(ignored)


def _timestamp(value: str, label: str) -> float:
    try:
        stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as error:
        raise ValueError(f"invalid {label} timestamp") from error
    if stamp.tzinfo is None:
        raise ValueError(f"{label} timestamp needs an offset")
    return stamp.timestamp()


def _round(value: float | None) -> float | None:
    return None if value is None else round(value, 2)


def optional_prepare_timing(root: Path) -> dict | None:
    """Read clone preparation timing when this fixture was made by a new launcher."""
    path = root / "prepare-timing.json"
    if not path.is_file():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("prepare timing record is invalid") from error
    total = value.get("throughManifestSeconds") if isinstance(value, dict) else None
    groups = value.get("verifiedCopySecondsByGroup") if isinstance(value, dict) else None
    valid_number = lambda item: isinstance(item, (int, float)) and not isinstance(item, bool) and item >= 0
    if not valid_number(total) or not isinstance(groups, dict) or any(
            not isinstance(name, str) or not valid_number(seconds) for name, seconds in groups.items()):
        raise ValueError("prepare timing record is malformed")
    return {"throughManifestSeconds": _round(total),
            "verifiedCopySecondsByGroup": {name: _round(seconds) for name, seconds in sorted(groups.items())}}


def optional_native_review_timing(capture: dict) -> dict | None:
    """Expose new diagnostic timings without fabricating them for historical captures."""
    timing = capture.get("timing")
    if timing is None:
        return None
    if not isinstance(timing, dict):
        raise ValueError("native review timing record is malformed")
    valid_number = lambda item: isinstance(item, (int, float)) and not isinstance(item, bool) and item >= 0
    allowed = {"censusSeconds", "captureSeconds"}
    if set(timing) - allowed or any(not valid_number(seconds) for seconds in timing.values()):
        raise ValueError("native review timing record is malformed")
    return {name: _round(seconds) for name, seconds in sorted(timing.items())}


def breakdown(root: Path, *, zone: timezone = timezone(timedelta(hours=9))) -> dict:
    root = Path(root)
    launch_file = root / "launch-result.json"
    launch = json.loads(launch_file.read_text(encoding="utf-8"))
    if not launch.get("passed") or launch.get("exit_code") != 0:
        raise ValueError("expected an original, successfully finished capture fixture")
    process = json.loads((root / "process.json").read_text(encoding="utf-8"))
    start = process.get("start_epoch")
    if not isinstance(start, (int, float)) or isinstance(start, bool):
        raise ValueError("process record lacks numeric start_epoch")
    receipt = launch.get("receipt")
    if not isinstance(receipt, dict):
        raise ValueError("launch result lacks receipt")
    capture = receipt.get("biomeVisualReview")
    if not isinstance(capture, dict):
        raise ValueError("launch receipt lacks biome visual review")
    phases = receipt.get("phases")
    if not isinstance(phases, list) or not phases:
        raise ValueError("launch receipt lacks completed chunk phases")
    phase_starts, phase_ends = [], []
    for index, phase in enumerate(phases):
        if not isinstance(phase, dict) or not isinstance(phase.get("elapsedSeconds"), (int, float)):
            raise ValueError(f"phase {index} lacks elapsedSeconds")
        phase_start = _timestamp(phase.get("startedUtc"), f"phase {index}")
        phase_starts.append(phase_start)
        phase_ends.append(phase_start + phase["elapsedSeconds"])
    folder = (root / "screenshots").resolve()
    frames = []
    for site in capture.get("sites", []):
        for image in site.get("images", []):
            path = Path(image["path"]).resolve()
            if not path.is_relative_to(folder):
                raise ValueError("screenshot does not belong to this fixture")
            frames.append((path.stat().st_mtime, site.get("id"), path))
    if not frames or len({path for _, _, path in frames}) != len(frames):
        raise ValueError("empty capture or duplicated frame file")
    frames.sort()
    first, last, end = frames[0][0], frames[-1][0], launch_file.stat().st_mtime
    if not start <= first <= last <= end:
        raise ValueError("timestamps do not describe one original run")
    phase_start, phase_end = phase_starts[0], phase_ends[-1]
    if not start <= phase_start <= phase_end <= first:
        raise ValueError("phase timestamps do not precede captured frames")
    same, moved = [], []
    for (before, previous, _), (after, current, _) in zip(frames, frames[1:]):
        (same if previous == current else moved).append(after - before)

    markers, invalid = log_markers(root / "logs" / "latest.log", zone, after=start, before=phase_start)
    world_open, settle_start = markers.get("worldOpen"), markers.get("settleStart")
    if world_open is not None and settle_start is not None and world_open > settle_start:
        raise ValueError("world opening and settling log markers are out of order")
    startup = {
        "logTimezone": zone.tzname(None),
        "processToWorldOpenSeconds": _round(world_open - start) if world_open is not None else None,
        "worldOpenToSettleStartSeconds": _round(settle_start - world_open)
        if world_open is not None and settle_start is not None else None,
        "settleStartToFirstPhaseSeconds": _round(phase_start - settle_start)
        if settle_start is not None else None,
        "processToFirstPhaseSeconds": _round(phase_start - start),
        "missingMarkers": sorted(set(MARKERS) - set(markers)),
        "ignoredOutOfRangeMarkers": invalid,
    }
    phase_elapsed = sum(end - begin for begin, end in zip(phase_starts, phase_ends))
    phase_wall = phase_end - phase_start
    return {
        "sites": len(capture.get("sites", [])), "images": len(frames),
        "processToFirstImageSeconds": _round(first - start),
        "firstToLastImageSeconds": _round(last - first), "lastImageToResultSeconds": _round(end - last),
        "sameSiteMedianGapSeconds": _round(median(same)) if same else None,
        "newSiteMedianGapSeconds": _round(median(moved)) if moved else None,
        "startup": startup,
        "phases": {
            "count": len(phases), "sumElapsedSeconds": _round(phase_elapsed),
            "wallSeconds": _round(phase_wall),
            "interPhaseOverheadSeconds": _round(phase_wall - phase_elapsed),
            "phaseCompletionToFirstImageSeconds": _round(first - phase_end),
        },
        # Preparation is a distinct earlier lifecycle. It is deliberately not
        # added to process-to-first-image or to native review timings.
        "preparation": optional_prepare_timing(root),
        "nativeReview": optional_native_review_timing(capture),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--log-timezone", default="+09:00",
                        help="fixed offset for latest.log timestamps (default: +09:00)")
    args = parser.parse_args()
    print(json.dumps(breakdown(args.fixture, zone=log_timezone(args.log_timezone)), indent=2))
