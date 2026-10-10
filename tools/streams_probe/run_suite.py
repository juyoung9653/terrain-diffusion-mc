"""Run prepared Streams probe worlds in an exclusive, sequential suite."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import Job, default_java_home, environment, gradle_command, prepared_run, run_suite, streams_receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+", help="prepared names under build/streams-probe")
    parser.add_argument("--gradle-user-home", required=True)
    parser.add_argument("--java-home", default=default_java_home())
    parser.add_argument("--daemon", action="store_true", help="opt in to Gradle daemon")
    args = parser.parse_args(argv)
    try:
        runs = []
        for name in args.names:
            if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                raise ValueError(f"invalid run name: {name}")
            runs.append(prepared_run(ROOT, "streams-probe", f"build/streams-probe/{name}", "input-manifest.json"))
        jobs = [Job(run, gradle_command(ROOT, ["-I", "tools/streams_probe/probe.init.gradle",
                                               f"-PtdProbeDir={run}", "-PtdProbe.maxChunks=4",
                                               "-PtdProbe.routeSearchRadius=32", "-PtdProbe.timeoutSeconds=900"],
                                        args.daemon), run / "world/td-streams-probe.jsonl", streams_receipt)
                for run in runs]
        return run_suite(ROOT, "streams-probe", jobs, environment(args.java_home, args.gradle_user_home))
    except (ValueError, RuntimeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
