"""Exercise production height transforms without starting Minecraft or loading ONNX."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]

def main():
    output = ROOT / "build/ocean-depth-test"
    output.mkdir(parents=True, exist_ok=True)
    home = next(Path(p) for p in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21")
                if p and (Path(p) / "bin/javac.exe").is_file())
    stubs = {
        "WorldScaleManager.java": "package com.github.xandergos.terraindiffusionmc.world; public final class WorldScaleManager { public static int getCurrentScale(){return 2;} public static int clampScale(int s){return Math.max(1,Math.min(6,s));} }",
        "WorldPipelineModelConfig.java": "package com.github.xandergos.terraindiffusionmc.pipeline; public final class WorldPipelineModelConfig { public static float nativeResolution(){return 30;} }",
    }
    for name, source in stubs.items():
        (output / name).write_text(source, encoding="utf-8")
    production = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world"
    sources = [production / "OceanDepthProfile.java", production / "HeightConverter.java",
               production / "DeepOceanFluidPolicy.java", ROOT / "tools/geology/DeepOceanFluidPolicyTest.java",
               ROOT / "tools/geology/OceanDepthProfileTest.java", *(output / n for n in stubs)]
    commands = [[str(home / "bin/javac.exe"), "--release", "21", "-d", str(output), *map(str, sources)],
                [str(home / "bin/java.exe"), "-ea", "-cp", str(output), "OceanDepthProfileTest"],
                [str(home / "bin/java.exe"), "-ea", "-cp", str(output), "DeepOceanFluidPolicyTest"]]
    for command in commands:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        print(result.stdout, end=""); print(result.stderr, end="")
        result.check_returncode()

if __name__ == "__main__":
    main()
