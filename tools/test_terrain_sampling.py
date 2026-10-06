"""Run the real provider/cache/sampling code against a deterministic, non-native pipeline fixture."""
from pathlib import Path
import argparse
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com/github/xandergos/terraindiffusionmc"
STUBS = {
"org/slf4j/Logger.java": "package org.slf4j; public interface Logger { default void info(String s,Object... a){} default void debug(String s,Object... a){} }",
"org/slf4j/LoggerFactory.java": "package org.slf4j; public final class LoggerFactory { public static Logger getLogger(Class<?> c){return new Logger(){};} }",
PACKAGE+"/world/WorldScaleManager.java": "package com.github.xandergos.terraindiffusionmc.world; public final class WorldScaleManager { public static int scale=1; public static int getCurrentScale(){return scale;} public static int clampScale(int s){return Math.max(1,Math.min(6,s));} }",
PACKAGE+"/config/TerrainDiffusionConfig.java": "package com.github.xandergos.terraindiffusionmc.config; public final class TerrainDiffusionConfig { public static int tileSize(){return 256;} }",
PACKAGE+"/infinitetensor/FloatTensor.java": "package com.github.xandergos.terraindiffusionmc.infinitetensor; public final class FloatTensor {}",
PACKAGE+"/pipeline/WorldPipelineModelConfig.java": "package com.github.xandergos.terraindiffusionmc.pipeline; final class WorldPipelineModelConfig { static float nativeResolution(){return 16;} static int latentCompression(){return 8;} }",
PACKAGE+"/pipeline/OnnxModel.java": "package com.github.xandergos.terraindiffusionmc.pipeline; final class OnnxModel { static String getResolvedInferenceProvider(){return \"fixture\";} }",
PACKAGE+"/pipeline/PipelineModels.java": "package com.github.xandergos.terraindiffusionmc.pipeline; final class PipelineModels { static void awaitLoad(){} static void stopLoadingAndAwait(){} static PipelineModels getInstance(){return new PipelineModels();} }",
PACKAGE+"/pipeline/CoarseBiomeHints.java": "package com.github.xandergos.terraindiffusionmc.pipeline; import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor; final class CoarseBiomeHints { CoarseBiomeHints(FloatTensor d,int x,int z,int w,int h,int b,float m){} }",
PACKAGE+"/pipeline/WorldPipeline.java": r'''package com.github.xandergos.terraindiffusionmc.pipeline;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
final class WorldPipeline {
    static final AtomicInteger calls = new AtomicInteger();
    static volatile CountDownLatch entered, release;
    private long seed;
    WorldPipeline(long seed, PipelineModels models) { this.seed = seed; }
    void setSeed(long seed) { this.seed = seed; }
    long getTotalComputedWindowCount() { return calls.get(); }
    FloatTensor getCoarseSlice(int a,int b,int c,int d){return new FloatTensor();}
    float[][] get(int i,int j,int ei,int ej,boolean climate) {
        calls.incrementAndGet();
        CountDownLatch signal=entered, gate=release;
        if(signal!=null){signal.countDown();try { gate.await(); } catch(InterruptedException e){throw new AssertionError(e);}}
        int h=ei-i,w=ej-j,n=h*w;
        float[] elevation=new float[n], cl=climate?new float[5*n]:null;
        for(int r=0;r<h;r++) for(int c=0;c<w;c++){
            int k=r*w+c; elevation[k]=(float)(Math.sin((i+r)*.031)*450+Math.cos((j+c)*.047)*280 + (i % 7) * .13 + (j % 11) * .17 + h * .019 + w * .021 + seed * 10);
            if(climate){cl[k]=15;cl[n+k]=7;cl[2*n+k]=650;cl[3*n+k]=.3f;}
        }
        return new float[][]{elevation,cl};
    }
    BroadEcologyClimate getBroadEcologyClimate(int i,int j,int ei,int ej,int scale){
        int stride=256, radius=BroadEcologyClimate.smoothingRadius(scale,stride);
        int r0=BroadEcologyClimate.floorCoarse(i,scale,stride)-radius;
        int c0=BroadEcologyClimate.floorCoarse(j,scale,stride)-radius;
        int h=BroadEcologyClimate.floorCoarse(ei-1,scale,stride)+2+radius-r0;
        int w=BroadEcologyClimate.floorCoarse(ej-1,scale,stride)+2+radius-c0;
        float[] e=new float[h*w],t=new float[h*w],p=new float[h*w],v=new float[h*w];
        java.util.Arrays.fill(e,12);java.util.Arrays.fill(t,16);java.util.Arrays.fill(p,650);java.util.Arrays.fill(v,.3f);
        return new BroadEcologyClimate(e,t,p,v,r0,c0,h,w,i,j,ei,ej,scale,stride);
    }
}
''',
}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--java-home",type=Path,default=Path("C:/Program Files/Java/jdk-21"))
    parser.add_argument("--benchmark",action="store_true",help="Also compare warmed provider lookup with recent-tile cache disabled/enabled in separate JVMs")
    args=parser.parse_args()
    target=ROOT/"build/chunk-performance/provider-tests"
    generated=target/"fixture-src"
    sources=[]
    for name,text in STUBS.items():
        path=generated/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding="utf-8");sources.append(path)
    for name in ("LocalTerrainProvider","InferenceExecutor","ExactTerrainSampling","EcologyData","FastNoiseLite","LaplacianUtils",
                 "BiomeClassifier","TerralithBiomeClassifier","MacroBiomeContext","BroadEcologyClimate","EcologyClassifier",
                 "ShoreClassifier","ShoreContext"):
        sources.append(ROOT/"common/src/main/java"/PACKAGE/"pipeline"/(name+".java"))
    sources.append(ROOT/"tools/terrain_sampling/LocalTerrainProviderCacheTest.java")
    for name in ("MacroBiomeContextTest","ExactTerrainSamplingTest","InferenceExecutorTest"):
        sources.append(ROOT/"common/src/test/java"/PACKAGE/"pipeline"/(name+".java"))
    subprocess.run([str(args.java_home/"bin/javac.exe"),"--release","21","-encoding","UTF-8","-d",str(target),*map(str,sources)],check=True)
    for name in ("InferenceExecutorTest","ExactTerrainSamplingTest","MacroBiomeContextTest","LocalTerrainProviderCacheTest"):
        subprocess.run([str(args.java_home/"bin/java.exe"),"-cp",os.pathsep.join((str(target),str(ROOT/"common/src/main/resources"))),"com.github.xandergos.terraindiffusionmc.pipeline."+name],check=True)
    if args.benchmark:
        for enabled in ("false", "true"):
            subprocess.run([str(args.java_home/"bin/java.exe"),"-Dtd.inference.recentTileCache="+enabled,
                            "-cp",os.pathsep.join((str(target),str(ROOT/"common/src/main/resources"))),
                            "com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProviderCacheTest","benchmark"],check=True)

if __name__=="__main__":main()
