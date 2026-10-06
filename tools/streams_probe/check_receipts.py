"""Reject a failed diagnostic even when Gradle/the JVM exited successfully."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("log", type=Path)
parser.add_argument("--require", action="append", default=[])
args = parser.parse_args()
raw = args.log.read_bytes()
text = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8", errors="replace")
records = [json.loads(line.split("[TD-SR-PROBE] ", 1)[1])
           for line in text.splitlines() if "[TD-SR-PROBE] {" in line]
failures = [r for r in records if r.get("event") == "fatal" or r.get("status") in ("error", "failed")]
complete = [r for r in records if r.get("event") == "complete"]
if not complete or complete[-1].get("status") != "success":
    failures.append({"error": "missing successful completion"})
for required in args.require:
    if not any(r.get("event") == required and r.get("status") == "passed" for r in records):
        failures.append({"error": "missing passed receipt", "event": required})
if failures:
    raise SystemExit(json.dumps(failures, ensure_ascii=True))
print(json.dumps({"status": "passed", "required": args.require, "log": str(args.log)}))
