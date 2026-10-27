"""Compile and execute the production Hybrid Aquatic TD density adapter."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/hybridaquatic"

STUBS = {
    "com.mojang.serialization.MapCodec": """import java.util.function.Supplier; public class MapCodec<T>{public static <T> MapCodec<T> unit(Supplier<T> x){return new MapCodec<>();}}""",
    "net.minecraft.util.KeyDispatchDataCodec": """import com.mojang.serialization.MapCodec; public class KeyDispatchDataCodec<T>{public static <T> KeyDispatchDataCodec<T> of(MapCodec<T> x){return new KeyDispatchDataCodec<>();}}""",
    "net.minecraft.world.level.levelgen.DensityFunction": """import net.minecraft.util.KeyDispatchDataCodec; public interface DensityFunction { interface FunctionContext {int blockX();int blockY();int blockZ();} interface ContextProvider {FunctionContext forIndex(int i);} interface Visitor {DensityFunction apply(DensityFunction x);} double compute(FunctionContext c);void fillArray(double[] a,ContextProvider p);DensityFunction mapAll(Visitor v);double minValue();double maxValue();KeyDispatchDataCodec<? extends DensityFunction> codec(); }""",
    "com.github.xandergos.terraindiffusionmc.world.HeightConverter": """public final class HeightConverter { public static int convertToMinecraftHeight(short raw) { return raw / 2 + 13; } }""",
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
  LocalTerrainProvider.reset();boolean threw=false;try{d.compute(new P(0,0,0));}catch(IllegalStateException e){threw=true;}ok(threw,"missing tile fails closed");System.out.println("HybridAquaticDensityTest: PASS (conversion, cursor, fill, negative, missing)"); }
}
"""

def main():
    home=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None; ext=".exe" if os.name=="nt" else ""
    javac=str(home/"bin"/("javac"+ext)) if home else shutil.which("javac"); java=str(home/"bin"/("java"+ext)) if home else shutil.which("java")
    if not javac or not java: raise SystemExit("JDK required; set JAVA_HOME")
    scratch=ROOT/".tmp";scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hybrid-aquatic-density-",dir=scratch) as directory:
        out=Path(directory); sources=[str(BASE/"HybridAquaticMarineProfile.java"),str(BASE/"HybridAquaticContinentalnessDensityFunction.java")]
        for name,body in STUBS.items():
            package,_,_=name.rpartition(".");path=out/(name.replace(".","/")+".java");path.parent.mkdir(parents=True,exist_ok=True);path.write_text(f"package {package};\n{body}",encoding="utf-8");sources.append(str(path))
        harness=out/"HybridAquaticDensityTest.java";harness.write_text(HARNESS,encoding="utf-8");sources.append(str(harness))
        subprocess.run([javac,"--release","21","-d",directory,*sources],check=True);subprocess.run([java,"-cp",directory,"HybridAquaticDensityTest"],check=True)
if __name__=="__main__":main()
