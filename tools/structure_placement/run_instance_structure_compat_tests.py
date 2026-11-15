"""Compile the TD-local installed-structure compatibility contracts without Gradle."""
from pathlib import Path
import os, subprocess

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/"build"/"instance-structure-compat-tests"
HELPER=ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/InstanceStructureCompatibility.java"
HARNESS=Path(__file__).with_name("InstanceStructureCompatibilityTest.java")

STUBS={
"com/github/xandergos/terraindiffusionmc/world/TerrainDiffusionBiomeSources.java":"package com.github.xandergos.terraindiffusionmc.world; import net.minecraft.world.level.biome.BiomeSource; public final class TerrainDiffusionBiomeSources { public static boolean isTerrainDiffusion(BiomeSource s){return s.td;} }",
"net/minecraft/resources/ResourceLocation.java":"package net.minecraft.resources; public record ResourceLocation(String namespace,String path){ public static ResourceLocation fromNamespaceAndPath(String n,String p){return new ResourceLocation(n,p);} public static ResourceLocation parse(String v){String[] p=v.split(\":\",2);return new ResourceLocation(p[0],p[1]);} public String toString(){return namespace+\":\"+path;} }",
"net/minecraft/resources/ResourceKey.java":"package net.minecraft.resources; public record ResourceKey<T>(Object registry,ResourceLocation location){public static <T> ResourceKey<T> create(Object r,ResourceLocation l){return new ResourceKey<>(r,l);}}",
"net/minecraft/core/registries/Registries.java":"package net.minecraft.core.registries; public final class Registries {public static final Object BIOME=new Object();}",
"net/minecraft/core/Holder.java":"package net.minecraft.core; import java.util.*; import net.minecraft.resources.ResourceKey; public class Holder<T>{private final T value;private final ResourceKey<T> key;public Holder(T v,ResourceKey<T> k){value=v;key=k;}public T value(){return value;}public Optional<ResourceKey<T>> unwrapKey(){return Optional.ofNullable(key);}public boolean is(ResourceKey<?> x){return x.equals(key);}}",
"net/minecraft/world/level/biome/Biome.java":"package net.minecraft.world.level.biome; public record Biome(String id){}",
"net/minecraft/world/level/biome/BiomeSource.java":"package net.minecraft.world.level.biome; import java.util.*; import net.minecraft.core.Holder; public class BiomeSource {public final boolean td;private final List<Holder<Biome>> biomes;public BiomeSource(boolean t,List<Holder<Biome>> b){td=t;biomes=b;}public List<Holder<Biome>> possibleBiomes(){return biomes;}}",
"net/minecraft/world/level/levelgen/structure/Structure.java":"package net.minecraft.world.level.levelgen.structure; public class Structure{}",
"net/minecraft/world/level/levelgen/structure/placement/StructurePlacement.java":"package net.minecraft.world.level.levelgen.structure.placement; public class StructurePlacement{}",
"net/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement.java":"package net.minecraft.world.level.levelgen.structure.placement; public class RandomSpreadStructurePlacement extends StructurePlacement{}",
"net/minecraft/world/level/levelgen/structure/StructureSet.java":"package net.minecraft.world.level.levelgen.structure; import java.util.*; import net.minecraft.core.Holder; import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement; public class StructureSet {private final List<StructureSelectionEntry> entries;private final StructurePlacement placement;public StructureSet(List<StructureSelectionEntry> e,StructurePlacement p){entries=e;placement=p;}public List<StructureSelectionEntry> structures(){return entries;}public StructurePlacement placement(){return placement;}public record StructureSelectionEntry(Holder<Structure> structure){}}"
}

def main():
    OUT.mkdir(parents=True,exist_ok=True); src=OUT/"src"; files=[]
    for name,text in STUBS.items():
        path=src/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(text,encoding="utf-8"); files.append(path)
    java=Path(os.environ.get("JAVA_HOME",r"C:\Program Files\Android\Android Studio\jbr"))/"bin"
    subprocess.run([str(java/"javac.exe"),"--release","21","-d",str(OUT),str(HELPER),str(HARNESS),*map(str,files)],cwd=ROOT,check=True,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    result=subprocess.run([str(java/"java.exe"),"-ea","-cp",str(OUT),"com.github.xandergos.terraindiffusionmc.world.structure.InstanceStructureCompatibilityTest"],cwd=ROOT,text=True,capture_output=True,check=True,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    runtime=(ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/StructurePlacementRuntime.java").read_text(encoding="utf-8")
    mixin=(ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/ChunkGeneratorStructureStateMixin.java").read_text(encoding="utf-8")
    reservations=(ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/StructureReservations.java").read_text(encoding="utf-8")
    marker="InstanceStructureCompatibility.validBiomes"
    generate=runtime[runtime.index("public static StructureStart generate"):runtime.index("private record Assembly")]
    assemble=runtime[runtime.index("private static Assembly assemble"):]
    assert generate.index(marker)<generate.index("if (!config.enabled())"),"eligibility must precede config bypass"
    assert assemble.index(marker)<assemble.index("for (int attemptIndex="),"preflight assembly must receive mapped eligibility"
    galleon="net.mehvahdjukaar.supplementaries.common.worldgen.RandomSpreadStructurePlacementWithExclusion"
    assert galleon in reservations,"installed galleon placement must be an exact supported subclass"
    assert "isNativeOnlyStructure(id)" not in generate,"galleon must use TD planner"
    assert "InstanceStructureCompatibility.isNativeOnlySet" not in reservations,"galleon set must retain TD arbitration"
    assert "placement instanceof RandomSpreadStructurePlacement" in reservations,"unknown subclasses must not be implicitly accepted"
    assert mixin.index("InstanceStructureCompatibility.retainsSet")<mixin.index("InstanceStructureCompatibility.addPlacements"),"structure-state hook order changed"
    locate=(ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/FastLocateCommand.java").read_text(encoding="utf-8")
    assert "InstanceStructureCompatibility.addEligibleBiomes" in locate,"fast locate must use TD mapped eligibility"
    print(result.stdout.strip()); print("InstanceStructureCompatibilityIntegrationTest: PASS all TD eligibility gates")

if __name__=="__main__": main()
