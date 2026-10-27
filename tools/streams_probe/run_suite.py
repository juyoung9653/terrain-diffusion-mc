"""Run prepared Streams probe worlds in an exclusive, sequential suite."""

from __future__ import annotations

import argparse
import json
from functools import partial
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import Job, default_java_home, environment, gradle_command, prepared_run, run_suite, streams_receipt


def topology_receipt(path: Path) -> str:
    status = streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    audits = [row for row in rows if row.get("event") == "streams_topology_audit"]
    return "success" if len(audits) == 1 and audits[0].get("status") == "passed" else "topology_not_passed"


def metrics_receipt(path: Path, topology: bool = False, terrain_parity: bool = False, cpu_profile: bool = False) -> str:
    status = topology_receipt(path) if topology else streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    if cpu_profile:
        profiles = [row for row in rows if row.get("event") == "terrain_cpu_profile"]
        if len(profiles) != 1 or profiles[0].get("status") != "passed":
            return "cpu_profile_not_passed"
    if terrain_parity:
        audits = [row for row in rows if row.get("event") == "terrain_performance_audit"]
        if len(audits) != 1 or audits[0].get("status") != "passed":
            return "terrain_parity_not_passed"
    metrics = [row for row in rows if row.get("event") == "inference_metrics"]
    return "success" if len(metrics) == 1 else "inference_metrics_missing_or_duplicate"


def slab_receipt(path: Path) -> str:
    status = streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    audits = [row for row in rows if row.get("event") == "terrain_slab_audit"]
    return "success" if len(audits) == 1 and audits[0].get("status") == "passed" else "slab_audit_not_passed"



def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+", help="prepared names under build/streams-probe")
    parser.add_argument("--gradle-user-home", required=True)
    parser.add_argument("--java-home", default=default_java_home())
    parser.add_argument("--daemon", action="store_true", help="opt in to Gradle daemon")
    parser.add_argument("--max-chunks", type=int, choices=range(1, 17), default=4)
    parser.add_argument("--chunks", help="fixed chunk order: X,Z;X,Z (use --chunks=... for negatives)")
    parser.add_argument("--topology-audit", action="store_true")
    parser.add_argument("--terrain-slab-audit", action="store_true",
                        help="native slab contour and loaded Landmarks registry checks on controlled terrain")
    parser.add_argument("--inference-metrics", action="store_true", help="record ONNX and provider timing in this isolated run")
    parser.add_argument("--terrain-parity", action="store_true",
                        help="audit native ground/full tile parity at scales 1 through 6; changes timed workload")
    parser.add_argument("--cpu-profile", action="store_true",
                        help="sample server thread CPU/stacks; profiling overhead invalidates speed comparisons")
    parser.add_argument("--fast-ground", choices=("true", "false"),
                        help="diagnostic override of the saved ground-only sampling profile")
    args = parser.parse_args(argv)
    try:
        if args.terrain_slab_audit and (args.topology_audit or args.inference_metrics or args.terrain_parity or args.cpu_profile or args.chunks):
            raise ValueError("terrain-slab-audit is a dedicated controlled-terrain audit")
        if args.chunks and not re.fullmatch(r"-?\d+,-?\d+(;-?\d+,-?\d+)*", args.chunks):
            raise ValueError("chunks must be semicolon-separated X,Z integer pairs")
        if args.chunks and len(args.chunks.split(";")) > args.max_chunks:
            raise ValueError("max-chunks must include all requested chunks")
        options = [f"-PtdProbe.maxChunks={args.max_chunks}", "-PtdProbe.routeSearchRadius=32",
                   "-PtdProbe.timeoutSeconds=900"]
        if args.chunks:
            options.append(f"-PtdProbe.chunks={args.chunks}")
        if args.topology_audit:
            options.append("-PtdProbe.streamsTopologyAudit=true")
        if args.terrain_slab_audit:
            options.append("-PtdProbe.terrainSlabAudit=true")
        if args.inference_metrics or args.terrain_parity or args.cpu_profile:
            options.append("-PtdInference.metrics=true")
        if args.cpu_profile:
            options.append("-PtdProbe.cpuProfile=true")
        if args.terrain_parity:
            options.extend(("-PtdProbe.performanceAudit=true", "-PtdProbe.performanceParity=true",
                            "-PtdProbe.performanceScales=1,2,3,4,5,6"))
        if args.fast_ground is not None:
            options.append(f"-PtdInference.fastGround={args.fast_ground}")
        runs = []
        for name in args.names:
            if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                raise ValueError(f"invalid run name: {name}")
            runs.append(prepared_run(ROOT, "streams-probe", f"build/streams-probe/{name}", "input-manifest.json"))
        check_receipt = (partial(metrics_receipt, topology=args.topology_audit, terrain_parity=args.terrain_parity, cpu_profile=args.cpu_profile)
                         if args.inference_metrics or args.terrain_parity or args.cpu_profile
                         else topology_receipt if args.topology_audit else streams_receipt)
        if args.terrain_slab_audit:
            check_receipt = slab_receipt
        jobs = [Job(run, gradle_command(ROOT, ["-I", "tools/streams_probe/probe.init.gradle",
                                               f"-PtdProbeDir={run}", *options],
                                        args.daemon), run / "world/td-streams-probe.jsonl",
                    check_receipt)
                for run in runs]
        return run_suite(ROOT, "streams-probe", jobs, environment(args.java_home, args.gradle_user_home))
    except (ValueError, RuntimeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
