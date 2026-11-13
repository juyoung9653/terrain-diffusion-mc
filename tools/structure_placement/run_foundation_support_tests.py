"""Compile and exercise the bounded natural-floor repair admission policy."""
from pathlib import Path

try:
    from .java_test_support import compile_and_run
except ImportError:  # Direct script execution has no package context.
    from java_test_support import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure"
compile_and_run(ROOT / "build" / "foundation-support-tests",
                [SOURCE / "FoundationSupport.java",
                 Path(__file__).with_name("FoundationSupportTest.java")],
                "FoundationSupportTest")
