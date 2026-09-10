"""Run prepared probe servers sequentially so only one process uses the GPU."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+")
    parser.add_argument("--gradle-user-home", required=True)
    args = parser.parse_args()
    if any(not re.fullmatch(r"[a-zA-Z0-9_-]+", name) for name in args.names):
        parser.error("run names must contain only letters, digits, underscore or hyphen")
    if any(not (ROOT / "build/streams-probe" / name / "input-manifest.json").is_file() for name in args.names):
        parser.error("all runs must already be prepared")
    results = []
    status = ROOT / "build/streams-probe/suite-status.json"
    for name in args.names:
        # run.ps1 validates names and requires a prepared isolated directory.
        started = datetime.now(timezone.utc).isoformat()
        started_seconds = time.time()
        status.write_text(json.dumps({"active": name, "started": started, "completed": results}, indent=2))
        result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                 str(ROOT / "tools/streams_probe/run.ps1"), "-Name", name,
                                 "-GradleUserHome", args.gradle_user_home, "-Offline"],
                                cwd=ROOT, capture_output=True)
        run = ROOT / "build/streams-probe" / name
        (run / "launcher-output.txt").write_bytes(result.stdout + result.stderr)
        receipt = run / "world/td-streams-probe.jsonl"
        state = "not_completed"
        if receipt.is_file() and receipt.stat().st_mtime >= started_seconds:
            rows = [json.loads(line) for line in receipt.read_text(encoding="utf-8").splitlines()]
            terminal = [row for row in rows if row.get("event") == "complete"]
            if terminal:
                state = terminal[-1]["status"]
        summary = {"name": name, "processExit": result.returncode, "probeStatus": state}
        results.append(summary)
        print(json.dumps(summary), flush=True)
        status.write_text(json.dumps({"active": None, "completed": results}, indent=2))


if __name__ == "__main__":
    main()
