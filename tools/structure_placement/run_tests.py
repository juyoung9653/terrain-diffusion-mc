"""Compile and run the production pure geometry without starting Minecraft."""
from pathlib import Path

try:
    from .java_test_support import compile_and_run
except ImportError:  # Direct script execution has no package context.
    from java_test_support import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/PlacementGeometry.java"
compile_and_run(ROOT / "build" / "structure-placement-tests",
                [SOURCE, Path(__file__).with_name("PlacementGeometryTest.java")],
                "PlacementGeometryTest", encoding=False)
