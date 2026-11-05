"""Run V2 geology contracts and prove the legacy constructor stayed byte-stable."""
from __future__ import annotations
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/geology/GeologyModel.java"
TEST = ROOT / "tools/geology/GeologyV2Test.java"
EXPECTED_LEGACY_SHA256 = "fa7460947076a1bae328e629eb1a6f1919f67ba4b17f6094ddc4ae4a494abf2c"

def jdk() -> Path:
    for home in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21", r"C:\Program Files\Android\Android Studio\jbr"):
        if home and (Path(home) / "bin/javac.exe").is_file(): return Path(home)
    raise SystemExit("JDK 21 with javac is required")

def run(command: list[str]) -> str:
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, creationflags=flags)
    print(result.stdout, end=""); print(result.stderr, end="")
    result.check_returncode()
    return result.stdout

def main() -> None:
    home = jdk(); output = ROOT / "build/geology-v2-test"; output.mkdir(parents=True, exist_ok=True)
    current = output / "current"; current.mkdir(exist_ok=True)
    run([str(home / "bin/javac.exe"), "--release", "21", "-d", str(current), str(SOURCE), str(TEST)])
    after = run([str(home / "bin/java.exe"), "-ea", "-cp", str(current), "GeologyV2Test"])
    actual = next(line for line in after.splitlines() if line.startswith("legacy-sha256="))
    if actual != "legacy-sha256=" + EXPECTED_LEGACY_SHA256:
        raise AssertionError(f"legacy profile drifted: {actual}")
    print("Geology V2 legacy parity: PASS")

if __name__ == "__main__": main()
