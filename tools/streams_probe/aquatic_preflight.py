"""Check the selected world's seed and production aquatic policy before Gradle starts."""
from __future__ import annotations

import gzip
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from streams_probe.preflight import coordinate_pairs

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path("com/github/xandergos/terraindiffusionmc")


def fixture_seed(run: Path) -> int:
    level = run / "world/level.dat"
    if level.exists():
        reader_path = ROOT / "tools/river_planner/prepare-playtest.py"
        spec = importlib.util.spec_from_file_location("aquatic_seed_nbt", reader_path)
        reader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reader)
        try:
            seed = reader.nbt_fields(gzip.decompress(level.read_bytes())).get(
                ("Data", "WorldGenSettings", "seed"), (None, None, None, None))[3]
        except (OSError, ValueError, IndexError) as exc:
            raise ValueError("cannot read aquatic fixture seed from level.dat") from exc
    else:
        values = re.findall(r"(?m)^\s*level-seed\s*=\s*([^\r\n]*)",
                            (run / "server.properties").read_text(encoding="utf-8"))
        if len(values) != 1 or not re.fullmatch(r"-?\d+", values[0].strip()):
            raise ValueError("aquatic preflight requires an explicit numeric level-seed")
        seed = int(values[0])
    if type(seed) is not int or not -(1 << 63) <= seed < (1 << 63):
        raise ValueError("aquatic fixture has no valid signed-long world seed")
    manifest = json.loads((run / "input-manifest.json").read_text(encoding="utf-8"))
    if "seed" in manifest and (type(manifest["seed"]) is not int or manifest["seed"] != seed):
        raise ValueError("aquatic fixture manifest seed differs from actual world seed")
    return seed


def validate_aquatic_fixture(root: Path, run: Path, chunks: str | None,
                             java_home: Path) -> dict:
    if not chunks:
        raise ValueError("aquatic-placement-audit requires explicit --chunks or --block-sites")
    pairs = coordinate_pairs(chunks)
    if any(abs(c) > 1_874_999 for pair in pairs for c in pair):
        raise ValueError("aquatic fixture chunks must be inside the Minecraft world border")
    seed = fixture_seed(run)
    suffix = ".exe" if os.name == "nt" else ""
    java, javac = (java_home / "bin" / (name + suffix) for name in ("java", "javac"))
    if not java.is_file() or not javac.is_file():
        raise ValueError("aquatic preflight requires a JDK with java and javac")
    policy = root / "common/src/main/java" / PACKAGE / "world/ecology/SubmergedPlantPolicy.java"
    helper = root / "tools/streams_probe/java" / PACKAGE / "diagnostic/AquaticFixturePlan.java"
    scratch = root / ".tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="aquatic-preflight-", dir=scratch) as raw:
        env = os.environ.copy()
        env.update(TMP=raw, TEMP=raw, JAVA_HOME=str(java_home))
        commands = ([str(javac), "--release", "21", "-encoding", "UTF-8", "-d", raw,
                     str(policy), str(helper)],
                    [str(java), "-cp", raw,
                     "com.github.xandergos.terraindiffusionmc.diagnostic.AquaticFixturePlan",
                     str(seed), chunks])
        for command in commands:
            try:
                result = subprocess.run(command, capture_output=True, text=True, env=env,
                                        timeout=60, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            except subprocess.TimeoutExpired as exc:
                raise ValueError("aquatic preflight timed out before Minecraft launch") from exc
            if result.returncode:
                raise ValueError("aquatic preflight failed before Minecraft launch: "
                                 + (result.stderr or result.stdout).strip())
        report = json.loads(result.stdout)
    report["chunks"] = chunks
    return report
