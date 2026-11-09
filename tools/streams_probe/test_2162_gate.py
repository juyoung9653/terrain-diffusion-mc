"""JDK-only version-gate regression for Streams compatibility Mixins."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2162-review" / "gate-test"
PLUGIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsStructureShieldMixinPlugin.java"

STUBS = {
    "net/neoforged/fml/loading/FMLLoader.java": '''package net.neoforged.fml.loading;
import java.util.*; public final class FMLLoader { private static List<Mod> mods=List.of();
 public static Loading getLoadingModList(){return new Loading(mods);} public static void set(String... v){
 List<Mod> x=new ArrayList<>(); for(String s:v){String[] p=s.split("@",2);x.add(new Mod(p[0],p.length==2?p[1]:""));}mods=x;}
 public record Loading(List<Mod> mods){public List<Mod> getMods(){return mods;}}
 public record Mod(String modId,String version){public String getModId(){return modId;} public Object getVersion(){return version;}} }''',
    "org/objectweb/asm/tree/ClassNode.java": "package org.objectweb.asm.tree; public class ClassNode {}",
    "org/spongepowered/asm/mixin/extensibility/IMixinInfo.java": "package org.spongepowered.asm.mixin.extensibility; public interface IMixinInfo {}",
    "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin.java": '''package org.spongepowered.asm.mixin.extensibility;
import java.util.Set; import java.util.List; import org.objectweb.asm.tree.ClassNode;
public interface IMixinConfigPlugin {void onLoad(String s);String getRefMapperConfig();boolean shouldApplyMixin(String a,String b);void acceptTargets(Set<String>a,Set<String>b);List<String>getMixins();void preApply(String a,ClassNode b,String c,IMixinInfo d);void postApply(String a,ClassNode b,String c,IMixinInfo d);}''',
    "GateHarness.java": '''import net.neoforged.fml.loading.FMLLoader; import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStructureShieldMixinPlugin;
public class GateHarness { static final String P="com.github.xandergos.terraindiffusionmc.mixin."; static StreamsStructureShieldMixinPlugin g=new StreamsStructureShieldMixinPlugin();
 static void ok(boolean b,String s){if(!b)throw new AssertionError(s);} static boolean a(String n){return g.shouldApplyMixin("",P+n);}
 static void v(String... x){FMLLoader.set(x);} public static void main(String[] z){
 for(String version:new String[]{"2.13.1","2.13.5","2.13.8","2.15.2","2.16.2","2.16.3"}) {
 v("streamsreflowing@"+version);ok(StreamsStructureShieldMixinPlugin.hasPlanformAdapter()==version.equals("2.16.2"),"planform version gate "+version); }
 v();ok(!StreamsStructureShieldMixinPlugin.hasPlanformAdapter(),"no planform without Streams");
 v("streamsreflowing@2.16.2"); for(String n:new String[]{"StreamsStartup2162Mixin","StreamsWorldgenScale2162Mixin","StreamsNaturalRiver2162Mixin","StreamsNaturalNetwork2162Mixin","StreamsBankMaterial2162Mixin","StreamsSeagrassFeature2162Mixin"})ok(a(n),"2162 "+n);
 for(String n:new String[]{"StreamsWorldgenPrefetchMixin","StreamsDemandPriorityMixin","StreamsSamplerPriorityMixin","StreamsBankMaterial2152Mixin","StreamsShallowBanks2152Mixin","StreamsStartupMixin","StreamsStructureShieldMixin","StreamsWorldgenScaleMixin","StreamsNaturalRiverMixin","StreamsNaturalNetworkMixin","StreamsRiverBanksMixin","StreamsShallowBanksMixin","StreamsRiverRasterMixin","StreamsPlayerPrefetchMixin","StreamsLakeLevelsMixin","StreamsBankMaterialMixin","StreamsBankMaterial2138Mixin"})ok(!a(n),"2162 blocks old "+n);
 for(String n:new String[]{"StreamsShortRiverTunnel2162Mixin","ShortRiverTunnelWorldGenRegionMixin","StreamsShallowBed2162Mixin"}){
   v("streamsreflowing@2.16.2");ok(a(n),"2162 tunnel "+n);
   for(String version:new String[]{"2.13.8","2.15.2","2.16.3"}){v("streamsreflowing@"+version);ok(!a(n),"unsupported tunnel "+version);}
   v();ok(!a(n),"no streams tunnel "+n);
 }
 v("streamsreflowing@2.15.2"); ok(a("StreamsWorldgenPrefetchMixin"),"2152 legacy hook");ok(a("StreamsBankMaterial2152Mixin"),"2152 bank hook");ok(!a("StreamsStartup2162Mixin"),"2152 blocks new");
 v("streamsreflowing@2.14.9");ok(!a("StreamsStartup2162Mixin"),"unknown blocks new");ok(!a("StreamsWorldgenPrefetchMixin"),"unknown blocks old");
 v();ok(!a("StreamsStartup2162Mixin"),"no streams blocks new"); v("streamsreflowing@2.16.2","terrain_slabs@3.1.2");ok(a("TerrainSlabRiverFeatureMixin"),"2162 slab guard"); v("streamsreflowing@2.16.2","terrain_slabs@3.1.1");ok(!a("TerrainSlabRiverFeatureMixin"),"wrong slab version"); System.out.println("GateHarness: PASS"); }}''',
}

def main() -> None:
    jdk = next((Path(path) for path in (r"C:\Program Files\Android\Android Studio\jbr", r"C:\Program Files\Java\jdk-21") if Path(path, "bin", "javac.exe").is_file()), None)
    if jdk is None: raise SystemExit("JDK 21 required")
    src = OUT / "src"; src.mkdir(parents=True, exist_ok=True)
    paths=[]
    for name,text in STUBS.items():
        target=src/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(text,encoding="utf-8"); paths.append(target)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-d", str(OUT), str(PLUGIN), *map(str,paths)],cwd=ROOT,check=True)
    subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", str(OUT), "GateHarness"],cwd=ROOT,check=True)

if __name__ == "__main__": main()
