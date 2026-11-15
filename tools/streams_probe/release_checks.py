"""Mandatory river evidence for releases tested/installed with Streams 2.17.3.

Other gameplay checks remain reviewer-selected. These checks prevent the known
version-port omission from being accepted on an unrelated successful probe.
"""
from __future__ import annotations

import json
from pathlib import Path

from build_evidence import sha256


def diagnostic_inputs(root: Path) -> tuple[Path, ...]:
    return (
        root / "tools/streams_probe/java",
        root / "tools/streams_probe/resources",
        root / "tools/streams_probe/probe.init.gradle",
        root / "tools/probe_suite.py",
        root / "tools/build_evidence.py",
        *sorted((root / "tools/streams_probe").glob("*.py")),
    )


def require_streams_coverage(entries: list[dict], *, root: Path,
                             installed_mods: Path | None = None) -> None:
    from streams_probe.compat_preflight import find_streams_jar

    # Legacy versions keep their existing validation routes; future versions
    # need an explicit policy instead of silently inheriting a previous port.
    supported = {"2.13.1", "2.13.5", "2.13.8", "2.15.2", "2.16.2", "2.17.1", "2.17.3"}
    required: set[str] = set()
    candidates = []
    for entry in entries:
        fixture = Path(entry["path"]).parent
        selected = find_streams_jar(fixture / "mods")
        manifest_path = fixture / "input-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
        declared = manifest.get("streamsVersion")
        manifest_streams = any("streamsreflowing" in str(name).lower()
                               for name in manifest.get("sha256", {}))
        manifest_streams |= any("streamsreflowing" in str(record.get("file", "")).lower()
                                for record in manifest.get("mods", []) if isinstance(record, dict))
        if (declared or manifest_streams) and not selected:
            raise ValueError(f"Streams validation fixture JAR missing: {fixture}")
        if not selected:
            continue
        jar, version = selected
        if declared and declared != version:
            raise ValueError(f"Streams fixture metadata/version mismatch: {fixture}")
        if version not in supported:
            raise ValueError(f"Streams release policy not audited for {version}")
        if version == "2.17.3":
            digest = sha256(jar)
            # Bind to prepared bytes, not just whichever JAR is there today.
            records = manifest.get("mods", [])
            hashed = manifest.get("sha256", {})
            expected = next((row.get("sha256") for row in records
                             if isinstance(row, dict) and row.get("file") == jar.name), None)
            expected = expected or hashed.get("mods/" + jar.name)
            if expected != digest:
                raise ValueError(f"Streams JAR differs from prepared inputs: {jar}")
            required.add(digest)
            candidates.append((digest, entry, fixture))
    if installed_mods is not None:
        selected = find_streams_jar(installed_mods)
        if selected:
            jar, version = selected
            if version not in supported:
                raise ValueError(f"Streams installation version not audited: {version}")
            if version == "2.17.3":
                required.add(sha256(jar))
    if not required:
        return

    from streams_probe.run_suite import river_regression_receipt, native_water_settlement_receipt

    covered = set()
    needed_inputs = {p.relative_to(root).as_posix() for p in diagnostic_inputs(root)}
    for digest, entry, fixture in candidates:
        row = entry["result"]
        command = row.get("command", [])
        if ("-PtdProbe.riverRegressionAudit=true" not in command
                or "-PtdProbe.settleWater=true" not in command
                or not needed_inputs.issubset(set(row.get("diagnosticInputs", [])))
                or not row.get("diagnosticInputsSha256")):
            continue
        chunk_args = [arg.split("=", 1)[1] for arg in command
                      if arg.startswith("-PtdProbe.chunks=")]
        if len(chunk_args) != 1:
            continue
        expected = set(chunk_args[0].split(";"))
        receipt = fixture / row["receipt"]
        if (native_water_settlement_receipt(receipt, expected=expected) == "success"
                and river_regression_receipt(receipt, expected=expected) == "success"):
            covered.add(digest)
    if required - covered:
        raise ValueError("Streams 2.17.3 release requires --river-regression-audit --settle-water "
                         "evidence with frozen diagnostic inputs and the selected Streams JAR")
