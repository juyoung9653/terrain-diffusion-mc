"""Static and handler-level regression for Streams Reflowing 2.17.1 aquatic flora gating."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import unittest

from test_2171_adapter import find_jdk

ROOT = Path(__file__).resolve().parents[2]
MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsSeagrassFeature2171Mixin.java"
OUT = ROOT / "build/streams-2171-review/seagrass-test"
TARGET = "dev.streamsreflowing.worldgen.AllRightsReservedNiceJohn_004"
PLACE_COLUMN = ("(Lnet/minecraft/world/level/WorldGenLevel;"
                "Lnet/minecraft/util/RandomSource;"
                "Lnet/minecraft/core/BlockPos$MutableBlockPos;IIII)Z")
TALL = ("Lnet/minecraft/world/level/block/DoublePlantBlock;placeAt("
        "Lnet/minecraft/world/level/LevelAccessor;"
        "Lnet/minecraft/world/level/block/state/BlockState;"
        "Lnet/minecraft/core/BlockPos;I)V")
SHORT = ("Lnet/minecraft/world/level/WorldGenLevel;setBlock("
         "Lnet/minecraft/core/BlockPos;"
         "Lnet/minecraft/world/level/block/state/BlockState;I)Z")

STUBS = {
    "org/spongepowered/asm/mixin/Mixin.java": "package org.spongepowered.asm.mixin; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Mixin { String[] targets(); boolean remap() default true; }",
    "org/spongepowered/asm/mixin/Pseudo.java": "package org.spongepowered.asm.mixin; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Pseudo {}",
    "org/spongepowered/asm/mixin/injection/At.java": "package org.spongepowered.asm.mixin.injection; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.ANNOTATION_TYPE}) public @interface At { String value(); String target() default \"\"; }",
    "com/llamalad7/mixinextras/injector/wrapoperation/Operation.java": "package com.llamalad7.mixinextras.injector.wrapoperation; public interface Operation<T> { T call(Object... args); }",
    "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.java": "package com.llamalad7.mixinextras.injector.wrapoperation; public @interface WrapOperation { String method(); org.spongepowered.asm.mixin.injection.At at(); int require() default 0; int expect() default 0; boolean remap() default true; }",
    "net/minecraft/core/BlockPos.java": "package net.minecraft.core; public class BlockPos {}",
    "net/minecraft/world/level/LevelAccessor.java": "package net.minecraft.world.level; public class LevelAccessor {}",
    "net/minecraft/world/level/WorldGenLevel.java": "package net.minecraft.world.level; public class WorldGenLevel extends LevelAccessor {}",
    "net/minecraft/world/level/block/state/BlockState.java": "package net.minecraft.world.level.block.state; public class BlockState {}",
    "com/github/xandergos/terraindiffusionmc/compat/ecology/SubmergedPlantPlacementGuard.java": "package com.github.xandergos.terraindiffusionmc.compat.ecology; import net.minecraft.world.level.WorldGenLevel; import net.minecraft.core.BlockPos; public final class SubmergedPlantPlacementGuard { public static boolean allow; public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos) { return allow; } public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos, boolean tall) { if (!tall) throw new AssertionError(\"tall writer must identify its form\"); return allow; } }",
    "SeagrassHarness.java": r'''import java.lang.reflect.*; import net.minecraft.core.*; import net.minecraft.world.level.*; import net.minecraft.world.level.block.state.*; import com.llamalad7.mixinextras.injector.wrapoperation.*; import com.github.xandergos.terraindiffusionmc.compat.ecology.*;
public class SeagrassHarness {
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 static final class Op<T> implements Operation<T>{int calls; final T value; Op(T x){value=x;} public T call(Object... a){calls++; return value;}}
 static Method tall,shorty; static {try{Class<?> c=Class.forName("com.github.xandergos.terraindiffusionmc.mixin.StreamsSeagrassFeature2171Mixin"); tall=c.getDeclaredMethod("terrainDiffusion$limitTallSeagrass",LevelAccessor.class,BlockState.class,BlockPos.class,int.class,Operation.class); shorty=c.getDeclaredMethod("terrainDiffusion$limitShortSeagrass",WorldGenLevel.class,BlockPos.class,BlockState.class,int.class,Operation.class); tall.setAccessible(true);shorty.setAccessible(true);}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] x)throws Exception { WorldGenLevel world=new WorldGenLevel(); BlockPos pos=new BlockPos(); BlockState plant=new BlockState(); Op<Void> top=new Op<>(null); SubmergedPlantPlacementGuard.allow=false; tall.invoke(null,world,plant,pos,2,top);ok(top.calls==0,"denied tall writes"); SubmergedPlantPlacementGuard.allow=true;tall.invoke(null,world,plant,pos,2,top);ok(top.calls==1,"allowed tall omitted"); Op<Void> foreign=new Op<>(null);SubmergedPlantPlacementGuard.allow=false;tall.invoke(null,new LevelAccessor(),plant,pos,2,foreign);ok(foreign.calls==1,"non-worldgen tall changed"); Op<Boolean> shortOp=new Op<>(true);Boolean denied=(Boolean)shorty.invoke(null,world,pos,plant,2,shortOp);ok(!denied&&shortOp.calls==0,"denied short writes");SubmergedPlantPlacementGuard.allow=true;Boolean allowed=(Boolean)shorty.invoke(null,world,pos,plant,2,shortOp);ok(allowed&&shortOp.calls==1,"allowed short omitted");System.out.println("Seagrass2171Harness: PASS short/tall gate and non-worldgen pass-through");}}
''',
}


def javap(jdk: Path, jar: Path) -> str:
    return subprocess.run([str(jdk / "bin/javap.exe"), "-p", "-s", "-c", "-classpath", str(jar), TARGET],
                          cwd=ROOT, text=True, capture_output=True, check=True,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout


def method_for_descriptor(text: str, descriptor: str) -> str:
    marker = "descriptor: " + descriptor
    index = text.index(marker)
    start = max(text.rfind("\n  private ", 0, index), text.rfind("\n  public ", 0, index))
    end = min((point for point in (text.find("\n  private ", index + len(marker)),
                                    text.find("\n  public ", index + len(marker)),
                                    text.find("\n  static ", index + len(marker))) if point >= 0),
              default=len(text))
    return text[start:end]


class SeagrassStaticContractTest(unittest.TestCase):
    jar: Path
    jdk: Path

    def test_direct_2171_writer_and_mixin_contract(self) -> None:
        method = method_for_descriptor(javap(self.jdk, self.jar), PLACE_COLUMN)
        self.assertEqual(1, method.count("Blocks.TALL_SEAGRASS"))
        self.assertEqual(1, method.count("Blocks.SEAGRASS"))
        self.assertEqual(1, method.count("DoublePlantBlock.placeAt"))
        self.assertEqual(1, method.count("WorldGenLevel.setBlock"))
        source = MIXIN.read_text(encoding="utf-8-sig")
        self.assertIn('targets = "' + TARGET + '"', source)
        self.assertIn("Lnet/minecraft/core/BlockPos$MutableBlockPos;IIII)Z", source)
        self.assertIn("Lnet/minecraft/util/RandomSource;", source)
        self.assertIn("DoublePlantBlock;placeAt(", source)
        self.assertIn("Lnet/minecraft/world/level/LevelAccessor;", source)
        self.assertIn("Lnet/minecraft/world/level/WorldGenLevel;setBlock(", source)
        self.assertIn("Lnet/minecraft/world/level/block/state/BlockState;I)Z", source)
        self.assertEqual(2, source.count("SubmergedPlantPlacementGuard.allowsPlacement"))


def compile_and_run(jdk: Path) -> None:
    source = OUT / "src"
    source.mkdir(parents=True, exist_ok=True)
    files: list[Path] = []
    for name, contents in STUBS.items():
        target = source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
        files.append(target)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-d", str(OUT), str(MIXIN), *map(str, files)],
                   cwd=ROOT, check=True, creationflags=flags)
    result = subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", str(OUT), "SeagrassHarness"],
                            cwd=ROOT, text=True, capture_output=True, check=True, creationflags=flags)
    if "Seagrass2171Harness: PASS" not in result.stdout:
        raise AssertionError(result.stdout + result.stderr)
    print(result.stdout.strip())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    SeagrassStaticContractTest.jar = args.jar.resolve()
    SeagrassStaticContractTest.jdk = find_jdk(args.java_home)
    unittest.main(argv=[sys.argv[0]], exit=False)
    compile_and_run(SeagrassStaticContractTest.jdk)


if __name__ == "__main__":
    main()


