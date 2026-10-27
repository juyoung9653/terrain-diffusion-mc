"""Stream a Java Flight Recorder allocation/CPU summary without loading its XML.

Use after a bounded, user-authorized client recording. ObjectAllocationSample is
an allocation *sample*: ``weight`` is an estimate of represented bytes, not an
exact allocation counter. ExecutionSample is CPU sampling, not elapsed CPU time.
ThreadAllocationStatistics values are reported cumulative snapshots and are kept
as their final observed value per thread; they are never summed across samples.
"""
from __future__ import annotations

import argparse
import collections
from datetime import datetime
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def direct_value(element: ET.Element, name: str) -> str | None:
    for child in element:
        if child.tag == "value" and child.get("name") == name:
            return child.text
    return None


def nested_value(element: ET.Element, name: str) -> str | None:
    for child in element.iter("value"):
        if child.get("name") == name and child.text:
            return child.text
    return None


def named_struct(element: ET.Element, name: str) -> ET.Element | None:
    return next((child for child in element if child.tag == "struct" and child.get("name") == name), None)


def event_thread_key(event: ET.Element) -> tuple[str, str]:
    thread = named_struct(event, "eventThread")
    if thread is None:
        return "<unknown thread>", "?"
    return (direct_value(thread, "javaName") or "<unknown thread>",
            direct_value(thread, "javaThreadId") or "?")


def stack_key(event: ET.Element) -> str:
    trace = named_struct(event, "stackTrace")
    if trace is None:
        return "<no Java stack>"
    frame_array = next((child for child in trace if child.tag == "array" and child.get("name") == "frames"), None)
    if frame_array is None:
        return "<no Java stack>"
    frames = []
    for frame in frame_array:
        method_struct = named_struct(frame, "method")
        type_struct = named_struct(method_struct, "type") if method_struct is not None else None
        type_name = direct_value(type_struct, "name") if type_struct is not None else "?"
        method = direct_value(method_struct, "name") if method_struct is not None else "?"
        frames.append(f"{type_name}.{method}")
        if len(frames) == 4:
            break
    return " <- ".join(frames) if frames else "<no Java stack>"


def number(value: str | None) -> int:
    try:
        return int(value or "0")
    except ValueError:
        return 0


def duration_ns(value: str | None) -> int:
    """Parse the ISO-8601 seconds form emitted by `jfr print --xml`."""
    match = re.fullmatch(r"PT([0-9]+(?:\.[0-9]+)?)S", value or "")
    return int(float(match.group(1)) * 1_000_000_000) if match else number(value)


def top(counter: collections.Counter, count: int) -> list[dict[str, int | str]]:
    return [{"key": key, "bytes": value} for key, value in counter.most_common(count)]


def profile(jfr: Path, java: Path, limit: int) -> dict:
    command = [str(java), "print", "--xml", "--stack-depth", "8",
               "--events", "jdk.ObjectAllocationSample,jdk.ExecutionSample,jdk.ThreadAllocationStatistics,jdk.GCPhasePause", str(jfr)]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace")
    assert proc.stdout is not None
    allocation_class: collections.Counter = collections.Counter()
    allocation_thread: collections.Counter = collections.Counter()
    allocation_stack: collections.Counter = collections.Counter()
    cpu_stack: collections.Counter = collections.Counter()
    event_counts: collections.Counter = collections.Counter()
    # First/last reported cumulative totals per JFR thread id. Never sum snapshots.
    thread_allocated: dict[str, list[tuple[datetime, int, str]]] = {}
    gc_pause_ns = 0
    try:
        for _, event in ET.iterparse(proc.stdout, events=("end",)):
            if event.tag != "event":
                continue
            kind = event.get("type", "unknown")
            event_counts[kind] += 1
            if kind == "jdk.ObjectAllocationSample":
                represented = number(direct_value(event, "weight")) or number(direct_value(event, "allocationSize"))
                object_class_struct = named_struct(event, "objectClass")
                object_class = direct_value(object_class_struct, "name") if object_class_struct is not None else None
                object_class = object_class or "<unknown class>"
                thread_name, thread_id = event_thread_key(event)
                thread = f"{thread_name}#{thread_id}"
                allocation_class[object_class] += represented
                allocation_thread[thread] += represented
                allocation_stack[stack_key(event)] += represented
            elif kind == "jdk.ExecutionSample":
                cpu_stack[stack_key(event)] += 1
            elif kind == "jdk.ThreadAllocationStatistics":
                thread_name, thread_id = event_thread_key(event)
                thread = f"{thread_name}#{thread_id}"
                allocated = number(direct_value(event, "allocated"))
                try:
                    timestamp = datetime.fromisoformat(direct_value(event, "startTime") or "")
                except ValueError:
                    timestamp = datetime.min
                thread_allocated.setdefault(thread, []).append((timestamp, allocated, thread_name))
            elif kind == "jdk.GCPhasePause":
                gc_pause_ns += duration_ns(direct_value(event, "duration"))
            event.clear()
    finally:
        stderr = proc.stderr.read() if proc.stderr else ""
        exit_code = proc.wait()
    if exit_code:
        raise RuntimeError(f"jfr print failed ({exit_code}): {stderr.strip()}")
    thread_deltas = []
    for thread, values in thread_allocated.items():
        values.sort()
        first, last = values[0], values[-1]
        seconds = max(0.0, (last[0] - first[0]).total_seconds())
        delta = max(0, last[1] - first[1])
        thread_deltas.append({"key": thread, "allocated_bytes_delta": delta,
                              "interval_seconds": seconds,
                              "estimated_bytes_per_second": int(delta / seconds) if seconds else None,
                              "snapshot_count": len(values)})
    thread_deltas.sort(key=lambda row: row["allocated_bytes_delta"], reverse=True)
    return {
        "recording": str(jfr),
        "method": {
            "allocations": "ObjectAllocationSample weighted estimates; not exact bytes",
            "cpu": "ExecutionSample count; sampled stacks, not CPU seconds",
            "thread_allocation": "ThreadAllocationStatistics first-to-last cumulative delta by thread id; not sums of snapshots",
            "gc_pause_ns": "sum of recorded GCPhasePause durations",
        },
        "event_counts": dict(event_counts),
        "allocation_estimated_bytes_by_class": top(allocation_class, limit),
        "allocation_estimated_bytes_by_thread": top(allocation_thread, limit),
        "allocation_estimated_bytes_by_stack": top(allocation_stack, limit),
        "cpu_sample_count_by_stack": [{"key": key, "samples": value} for key, value in cpu_stack.most_common(limit)],
        "thread_allocation_cumulative_delta": thread_deltas[:limit],
        "gc_pause_ns": gc_pause_ns,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--java", type=Path, default=Path("C:/Program Files/Java/jdk-21/bin/java.exe"))
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.recording.is_file() or args.top < 1:
        raise SystemExit("recording must exist and --top must be positive")
    # `jfr print --xml` recursively expands class-loader metadata. This real
    # recording makes JDK 21's XML writer overflow its StringBuilder, so use the
    # streaming consumer API instead. Source-file mode keeps the helper portable.
    source = Path(__file__).with_name("JfrSummary.java")
    completed = subprocess.run([str(args.java), str(source), str(args.recording), str(args.top)],
                               text=True, encoding="utf-8", errors="replace", capture_output=True)
    if completed.returncode:
        raise SystemExit(completed.stderr.strip() or "JFR consumer failed")
    result = json.loads(completed.stdout)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
