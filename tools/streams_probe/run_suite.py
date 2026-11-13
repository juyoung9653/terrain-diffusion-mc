"""Run prepared Streams probe worlds in an exclusive, sequential suite."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from functools import partial
from pathlib import Path
import re
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import (Job, atomic_json, default_java_home, environment, exclusive_lock,
                         gradle_command, prepared_run, run_suite, streams_receipt)
from streams_probe.preflight import coordinate_pairs, select_chunks, validate_fixture
from streams_probe.restart_snapshot import capture_snapshot, compare_snapshots
from streams_probe.tree_ground_runtime import validate_tree_ground_runtime
from streams_probe.aquatic_preflight import validate_aquatic_fixture


def cold_audit_receipt(path: Path, *, weather: bool, spatial: bool,
                       started_ns: int) -> str:
    """Require requested cold diagnostics and their fresh, local artifact."""
    if not weather and not spatial:
        return "success"
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "cold_audit_receipt_unreadable"
    if weather:
        audits = [row for row in rows if row.get("event") == "cold_weather_audit"]
        if not audits:
            return "cold_weather_audit_missing"
        if any(row.get("passed") is not True or row.get("status") != "passed"
               or row.get("loaded") is False for row in audits):
            return "cold_weather_audit_not_loaded_or_failed"
    if spatial:
        audits = [row for row in rows if row.get("event") == "cold_biome_spatial_audit"]
        if (len(audits) != 1 or audits[0].get("status") != "passed"
                or not isinstance(audits[0].get("samples"), int)
                or audits[0]["samples"] <= 0 or not isinstance(audits[0].get("csv"), str)):
            return "cold_biome_spatial_audit_missing"
        artifact = path.parent / "td-cold-biomes.csv"
        if not artifact.is_file() or artifact.stat().st_mtime_ns < started_ns:
            return "cold_biome_spatial_artifact_missing_or_stale"
    return "success"


def native_water_settlement_receipt(path: Path, *, expected: set[str]) -> str:
    """Require the native-water branch to settle every selected chunk for 100 ticks."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "native_water_settlement_receipt_unreadable"

    def integer(row: dict, field: str) -> int:
        value = row.get(field)
        if isinstance(value, bool):
            return -1
        if isinstance(value, int):
            return value
        return int(value) if isinstance(value, str) and re.fullmatch(r"-?\d+", value) else -1

    settled = [row for row in rows if row.get("event") == "native_water_settled"]
    if len(settled) != 1 or settled[0].get("status") != "passed":
        return "native_water_settlement_missing"
    row = settled[0]
    start, end = integer(row, "startTick"), integer(row, "endTick")
    tickable, selected = integer(row, "tickableChunks"), integer(row, "selectedChunks")
    if min(start, end, tickable, selected) < 0 or end - start < 100:
        return "native_water_settlement_ticks_missing"
    if tickable != len(expected) or selected != len(expected):
        return "native_water_settlement_chunk_count_mismatch"
    return "success"


def vanilla_kelp_receipt(path: Path) -> str:
    """Require both registered vanilla features and the revised littoral evidence."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "vanilla_kelp_audit_receipt_unreadable"
    audits = [row for row in rows if row.get("event") == "vanilla_kelp_audit"]
    if len(audits) != 1 or audits[0].get("status") != "passed":
        return "vanilla_kelp_audit_not_passed"
    targets = audits[0].get("targets")
    if not isinstance(targets, dict) or set(targets) != {"minecraft:kelp_cold", "minecraft:kelp_warm"}:
        return "vanilla_kelp_audit_targets_missing"
    required = ("shelfSixAccepted", "transitionSevenAccepted", "deepAccepted",
                "shelfSixNativeStalks", "transitionSevenNativeStalks", "deepTwelveNativeStalks")
    for target in targets.values():
        if not isinstance(target, dict) or any(not isinstance(target.get(field), int) for field in required):
            return "vanilla_kelp_audit_depth_evidence_missing"
        if (target["shelfSixAccepted"] != 0 or target["shelfSixNativeStalks"] != 0
                or target["deepAccepted"] <= 0 or target["deepTwelveNativeStalks"] <= 0
                or target["transitionSevenAccepted"] > target["deepAccepted"]
                or target["transitionSevenNativeStalks"] > target["deepTwelveNativeStalks"]):
            return "vanilla_kelp_audit_littoral_gate_failed"
    return "success"


def aquatic_placement_receipt(path: Path) -> str:
    """Require fresh physical short/tall and sea-pickle fixture evidence."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "aquatic_placement_receipt_unreadable"
    audits = [row for row in rows if row.get("event") == "aquatic_placement_audit"]
    fixtures = [row for row in rows if row.get("event") == "aquatic_placement_fixture"]
    if len(audits) != 1 or audits[0].get("status") != "passed":
        return "aquatic_placement_audit_missing_or_failed"
    if len(fixtures) != 1 or fixtures[0].get("status") != "passed":
        return "aquatic_placement_fixture_missing_or_failed"
    audit, fixture = audits[0], fixtures[0]
    for field in ("vanillaHook", "seaPickleHook", "reefSeaPickleHook", "policyGuards",
                  "streamsPresent", "streamsHook"):
        if audit.get(field) is not True:
            return "aquatic_placement_hook_or_policy_missing"
    required = ("acceptedShort", "acceptedTallPair", "rejectedShort", "rejectedTallPair",
                "acceptedSeaPickle", "rejectedSeaPickle", "acceptedReefSeaPickle",
                "rejectedReefSeaPickle", "streamsPresent", "streamsAcceptedShort",
                "streamsAcceptedTallPair", "streamsRejectedShort", "streamsRejectedTallPair")
    if any(fixture.get(field) is not True for field in required):
        return "aquatic_placement_physical_evidence_missing"
    return "success"


def submerged_river_bed_receipt(path: Path, *, expected: set[str]) -> str:
    """Require a post-100-tick physical water-bottom scan, never a plan snapshot."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "submerged_river_bed_receipt_unreadable"
    audits = [row for row in rows if row.get("event") == "submerged_river_bed_audit"]
    if len(audits) != 1 or audits[0].get("status") != "passed":
        return "submerged_river_bed_audit_not_passed"
    audit = audits[0]
    fields = ("settledTicks", "fullChunks", "waterBeds", "surfaceWaterBeds",
              "submergedGrassBeds", "dryExposedGrassBlocks", "treeGroundWritesSkipped")
    if any(not isinstance(audit.get(field), int) for field in fields):
        return "submerged_river_bed_audit_fields_missing"
    if (audit.get("method") != "actual_loaded_water_bottom_after_settlement"
            or audit["settledTicks"] < 100 or audit["fullChunks"] != len(expected)
            or audit["waterBeds"] < 1 or audit["surfaceWaterBeds"] < 1
            or audit["submergedGrassBeds"] != 0):
        return "submerged_river_bed_audit_physical_evidence_failed"
    return "success"


def plan_audit_receipt(path: Path, *, expected: set[str], started_ns: int) -> str:
    """Require a fresh, complete native plan CSV for every generated chunk."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "plan_audit_receipt_unreadable"
    audits = [row for row in rows if row.get("event") == "chunk"]
    if not audits:
        return "plan_audit_chunk_receipts_missing"
    chunks = [row.get("chunk") for row in audits]
    required = expected or set(chunks)
    if (len(chunks) != len(set(chunks)) or set(chunks) != required
            or any(not isinstance(chunk, str) for chunk in chunks)):
        return "plan_audit_chunk_receipts_mismatch"
    header = ("x,z,naturalPreCarveY,actualSurfaceY,actualGroundY,actualTopWaterY,"
              "naturalBiome,inChannel,planWaterSurfaceY,planWidth,planDistance,planSlope,"
              "planBankBlend,naturalGroundMinusPlanWater,actualWaterMinusPlanWater")
    for row in audits:
        if row.get("planAuditStatus") != "engine_plan_after_full_chunk":
            return "plan_audit_status_missing_or_failed"
        chunk = row["chunk"]
        expected_name = f"td-streams-probe-plan-audit-{chunk.replace(',', '-')}.csv"
        name = row.get("planAuditCsv")
        if name != expected_name:
            return "plan_audit_csv_name_mismatch"
        artifact = path.parent / name
        try:
            lines = artifact.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError):
            return "plan_audit_csv_missing"
        if artifact.stat().st_mtime_ns < started_ns:
            return "plan_audit_csv_stale"
        if len(lines) != 257 or lines[0] != header or any(not line for line in lines[1:]):
            return "plan_audit_csv_not_256_rows"
        try:
            widths = [len(columns) for columns in csv.reader(lines)]
        except csv.Error:
            return "plan_audit_csv_bad_columns"
        if any(width != 15 for width in widths):
            return "plan_audit_csv_bad_columns"
    return "success"


def validate_neoforge_version(run: Path, requested: str) -> None:
    """Do not launch a fixture prepared for a different NeoForge runtime."""
    try:
        manifest = json.loads((run / "input-manifest.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid input manifest for {run.name}") from exc
    actual = manifest.get("neoforge") if isinstance(manifest, dict) else None
    if actual != requested:
        raise ValueError(f"fixture {run.name} requires NeoForge {actual!r}, requested {requested!r}")


def streams_profile_receipt(path: Path, *, natural_rivers: str | None) -> str:
    """Check the persisted native Streams profile only when the caller requested one."""
    if natural_rivers is None:
        return "success"
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "streams_profile_receipt_unreadable"
    profiles = [row for row in rows if row.get("event") == "streams_profile"]
    if len(profiles) != 1 or profiles[0].get("status") != "passed":
        return "streams_profile_missing_or_failed"
    profile = profiles[0]
    def flag(field: str) -> bool:
        return profile.get(field) is True or profile.get(field) == "true"
    version = profile.get("version")
    version = int(version) if isinstance(version, str) and version.isdecimal() else version
    if not flag("exactTerrain"):
        return "streams_profile_not_exact_terrain"
    if natural_rivers == "true":
        if not flag("enabled") or version != 14:
            return "streams_profile_not_enabled_v14"
    elif flag("enabled"):
        return "streams_profile_not_disabled"
    return "success"


def natural_network_policy_receipt(path: Path) -> str:
    """Fresh V14 runs must execute the production network hook, not just set config."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        audits = [row for row in rows if row.get("event") == "natural_network_policy"]
        if len(audits) != 1:
            return "natural_network_policy_missing"
        row = audits[0]
        values = [str(row.get(key, "")) for key in ("invocations", "completed", "failed")]
        if any(not re.fullmatch(r"[0-9]+", value) for value in values):
            return "natural_network_policy_invalid"
        invoked, completed, failed = map(int, values)
        if failed or not 0 < completed <= invoked or row.get("status") != "observed":
            return "natural_network_policy_not_executed_or_failed"
        return "success"
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "natural_network_policy_unreadable"


def greeneries_density_receipt(path: Path) -> str:
    """Require the native Greeneries fixture audit to complete successfully."""
    try:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "greeneries_density_receipt_unreadable"
    audits = [row for row in rows if row.get("event") == "greeneries_density_fixture"]
    if len(audits) != 1:
        return "greeneries_density_fixture_missing"
    return "success" if audits[0].get("status") == "passed" else "greeneries_density_fixture_failed"


def network_audit_receipt(path: Path, *, started_ns: int, natural_rivers: str | None) -> str:
    """Require a fresh bounded native graph export and its saved-profile evidence."""
    status = streams_profile_receipt(path, natural_rivers=natural_rivers)
    if status != "success":
        return status
    for name in ("td-natural-river-network.csv", "td-natural-river-profile.txt"):
        artifact = path.parent / name
        try:
            content = artifact.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return "network_audit_artifact_missing"
        if artifact.stat().st_mtime_ns < started_ns:
            return "network_audit_artifact_stale"
        if not content.strip():
            return "network_audit_artifact_empty"
    return "success"


def surface_checks(run: Path, args, check_receipt):
    """Keep the pre-launch baseline and the post-launch verdict under the suite lock."""
    state = {}
    expected = set(args.chunks.split(";")) if args.chunks else set()
    csv_names = {f"td-streams-probe-surface-audit-{chunk.replace(',', '-')}.csv" for chunk in expected}

    def matching_audits(path):
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        audits = [row for row in rows if row.get("event") == "surface_audit"]
        return len(audits) == len(expected) and {row.get("chunk") for row in audits} == expected

    def checked_snapshot():
        snapshot = capture_snapshot(run / "world")
        if set(snapshot["chunks"]) != expected:
            raise ValueError("surface CSV chunks differ from requested chunks; use a matching fixture")
        return snapshot

    def prepare(dry_run=False):
        state["fixture_preflight"] = validate_fixture(run, args.expect_river_cache)
        # The merged artifact contains NeoForge's patched Minecraft methods;
        # inspecting the vanilla SRG compile jar misses its event overloads.
        version = getattr(args, "neoforge_version", "21.1.249")
        runtime = ROOT / "neoforge/build/moddev/artifacts" / f"neoforge-{version}-merged.jar"
        java_home = getattr(args, "java_home", None) or os.environ.get("JAVA_HOME") or default_java_home()
        if not java_home:
            javap = shutil.which("javap")
            if not javap:
                raise ValueError("runtime preflight requires --java-home with a JDK containing javap")
            java_home = str(Path(javap).resolve().parent.parent)
        if getattr(args, "aquatic_placement_audit", False):
            state["aquatic_preflight"] = validate_aquatic_fixture(ROOT, run, args.chunks, Path(java_home))
            if not dry_run:
                atomic_json(run / "aquatic-preflight.json", state["aquatic_preflight"])
        state["runtime_preflight"] = validate_tree_ground_runtime(
            runtime, Path(java_home),
            ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/SubmergedTreeGroundMixin.java")
        if not dry_run:
            atomic_json(run / "runtime-preflight.json", state["runtime_preflight"])
        if args.surface_audit:
            existing = {p.name for p in (run / "world").glob("td-streams-probe-surface-audit-*.csv")}
            if existing - csv_names:
                raise ValueError("fixture contains surface CSVs outside requested chunks; use a matching fixture")
        if args.verify_restart:
            receipt = run / "world/td-streams-probe.jsonl"
            if check_receipt(receipt) != "success" or not matching_audits(receipt):
                raise ValueError("restart requires a prior successful probe receipt")
            before = checked_snapshot()
            if not dry_run:
                archive = run / "restart-checks" / str(time.time_ns())
                archive.mkdir(parents=True, exist_ok=False)
                atomic_json(archive / "before.json", before)
                shutil.copy2(receipt, archive / "receipt-before.jsonl")
                if (run / "launcher-output.txt").is_file():
                    shutil.copy2(run / "launcher-output.txt", archive / "launcher-before.txt")
                state.update(before=before, archive=archive)
        state["started_ns"] = time.time_ns()

    def check(path):
        status = check_receipt(path)
        if status != "success":
            return status
        status = streams_profile_receipt(path, natural_rivers=getattr(args, "natural_rivers", None))
        if status != "success":
            return status
        if (getattr(args, "natural_rivers", None) == "true"
                and state.get("fixture_preflight", {}).get("effectiveCachePolicy",
                        getattr(args, "expect_river_cache", None)) == "fresh"):
            status = natural_network_policy_receipt(path)
            if status != "success":
                return status
        status = cold_audit_receipt(path, weather=getattr(args, "cold_weather_audit", False),
                                    spatial=getattr(args, "cold_biome_spatial_audit", False),
                                    started_ns=state["started_ns"])
        if status != "success":
            return status
        if getattr(args, "plan_audit", False):
            status = plan_audit_receipt(path, expected=expected, started_ns=state["started_ns"])
            if status != "success":
                return status
        if getattr(args, "settle_water", False):
            status = native_water_settlement_receipt(path, expected=expected)
            if status != "success":
                return status
        if getattr(args, "river_bed_material_audit", False):
            status = submerged_river_bed_receipt(path, expected=expected)
            if status != "success":
                return status
        if getattr(args, "vanilla_kelp_audit", False):
            status = vanilla_kelp_receipt(path)
            if status != "success":
                return status
        if getattr(args, "aquatic_placement_audit", False):
            status = aquatic_placement_receipt(path)
            if status != "success":
                return status
        if getattr(args, "greeneries_density_audit", False):
            status = greeneries_density_receipt(path)
            if status != "success":
                return status
        if getattr(args, "network_audit", False):
            status = network_audit_receipt(path, started_ns=state["started_ns"],
                                           natural_rivers=getattr(args, "natural_rivers", None))
            if status != "success":
                return status
        if not args.surface_audit:
            return status
        if not matching_audits(path):
            return "surface_evidence_missing_or_duplicate"
        files = [path.parent / "td-natural-river-network.csv"]
        files += [path.parent / name for name in sorted(csv_names)]
        if any(not p.is_file() or p.stat().st_mtime_ns < state["started_ns"] for p in files):
            return "surface_evidence_missing_or_stale"
        after = checked_snapshot()
        if args.verify_restart:
            comparison = compare_snapshots(state["before"], after)
            atomic_json(state["archive"] / "after.json", after)
            atomic_json(state["archive"] / "comparison.json", comparison)
            return "success" if comparison["passed"] else "restart_state_changed"
        return "success"

    return prepare, check


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


def short_tunnel_receipt(path: Path) -> str:
    """Require settled physical evidence, not only a producer's passed label."""
    status = streams_receipt(path)
    if status != "success":
        return status
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    audits = [row for row in rows if row.get("event") == "short_river_tunnel_audit"]
    if len(audits) != 1 or audits[0].get("status") != "passed":
        return "short_river_tunnel_not_passed"
    audit = audits[0]
    before = [row for row in rows if row.get("event") == "short_river_tunnel_pre_ticks"]
    if len(before) != 1 or rows.index(before[0]) >= rows.index(audit):
        return "short_river_tunnel_missing_pre_tick_evidence"

    def count(row: dict, field: str) -> int:
        value = str(row.get(field, ""))
        return int(value) if re.fullmatch(r"[0-9]{1,12}", value) else -1

    if count(audit, "settledTicks") < 100:
        return "short_river_tunnel_not_settled"
    interior = count(audit, "interiorColumns")
    protected = count(before[0], "protectedColumns")
    if (audit.get("method") != "actual_full_chunks_full_mask_connected_water_roof_and_portals"
            or count(audit, "plansInBounds") < 1 or count(audit, "fullChunks") < 1
            or interior < 2 or protected <= interior
            or not 0 <= count(before[0], "wetColumns") <= protected
            or before[0].get("connected") not in ("true", "false")
            or audit.get("firstGeneration") not in ("true", "false")
            or count(audit, "retainedWrites") < (1 if audit.get("firstGeneration") == "true" else 0)):
        return "short_river_tunnel_missing_physical_evidence"
    try:
        # Fixed artifact names avoid trusting paths supplied inside a receipt.
        plan = (path.parent / "td-short-river-tunnel-plan.txt").read_text(encoding="utf-8")
        samples = (path.parent / "td-short-river-tunnel-audit.csv").read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return "short_river_tunnel_missing_artifact"
    if (hashlib.sha256(plan.encode("utf-8")).hexdigest() != audit.get("planHash")
            or hashlib.sha256("\n".join(samples.splitlines()).encode("utf-8")).hexdigest()
            != audit.get("physicalSampleHash")):
        return "short_river_tunnel_artifact_mismatch"
    return "success"



def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+", help="prepared names under build/streams-probe")
    parser.add_argument("--gradle-user-home", required=True)
    parser.add_argument("--java-home", default=default_java_home())
    parser.add_argument("--daemon", action="store_true", help="opt in to Gradle daemon")
    parser.add_argument("--neoforge-version", default="21.1.249",
                        help="exact dotted NeoForge version recorded by the fixture")
    parser.add_argument("--max-chunks", type=int, choices=range(1, 17), default=4)
    parser.add_argument("--chunks", help="fixed chunk order: X,Z;X,Z (use --chunks=... for negatives)")
    parser.add_argument("--center-chunk", help="bounded route-discovery center in chunk coordinates X,Z")
    parser.add_argument("--inland-routes", action="store_true", help="discover channels with planned water above sea level")
    parser.add_argument("--block-sites", help="world-block X,Z;X,Z; derive chunks or check --chunks coverage")
    parser.add_argument("--expect-river-cache", choices=("fresh", "present"),
                        help="assert fresh routing or an existing graph before launch")
    parser.add_argument("--preflight-only", action="store_true", help="validate inputs without launching Java")
    parser.add_argument("--surface-audit", action="store_true",
                        help="require complete surface/ecology columns and a natural river export")
    parser.add_argument("--verify-restart", action="store_true",
                        help="compare a prior successful surface audit against one restart")
    parser.add_argument("--network-audit", action="store_true",
                        help="export one bounded native Streams graph without a surface audit")
    parser.add_argument("--natural-rivers", choices=("true", "false"),
                        help="request the fresh TD natural-river profile; omit to respect saved state")
    parser.add_argument("--natural-center", help="world-block X,Z center of the bounded river export")
    parser.add_argument("--natural-radius", type=int, choices=range(64, 2049), metavar="64..2048")
    parser.add_argument("--cold-weather-audit", action="store_true",
                        help="require cached-climate snow and ice eligibility evidence")
    parser.add_argument("--cold-biome-spatial-audit", action="store_true",
                        help="write and verify bounded cached cold-biome samples")
    parser.add_argument("--cold-center", help="world-block X,Z start for the cold spatial audit")
    parser.add_argument("--cold-tiles", type=int, default=1,
                        help="cold spatial-audit tiles (1..8; default 1)")
    parser.add_argument("--cold-step", type=int, default=4,
                        help="cold spatial sample step (1..16; default 4)")
    parser.add_argument("--bank-write-trace",
                        help="world-block X,Z center for a bounded bank block-write trace")
    parser.add_argument("--bank-trace-min-y", type=int, default=140,
                        help="minimum Y for --bank-write-trace (default 140)")
    parser.add_argument("--bank-trace-max-y", type=int, default=160,
                        help="maximum Y for --bank-write-trace (default 160)")
    parser.add_argument("--plan-audit", action="store_true",
                        help="record native channel distance, width, and water evidence")
    parser.add_argument("--settle-water", action="store_true",
                        help="require native water settlement for 100 ticks in fixed chunks")
    parser.add_argument("--river-bed-material-audit", action="store_true",
                        help="after --settle-water, reject actual water bottoms on grass blocks")
    parser.add_argument("--vanilla-kelp-audit", action="store_true",
                        help="exercise registered vanilla kelp placements at the littoral depth gate")
    parser.add_argument("--aquatic-placement-audit", action="store_true",
                        help="require physical seagrass and sea-pickle placement gates")
    parser.add_argument("--greeneries-density-audit", action="store_true",
                        help="require the native Greeneries density fixture audit")
    parser.add_argument("--topology-audit", action="store_true")
    parser.add_argument("--terrain-slab-audit", action="store_true",
                        help="native slab contour and loaded Landmarks registry checks on controlled terrain")
    parser.add_argument("--short-tunnel-audit", action="store_true",
                        help="require a physical, bounded mountain-river roof tunnel in a fresh V12 world")
    parser.add_argument("--short-tunnel-center", help="bounded world-block X,Z search anchor for --short-tunnel-audit")
    parser.add_argument("--short-tunnel-radius", type=int,
                        help="world-block search radius (96..2048) for --short-tunnel-audit")
    parser.add_argument("--short-tunnel-reverse-chunk-order", action="store_true",
                        help="generate the selected tunnel footprint in reverse chunk order for determinism comparison")
    parser.add_argument("--inference-metrics", action="store_true", help="record ONNX and provider timing in this isolated run")
    parser.add_argument("--terrain-parity", action="store_true",
                        help="audit native ground/full tile parity at scales 1 through 6; changes timed workload")
    parser.add_argument("--cpu-profile", action="store_true",
                        help="sample server thread CPU/stacks; profiling overhead invalidates speed comparisons")
    parser.add_argument("--fast-ground", choices=("true", "false"),
                        help="diagnostic override of the saved ground-only sampling profile")
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r"\d+\.\d+\.\d+", args.neoforge_version):
            raise ValueError("neoforge-version must be dotted numeric")
        args.chunks = select_chunks(args.chunks, args.block_sites, args.max_chunks)
        args.surface_audit = args.surface_audit or args.verify_restart
        if args.settle_water and not args.chunks:
            raise ValueError("settle-water requires fixed chunks or block-sites")
        if args.river_bed_material_audit and (not args.settle_water or not args.chunks):
            raise ValueError("river-bed-material-audit requires --settle-water and fixed chunks or block-sites")
        if args.surface_audit and (not args.chunks or args.topology_audit
                                   or args.terrain_slab_audit or args.short_tunnel_audit):
            raise ValueError("surface/restart audit requires fixed chunks or block-sites without a discovery audit")
        if args.verify_restart and args.expect_river_cache == "fresh":
            raise ValueError("restart cannot require fresh routing")
        if (args.natural_center or args.natural_radius is not None) and not (args.surface_audit or args.network_audit):
            raise ValueError("natural export options require --surface-audit, --verify-restart, or --network-audit")
        if args.natural_center and len(coordinate_pairs(args.natural_center)) != 1:
            raise ValueError("natural-center must be one X,Z pair")
        if not 1 <= args.cold_tiles <= 8:
            raise ValueError("cold-tiles must be between 1 and 8")
        if not 1 <= args.cold_step <= 16:
            raise ValueError("cold-step must be between 1 and 16")
        if args.cold_biome_spatial_audit and not args.cold_center:
            raise ValueError("cold-biome-spatial-audit requires --cold-center=X,Z")
        if args.cold_center:
            if not args.cold_biome_spatial_audit:
                raise ValueError("cold-center requires --cold-biome-spatial-audit")
            if len(coordinate_pairs(args.cold_center)) != 1:
                raise ValueError("cold-center must be one X,Z pair")
        if args.bank_write_trace:
            if len(coordinate_pairs(args.bank_write_trace)) != 1:
                raise ValueError("bank-write-trace must be one X,Z pair")
            if not -64 <= args.bank_trace_min_y <= args.bank_trace_max_y <= 320:
                raise ValueError("bank trace Y range must be within -64..320 and ordered")
        elif args.bank_trace_min_y != 140 or args.bank_trace_max_y != 160:
            raise ValueError("bank trace Y options require --bank-write-trace=X,Z")
        if args.terrain_slab_audit and (args.topology_audit or args.short_tunnel_audit or args.inference_metrics or args.terrain_parity or args.cpu_profile or args.chunks):
            raise ValueError("terrain-slab-audit is a dedicated controlled-terrain audit")
        if args.short_tunnel_audit and (args.topology_audit or args.inference_metrics or args.terrain_parity or args.cpu_profile or args.chunks):
            raise ValueError("short-tunnel-audit discovers and checks one real bounded tunnel itself")
        if (args.short_tunnel_center or args.short_tunnel_radius is not None or args.short_tunnel_reverse_chunk_order) and not args.short_tunnel_audit:
            raise ValueError("short-tunnel options require --short-tunnel-audit")
        if args.short_tunnel_center and not re.fullmatch(r"-?\d+,-?\d+", args.short_tunnel_center):
            raise ValueError("short-tunnel-center must be X,Z world-block coordinates")
        if args.short_tunnel_radius is not None and not 96 <= args.short_tunnel_radius <= 2048:
            raise ValueError("short-tunnel-radius must be 96..2048")
        options = [f"-PtdProbe.maxChunks={args.max_chunks}", "-PtdProbe.routeSearchRadius=32",
                   "-PtdProbe.timeoutSeconds=900"]
        if args.center_chunk:
            centers = coordinate_pairs(args.center_chunk)
            if len(centers) != 1:
                raise ValueError("center-chunk must be one X,Z pair")
            cx, cz = centers[0]
            options.extend((f"-PtdProbe.centerChunkX={cx}", f"-PtdProbe.centerChunkZ={cz}"))
        if args.inland_routes:
            options.append("-PtdProbe.inlandRoutes=true")
        if args.chunks:
            options.append(f"-PtdProbe.chunks={args.chunks}")
        if args.natural_rivers is not None:
            options.append(f"-PtdRiver.natural={args.natural_rivers}")
        if args.surface_audit:
            options.extend(("-PtdProbe.surfaceAudit=true", "-PtdProbe.ecologyAudit=true",
                            "-PtdProbe.naturalRiverAudit=true"))
            if args.natural_center:
                x, z = coordinate_pairs(args.natural_center)[0]
                options.extend((f"-PtdProbe.naturalCenterX={x}", f"-PtdProbe.naturalCenterZ={z}"))
            if args.natural_radius is not None:
                options.append(f"-PtdProbe.naturalRadius={args.natural_radius}")
        elif args.network_audit:
            options.append("-PtdProbe.naturalRiverAudit=true")
            if args.natural_center:
                x, z = coordinate_pairs(args.natural_center)[0]
                options.extend((f"-PtdProbe.naturalCenterX={x}", f"-PtdProbe.naturalCenterZ={z}"))
            if args.natural_radius is not None:
                options.append(f"-PtdProbe.naturalRadius={args.natural_radius}")
        if args.cold_weather_audit:
            options.append("-PtdProbe.coldWeatherAudit=true")
        cold_options = []
        if args.cold_biome_spatial_audit:
            cold_x, cold_z = coordinate_pairs(args.cold_center)[0]
            cold_options.extend(("-PtdProbe.coldBiomeSpatialAudit=true",
                                 f"-PtdProbe.coldX={cold_x}", f"-PtdProbe.coldZ={cold_z}",
                                 f"-PtdProbe.coldTiles={args.cold_tiles}",
                                 f"-PtdProbe.coldStep={args.cold_step}"))
        if args.bank_write_trace:
            bank_x, bank_z = coordinate_pairs(args.bank_write_trace)[0]
            options.extend(("-PtdProbe.bankWriteTrace=true", f"-PtdProbe.bankTraceX={bank_x}",
                            f"-PtdProbe.bankTraceZ={bank_z}",
                            f"-PtdProbe.bankTraceMinY={args.bank_trace_min_y}",
                            f"-PtdProbe.bankTraceMaxY={args.bank_trace_max_y}"))
        if args.plan_audit:
            options.append("-PtdProbe.planAudit=true")
        if args.settle_water:
            options.append("-PtdProbe.settleWater=true")
        if args.river_bed_material_audit:
            options.append("-PtdProbe.riverBedMaterialAudit=true")
        if args.vanilla_kelp_audit:
            options.append("-PtdProbe.vanillaKelpAudit=true")
        if args.aquatic_placement_audit:
            options.extend(("-PtdProbe.aquaticPlacementFixture=true",
                            "-PtdProbe.aquaticPlacementAudit=true"))
        if args.greeneries_density_audit:
            options.append("-PtdProbe.greeneriesDensityAudit=true")
        if args.topology_audit:
            options.append("-PtdProbe.streamsTopologyAudit=true")
        if args.terrain_slab_audit:
            options.append("-PtdProbe.terrainSlabAudit=true")
        if args.short_tunnel_audit:
            options.extend(("-PtdProbe.shortTunnelAudit=true", "-PtdProbe.expectedNaturalProfile=12"))
            if args.short_tunnel_center:
                tunnel_x, tunnel_z = args.short_tunnel_center.split(",")
                options.extend((f"-PtdProbe.shortTunnelCenterX={tunnel_x}",
                                f"-PtdProbe.shortTunnelCenterZ={tunnel_z}"))
            if args.short_tunnel_radius is not None:
                options.append(f"-PtdProbe.shortTunnelRadius={args.short_tunnel_radius}")
            if args.short_tunnel_reverse_chunk_order:
                options.append("-PtdProbe.shortTunnelReverseChunkOrder=true")
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
            run = prepared_run(ROOT, "streams-probe", f"build/streams-probe/{name}", "input-manifest.json")
            validate_neoforge_version(run, args.neoforge_version)
            runs.append(run)
        check_receipt = (partial(metrics_receipt, topology=args.topology_audit, terrain_parity=args.terrain_parity, cpu_profile=args.cpu_profile)
                         if args.inference_metrics or args.terrain_parity or args.cpu_profile
                         else topology_receipt if args.topology_audit else streams_receipt)
        if args.terrain_slab_audit:
            check_receipt = slab_receipt
        if args.short_tunnel_audit:
            check_receipt = short_tunnel_receipt
        jobs = []
        checks = [surface_checks(run, args, check_receipt) for run in runs]
        if args.preflight_only:
            with exclusive_lock(ROOT):
                for run, (prepare, _) in zip(runs, checks):
                    prepare(dry_run=True)
                    print(json.dumps({"name": run.name, "preflight": "passed", "chunks": args.chunks}))
            return 0
        for run, (prepare, check) in zip(runs, checks):
            run_options = [*options, *cold_options]
            if args.cold_biome_spatial_audit:
                run_options.append(f"-PtdProbe.coldOutput={run / 'world/td-cold-biomes.csv'}")
            jobs.append(Job(run, gradle_command(ROOT, ["-I", "tools/streams_probe/probe.init.gradle",
                                                       f"-PtdProbeDir={run}", *run_options], args.daemon,
                                           neoforge_version=args.neoforge_version),
                            run / "world/td-streams-probe.jsonl", check, prepare))
        return run_suite(ROOT, "streams-probe", jobs, environment(args.java_home, args.gradle_user_home))
    except (ValueError, RuntimeError, OSError, KeyError, TypeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
