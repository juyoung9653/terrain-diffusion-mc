"""Execute the optional Fast Noise mixin version gate without starting Minecraft."""
from pathlib import Path
import subprocess

from test_2162_gate import PLUGIN, ROOT, STUBS
from test_2171_gate import find_jdk, PROCESS_FLAGS

HARNESS = r'''
import net.neoforged.fml.loading.FMLLoader;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStructureShieldMixinPlugin;
public class FastNoiseGateTest {
  public static void main(String[] args) {
    var gate = new StreamsStructureShieldMixinPlugin();
    String mixin = "com.github.xandergos.terraindiffusionmc.mixin.FastNoiseBiomePaletteMixin";
    String[] versions = {"1.0.12+1.21.1+neoforge", "1.0.13", "1.0.13+1.21.1+fabric",
      "1.0.13+1.21.1+neoforge", "1.0.14+1.21.1+neoforge"};
    FMLLoader.set();
    if (gate.shouldApplyMixin("", mixin)) throw new AssertionError("Missing mod admitted");
    for (String version : versions) {
      FMLLoader.set("zfastnoise@" + version);
      if (gate.shouldApplyMixin("", mixin) != version.equals("1.0.13+1.21.1+neoforge"))
        throw new AssertionError("Unexpected version admitted: " + version);
    }
    FMLLoader.set("streamsreflowing@2.17.3");
    if (gate.shouldApplyMixin("", mixin)) throw new AssertionError("Wrong mod admitted");
    FMLLoader.set("streamsreflowing@2.17.3", "zfastnoise@1.0.13+1.21.1+neoforge");
    if (!gate.shouldApplyMixin("", mixin)) throw new AssertionError("Audited full pack rejected");
    System.out.println("FASTNOISE-GATE PASS cases=8");
  }
}
'''


def main():
    out = ROOT / "build/fastnoise-gate"
    out.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, source in {**STUBS, "FastNoiseGateTest.java": HARNESS}.items():
        path = out / "src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        paths.append(path)
    jdk = find_jdk(Path("C:/Program Files/Java/jdk-21"))
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-d", str(out),
                    str(PLUGIN), *map(str, paths)], check=True, creationflags=PROCESS_FLAGS)
    result = subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", str(out), "FastNoiseGateTest"],
                            capture_output=True, text=True, creationflags=PROCESS_FLAGS)
    (out / "result.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode or "FASTNOISE-GATE PASS cases=8" not in result.stdout:
        raise AssertionError(result.stdout + result.stderr)
    print(result.stdout.strip())


if __name__ == "__main__":
    main()
