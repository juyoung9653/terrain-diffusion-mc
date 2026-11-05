"""Compile and execute the production Hybrid Aquatic TD density adapter."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/hybridaquatic"

STUBS = {
    "com.mojang.serialization.MapCodec": """import java.util.function.Function; public class MapCodec<T>{public <O> MapCodec<T> forGetter(Function<O,T> x){return this;}}""",
    "com.mojang.serialization.Codec": """public class Codec<T>{public static Codec<Integer> intRange(int a,int b){return new Codec<>();} public MapCodec<T> optionalFieldOf(String n,T d){return new MapCodec<>();}}""",
    "com.mojang.serialization.codecs.RecordCodecBuilder": """import com.mojang.serialization.MapCodec; import java.util.function.Function; public final class RecordCodecBuilder { public static <O> MapCodec<O> mapCodec(Function<Instance<O>,MapCodec<O>> f){return new MapCodec<>();} public static final class Instance<O>{public <A> Group<O,A> group(MapCodec<A> field){return new Group<>();}} public static final class Group<O,A>{public MapCodec<O> apply(Instance<O> i,Function<A,O> constructor){return new MapCodec<>();}} }""",
    "net.minecraft.util.KeyDispatchDataCodec": """import com.mojang.serialization.MapCodec; public class KeyDispatchDataCodec<T>{public static <T> KeyDispatchDataCodec<T> of(MapCodec<T> x){return new KeyDispatchDataCodec<>();}}""",
    "net.minecraft.world.level.levelgen.DensityFunction": """import net.minecraft.util.KeyDispatchDataCodec; public interface DensityFunction { interface FunctionContext {int blockX();int blockY();int blockZ();} interface ContextProvider {FunctionContext forIndex(int i);} interface Visitor {DensityFunction apply(DensityFunction x);} double compute(FunctionContext c);void fillArray(double[] a,ContextProvider p);DensityFunction mapAll(Visitor v);double minValue();double maxValue();KeyDispatchDataCodec<? extends DensityFunction> codec(); }""",
    "com.github.xandergos.terraindiffusionmc.world.HeightConverter": """public final class HeightConverter { public static int convertToMinecraftHeight(short raw) { return raw / 2 + 13; } public static int convertToMinecraftHeight(short raw,int scale,int profile) { if(profile==1)return convertToMinecraftHeight(raw); return raw>=0?convertToMinecraftHeight(raw):raw / 2 - 77; } }""",
    "com.github.xandergos.terraindiffusionmc.world.WorldScaleManager": """public final class WorldScaleManager { public static int getCurrentScale(){return 1;} }""",
    "com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider": """
public final class LocalTerrainProvider {
 public static final class HeightmapData { public final short[][] heightmap; public final int width,height; public HeightmapData(short[][] v){heightmap=v;height=v.length;width=v[0].length;} }
 public static int tileSize=2, scoped=Integer.MIN_VALUE, fetches; public static HeightmapData[][] tiles=new HeightmapData[5][5]; private static final LocalTerrainProvider INSTANCE=new LocalTerrainProvider();
 public static int scopedGroundHeight(int x,int z){return scoped;} public static int sampleTileSize(){return tileSize;} public static LocalTerrainProvider getInstance(){return INSTANCE;}
 public HeightmapData fetchGroundHeightmap(int z0,int x0,int z1,int x1){fetches++;int ix=Math.floorDiv(x0,tileSize)+2,iz=Math.floorDiv(z0,tileSize)+2;return ix<0||iz<0||ix>=5||iz>=5?null:tiles[iz][ix];}
 public static void reset(){scoped=Integer.MIN_VALUE;fetches=0;tiles=new HeightmapData[5][5];}
 public static void put(int tileX,int tileZ,short raw){short[][] v=new short[tileSize][tileSize];for(int z=0;z<tileSize;z++)java.util.Arrays.fill(v[z],raw);tiles[tileZ+2][tileX+2]=new HeightmapData(v);}
}
""",
}

HARNESS = """
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.*; import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider; import net.minecraft.world.level.levelgen.DensityFunction;
public class HybridAquaticDensityTest {
 record P(int x,int y,int z) implements DensityFunction.FunctionContext {public int blockX(){return x;}public int blockY(){return y;}public int blockZ(){return z;}}
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);} static void eq(double a,double b,String m){ok(Math.abs(a-b)<1e-12,m+" "+a+" != "+b);}
 static DensityFunction.ContextProvider provider(P[] p){return i->p[i];}
 public static void main(String[] args){var d=HybridAquaticContinentalnessDensityFunction.INSTANCE;
  LocalTerrainProvider.reset(); LocalTerrainProvider.put(0,0,(short)100); double fetched=d.compute(new P(0,-64,0)); eq(fetched,HybridAquaticMarineProfile.continentalnessForHeight(63),"fetched raw conversion");
  LocalTerrainProvider.scoped=100; double scoped=d.compute(new P(999,32000,-999)); eq(scoped,fetched,"scoped conversion parity"); ok(LocalTerrainProvider.fetches==1,"scoped avoids provider");
  LocalTerrainProvider.reset(); LocalTerrainProvider.put(0,0,(short)52); double y0=d.compute(new P(0,-64,0)),y1=d.compute(new P(0,319,0)); eq(y0,y1,"Y invariant");
  LocalTerrainProvider.reset(); LocalTerrainProvider.put(0,0,(short)100);LocalTerrainProvider.put(1,0,(short)52); P[] ps={new P(0,0,0),new P(1,8,0),new P(2,16,0),new P(3,24,1)};double[] out=new double[ps.length];d.fillArray(out,provider(ps));for(int i=0;i<ps.length;i++){double expected=HybridAquaticMarineProfile.continentalnessForHeight(i<2?63:39);eq(out[i],expected,"fill parity "+i);}ok(LocalTerrainProvider.fetches==2,"one fetch per positive tile transition");
  LocalTerrainProvider.reset();LocalTerrainProvider.put(-1,-1,(short)100);LocalTerrainProvider.put(-2,-1,(short)52);P[] neg={new P(-1,0,-1),new P(-2,0,-1),new P(-3,0,-1)};out=new double[3];d.fillArray(out,provider(neg));ok(LocalTerrainProvider.fetches==2,"negative tile transitions");
  LocalTerrainProvider.reset();LocalTerrainProvider.put(0,0,(short)-20);double legacy=d.compute(new P(0,0,0));var deep=new HybridAquaticContinentalnessDensityFunction(2);double corrected=deep.compute(new P(0,0,0));eq(legacy,HybridAquaticMarineProfile.continentalnessForHeight(3),"legacy negative conversion parity");eq(corrected,HybridAquaticMarineProfile.continentalnessForHeight(-87),"V2 negative conversion");ok(legacy!=corrected,"V2 must change only its own marine sample");
  LocalTerrainProvider.reset();boolean threw=false;try{d.compute(new P(0,0,0));}catch(IllegalStateException e){threw=true;}ok(threw,"missing tile fails closed");System.out.println("HybridAquaticDensityTest: PASS (conversion, cursor, fill, negative, missing)"); }
}
"""

def main():
    homes=([Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else []) + [
        Path(r"C:\Program Files\Java\jdk-21"), Path(r"C:\Program Files\Android\Android Studio\jbr")]
    home=next((candidate for candidate in homes if (candidate/"bin/javac.exe").is_file()),None)
    if home is None: raise SystemExit("JDK 21 required; set JAVA_HOME")
    javac=str(home/"bin/javac.exe"); java=str(home/"bin/java.exe")
    scratch=ROOT/".tmp";scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hybrid-aquatic-density-",dir=scratch) as directory:
        out=Path(directory); sources=[str(BASE/"HybridAquaticMarineProfile.java"),str(BASE/"HybridAquaticContinentalnessDensityFunction.java")]
        for name,body in STUBS.items():
            package,_,_=name.rpartition(".");path=out/(name.replace(".","/")+".java");path.parent.mkdir(parents=True,exist_ok=True);path.write_text(f"package {package};\n{body}",encoding="utf-8");sources.append(str(path))
        harness=out/"HybridAquaticDensityTest.java";harness.write_text(HARNESS,encoding="utf-8");sources.append(str(harness))
        flags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        for command in ([javac,"--release","21","-d",directory,*sources],
                        [java,"-cp",directory,"HybridAquaticDensityTest"]):
            result=subprocess.run(command,capture_output=True,text=True,creationflags=flags)
            print(result.stdout,end="")
            print(result.stderr,end="",file=sys.stderr)
            result.check_returncode()
if __name__=="__main__":main()
