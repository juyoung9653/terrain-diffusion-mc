"""Fail closed when Snow Real Magic changes the weather hook TD replaces."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsStructureShieldMixinPlugin.java"
MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/SnowRealMagicClimateMixin.java"
INSTANCE_MODS = (Path(os.environ.get("APPDATA", ""))
                 / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods")
EXPECTED_DESCRIPTOR = (
    "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
    "Lnet/minecraft/core/Holder;)Z"
)


def installed_jar() -> Path:
    configured = os.environ.get("SNOW_REAL_MAGIC_JAR")
    if configured:
        return Path(configured)
    jars = sorted(INSTANCE_MODS.glob("SnowRealMagic-*.jar"))
    if len(jars) != 1:
        raise AssertionError(f"expected one installed Snow Real Magic JAR, found {jars}")
    return jars[0]


def javap() -> Path:
    for home in (Path(r"C:\Program Files\Java\jdk-21"),
                 Path(r"C:\Program Files\Android\Android Studio\jbr")):
        tool = home / "bin/javap.exe"
        if tool.is_file():
            return tool
    raise AssertionError("JDK 21 javap is required")


def provenance(jar: Path) -> dict[str, str]:
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    version = next(line.removeprefix("Implementation-Version: ")
                   for line in manifest.splitlines()
                   if line.startswith("Implementation-Version: "))
    with jar.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"jar": jar.name, "runtimeVersion": version, "sha256": digest}


class SnowRealMagicCompatibilityTest(unittest.TestCase):
    def test_installed_1225_weather_hook_matches_the_mixin_contract(self):
        jar = installed_jar()
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
            mods = archive.read("META-INF/neoforge.mods.toml").decode("utf-8")
        self.assertIn('modId = "snowrealmagic"', mods)
        self.assertIn("Implementation-Version: 12.2.5+neoforge", manifest)

        result = subprocess.run(
            [str(javap()), "-p", "-s", "-c", "-classpath", str(jar),
             "snownee.snow.util.CommonProxy"],
            cwd=ROOT, text=True, capture_output=True, check=True,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        declaration = "public static boolean coldEnoughToSnow("
        start = result.stdout.index(declaration)
        descriptor = result.stdout.index("descriptor: ", start)
        self.assertLess(descriptor, result.stdout.index("Code:", start))
        self.assertIn(EXPECTED_DESCRIPTOR, result.stdout[descriptor:descriptor + 180])
        # The ordinary path remains the vanilla biome decision. TD's HEAD
        # injection only substitutes that decision in natural TD worlds.
        body = result.stdout[start:result.stdout.index("public static boolean isWinter(", start)]
        self.assertIn("Biome.coldEnoughToSnow:", body)
        self.assertIn("SereneSeasonsCompat.coldEnoughToSnow:", body)

    def test_mixin_and_version_gate_are_explicit_for_the_audited_runtime(self):
        mixin = MIXIN.read_text(encoding="utf-8")
        plugin = PLUGIN.read_text(encoding="utf-8")
        self.assertIn('@Mixin(targets = "snownee.snow.util.CommonProxy", remap = false)', mixin)
        self.assertIn('@Inject(method = "coldEnoughToSnow", at = @At("HEAD"),', mixin)
        self.assertIn("NaturalSnowCover.weatherEligible(reader, pos, false)", mixin)
        self.assertIn('"12.2.5".equals(mod.getVersion().toString())', plugin)
        self.assertIn('"12.2.5+neoforge".equals(mod.getVersion().toString())', plugin)

if __name__ == "__main__":
    print(json.dumps({"snowRealMagicAudit": provenance(installed_jar())},
                     separators=(",", ":")))
    unittest.main()
