"""Run prepared structure-density worlds in an exclusive, sequential suite."""

from __future__ import annotations

import argparse
from functools import partial
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT))
from probe_suite import (Job, default_gradle_user_home, default_java_home, density_receipt,
                         environment, gradle_command, is_link, prepared_run, run_suite)
from tools.structure_placement.terrain_snapshot import prepare_snapshot, seal_snapshot


def requested_receipt(path: Path, *, support: bool, well: bool, shore: bool,
                      underground: bool = False, terrain_mode: str = "full", snapshot_id: str | None = None) -> str:
    status = density_receipt(path)
    if status != "success":
        return status
    row = json.loads(path.read_text(encoding="utf-8"))
    evidence = row.get("support")
    def positive(value: object) -> bool:
        return type(value) is int and value > 0
    if support and (not isinstance(evidence, dict) or not positive(evidence.get("featureSupport"))):
        return "missing_support_evidence"
    if well and (not isinstance(evidence, dict) or not positive(evidence.get("wellFloorBlocks"))):
        return "missing_well_evidence"
    foundation = row.get("foundationSupportChecks")
    if support and (not isinstance(foundation, dict) or foundation.get("status") != "passed"
                    or not positive(foundation.get("restored"))
                    or not positive(foundation.get("rejected"))):
        return "missing_foundation_repair_evidence"
    if shore and not positive(row.get("shoreSamples")):
        return "missing_shore_evidence"
    evidence = row.get("underground")
    if underground and (not isinstance(evidence, dict)
                        or not positive(evidence.get("sampledChunks"))
                        or not positive(evidence.get("columns"))
                        or row.get("chunkCensusStatus") != "minecraft:full"):
        return "missing_underground_evidence"
    if terrain_mode != "full":
        replay = row.get("terrainReplay")
        if (row.get("terrainMode") != terrain_mode or not isinstance(replay, dict)
                or replay.get("mode") != terrain_mode or replay.get("snapshotId") != snapshot_id
                or type(replay.get("misses")) is not int or replay.get("misses") != 0
                or replay.get("failure")):
            return "invalid_terrain_replay_evidence"
        counter = "replayedTiles" if terrain_mode == "replay" else "recordedTiles"
        if not positive(replay.get(counter)):
            return "missing_terrain_replay_evidence"
    return "success"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", help="prepared build/structure-density/<name> directories")
    parser.add_argument("--java-home", default=default_java_home())
    parser.add_argument("--gradle-user-home", default=default_gradle_user_home(ROOT))
    parser.add_argument("--daemon", action="store_true", help="opt in to Gradle daemon")
    parser.add_argument("--prefetch", type=int, help="density probe batch size (1-32)")
    parser.add_argument("--shore-audit", action="store_true", help="enable shore diagnostic mixin")
    parser.add_argument("--verify-support", action="store_true", help="require physical foundation checks in this server run")
    parser.add_argument("--verify-well-support", action="store_true", help="also require a basic village with a verified buried well")
    parser.add_argument("--underground-audit", action="store_true",
                        help="record bounded post-census material/cave/bedrock evidence")
    terrain = parser.add_mutually_exclusive_group()
    terrain.add_argument("--record-terrain", type=Path, metavar="SNAPSHOT", help="record terrain inputs during a full check (new build/terrain-replay/name)")
    terrain.add_argument("--replay-terrain", type=Path, metavar="SNAPSHOT", help="structure checks against sealed frozen terrain; excludes terrain/classifier validation")
    args = parser.parse_args(argv)
    try:
        if args.prefetch is not None and not 1 <= args.prefetch <= 32:
            raise ValueError("--prefetch must be between 1 and 32")
        terrain_mode = "record" if args.record_terrain else "replay" if args.replay_terrain else "full"
        snapshot = args.record_terrain or args.replay_terrain
        if snapshot and len(args.runs) != 1:
            raise ValueError("terrain recording/replay accepts one fresh fixture per invocation")
        if args.replay_terrain and args.shore_audit:
            raise ValueError("--shore-audit requires live terrain classification; cannot use --replay-terrain")
        runs = [prepared_run(ROOT, "structure-density", value, "server.properties") for value in args.runs]
        if any(not (run / "eula.txt").is_file() or is_link(run / "eula.txt") for run in runs):
            raise ValueError("each density run needs a real eula.txt")
        env = environment(args.java_home, args.gradle_user_home)
        snapshot_id = prepare_snapshot(ROOT, runs[0], snapshot, terrain_mode) if snapshot else None
        jobs = []
        for run in runs:
            well = args.verify_well_support or (run / "verify-well-support.txt").is_file()
            support = args.verify_support or well or (run / "verify-support.txt").is_file()
            options = ["-I", "tools/structure_placement/density.init.gradle",
                       f"-PtdStructureProbeDir={run}"]
            if args.prefetch is not None:
                options.append(f"-PtdDensityPrefetch={args.prefetch}")
            if args.shore_audit:
                options.append("-PtdShoreAudit=true")
            if support:
                options.append("-PtdDensitySupport=true")
            if well:
                options.append("-PtdDensityWellSupport=true")
            if args.underground_audit:
                options.append("-PtdDensityUnderground=true")
            if snapshot:
                options.extend([f"-PtdTerrainMode={terrain_mode}",
                                f"-PtdTerrainSnapshotDir={snapshot.absolute()}",
                                f"-PtdTerrainSnapshotId={snapshot_id}"])
            jobs.append(Job(run, gradle_command(ROOT, options, args.daemon),
                            run / "world/density-audit.json",
                            partial(requested_receipt, support=support, well=well, shore=args.shore_audit,
                                    underground=args.underground_audit,
                                    terrain_mode=terrain_mode, snapshot_id=snapshot_id)))
        code = run_suite(ROOT, "structure-density", jobs, env)
        if code == 0 and args.record_terrain:
            seal_snapshot(args.record_terrain, jobs[0].receipt)
        return code
    except (OSError, ValueError, RuntimeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
