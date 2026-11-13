"""Stage the exact Towns and Towers beach-village terrain override.

This never changes an instance config.  It writes a reviewed copy and provenance
receipt under the workspace, so a later fixture or manual install can use it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tempfile


ROOT = Path(__file__).resolve().parents[2]
STRUCTURE_ID = "towns_and_towers:village_beach"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stage(source: Path, output: Path) -> dict[str, object]:
    """Write a copy with only ``village_beach.surfaceMode = SURFACE`` changed."""
    source = source.resolve(strict=True)
    output = output.resolve()
    workspace = ROOT.resolve()
    if not output.is_relative_to(workspace / "build" / "structure-placement"):
        raise ValueError("output must stay under build/structure-placement")
    if output.exists():
        raise ValueError(f"refusing to overwrite existing output: {output}")
    raw = source.read_bytes()
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid structure config: {source}") from error
    if not isinstance(document, dict) or not isinstance(document.get("structures"), dict):
        raise ValueError("structure config must contain a structures object")
    structures = document["structures"]
    previous = structures.get(STRUCTURE_ID, {})
    if previous is None or not isinstance(previous, dict):
        raise ValueError(f"invalid existing override for {STRUCTURE_ID}")
    if previous.get("surfaceMode") not in (None, "SURFACE"):
        raise ValueError(f"existing {STRUCTURE_ID} surfaceMode is not safe to replace")
    updated = dict(previous)
    updated["surfaceMode"] = "SURFACE"
    structures[STRUCTURE_ID] = updated
    encoded = (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    output.mkdir(parents=True, exist_ok=False)
    config = output / "terrain-diffusion-structures.json"
    receipt = {
        "schema": 1,
        "source": str(source),
        "sourceSha256": digest(raw),
        "stagedSha256": digest(encoded),
        "structure": STRUCTURE_ID,
        "changed": {"surfaceMode": {"before": previous.get("surfaceMode"), "after": "SURFACE"}},
        "unchangedTopLevel": sorted(key for key in document if key != "structures"),
        "note": "Use only for a fresh world or fresh structure starts; existing starts are pinned.",
    }
    for target, data in ((config, encoded), (output / "manifest.json", (json.dumps(receipt, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))):
        with tempfile.NamedTemporaryFile(dir=output, prefix=".stage-", delete=False) as temporary:
            temporary.write(data)
            temporary_path = Path(temporary.name)
        temporary_path.replace(target)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(stage(args.source, args.output), ensure_ascii=False))


if __name__ == "__main__":
    main()
