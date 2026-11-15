package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;

/** Dependency-free contract for TD-local structure biome replacements. */
public final class InstanceStructureCompatibilityTest {
    private static Holder<Biome> biome(String id) { return new Holder<>(new Biome(id),
            ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id))); }
    private static Holder<Structure> structure(String id) { return new Holder<>(new Structure(),
            ResourceKey.create("structure", ResourceLocation.parse(id))); }
    private static StructureSet set(Holder<Structure> structure, StructurePlacement placement) {
        return new StructureSet(List.of(new StructureSet.StructureSelectionEntry(structure)), placement);
    }
    private static BiomeSource source(boolean td, Holder<Biome>... biomes) { return new BiomeSource(td, List.of(biomes)); }
    private static void ok(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] ignored) {
        Holder<Biome> jungle=biome("terralith:cave/underground_jungle");
        Holder<Biome> deep=biome("terralith:cave/deep_caves");
        Holder<Biome> fungal=biome("terralith:cave/fungal_caves");
        Holder<Biome> mangrove=biome("terrain-diffusion-mc:ecology/mangrove_swamp");
        Holder<Biome> vanilla=biome("minecraft:lush_caves");
        BiomeSource td=source(true,jungle,deep,fungal,mangrove);
        BiomeSource nonTd=source(false,jungle,deep,fungal,mangrove);
        Map<String, Holder<Biome>> cases=Map.of(
                "hopo:portal/overworld/portal_lush", jungle,
                "hopo:portal/overworld/portal_dripstone", deep,
                "cataclysm:amethyst_nest", jungle,
                "structory:swamp_ruin", mangrove,
                "terralith:underground/oak_cabin", jungle,
                "terralith:underground_cabin", jungle,
                "bettermineshafts:mineshaft_lush", jungle,
                "bettermineshafts:mineshaft_dripstone", deep,
                "bettermineshafts:mineshaft_mushroom", fungal);
        RandomSpreadStructurePlacement random=new RandomSpreadStructurePlacement();
        for (var item : cases.entrySet()) {
            Holder<Structure> target=structure(item.getKey());
            StructureSet targetSet=set(target, random);
            ok(InstanceStructureCompatibility.retainsSet(targetSet,td), item.getKey()+" retains TD set");
            Map<Structure,List<StructurePlacement>> placements=new LinkedHashMap<>();
            InstanceStructureCompatibility.addPlacements(td,List.of(new Holder<>(targetSet,null)),placements);
            ok(placements.get(target.value()).equals(List.of(random)), item.getKey()+" retains exact placement");
            Predicate<Holder<Biome>> original=b -> b==vanilla;
            Predicate<Holder<Biome>> adjusted=InstanceStructureCompatibility.validBiomes(td,item.getKey(),original);
            ok(adjusted.test(vanilla) && adjusted.test(item.getValue()), item.getKey()+" retains original and mapped biome");
            ok(InstanceStructureCompatibility.validBiomes(nonTd,item.getKey(),original)==original,
                    item.getKey()+" has no non-TD change");
        }
        Set<Holder<Biome>> accepted=new LinkedHashSet<>();
        InstanceStructureCompatibility.addEligibleBiomes(td, "structory:swamp_ruin", accepted);
        ok(accepted.equals(Set.of(mangrove)), "fast locate receives mapped mangrove biome");
        InstanceStructureCompatibility.addEligibleBiomes(nonTd, "structory:swamp_ruin", accepted);
        ok(accepted.equals(Set.of(mangrove)), "fast locate has no non-TD additions");
        InstanceStructureCompatibility.addEligibleBiomes(td, "example:other", accepted);
        ok(accepted.equals(Set.of(mangrove)), "unknown structures are not broadened");
        System.out.println("InstanceStructureCompatibilityTest: PASS TD-only biome mappings and fast-locate eligibility");
    }
}
