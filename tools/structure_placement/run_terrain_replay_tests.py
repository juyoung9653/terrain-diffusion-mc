"""Pure codec and store checks; uses cached Gson and DTO stubs, no Minecraft/Gradle."""
from pathlib import Path
import json
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "terrain-replay-tests"
OUT.mkdir(parents=True, exist_ok=True)
DIAGNOSTIC = ROOT / "tools/structure_placement/probe-java/com/github/xandergos/terraindiffusionmc/diagnostic"
PIPELINE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline"
WINDOWLESS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
GSON = next((ROOT / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/gson-*.jar"))
CLASSPATH = os.pathsep.join((str(OUT.relative_to(ROOT)), str(GSON.relative_to(ROOT))))


def run(command):
    result = subprocess.run([str(x) for x in command], cwd=ROOT, capture_output=True, text=True, creationflags=WINDOWLESS)
    print(result.stdout, end="")
    print(result.stderr, end="")
    result.check_returncode()
    if "AccessDeniedException" in result.stderr or "An exception has occurred in the compiler" in result.stderr:
        raise RuntimeError("JDK reported an exception despite successful exit status")


# Compile the actual DTO definition from the provider, not a hand-copied contract.
provider = (PIPELINE / "LocalTerrainProvider.java").read_text(encoding="utf-8")
dto = provider[provider.index("    public static final class HeightmapData {"):provider.index("    private static record CacheKey")]
stub = OUT / "LocalTerrainProvider.java"
stub.write_text("package com.github.xandergos.terraindiffusionmc.pipeline;\npublic final class LocalTerrainProvider {\n"
                + dto + '\npublic static boolean legacySampling() { return Boolean.parseBoolean(System.getProperty("td.inference.legacySampling", "true")); }\n}', encoding="utf-8")
classifier = (PIPELINE / "EcologyClassifier.java").read_text(encoding="utf-8")
sample = re.search(r"public record Sample\([^}]+\}", classifier).group(0)
classifier_stub = OUT / "EcologyClassifier.java"
classifier_stub.write_text("package com.github.xandergos.terraindiffusionmc.pipeline;\npublic final class EcologyClassifier {\n" + sample + "\n}", encoding="utf-8")
assert re.search(r"private\s+(?:volatile\s+)?long seed", provider), "Mixin requires an instance seed"
mixin = (DIAGNOSTIC / "mixin/TerrainReplayMixin.java").read_text(encoding="utf-8")
for method, count in [("handle1x", 4), ("handleUpsampled", 5)]:
    signature = re.search(r"private HeightmapData " + method + r"\(([^)]+)\)", provider)
    assert signature and signature.group(1).count("int ") == count
    assert mixin.count('method = "' + method + '"') == 2
    for prefix in ("record", "replay"):
        handler = re.search(r"private void " + prefix + ("1x" if count == 4 else "Upsampled") + r"\(([^)]+)\)", mixin)
        assert handler and handler.group(1).count("int ") == count
assert "@Shadow private long seed;" in mixin and "getSeed(" not in mixin
run([JAVA / "javac", "-encoding", "UTF-8", "-cp", GSON.relative_to(ROOT), "-d", OUT, stub, classifier_stub,
     PIPELINE / "EcologyData.java", DIAGNOSTIC / "TerrainReplayCodec.java", DIAGNOSTIC / "TerrainReplayStore.java",
     Path(__file__).with_name("TerrainReplayCodecTest.java")])


def check(case, mode="full", directory="relative-is-unused", **props):
    properties = {"td.terrainMode": mode, "td.terrainSnapshotDir": str(directory), "td.terrainSnapshotId": "a" * 64, **props}
    run([JAVA / "java", *[f"-D{k}={v}" for k, v in properties.items()], "-cp", CLASSPATH, "TerrainReplayCodecTest", case])


check("codec")
check("full", **{"td.terrainSnapshotId": "invalid", "td.inference.legacySampling": "false"})
check("reject-mode", "unknown")
check("reject-mode", "record")
with tempfile.TemporaryDirectory(prefix="snapshot-", dir=OUT) as temporary:
    snapshot = Path(temporary)
    check("reject-mode", "record", snapshot, **{"td.inference.legacySampling": "false"})
    check("reject-mode", "replay", snapshot, **{"td.shoreAudit": "true"})
    check("reject-mode", "record", snapshot, **{"td.terrainSnapshotId": "invalid"})
    check("reject-mode", "record", snapshot)
    request = snapshot / "request.json"
    for content in ['{}', '{"schema":1,"fingerprint":"' + "b" * 64 + '"}',
                    '{"schema":1,"fingerprint":"' + "a" * 64 + '","fingerprint":"' + "a" * 64 + '"}',
                    '{"schema":1,"fingerprint":"' + "a" * 64 + '"} trailing']:
        request.write_text(content, encoding="utf-8")
        check("reject-mode", "record", snapshot)
    request.write_text(json.dumps({"schema": 1, "fingerprint": "a" * 64, "files": {}, "exclusions": []}), encoding="utf-8")
    check("record", "record", snapshot)
    before = {p.name: p.read_bytes() for p in snapshot.glob("*.tile")}
    assert len(before) == 2
    check("mismatch", "record", snapshot)
    assert before == {p.name: p.read_bytes() for p in snapshot.glob("*.tile")}
    check("replay", "replay", snapshot)
    tile = snapshot / "-123_1_-2_3_0_5.tile"
    tile.write_bytes(b"corrupt")
    check("miss", "replay", snapshot)
    tile.unlink()
    check("miss", "replay", snapshot)
print("PASS terrain replay codec/store and both hook source signatures; native mixin weaving requires integration probe")
