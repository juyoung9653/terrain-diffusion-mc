"""Run the audited, independent structure-placement checks concurrently.

Run from any directory: ``python tools/structure_placement/run_fast_tests.py``.
Use ``--only placement village`` for a fixed subset, or ``--jobs 1`` for a
sequential baseline. Each child keeps its existing output directory; the lock
prevents two instances of this launcher from writing those directories at once.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REPORT_ROOT = ROOT / "build" / "structure-placement" / "fast-tests"
MAX_JOBS = 8


@dataclass(frozen=True)
class Check:
    id: str
    script: str
    description: str
    prerequisite: str = ""


# Audited runners only. Their fixed build outputs do not overlap one another.
# test_basic_villages runs as a module so its absolute `tools` imports resolve.
CHECKS = (
    Check("placement", "run_tests.py", "Placement geometry"),
    Check("village", "run_village_tests.py", "Village distribution geometry"),
    Check("height-cache", "run_height_cache_tests.py", "Bounded native structure-height sharing"),
    Check("foundation-support", "run_foundation_support_tests.py", "Bounded natural floor support repair"),
    Check("component", "run_component_tests.py", "Component geometry and original NBT"),
    Check("structure-site", "run_site_compatibility_tests.py", "Deep-water monument site admission"),
    Check("basic-villages", "test_basic_villages.py", "Checked-in village assets"),
    Check("config", "test_config.py", "Config behavior with cached Gson", "gson"),
    Check("moss", "run_moss_tests.py", "Moss geometry with cached Gson and Minecraft client", "moss"),
    Check("preflight", "run_preflight_tests.py", "Preflight control with supplied Streams JAR", "streams"),
)


def _cache_dirs() -> tuple[Path, ...]:
    return ROOT / ".gradle", ROOT / ".gradle-user-home", Path.home() / ".gradle"


def unavailable(check: Check, streams_jar: Path | None) -> str | None:
    """Return a diagnostic for a missing external prerequisite, if any."""
    if check.prerequisite == "streams":
        if streams_jar is None:
            return "supply --streams-jar PATH"
        if not streams_jar.is_file():
            return f"Streams JAR does not exist: {streams_jar}"
    if check.prerequisite in ("gson", "moss"):
        if not any(any(cache.glob("caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar"))
                   for cache in _cache_dirs()):
            return "cached Gson JAR is missing (.gradle, .gradle-user-home, or ~/.gradle)"
    if check.prerequisite == "moss":
        if not any((cache / "caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar").is_file()
                   for cache in _cache_dirs()):
            return "cached minecraft_1.21.1_client.jar is missing"
    return None


def command(check: Check, streams_jar: Path | None) -> list[str]:
    if check.id == "basic-villages":
        return [sys.executable, "-m", "tools.structure_placement.test_basic_villages"]
    args = [sys.executable, str(HERE / check.script)]
    if check.id == "preflight":
        assert streams_jar is not None
        args.append(str(streams_jar))
    return args


class LauncherLock:
    """Advisory process-held lock for the runners' shared fixed output dirs."""

    def __init__(self, path: Path):
        self.path = path
        self.handle = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                self.handle.seek(0)
                if not self.handle.read(1):
                    self.handle.write(b"0")
                    self.handle.flush()
                self.handle.seek(0)
                msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            self.handle.close()
            self.handle = None
            raise RuntimeError("another fast-test launcher is using the fixed test output directories") from exc
        return self

    def __exit__(self, *_):
        assert self.handle is not None
        if os.name == "nt":
            import msvcrt
            self.handle.seek(0)
            msvcrt.locking(self.handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.handle, fcntl.LOCK_UN)
        self.handle.close()


def run_checks(checks: list[Check], jobs: int, report_dir: Path,
               streams_jar: Path | None) -> tuple[list[dict], int]:
    """Run all selected checks, preserving selection order in the results."""
    active = 0
    maximum = 0
    guard = threading.Lock()

    def run_one(check: Check) -> dict:
        nonlocal active, maximum
        argv = command(check, streams_jar)
        log = report_dir / f"{check.id}.log"
        started = time.monotonic()
        with guard:
            active += 1
            maximum = max(maximum, active)
        try:
            with log.open("wb") as output:
                try:
                    process = subprocess.run(
                        argv, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
                        check=False,
                    )
                    code = process.returncode
                except OSError as exc:
                    output.write(f"Unable to start {argv[0]}: {exc}\n".encode("utf-8", errors="replace"))
                    code = 127
        finally:
            with guard:
                active -= 1
        return {"id": check.id, "status": "passed" if code == 0 else "failed",
                "exit_code": code, "elapsed_seconds": round(time.monotonic() - started, 3),
                "command": argv, "log": str(log)}

    by_id = {}
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(run_one, check): check for check in checks}
        for future in as_completed(futures):
            check = futures[future]
            try:
                by_id[check.id] = future.result()
            except Exception as exc:
                log = report_dir / f"{check.id}.log"
                log.write_text(f"Launcher error: {exc!r}\n", encoding="utf-8")
                by_id[check.id] = {"id": check.id, "status": "failed", "exit_code": 127,
                                   "elapsed_seconds": None, "command": command(check, streams_jar),
                                   "log": str(log)}
    return [by_id[check.id] for check in checks], maximum


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=min(4, os.cpu_count() or 1),
                        help="maximum simultaneous checks (default: min(4, CPU count), maximum: 8)")
    parser.add_argument("--only", nargs="+", choices=[check.id for check in CHECKS],
                        metavar="ID", help="run exactly these check IDs; missing prerequisites are errors")
    parser.add_argument("--streams-jar", type=Path, help="inspected Streams Reflowing JAR for preflight")
    parser.add_argument("--list", action="store_true", help="list check IDs and availability, then exit")
    args = parser.parse_args(argv)
    if not 1 <= args.jobs <= MAX_JOBS:
        parser.error(f"--jobs must be between 1 and {MAX_JOBS}")
    if args.only and len(args.only) != len(set(args.only)):
        parser.error("--only contains duplicate check IDs")
    streams_jar = args.streams_jar.resolve() if args.streams_jar else None
    if args.list:
        for check in CHECKS:
            reason = unavailable(check, streams_jar)
            print(f"{check.id:15} {check.description} ({'unavailable: ' + reason if reason else 'available'})")
        return 0

    selected = [check for check in CHECKS if args.only is None or check.id in args.only]
    missing = [(check, unavailable(check, streams_jar)) for check in selected]
    if args.only:
        for check, reason in missing:
            if reason:
                parser.error(f"{check.id}: {reason}")
    runnable = [check for check, reason in missing if not reason]
    skipped = [{"id": check.id, "status": "skipped", "reason": reason}
               for check, reason in missing if reason]

    try:
        with LauncherLock(REPORT_ROOT / ".launcher.lock"):
            report_dir = REPORT_ROOT / f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}-{uuid.uuid4().hex[:8]}"
            report_dir.mkdir(parents=True)
            start = time.monotonic()
            results, maximum = run_checks(runnable, args.jobs, report_dir, streams_jar)
            ordered = {row["id"]: row for row in (*results, *skipped)}
            report = {"jobs": args.jobs, "max_concurrency": maximum,
                      "wall_seconds": round(time.monotonic() - start, 3),
                      "results": [ordered[check.id] for check in selected]}
            report_file = report_dir / "report.json"
            report_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except RuntimeError as exc:
        print(f"fast-tests: {exc}", file=sys.stderr)
        return 2

    for row in report["results"]:
        detail = row.get("reason") or f"exit={row['exit_code']} elapsed={row['elapsed_seconds']}s"
        print(f"{row['id']}: {row['status']} ({detail})")
    print(f"wall={report['wall_seconds']}s max_concurrency={maximum} report={report_file}")
    return 1 if any(row["status"] == "failed" for row in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
