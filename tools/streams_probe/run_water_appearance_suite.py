"""Run bounded native kelp and river-bed checks in prepared, isolated fixtures."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import (Job, default_java_home, environment, exclusive_lock, gradle_command,
                         prepared_run, run_suite, streams_receipt)
from streams_probe.preflight import validate_fixture
from streams_probe.water_appearance_preflight import check_receipt_contract, check_streams_contract


def integer(value) -> int:
    if isinstance(value, bool):
        return -1
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdecimal():
        return int(value)
    return -1


def receipt(path: Path, *, smooth: bool, kelp: bool, started_ns: int = 0, ocean: bool = False) -> str:
    status = streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    settled = [row for row in rows if row.get("event") == "appearance_water_settled"]
    if len(settled) != 1 or integer(settled[0].get("gameTicks")) < 100 or integer(settled[0].get("tickableChunks")) < 25:
        return "appearance_water_settlement_missing"
    if ocean:
        generated = [row for row in rows if row.get("event") == "appearance_ocean_generated"]
        if len(generated) != 1 or integer(generated[0].get("fullChunks")) != 72:
            return "appearance_ocean_missing"
    river = [row for row in rows if row.get("event") == "river_bed_appearance_audit"]
    if len(river) != 1 or river[0].get("status") != "passed":
        return "river_bed_appearance_missing"
    row = river[0]
    for key in ("fullChunks", "waterColumns", "supportedWaterColumns"):
        if not isinstance(row.get(key), int) or row[key] <= 0:
            return "river_bed_appearance_empty_" + key
    if not isinstance(row.get("isolatedOneCellDips"), int):
        return "river_bed_appearance_missing_dip_count"
    if smooth and row["isolatedOneCellDips"] != 0:
        return "river_bed_appearance_pockets_remain"
    csv = path.parent / "td-river-bed-appearance.csv"
    if not csv.is_file() or csv.stat().st_mtime_ns < started_ns:
        return "river_bed_appearance_csv_missing_or_stale"
    if kelp:
        audits = [row for row in rows if row.get("event") == "vanilla_kelp_audit"]
        if len(audits) != 1 or audits[0].get("status") != "passed":
            return "vanilla_kelp_audit_missing"
        targets = audits[0].get("targets", {})
        for name in ("minecraft:kelp_cold", "minecraft:kelp_warm"):
            target = targets.get(name, {})
            if target.get("nativePlantBlocks", 0) <= 0:
                return "vanilla_kelp_audit_empty_" + name
            if (not isinstance(target.get("shallowAccepted"), int)
                    or not isinstance(target.get("deepAccepted"), int)
                    or target["shallowAccepted"] * 4 >= target["deepAccepted"]):
                return "vanilla_kelp_shallow_thinning_missing_" + name
            native_depth = ("oneBlockNativeStalks", "shallowNativeStalks", "deepNativeStalks")
            if (any(not isinstance(target.get(key), int) or target[key] < 0 for key in native_depth)
                    or target["shallowNativeStalks"] * 2 >= target["deepNativeStalks"]
                    or target["oneBlockNativeStalks"] > target["shallowNativeStalks"]):
                return "vanilla_kelp_native_depth_gate_missing_" + name
            if target.get("fixedCandidates") != 64 or any(target.get(key) is not True for key in
                    ("customChainRejected", "dryRejected", "excludedBiomeRejected")):
                return "vanilla_kelp_guards_missing_" + name
    return "success"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+")
    parser.add_argument("--gradle-user-home", required=True)
    parser.add_argument("--java-home", default=default_java_home())
    parser.add_argument("--baseline", action="store_true", help="record the original river pockets")
    parser.add_argument("--skip-kelp", action="store_true")
    parser.add_argument("--generate-ocean", action="store_true")
    parser.add_argument("--expected-natural-profile", type=int, choices=(12, 13), default=13,
                        help="require the selected saved river policy (default: new-world V13)")
    parser.add_argument("--reverse-order", action="store_true")
    parser.add_argument("--check-only", action="store_true", help="run all preflight checks without Gradle/Minecraft")
    args = parser.parse_args()
    env = environment(args.java_home, args.gradle_user_home)
    receipt_checked = False
    jobs = []
    for name in args.names:
        run = prepared_run(ROOT, "streams-probe", f"build/streams-probe/{name}", "input-manifest.json")
        options = ["-I", "tools/streams_probe/probe.init.gradle", f"-PtdProbeDir={run}",
                   "-PtdProbe.waterAppearanceAudit=true", "-PtdProbe.timeoutSeconds=900",
                   "-PtdProbe.expectedScale=1",
                   f"-PtdProbe.expectedNaturalProfile={args.expected_natural_profile}",
                   "-PtdProbe.riverBedExpectedScale=1",
                   f"-PtdProbe.riverBedExpectedNaturalProfile={args.expected_natural_profile}",
                   f"-PtdProbe.requireSmoothBed={str(not args.baseline).lower()}",
                   f"-PtdProbe.vanillaKelpAudit={str(not args.skip_kelp).lower()}",
                   f"-PtdProbe.generateAppearanceOcean={str(args.generate_ocean).lower()}",
                   f"-PtdProbe.appearanceReverseOrder={str(args.reverse_order).lower()}"]
        state = {}
        def prepare(run=run, state=state):
            nonlocal receipt_checked
            validate_fixture(run)
            if not receipt_checked:
                check_receipt_contract(env)
                receipt_checked = True
            check_streams_contract(run, env)
            state["started_ns"] = time.time_ns()
        jobs.append(Job(run, gradle_command(ROOT, options, False), run / "world/td-streams-probe.jsonl",
                        lambda path, state=state: receipt(path, smooth=not args.baseline,
                            kelp=not args.skip_kelp, started_ns=state["started_ns"], ocean=args.generate_ocean), prepare))
    if args.check_only:
        with exclusive_lock(ROOT):
            for job in jobs:
                job.prepare()
        print("Appearance preflight passed; Gradle/Minecraft not started.")
        return 0
    return run_suite(ROOT, "streams-probe", jobs, env)


if __name__ == "__main__":
    raise SystemExit(main())
