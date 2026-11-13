"""Compile and run the dependency-free density telemetry unit checks."""

from pathlib import Path

try:
    from .java_test_support import compile_and_run
except ImportError:  # Direct script execution has no package context.
    from java_test_support import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/structure_placement/probe-java/com/github/xandergos/terraindiffusionmc/diagnostic/DensityProbeTelemetry.java"
compile_and_run(ROOT / "build" / "density-telemetry-tests",
                [SOURCE, Path(__file__).with_name("DensityProbeTelemetryTest.java")],
                "DensityProbeTelemetryTest")
