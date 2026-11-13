"""Compile the production village seed geometry without a Minecraft process."""
from pathlib import Path

try:
    from .java_test_support import compile_and_run
except ImportError:  # Direct script execution has no package context.
    from java_test_support import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure"
compile_and_run(ROOT / "build" / "village-distribution-tests",
                [SOURCE / "PlacementGeometry.java", SOURCE / "VillageDistributionGeometry.java",
                 Path(__file__).with_name("VillageDistributionTest.java")],
                "VillageDistributionTest")
