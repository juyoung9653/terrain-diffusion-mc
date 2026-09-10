"""Print compact evidence from a probe run; never infer compatibility from process exit alone."""
import argparse
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--since", type=float, help="Reject receipts from an earlier invocation (Unix seconds)")
    parser.add_argument("--compact", action="store_true")
    args = parser.parse_args()
    log = args.run / "logs/latest.log"
    if not log.is_file() or args.since is not None and log.stat().st_mtime < args.since:
        print(json.dumps({"status": "not_started", "run": str(args.run)}))
        return
    receipts, errors = [], []
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if "[TD-SR-PROBE] " in line:
            payload = line.split("[TD-SR-PROBE] ", 1)[1]
            try:
                receipts.append(json.loads(payload))
            except json.JSONDecodeError:
                errors.append(payload[:300])
        elif re.search(r"(\bERROR\b|Exception:|Caused by:|Streams Reflowing:)", line):
            errors.append(line[:500])
    if args.compact:
        complete = [r for r in receipts if r.get("event") == "complete"]
        chunks = [{key: r.get(key) for key in ("chunk", "waterBlocks", "waterReplacingSolid", "naturalBiomeColumns")}
                  for r in receipts if r.get("event") == "chunk"]
        print(json.dumps({"complete": complete[-1] if complete else None,
                          "chunks": chunks, "diagnostics": errors[-4:]}, indent=2))
    else:
        print(json.dumps({"receipts": receipts, "diagnostics": errors[-15:]}, indent=2))


if __name__ == "__main__":
    main()
