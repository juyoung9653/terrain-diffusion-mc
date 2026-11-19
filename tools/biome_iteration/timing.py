"""Describe a completed native capture without starting a client or assuming causality."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median


def breakdown(root):
    launch_file = root / "launch-result.json"
    launch = json.loads(launch_file.read_text(encoding="utf-8"))
    if not launch["passed"] or launch["exit_code"] != 0:
        raise ValueError("expected an original, successfully finished capture fixture")
    start = json.loads((root / "process.json").read_text(encoding="utf-8"))["start_epoch"]
    capture = launch["receipt"]["biomeVisualReview"]
    folder = (root / "screenshots").resolve()
    frames = []
    for site in capture["sites"]:
        for image in site["images"]:
            path = Path(image["path"]).resolve()
            if not path.is_relative_to(folder):
                raise ValueError("screenshot does not belong to this fixture")
            frames.append((path.stat().st_mtime, site["id"], path))
    if not frames or len({path for _, _, path in frames}) != len(frames):
        raise ValueError("empty capture or duplicated frame file")
    frames.sort()
    first, last, end = frames[0][0], frames[-1][0], launch_file.stat().st_mtime
    if not start <= first <= last <= end:
        raise ValueError("timestamps do not describe one original run")
    same, moved = [], []
    for (before, previous, _), (after, current, _) in zip(frames, frames[1:]):
        (same if previous == current else moved).append(after - before)
    return {"sites": len(capture["sites"]), "images": len(frames),
            "processToFirstImageSeconds": round(first-start, 2),
            "firstToLastImageSeconds": round(last-first, 2), "lastImageToResultSeconds": round(end-last, 2),
            "sameSiteMedianGapSeconds": round(median(same), 2) if same else None,
            "newSiteMedianGapSeconds": round(median(moved), 2) if moved else None}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    print(json.dumps(breakdown(parser.parse_args().fixture), indent=2))
