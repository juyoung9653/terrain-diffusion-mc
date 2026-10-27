"""Run the existing native marine and vegetation audits in one prepared fresh world."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import Job, environment, gradle_command, prepared_run, run_suite, streams_receipt


def receipt(path, kelp=False):
    status = streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    for event in ("hybrid_aquatic_audit", "hybrid_marine_audit", "lush_density_audit"):
        matches = [r for r in rows if r.get("event") == event]
        if len(matches) != 1 or matches[0].get("status") != "passed":
            return event + "_not_passed"
    if kelp:
        matches = [r for r in rows if r.get("event") == "hybrid_aquatic_kelp_audit"]
        if len(matches) != 1 or matches[0].get("status") != "passed":
            return "hybrid_aquatic_kelp_audit_not_passed"
    return "success"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name")
    parser.add_argument("--kelp", action="store_true")
    args = parser.parse_args()
    run = prepared_run(ROOT, "streams-probe", f"build/streams-probe/{args.name}", "input-manifest.json")
    command = gradle_command(ROOT, ["-I", "tools/streams_probe/probe.init.gradle",
        f"-PtdProbeDir={run}", "-PtdProbe.maxChunks=3", "-PtdProbe.timeoutSeconds=900",
        "-PtdProbe.hybridAquaticAudit=true", "-PtdProbe.hybridMarineAudit=true",
        "-PtdProbe.lushDensityAudit=true", *( ["-PtdProbe.hybridAquaticKelpAudit=true"] if args.kelp else [])], False)
    return run_suite(ROOT, "streams-probe", [Job(run, command, run / "world/td-streams-probe.jsonl", lambda path: receipt(path, args.kelp))],
                     environment("C:/Program Files/Java/jdk-21", str(ROOT / ".gradle")))


if __name__ == "__main__":
    raise SystemExit(main())
