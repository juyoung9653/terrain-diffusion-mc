"""Small, exclusive runner for prepared Gradle/CUDA probe worlds."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
from typing import Callable, Iterator


NAME = re.compile(r"[A-Za-z0-9_-]+\Z")


def is_link(path: Path) -> bool:
    return path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction())


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path: Path, value: object) -> None:
    if is_link(path):
        raise ValueError(f"refusing symlinked status path: {path}")
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    if is_link(temporary):
        raise ValueError(f"refusing symlinked temporary status path: {temporary}")
    try:
        temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def exclusive_lock(root: Path) -> Iterator[None]:
    """Use an OS lock; the persistent file is harmless after a crash."""
    path = root / "build/probe-suite.lock"
    if is_link(path):
        raise ValueError(f"refusing symlinked lock path: {path}")
    path.parent.mkdir(exist_ok=True)
    with path.open("a+b") as stream:
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise RuntimeError(f"another probe suite holds {path}") from exc
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise RuntimeError(f"another probe suite holds {path}") from exc
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def prepared_run(root: Path, family: str, raw: str, marker: str) -> Path:
    """Accept only a direct, real child of the selected build output family."""
    base = root / "build" / family
    candidate = Path(raw)
    if ".." in candidate.parts or "." in candidate.parts:
        raise ValueError(f"run path cannot contain traversal components: {raw}")
    if not candidate.is_absolute():
        candidate = root / candidate
    # Resolve without trusting an intervening symlink, including one within build.
    lexical = Path(os.path.abspath(candidate))
    expected = Path(os.path.abspath(base))
    if lexical.parent != expected or not NAME.fullmatch(lexical.name):
        raise ValueError(f"run must be a direct child of {base}")
    for part in (root / "build", base, lexical):
        if is_link(part):
            raise ValueError(f"refusing symlinked run path: {part}")
    if not lexical.is_dir() or not (lexical / marker).is_file():
        raise ValueError(f"run is not prepared ({marker} missing): {lexical}")
    if is_link(lexical / marker) or lexical.resolve(strict=True) != lexical:
        raise ValueError(f"refusing symlinked prepared run: {lexical}")
    for name in ("world", "config", "defaultconfigs", "mods", "terrain-diffusion-models"):
        path = lexical / name
        if is_link(path):
            raise ValueError(f"refusing linked world or fixture directory: {path}")
    # A real top-level world can still contain a region/playerdata junction.
    # Inspect before os.walk descends, and include files such as linked configs.
    for directory, folders, files in os.walk(lexical, followlinks=False):
        for name in folders + files:
            path = Path(directory) / name
            if is_link(path):
                raise ValueError(f"refusing linked fixture descendant: {path}")
    return lexical


def unique_runs(runs: list[Path]) -> None:
    if len(runs) != len(set(runs)):
        raise ValueError("duplicate or overlapping output runs in suite")


def gradle_command(root: Path, options: list[str], daemon: bool) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise ValueError(f"missing Gradle wrapper: {wrapper}")
    args = [str(wrapper), ":neoforge:runServer", "--offline", "--console=plain"]
    if daemon:
        args.append("--daemon")
    args.extend(["-PuseCuda=true", "-Pneoforge_version=21.1.249", *options])
    # A batch wrapper is parsed by cmd.exe even with shell=False. Reject cmd
    # metacharacters rather than attempting fragile second-layer quoting.
    if os.name == "nt" and any(re.search(r'[&|<>^%!"\r\n]', arg) for arg in args):
        raise ValueError("Windows batch arguments contain cmd.exe metacharacters")
    return args


def environment(java_home: str | None, gradle_user_home: str | None) -> dict[str, str]:
    env = os.environ.copy()
    for key, value in (("JAVA_HOME", java_home), ("GRADLE_USER_HOME", gradle_user_home)):
        if value is not None:
            path = Path(value).expanduser().resolve()
            if not path.is_dir():
                raise ValueError(f"{key} directory does not exist: {path}")
            env[key] = str(path)
    return env


def default_java_home() -> str | None:
    """Keep run.ps1's Windows JDK default when no Java environment is configured."""
    if os.environ.get("JAVA_HOME"):
        return None
    legacy = Path("C:/Program Files/Java/jdk-21")
    return str(legacy) if os.name == "nt" and legacy.is_dir() else None


def default_gradle_user_home(root: Path) -> str | None:
    if os.environ.get("GRADLE_USER_HOME"):
        return None
    shared = root / ".gradle-user-home"
    return str(shared) if shared.is_dir() else None


def density_receipt(path: Path) -> str:
    try:
        row = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return f"invalid_receipt:{type(exc).__name__}"
    return "success" if isinstance(row, dict) and row.get("success") is True else "not_success"


def streams_receipt(path: Path) -> str:
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return f"invalid_receipt:{type(exc).__name__}"
    if not rows or any(not isinstance(row, dict) for row in rows):
        return "invalid_receipt:shape"
    terminal = [row for row in rows if row.get("event") == "complete"]
    return "success" if len(terminal) == 1 and terminal[0].get("status") == "success" else "not_success"


@dataclass(frozen=True)
class Job:
    run: Path
    command: list[str]
    receipt: Path
    check_receipt: Callable[[Path], str]


def run_suite(root: Path, family: str, jobs: list[Job], env: dict[str, str], heartbeat_seconds: int = 30) -> int:
    """Run in order, retaining failure evidence and publishing one atomic status."""
    if not jobs:
        raise ValueError("suite needs at least one run")
    unique_runs([job.run for job in jobs])
    status = root / "build" / family / "suite-status.json"
    if is_link(status):
        raise ValueError(f"refusing symlinked status path: {status}")
    completed: list[dict[str, object]] = []
    with exclusive_lock(root):
        suite_started = utc_now()
        suite_begun = time.monotonic()
        for job in jobs:
            if is_link(job.receipt) or is_link(job.receipt.parent):
                raise ValueError(f"refusing symlinked receipt: {job.receipt}")
            log = job.run / "launcher-output.txt"
            if is_link(log):
                raise ValueError(f"refusing symlinked launcher log: {log}")
            prior = job.receipt.stat().st_mtime_ns if job.receipt.exists() else None
            started_ns = time.time_ns()
            begun = time.monotonic()
            started = utc_now()
            active = {"name": job.run.name, "started": started, "elapsedSeconds": 0}
            atomic_json(status, {"started": suite_started, "active": active, "completed": completed})
            code: int | None = None
            launch_error: str | None = None
            try:
                with log.open("wb") as output:
                    # A file descriptor streams child output without buffering it in RAM.
                    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
                    with subprocess.Popen(job.command, cwd=root, env=env, stdout=output,
                                          stderr=subprocess.STDOUT, shell=False,
                                          creationflags=flags) as process:
                        while True:
                            try:
                                code = process.wait(timeout=heartbeat_seconds)
                                break
                            except subprocess.TimeoutExpired:
                                active["elapsedSeconds"] = round(time.monotonic() - begun, 1)
                                atomic_json(status, {"started": suite_started, "active": active,
                                                     "completed": completed,
                                                     "elapsedSeconds": round(time.monotonic() - suite_begun, 1)})
            except OSError as exc:
                launch_error = f"launch_error:{type(exc).__name__}: {exc}"
                with log.open("ab") as output:
                    output.write((launch_error + "\n").encode("utf-8", errors="replace"))
            if is_link(job.receipt) or is_link(job.receipt.parent):
                receipt_status = "invalid_receipt:symlink"
            elif not job.receipt.is_file():
                receipt_status = "missing_receipt"
            else:
                modified = job.receipt.stat().st_mtime_ns
                if modified < started_ns or (prior is not None and modified <= prior):
                    receipt_status = "stale_receipt"
                else:
                    receipt_status = job.check_receipt(job.receipt)
            ok = code == 0 and receipt_status == "success"
            summary: dict[str, object] = {"name": job.run.name, "processExit": code,
                                          "probeStatus": receipt_status, "success": ok,
                                          "started": started, "finished": utc_now(),
                                          "elapsedSeconds": round(time.monotonic() - begun, 1),
                                          "log": str(log)}
            if launch_error:
                summary["error"] = launch_error
            completed.append(summary)
            print(json.dumps(summary), flush=True)
            atomic_json(status, {"started": suite_started, "active": None,
                                 "completed": completed,
                                 "elapsedSeconds": round(time.monotonic() - suite_begun, 1)})
        atomic_json(status, {"started": suite_started, "finished": utc_now(), "active": None,
                             "completed": completed,
                             "success": all(row["success"] for row in completed),
                             "elapsedSeconds": round(time.monotonic() - suite_begun, 1)})
    return 0 if all(row["success"] for row in completed) else 1
