"""JDK-only regression for 2.17.1's TD sampler, profile and native-store bindings."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess

from audit_2171 import audit_2171
from test_2171_adapter import find_jdk

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2171-review" / "inference-test"
MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsInference2171Mixin.java"

STUBS = {
    "org/spongepowered/asm/mixin/Mixin.java": "package org.spongepowered.asm.mixin; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Mixin { String[] targets(); boolean remap() default true; }",
    "org/spongepowered/asm/mixin/Pseudo.java": "package org.spongepowered.asm.mixin; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Pseudo {}",
    "org/spongepowered/asm/mixin/injection/At.java": "package org.spongepowered.asm.mixin.injection; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.ANNOTATION_TYPE}) public @interface At { String value(); String target() default \"\"; boolean remap() default true; }",
    "org/spongepowered/asm/mixin/injection/ModifyArgs.java": "package org.spongepowered.asm.mixin.injection; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface ModifyArgs { String method(); At at(); int require() default 0; int expect() default 0; boolean remap() default true; }",
    "org/spongepowered/asm/mixin/injection/invoke/arg/Args.java": "package org.spongepowered.asm.mixin.injection.invoke.arg; public final class Args { private final Object[] values; public Args(Object... x){values=x;} @SuppressWarnings(\"unchecked\") public <T>T get(int n){return (T)values[n];} public void set(int n,Object x){values[n]=x;} public Object at(int n){return values[n];} }",
    "net/minecraft/world/level/WorldGenLevel.java": "package net.minecraft.world.level; public class WorldGenLevel { public Object getLevel(){return this;} }",
    "com/github/xandergos/terraindiffusionmc/pipeline/LocalTerrainProvider.java": "package com.github.xandergos.terraindiffusionmc.pipeline; public final class LocalTerrainProvider { private static final LocalTerrainProvider INSTANCE=new LocalTerrainProvider(); public static LocalTerrainProvider getInstance(){return INSTANCE;} }",
    "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsScalePolicy.java": "package com.github.xandergos.terraindiffusionmc.compat.streams; import net.minecraft.world.level.WorldGenLevel; public final class StreamsScalePolicy { public static boolean enabled; public static int scale=1; public static boolean appliesTo(WorldGenLevel l){return enabled;} public static int activeScale(WorldGenLevel l){return scale;} }",
    "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsInferenceBinding.java": "package com.github.xandergos.terraindiffusionmc.compat.streams; import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider; public final class StreamsInferenceBinding { public record Bound(Object sampler,String contract,LocalTerrainProvider provider){} public static Object bind(Object sampler,String contract,LocalTerrainProvider provider){return new Bound(sampler,contract,provider);} }",
    "com/github/xandergos/terraindiffusionmc/world/river/WorldNaturalRiverState.java": "package com.github.xandergos.terraindiffusionmc.world.river; public final class WorldNaturalRiverState { public static boolean on; public static int profile; public static boolean enabled(Object l){return on;} public static int version(Object l){return profile;} }",
    "com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverProfile.java": "package com.github.xandergos.terraindiffusionmc.world.river; public final class NaturalRiverProfile { public static int watershedPlateV3(int nativePlate,int scale){return Math.max(nativePlate,2048*scale);} }",
    "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalRiverAdapter.java": "package com.github.xandergos.terraindiffusionmc.compat.streams; import java.nio.file.Path; import net.minecraft.world.level.WorldGenLevel; import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState; public final class StreamsNaturalRiverAdapter { public record Config(Object original,int profile){} public static Object configure(WorldGenLevel l,Object c){return WorldNaturalRiverState.on?new Config(c,WorldNaturalRiverState.profile):c;} public static Path storePath(Path p,Object c){return c instanceof Config x?p.resolve(\"td-natural-v\"+x.profile()):p;} }",
    "InferenceHarness.java": r'''import java.lang.reflect.*; import java.nio.file.*; import org.spongepowered.asm.mixin.injection.invoke.arg.Args; import net.minecraft.world.level.WorldGenLevel; import com.github.xandergos.terraindiffusionmc.compat.streams.*; import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsInferenceBinding.Bound; import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter.Config; import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider; import com.github.xandergos.terraindiffusionmc.world.river.*;
public class InferenceHarness { static void ok(boolean x,String m){if(!x)throw new AssertionError(m);} static Method bind,pin,store; static {try{Class<?> c=Class.forName("com.github.xandergos.terraindiffusionmc.mixin.StreamsInference2171Mixin"); bind=c.getDeclaredMethod("terrainDiffusion$bindSamplers",Args.class,WorldGenLevel.class); pin=c.getDeclaredMethod("terrainDiffusion$pinWatershed",Args.class,WorldGenLevel.class); store=c.getDeclaredMethod("terrainDiffusion$versionedStore",Args.class); for(Method m:new Method[]{bind,pin,store})m.setAccessible(true);}catch(Exception e){throw new RuntimeException(e);}} static void call(Method m,Object...a)throws Exception{m.invoke(null,a);} public static void main(String[] x)throws Exception {WorldGenLevel level=new WorldGenLevel(); Object seed=new Object(),nativeConfig=new Object(),terrain=new Object(),wetness=new Object(); StreamsScalePolicy.enabled=true;StreamsScalePolicy.scale=1;WorldNaturalRiverState.on=true;WorldNaturalRiverState.profile=14;Args fresh=new Args(seed,nativeConfig,terrain,wetness);call(bind,fresh,level);ok(fresh.at(0)==seed,"seed changed");ok(fresh.at(1) instanceof Config&&((Config)fresh.at(1)).profile()==14,"fresh V14 config absent");ok(fresh.at(2) instanceof Bound&&fresh.at(3) instanceof Bound,"samplers not bound");Bound tb=(Bound)fresh.at(2),wb=(Bound)fresh.at(3);ok(tb.sampler()==terrain&&wb.sampler()==wetness&&tb.provider()==LocalTerrainProvider.getInstance(),"wrong sampler binding");Path root=Paths.get("cache");Args pinned=new Args(root,Long.valueOf(5),Integer.valueOf(768));call(pin,pinned,level);ok(pinned.at(0).equals(root.resolve("td-natural-v14"))&&((Integer)pinned.at(2))==2048,"fresh domain not pinned");Args stored=new Args(root,Long.valueOf(5),fresh.at(1));call(store,stored);ok(stored.at(0).equals(root.resolve("td-natural-v14")),"store not config namespaced");WorldNaturalRiverState.on=false;Args disabled=new Args(seed,nativeConfig,terrain,wetness);call(bind,disabled,level);ok(disabled.at(1)==nativeConfig&&disabled.at(2) instanceof Bound&&disabled.at(3) instanceof Bound,"disabled native should retain config but bind TD samplers");Args noPin=new Args(root,Long.valueOf(5),Integer.valueOf(768));call(pin,noPin,level);ok(noPin.at(0).equals(root)&&((Integer)noPin.at(2))==768,"disabled native path changed");StreamsScalePolicy.enabled=false;WorldNaturalRiverState.on=true;Args other=new Args(seed,nativeConfig,terrain,wetness);call(bind,other,level);ok(other.at(0)==seed&&other.at(1)==nativeConfig&&other.at(2)==terrain&&other.at(3)==wetness,"non-TD changed");System.out.println("InferenceHarness: PASS V14 config, domain/store pinning, disabled and non-TD pass-through");}}''',
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    jdk, jar = find_jdk(args.java_home), args.jar.resolve()
    receipt = audit_2171(jdk, jar)
    if not receipt["passed"]:
        raise SystemExit("Streams 2.17.1 static audit failed: " + "; ".join(receipt["failures"]))
    source = OUT / "src"; source.mkdir(parents=True, exist_ok=True)
    files=[]
    for name, content in STUBS.items():
        target=source/name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(content,encoding="utf-8"); files.append(target)
    flags=getattr(subprocess,"CREATE_NO_WINDOW",0)
    subprocess.run([str(jdk/"bin/javac.exe"),"--release","21","-d",str(OUT),str(MIXIN),*map(str,files)],cwd=ROOT,check=True,creationflags=flags)
    result=subprocess.run([str(jdk/"bin/java.exe"),"-ea","-cp",str(OUT),"InferenceHarness"],cwd=ROOT,text=True,capture_output=True,check=True,creationflags=flags)
    if "InferenceHarness: PASS" not in result.stdout: raise AssertionError(result.stdout+result.stderr)
    print(result.stdout.strip())
if __name__ == "__main__": main()
