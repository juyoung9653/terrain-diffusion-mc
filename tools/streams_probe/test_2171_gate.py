"""JDK-only regression for the native-first Streams Reflowing 2.17.1 gate."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from test_2162_gate import PLUGIN, ROOT, STUBS

OUT = ROOT / "build" / "streams-2171-review" / "gate-test"
PROCESS_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

HARNESS = r'''import net.neoforged.fml.loading.FMLLoader;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStructureShieldMixinPlugin;
public class Gate2171Harness {
  static final String P="com.github.xandergos.terraindiffusionmc.mixin.";
  static final StreamsStructureShieldMixinPlugin G=new StreamsStructureShieldMixinPlugin();
  static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
  static boolean applies(String name){return G.shouldApplyMixin("",P+name);}
  static void versions(String... values){FMLLoader.set(values);}
  static void blocked(String... names){for(String name:names)ok(!applies(name),"2.17.1 blocks "+name);}
  public static void main(String[] ignored) {
    versions("streamsreflowing@2.17.1");
    ok(StreamsStructureShieldMixinPlugin.hasStartupAdapter(),"2.17.1 startup adapter");
    ok(StreamsStructureShieldMixinPlugin.isAuditedVersion("2.17.1"),"2.17.1 V14 audited");
    ok(!StreamsStructureShieldMixinPlugin.hasPlanformAdapter(),"2.17.1 no TD planform");
    ok(applies("StreamsStartup2171Mixin"),"2.17.1 startup");
    ok(applies("StreamsInference2171Mixin"),"2.17.1 inference binding");
    ok(applies("StreamsLandGrass2171Mixin"),"2.17.1 native land-grass policy");
    ok(applies("StreamsNaturalNetwork2171Mixin"),"2.17.1 native network refinement");
    ok(applies("StreamsSeagrassFeature2171Mixin"),"2.17.1 direct seagrass guard");
    blocked("StreamsStartup2162Mixin","StreamsWorldgenScale2162Mixin",
      "StreamsNaturalRiver2162Mixin","StreamsNaturalNetwork2162Mixin",
      "StreamsBankMaterial2162Mixin","StreamsSeagrassFeature2162Mixin",
      "StreamsShallowBed2162Mixin","StreamsShortRiverTunnel2162Mixin",
      "ShortRiverTunnelWorldGenRegionMixin","StreamsStructureShieldMixin",
      "StreamsWorldgenPrefetchMixin","StreamsStartupMixin",
      "StreamsWorldgenScaleMixin","StreamsNaturalRiverMixin","StreamsNaturalNetworkMixin",
      "StreamsRiverBanksMixin","StreamsShallowBanksMixin","StreamsShallowBanks2152Mixin",
      "StreamsRiverRasterMixin","StreamsPlayerPrefetchMixin",
      "StreamsLakeLevelsMixin","StreamsBankMaterialMixin","StreamsBankMaterial2138Mixin",
      "StreamsBankMaterial2152Mixin","StreamsDemandPriorityMixin","StreamsSamplerPriorityMixin",
      "StreamsSeagrassFeatureMixin");
    ok(applies("StreamsStartupLifecycleMixin"),"2.17.1 startup state validation");
    ok(applies("StreamsNaturalLifecycleMixin"),"2.17.1 saved-state validation");
    ok(!applies("TerrainSlabRiverFeatureMixin"),"2.17.1 slab needs Terrain Slabs 3.1.2");
    versions("streamsreflowing@2.17.1","terrain_slabs@3.1.2");
    ok(applies("TerrainSlabRiverFeatureMixin"),"2.17.1 qualified slab protection");
    versions("streamsreflowing@2.17.2","terrain_slabs@3.1.2");
    ok(!StreamsStructureShieldMixinPlugin.hasStartupAdapter(),"2.17.2 startup denied");
    ok(!StreamsStructureShieldMixinPlugin.isAuditedVersion("2.17.2"),"2.17.2 audit denied");
    ok(!applies("StreamsStartup2171Mixin"),"2.17.2 new hook denied");
    ok(!applies("StreamsInference2171Mixin"),"2.17.2 inference denied");
    ok(!applies("StreamsLandGrass2171Mixin"),"2.17.2 land-grass policy denied");
    ok(!applies("StreamsNaturalNetwork2171Mixin"),"2.17.2 network denied");
    ok(!applies("StreamsSeagrassFeature2171Mixin"),"2.17.2 seagrass guard denied");
    ok(!applies("TerrainSlabRiverFeatureMixin"),"2.17.2 slab protection denied");
    versions();
    ok(!StreamsStructureShieldMixinPlugin.hasStartupAdapter(),"no Streams startup denied");
    ok(!applies("StreamsStartup2171Mixin"),"no Streams new hook denied");
    ok(!applies("StreamsLandGrass2171Mixin"),"no Streams land-grass policy denied");
    versions("streamsreflowing@2.16.2");
    ok(StreamsStructureShieldMixinPlugin.hasStartupAdapter(),"2.16.2 startup preserved");
    ok(StreamsStructureShieldMixinPlugin.isAuditedVersion("2.16.2"),"2.16.2 audit preserved");
    ok(StreamsStructureShieldMixinPlugin.hasPlanformAdapter(),"2.16.2 planform preserved");
    ok(applies("StreamsStartup2162Mixin"),"2.16.2 hook preserved");
    ok(!applies("StreamsStartup2171Mixin"),"2.16.2 blocks 2171 hook");
    ok(!applies("StreamsLandGrass2171Mixin"),"2.16.2 blocks 2171 land-grass policy");
    System.out.println("Gate2171Harness: PASS");
  }
}'''


def find_jdk() -> Path:
    for candidate in (Path(r"C:\Program Files\Android\Android Studio\jbr"),
                      Path(r"C:\Program Files\Java\jdk-21")):
        if (candidate / "bin" / "javac.exe").is_file():
            return candidate
    raise SystemExit("JDK 21 required")


def main() -> None:
    jdk = find_jdk()
    src = OUT / "src"
    src.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, text in {**STUBS, "Gate2171Harness.java": HARNESS}.items():
        target = src / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        paths.append(target)
    subprocess.run([str(jdk / "bin" / "javac.exe"), "--release", "21", "-d", str(OUT),
                    str(PLUGIN), *map(str, paths)], cwd=ROOT, check=True,
                   creationflags=PROCESS_FLAGS)
    result = subprocess.run([str(jdk / "bin" / "java.exe"), "-ea", "-cp", str(OUT),
                            "Gate2171Harness"], cwd=ROOT, capture_output=True, text=True,
                            creationflags=PROCESS_FLAGS)
    if result.returncode:
        raise AssertionError(result.stdout + result.stderr)
    if "Gate2171Harness: PASS" not in result.stdout:
        raise AssertionError("gate harness did not emit success: " + result.stdout)
    print(result.stdout.strip())


if __name__ == "__main__":
    main()
