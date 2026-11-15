package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;

/** Dependency-free behavioral contract for the exact Lush Cave Ruins exception. */
public final class LushCaveRuinsCompatibilityTest {
    private static final ResourceKey<Biome> JUNGLE = ResourceKey.create("biome",
            ResourceLocation.fromNamespaceAndPath("terralith", "cave/underground_jungle"));
    private static Holder<Biome> biome(String id) { return new Holder<>(new Biome(id),
            ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id))); }
    private static Holder<Structure> structure(String id) { return new Holder<>(new Structure(),
            ResourceKey.create("structure", ResourceLocation.parse(id))); }
    private static StructureSet set(Holder<Structure> structure, StructurePlacement placement) {
        return new StructureSet(List.of(new StructureSet.StructureSelectionEntry(structure)), placement);
    }
    private static StructureSet mixedSet(Holder<Structure> first, Holder<Structure> second,
                                         StructurePlacement placement) {
        return new StructureSet(List.of(new StructureSet.StructureSelectionEntry(first),
                new StructureSet.StructureSelectionEntry(second)), placement);
    }
    private static BiomeSource source(boolean td, Holder<Biome>... biomes) {
        return new BiomeSource(td, List.of(biomes));
    }
    private static void ok(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] ignored) {
        Holder<Biome> jungle=biome("terralith:cave/underground_jungle");
        Holder<Biome> lush=biome("minecraft:lush_caves");
        Holder<Biome> fungal=biome("example:fungal");
        Holder<Biome> surface=biome("minecraft:plains");
        Holder<Structure> target=structure(LushCaveRuinsCompatibility.STRUCTURE_ID);
        Holder<Structure> other=structure("example:other");
        RandomSpreadStructurePlacement random=new RandomSpreadStructurePlacement();
        StructurePlacement nonRandom=new StructurePlacement();
        StructureSet targetSet=set(target,random), otherSet=set(other,random), nonRandomTarget=set(target,nonRandom);
        StructureSet mixedTarget=mixedSet(target,other,random);
        BiomeSource tdJungle=source(true,jungle), tdNoJungle=source(true,lush), nonTd=source(false,jungle);
        ok(LushCaveRuinsCompatibility.retainsSet(targetSet,tdJungle),"TD jungle retains target random set");
        ok(!LushCaveRuinsCompatibility.retainsSet(otherSet,tdJungle),"absent target untouched");
        ok(!LushCaveRuinsCompatibility.retainsSet(nonRandomTarget,tdJungle),"non-random target untouched");
        ok(!LushCaveRuinsCompatibility.retainsSet(targetSet,tdNoJungle),"TD without jungle does not retain");
        ok(!LushCaveRuinsCompatibility.retainsSet(targetSet,nonTd),"non-TD remains untouched");
        ok(LushCaveRuinsCompatibility.isNativeOnlySet(targetSet),"single exact target stays native-only");
        ok(!LushCaveRuinsCompatibility.isNativeOnlySet(mixedTarget),"mixed target set remains planner-visible");
        ok(!LushCaveRuinsCompatibility.isNativeOnlySet(otherSet),"unrelated set remains planner-visible");
        Map<Structure,List<StructurePlacement>> placements=new LinkedHashMap<>();
        LushCaveRuinsCompatibility.addPlacements(tdJungle,List.of(new Holder<>(targetSet,null),new Holder<>(otherSet,null)),placements);
        ok(placements.size()==1 && placements.containsKey(target.value()),"mixed sets add target only");
        ok(placements.get(target.value()).size()==1 && placements.get(target.value()).get(0)==random,"preserves random placement identity");
        LushCaveRuinsCompatibility.addPlacements(tdJungle,List.of(new Holder<>(targetSet,null)),placements);
        ok(placements.get(target.value()).size()==1,"no duplicate placement");
        Map<Structure,List<StructurePlacement>> untouched=new LinkedHashMap<>();
        LushCaveRuinsCompatibility.addPlacements(tdNoJungle,List.of(new Holder<>(targetSet,null)),untouched);
        LushCaveRuinsCompatibility.addPlacements(nonTd,List.of(new Holder<>(targetSet,null)),untouched);
        ok(untouched.isEmpty(),"no jungle/non-TD placement map change");
        Predicate<Holder<Biome>> original=b -> b==lush;
        Predicate<Holder<Biome>> td=LushCaveRuinsCompatibility.validBiomes(tdJungle,LushCaveRuinsCompatibility.STRUCTURE_ID,original);
        ok(td.test(lush)&&td.test(jungle),"original lush and exact jungle accepted");
        ok(!td.test(fungal)&&!td.test(surface),"no fungal or surface broadening");
        ok(LushCaveRuinsCompatibility.validBiomes(nonTd,LushCaveRuinsCompatibility.STRUCTURE_ID,original)==original,"non-TD predicate identity");
        ok(LushCaveRuinsCompatibility.validBiomes(tdJungle,"example:other",original)==original,"other predicate identity");
        System.out.println("LushCaveRuinsCompatibilityTest: PASS exact TD jungle eligibility and native placement preservation");
    }
}
